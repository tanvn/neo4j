/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.nioneo.store;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.helpers.Pair;
import org.neo4j.kernel.impl.nioneo.store.windowpool.WindowPool;
import org.neo4j.kernel.impl.util.StringLogger;

/**
 * Manages {@link PersistenceWindow persistence windows} for a store. Each store
 * can configure how much memory it has for
 * {@link MappedPersistenceWindow memory mapped windows}. This class tries to
 * make the most efficient use of those windows by allocating them in such a way
 * that the most frequently used records/blocks (be it for read or write
 * operations) are encapsulated by a memory mapped persistence window.
 */
public class PersistenceWindowPool implements WindowPool
{
    private static final int MAX_BRICK_COUNT = 100000;

    private final File storeName;
    // == recordSize
    private final int blockSize;
    private FileChannel fileChannel;
    private final ConcurrentMap<Long,PersistenceRow> activeRowWindows =
        new ConcurrentHashMap<Long,PersistenceRow>();
    private long availableMem = 0;
    private long memUsed = 0;
    private int brickCount = 0;
    private int brickSize = 0;
    private BrickElement brickArray[] = new BrickElement[0];
    private int brickMiss = 0;

    static final int REFRESH_BRICK_COUNT = 50000;
    private final FileChannel.MapMode mapMode;

    private int hit = 0;
    private int miss = 0;
    private int switches = 0;
    private int ooe = 0;
    private boolean useMemoryMapped = true;

    private final boolean readOnly;
    
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicInteger avertedRefreshes = new AtomicInteger();
    private final AtomicLong refreshTime = new AtomicLong();
    private final AtomicInteger refreshes = new AtomicInteger();
    private StringLogger log;

    /**
     * Create new pool for a store.
     *
     * @param storeName
     *            Name of store that use this pool
     * @param blockSize
     *            The size of each record/block in the store
     * @param fileChannel
     *            A fileChannel to the store
     * @param mappedMem
     *            Number of bytes dedicated to memory mapped windows
     * @throws IOException
     *             If unable to create pool
     */
    public PersistenceWindowPool( File storeName, int blockSize,
        FileChannel fileChannel, long mappedMem,
        boolean useMemoryMappedBuffers, boolean readOnly, StringLogger log )
    {
        this.storeName = storeName;
        this.blockSize = blockSize;
        this.fileChannel = fileChannel;
        this.availableMem = mappedMem;
        this.useMemoryMapped = useMemoryMappedBuffers;
        this.readOnly = readOnly;
        this.mapMode = readOnly ? MapMode.READ_ONLY : MapMode.READ_WRITE;
        this.log = log;
        setupBricks();
        dumpStatus();
    }

    /**
     * Acquires a windows for <CODE>position</CODE> and <CODE>operationType</CODE>
     * locking the window preventing other threads from using it.
     *
     * @param position
     *            The position the needs to be encapsulated by the window
     * @param operationType
     *            The type of operation (READ or WRITE)
     * @return A locked window encapsulating the position
     * @throws IOException
     *             If unable to acquire the window
     */
    @Override
    public PersistenceWindow acquire( long position, OperationType operationType )
    {
        LockableWindow window = null;
        if ( brickMiss >= REFRESH_BRICK_COUNT )
        {
            refreshBricks();
        }
        while ( window == null )
        {
            if ( brickSize > 0 )
            {
                int brickIndex = positionToBrickIndex( position );
                if ( brickIndex >= brickArray.length )
                {
                    expandBricks( brickIndex + 1 );
                }
                BrickElement brick = brickArray[brickIndex];
                window = brick.getWindow();
                if ( window != null && !window.markAsInUse() )
                {
                    // Oops, a refreshBricks call from another thread just closed
                    // this window, treat it as if we hadn't even found it.
                    window = null;
                }

                // assert window == null || window.encapsulates( position );
                brick.setHit();
            }
            if ( window == null )
            {
                // There was no mapped window for this brick. Go for active window instead.
                
                // Should be AtomicIntegers, but it's completely OK to miss some
                // updates for these statistics, right?
                miss++;
                brickMiss++;
    
                // Lock-free implementation of instantiating an active window for this position
                // See if there's already an active window for this position
                PersistenceRow dpw = activeRowWindows.get( position );
                if ( dpw != null && dpw.markAsInUse() )
                {   // ... there was and we managed to mark it as in use
                    window = dpw;
                    break;
                }
                
                // Either there was no active window for this position or it got
                // closed right before we managed to mark it as in use.
                // Either way instantiate a new active window for this position
                dpw = new PersistenceRow( position, blockSize, fileChannel );
                PersistenceRow existing = activeRowWindows.putIfAbsent( position, dpw );
                if ( existing == null )
                {
                    // No other thread managed to create an active window for
                    // this position before us.
                    window = dpw;
                }
                else
                {
                    // Someone else put it there before us. Close this row
                    // which was unnecessarily opened. The next go in this loop
                    // will get that one instead.
                    dpw.close();
                }
            }
            else
            {
                hit++;
            }
        }

        window.lock( operationType );
        return window;
    }

    private int positionToBrickIndex( long position )
    {
        return (int) (position * blockSize / brickSize);
    }
    
    private long brickIndexToPosition( int brickIndex )
    {
        return (long) brickIndex * brickSize / blockSize;
    }
    
    void dumpStatistics()
    {
        log.logMessage( storeName + " hit=" + hit + " miss=" + miss + " switches="
                        + switches + " ooe=" + ooe );
    }

    /**
     * Releases a window used for an operation back to the pool and unlocks it
     * so other threads may use it.
     *
     * @param window
     *            The window to be released
     * @throws IOException
     *             If unable to release window
     */
    @Override
    public void release( PersistenceWindow window )
    {
        try
        {
            if ( window instanceof PersistenceRow )
            {
                PersistenceRow dpw = (PersistenceRow) window;

                // If the corresponding window has been instantiated while we had
                // this active row we need to hand over the changes to that
                // window if the window isn't memory mapped.
                if ( brickSize > 0 && dpw.isDirty() )
                    applyChangesToWindowIfNecessary( dpw );

                if ( dpw.writeOutAndCloseIfFree( readOnly ) )
                {
                    activeRowWindows.remove( dpw.position(), dpw );
                }
                else
                {
                    dpw.reset();
                }
            }
        }
        finally
        {
            ((LockableWindow) window).unLock();
        }
    }

    private void applyChangesToWindowIfNecessary( PersistenceRow dpw )
    {
        int brickIndex = positionToBrickIndex( dpw.position() );
        LockableWindow existingBrickWindow = brickIndex < brickArray.length ?
                brickArray[brickIndex].getWindow() : null;
        if ( existingBrickWindow != null && !(existingBrickWindow instanceof MappedPersistenceWindow) &&
                existingBrickWindow.markAsInUse() )
        {
            // There is a non-mapped brick window here, let's have it
            // know about my changes.
            existingBrickWindow.lock( OperationType.WRITE );
            try
            {
                existingBrickWindow.acceptContents( dpw );
            }
            finally
            {
                existingBrickWindow.unLock();
            }
        }
    }

    @Override
    public synchronized void close()
    {
        flushAll();
        for ( BrickElement element : brickArray )
        {
            if ( element.getWindow() != null )
            {
                element.getWindow().close();
                element.setWindow( null );
            }
        }
        fileChannel = null;
        activeRowWindows.clear();
        dumpStatistics();
    }

    @Override
    public void flushAll()
    {
        if ( readOnly )
            return;

        for ( BrickElement element : brickArray )
        {
            PersistenceWindow window = element.getWindow();
            if ( window != null )
            {
                window.force();
            }
        }
        try
        {
            fileChannel.force( false );
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException(
                "Failed to flush file channel " + storeName, e );
        }
    }

    /**
     * Initial setup of bricks based on the size of the given channel and
     * available memory to map.
     */
    private void setupBricks()
    {
        long fileSize = -1;
        try
        {
            fileSize = fileChannel.size();
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException(
                "Unable to get file size for " + storeName, e );
        }
        if ( blockSize == 0 )
        {
            return;
        }

        // If we can't fit even 10 blocks in available memory don't even try
        // to use available memory.
        if(availableMem > 0 && availableMem < blockSize * 10l )
        {
            logWarn( "Unable to use " + availableMem
                    + "b as memory mapped windows, need at least " + blockSize * 10
                    + "b (block size * 10)" );
            logWarn( "Memory mapped windows have been turned off" );
            availableMem = 0;
            brickCount = 0;
            brickSize = 0;
            return;
        }
        if ( availableMem > 0 && fileSize > 0 )
        {
            double ratio = (availableMem + 0.0d) / fileSize;
            if ( ratio >= 1 )
            {
                brickSize = (int) (availableMem / 1000);
                if ( brickSize < 0 )
                {
                    brickSize = Integer.MAX_VALUE;
                }
                brickSize = (brickSize / blockSize) * blockSize;
                brickCount = (int) (fileSize / brickSize);
            }
            else
            {
                brickCount = (int) (1000.0d / ratio);
                if ( brickCount > MAX_BRICK_COUNT )
                {
                    brickCount = MAX_BRICK_COUNT;
                }
                if ( fileSize / brickCount > availableMem )
                {
                    logWarn( "Unable to use " + (availableMem / 1024)
                        + "kb as memory mapped windows, need at least "
                        + (fileSize / brickCount / 1024) + "kb" );
                    logWarn( "Memory mapped windows have been turned off" );
                    availableMem = 0;
                    brickCount = 0;
                    brickSize = 0;
                    return;
                }
                brickSize = (int) (fileSize / brickCount);
                if ( brickSize < 0 )
                {
                    brickSize = Integer.MAX_VALUE;
                    brickSize = (brickSize / blockSize) * blockSize;
                    brickCount = (int) (fileSize / brickSize);
                }
                else
                {
                    brickSize = (brickSize / blockSize) * blockSize;
                }
                assert brickSize > blockSize;
            }
        }
        else if ( availableMem > 0 )
        {
            brickSize = (int) (availableMem / 100);
            if ( brickSize < 0 )
            {
                brickSize = Integer.MAX_VALUE;
            }
            brickSize = (brickSize / blockSize) * blockSize;
        }
        brickArray = new BrickElement[brickCount];
        for ( int i = 0; i < brickCount; i++ )
        {
            BrickElement element = new BrickElement( i );
            brickArray[i] = element;
        }
    }

    /**
     * Called during expanding of bricks where we see that we use too much
     * memory and need to release some windows.
     * 
     * @param nr the number of windows to free.
     */
    private void freeWindows( int nr )
    {
        // Only called from expandBricks, so we're under a lock here
        if ( brickSize <= 0 )
        {
            // memory mapped turned off
            return;
        }
        ArrayList<BrickElement> mappedBricks = new ArrayList<BrickElement>();
        for ( int i = 0; i < brickCount; i++ )
        {
            BrickElement be = brickArray[i];
            if ( be.getWindow() != null )
            {
                be.snapshotHitCount();
                mappedBricks.add( be );
            }
        }
        Collections.sort( mappedBricks, BRICK_SORTER );
        for ( int i = 0; i < nr && i < mappedBricks.size(); i++ )
        {
            BrickElement mappedBrick = mappedBricks.get( i );
            LockableWindow window = mappedBrick.getWindow();
            if ( window.writeOutAndCloseIfFree( readOnly ) )
            {
                mappedBrick.setWindow( null );
                memUsed -= brickSize;
            }
        }
    }

    /**
     * Go through the bricks and see if they are optimally placed, and change
     * accordingly. This happens whenever we see that there has been a certain
     * amount of brick misses since the last refresh.
     */
    private void refreshBricks()
    {
        if ( brickMiss < REFRESH_BRICK_COUNT || brickSize <= 0 )
        {
            return;
        }

        if ( refreshing.compareAndSet( false, true ) )
        {
            // No one is doing refresh right now, go ahead and do it
            try
            {
                long t = System.currentTimeMillis();
                doRefreshBricks();
                refreshes.incrementAndGet();
                refreshTime.addAndGet( System.currentTimeMillis()-t );
            }
            finally
            {
                refreshing.set( false );
            }
        }
        else
        {
            // Another thread is doing refresh right now, trust it to refresh the bricks
            // and just go about my business.
            avertedRefreshes.incrementAndGet();
        }
    }
    
    private synchronized void doRefreshBricks()
    {
        brickMiss = 0;
        Pair<List<BrickElement>, List<BrickElement>> currentMappings = gatherMappedVersusUnmappedWindows();
        List<BrickElement> mappedBricks = currentMappings.first();
        List<BrickElement> unmappedBricks = currentMappings.other();
        
        // Fill up unused memory, i.e. map unmapped bricks as much as available memory allows
        // and request patterns signals. Start the loop from the end of the array where the
        // bricks with highest hit ratio are.
        int unmappedIndex = unmappedBricks.size() - 1;
        while ( memUsed + brickSize <= availableMem && unmappedIndex >= 0 )
        {
            BrickElement unmappedBrick = unmappedBricks.get( unmappedIndex-- );
            if ( unmappedBrick.getHit() == 0 )
                // We have more memory available, but no more windows have actually
                // been requested so don't map unused random windows.
                return;
            
            allocateNewWindow( unmappedBrick );
        }
        
        // Switch bad/unused mappings. Start iterating over mapped bricks
        // from the beginning (those with lowest hit ratio) and unmapped from the end
        // (or rather where the fill-up-unused-memory loop above left off) where we've
        // got the unmapped bricks with highest hit ratio.
        int mappedIndex = 0;
        while ( unmappedIndex >= 0 && mappedIndex < mappedBricks.size() )
        {
            BrickElement mappedBrick = mappedBricks.get( mappedIndex++ );
            BrickElement unmappedBrick = unmappedBricks.get( unmappedIndex-- );
            if ( mappedBrick.getHit() >= unmappedBrick.getHit() )
                // We've passed a point where we don't have any unmapped brick
                // with a higher hit ratio then the lowest mapped brick. We're done.
                break;
            
            LockableWindow window = mappedBrick.getWindow();
            if (window.writeOutAndCloseIfFree( readOnly ) )
            {
                mappedBrick.setWindow( null );
                memUsed -= brickSize;
                if ( allocateNewWindow( unmappedBrick ) )
                    switches++;
            }
        }
    }

    /**
     * Goes through all bricks in this pool and divides them between mapped and unmapped,
     * i.e. those with a mapped persistence window assigned to it and those without.
     * 
     * The two {@link List lists} coming back are also sorted where the first element
     * has got the lowest {@link BrickElement#getHit()} ratio, and the last the highest.
     * 
     * @return all bricks in this pool divided into mapped and unmapped.
     */
    private Pair<List<BrickElement>, List<BrickElement>> gatherMappedVersusUnmappedWindows()
    {
        List<BrickElement> mappedBricks = new ArrayList<BrickElement>();
        List<BrickElement> unmappedBricks = new ArrayList<BrickElement>();
        for ( int i = 0; i < brickCount; i++ )
        {
            BrickElement be = brickArray[i];
            be.snapshotHitCount();
            if ( be.getWindow() != null )
                mappedBricks.add( be );
            else
                unmappedBricks.add( be );
            be.refresh();
        }
        Collections.sort( unmappedBricks, BRICK_SORTER );
        Collections.sort( mappedBricks, BRICK_SORTER );
        return Pair.of( mappedBricks, unmappedBricks );
    }

    /**
     * Called every time we request a brick that has a greater index than
     * the current brick count. This happens as the underlying file channel
     * grows as new blocks/records are added to it.
     * 
     * @param newBrickCount the size to expand the brick count to.
     */
    private synchronized void expandBricks( int newBrickCount )
    {
        if ( newBrickCount > brickCount )
        {
            BrickElement tmpArray[] = new BrickElement[newBrickCount];
            System.arraycopy( brickArray, 0, tmpArray, 0, brickArray.length );
            if ( memUsed + brickSize >= availableMem )
            {
                freeWindows( 1 );
            }
            for ( int i = brickArray.length; i < tmpArray.length; i++ )
            {
                BrickElement be = new BrickElement( i );
                tmpArray[i] = be;
                if ( memUsed + brickSize <= availableMem )
                    allocateNewWindow( be );
            }
            brickArray = tmpArray;
            brickCount = tmpArray.length;
        }
    }

    /**
     * Allocates a new persistence window for the {@code brick}. Such an
     * allocation may fail with memory problems and such an error will be
     * caught and logged as well as a counter incremented. It's OK if
     * a memory mapping fails, because we can fall back on temporary
     * {@link PersistenceRow persistence rows}.
     * 
     * @param brick the {@link BrickElement} to allocate a new window for.
     * @return {@code true} if the window was successfully allocated,
     * otherwise {@code false}.
     */
    private boolean allocateNewWindow( BrickElement brick )
    {
        try
        {
            LockableWindow window = null;
            if ( useMemoryMapped )
            {
                 window = new MappedPersistenceWindow(
                    brickIndexToPosition( brick.index() ), blockSize,
                    brickSize, fileChannel, mapMode );
            }
            else
            {
                PlainPersistenceWindow dpw =
                    new PlainPersistenceWindow(
                        brickIndexToPosition( brick.index() ),
                        blockSize, brickSize, fileChannel );
                dpw.readFullWindow();
                window = dpw;
            }
            brick.setWindow( window );
            memUsed += brickSize;
            return true;
        }
        catch ( MappedMemException e )
        {
            ooe++;
            logWarn( "Unable to memory map", e );
        }
        catch ( OutOfMemoryError e )
        {
            ooe++;
            logWarn( "Unable to allocate direct buffer", e );
        }
        return false;
    }

    private void dumpStatus()
    {
        try
        {
            log.logMessage( "[" + storeName + "] brickCount=" + brickCount
                            + " brickSize=" + brickSize + "b mappedMem=" + availableMem
                            + "b (storeSize=" + fileChannel.size() + "b)" );
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException(
                "Unable to get file size for " + storeName, e );
        }
    }

    private void logWarn( String logMessage )
    {
        log.logMessage( "[" + storeName + "] " + logMessage );
    }

    private void logWarn( String logMessage, Throwable cause )
    {
        log.logMessage( "[" + storeName + "] " + logMessage, cause );
    }

    @Override
    public WindowPoolStats getStats()
    {
        int avgRefreshTime = refreshes.get() == 0 ? 0 : (int)(refreshTime.get()/refreshes.get());
        return new WindowPoolStats( storeName, availableMem, memUsed, brickCount,
                brickSize, hit, miss, ooe, switches, avgRefreshTime, refreshes.get(), avertedRefreshes.get() );
    }

    private static class BrickElement
    {
        private final int index;
        private int hitCount;
        private int hitCountSnapshot;
        private volatile LockableWindow window;

        BrickElement( int index )
        {
            this.index = index;
        }

        void setWindow( LockableWindow window )
        {
            this.window = window;
        }

        LockableWindow getWindow()
        {
            return window;
        }

        int index()
        {
            return index;
        }

        void setHit()
        {
            hitCount += 10;
            if ( hitCount < 0 )
            {
                hitCount -= 10;
            }
        }

        int getHit()
        {
            return hitCount;
        }

        void refresh()
        {
            if ( window == null )
            {
                hitCount /= 1.25;
            }
            else
            {
                hitCount /= 1.15;
            }
        }

        void snapshotHitCount()
        {
            hitCountSnapshot = hitCount;
        }

        int getHitCountSnapshot()
        {
            return hitCountSnapshot;
        }

        @Override
        public String toString()
        {
            return "" + hitCount + (window == null ? "x" : "o");
        }
    }

    /**
     * Sorts {@link BrickElement} by their {@link BrickElement#getHit()} ratio.
     * Lowest hit ratio will make that brick end up at a lower index in list,
     * so the least requested will be at the beginning and the most requested at the end.
     */
    private static final Comparator<BrickElement> BRICK_SORTER = new Comparator<BrickElement>()
    {
        public int compare( BrickElement o1, BrickElement o2 )
        {
            return o1.getHitCountSnapshot() - o2.getHitCountSnapshot();
        }
    };
}
