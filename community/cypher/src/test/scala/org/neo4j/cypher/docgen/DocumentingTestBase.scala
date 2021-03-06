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
package org.neo4j.cypher.docgen

import org.neo4j.graphdb.index.Index
import org.junit.{Before, After}
import scala.collection.JavaConverters._
import java.io.{StringWriter, PrintWriter, File, ByteArrayOutputStream}
import org.neo4j.graphdb._
import factory.GraphDatabaseSettings
import org.neo4j.visualization.graphviz.{AsciiDocStyle, GraphvizWriter, GraphStyle}
import org.neo4j.walk.Walker
import org.neo4j.visualization.asciidoc.AsciidocHelper
import org.neo4j.cypher.javacompat.GraphImpl
import org.neo4j.cypher.{CypherParser, ExecutionResult, ExecutionEngine}
import org.neo4j.test.{ImpermanentGraphDatabase, TestGraphDatabaseFactory, GraphDescription}
import org.scalatest.Assertions
import org.neo4j.test.AsciiDocGenerator
import org.neo4j.kernel.{GraphDatabaseAPI, AbstractGraphDatabase}
import org.neo4j.cypher.internal.helpers.GraphIcing
import org.neo4j.cypher.export.{SubGraphExporter, DatabaseSubGraph}
import org.neo4j.helpers.Settings
import org.neo4j.cypher.internal.parser.prettifier.Prettifier

trait DocumentationHelper extends GraphIcing {
  def generateConsole:Boolean
  def db: GraphDatabaseAPI

  def nicefy(in: String): String = in.toLowerCase.replace(" ", "-")

  def simpleName: String = this.getClass.getSimpleName.replaceAll("Test", "").toLowerCase
  
  def createDir(folder: String): File = {
    val dir = new File(path + nicefy(folder))
    if (!dir.exists()) {
      dir.mkdirs()
    }
    dir
  }

  def createWriter(title: String, dir: File): PrintWriter = {
    new PrintWriter(new File(dir, nicefy(title) + ".asciidoc"), "UTF-8")
  }

  val path: String = "target/docs/dev/ql/"

  val graphvizFileName = "cypher-" + simpleName + "-graph"

  def dumpGraphViz(dir: File, graphVizOptions:String) : String = {
    emitGraphviz(dir, graphvizFileName, graphVizOptions)
  }

  private def emitGraphviz(dir:File, testid:String, graphVizOptions:String): String = {
    val out = new ByteArrayOutputStream()
    val writer = new GraphvizWriter(getGraphvizStyle)

    db.inTx {
      writer.emit(out, Walker.fullGraph(db))
    }

    val graphOutput = """["dot", "%s.svg", "neoviz", "%s"]
----
%s
----

""".format(testid, graphVizOptions, out)
    ".Graph\n" + AsciiDocGenerator.dumpToSeparateFile(dir, testid, graphOutput)
  }

  protected def getGraphvizStyle: GraphStyle = AsciiDocStyle.withAutomaticRelationshipTypeColors()

}

abstract class DocumentingTestBase extends Assertions with DocumentationHelper with GraphIcing {

  def testQuery(title: String, text: String, queryText: String, returns: String, assertions: (ExecutionResult => Unit)*) {
    internalTestQuery(title, text, queryText, returns, None, assertions: _*)
  }

  def prepareAndTestQuery(title: String, text: String, queryText: String, returns: String, prepare: (()=> Any), assertions: (ExecutionResult => Unit)*) {
    internalTestQuery(title, text, queryText, returns, Some(prepare), assertions: _*)
  }

  def internalTestQuery(title: String, text: String, queryText: String, returns: String, prepare: Option[() => Any], assertions: (ExecutionResult => Unit)*) {
    dumpGraphViz(dir, graphvizOptions.trim)
    if (!graphvizExecutedAfter) {
      dumpGraphViz(dir, graphvizOptions.trim)
    }

    var consoleData: String = ""
    if (generateConsole) {
      if (generateInitialGraphForConsole) {
        val out = new StringWriter()
        db.inTx {
          new SubGraphExporter(DatabaseSubGraph.from(db)).export(new PrintWriter(out))
          consoleData = out.toString
        }
      }
      if (consoleData.isEmpty) {
        consoleData = "(0)"
      }
    }

    db.inTx({
      val r = testWithoutDocs(queryText, prepare, assertions:_*)
      val result: ExecutionResult = r._1
      val query: String = r._2

      val writer: PrintWriter = createWriter(title, dir)
      dumpToFile(dir, writer, title, query, returns, text, result, consoleData)}
    )

    if (graphvizExecutedAfter) {
      dumpGraphViz(dir, graphvizOptions.trim)
    }
  }

  var db: GraphDatabaseAPI = null
  val parser: CypherParser = new CypherParser
  var engine: ExecutionEngine = null
  var nodes: Map[String, Long] = null
  var nodeIndex: Index[Node] = null
  var relIndex: Index[Relationship] = null
  val properties: Map[String, Map[String, Any]] = Map()
  var generateConsole: Boolean = true
  var generateInitialGraphForConsole: Boolean = true
  val graphvizOptions: String = ""
  val noTitle: Boolean = false
  val graphvizExecutedAfter: Boolean = false

  def section: String
  val dir = createDir(section)

  def graphDescription: List[String]

  def indexProps: List[String] = List()

  def dumpToFile(dir: File, writer: PrintWriter, title: String, query: String, returns: String, text: String, result: ExecutionResult, consoleData: String) {
    val testId = nicefy(section + " " + title)
    writer.println("[[" + testId + "]]")
    if (!noTitle) writer.println("== " + title + " ==")
    writer.println(text)
    writer.println()
    runQuery(dir, writer, testId, query, returns, result, consoleData)
    writer.flush()
    writer.close()
  }

  def executeQuery(queryText: String): ExecutionResult = {
    var query = queryText
    nodes.keySet.foreach((key) => query = query.replace("%" + key + "%", node(key).getId.toString))
    engine.execute(query)
  }

  protected def assertIsDeleted(pc:PropertyContainer) {
    val internalDb:AbstractGraphDatabase = db.asInstanceOf[AbstractGraphDatabase]
    if(!internalDb.getNodeManager.isDeleted(pc)) {
      fail("Expected " + pc + " to be deleted, but it isn't.")
    }
  }

  def testWithoutDocs(queryText: String, prepare: Option[() => Any], assertions: (ExecutionResult => Unit)*): (ExecutionResult, String) = {
    prepare.foreach{ (prepareStep: () => Any) => prepareStep() }

    var query = queryText
      val keySet = nodes.keySet
      keySet.foreach((key) => query = query.replace("%" + key + "%", node(key).getId.toString))
      val result = engine.execute(query)
      assertions.foreach(_.apply(result))

    (result, query)
  }

  protected def getLabelsFromNode(p: ExecutionResult): Iterable[String] = p.columnAs[Node]("n").next().labels

  def indexProperties[T <: PropertyContainer](n: T, index: Index[T]) {
    indexProps.foreach((property) => {
      if (n.hasProperty(property)) {
        val value = n.getProperty(property)
        index.add(n, property, value)
      }
    })
  }

  def node(name: String): Node = db.getNodeById(nodes.getOrElse(name, throw new NotFoundException(name)))
  def nodes(names: String*): List[Node] = names.map(node).toList

  def rel(id: Long): Relationship = db.getRelationshipById(id)

  @After
  def teardown() {
    if (db != null) db.shutdown()
  }

  @Before
  def init() {
    db = new TestGraphDatabaseFactory().newImpermanentDatabaseBuilder().
      setConfig( GraphDatabaseSettings.node_keys_indexable, "name" ).
      setConfig( GraphDatabaseSettings.node_auto_indexing, Settings.TRUE ).
      newGraphDatabase().asInstanceOf[GraphDatabaseAPI]
    engine = new ExecutionEngine(db)

    db.asInstanceOf[ImpermanentGraphDatabase].cleanContent(false)

    db.inTx {
      nodeIndex = db.index().forNodes("nodes")
      relIndex = db.index().forRelationships("rels")
      val g = new GraphImpl(graphDescription.toArray[String])
      val description = GraphDescription.create(g)

      nodes = description.create(db).asScala.map {
        case (name, node) => name -> node.getId
      }.toMap

      db.getAllNodes.asScala.foreach((n) => {
        indexProperties(n, nodeIndex)
        n.getRelationships(Direction.OUTGOING).asScala.foreach(indexProperties(_, relIndex))
      })

      asNodeMap(properties) foreach { case (n: Node, seq: Map[String, Any]) =>
          seq foreach { case (k, v) => n.setProperty(k, v) }
      }
    }
  }

  private def asNodeMap[T : Manifest](m: Map[String, T]): Map[Node, T] =
    m map { case (k: String, v: T) => (node(k), v) }

  def runQuery(dir: File, writer: PrintWriter, testId: String, query: String, returns: String, result: ExecutionResult, consoleData: String) {
    val output = new StringBuilder(2048)
    output.append(".Query\n")
    output.append(createCypherSnippet(query))
    writer.println(AsciiDocGenerator.dumpToSeparateFile(dir, testId + ".query", output.toString))
    writer.println
    writer.println(returns)
    writer.println

    val resultText = result.dumpToString()
    output.clear
    output.append(".Result\n")
    output.append(AsciidocHelper.createQueryResultSnippet(resultText))
    output.append('\n')
    writer.println(AsciiDocGenerator.dumpToSeparateFile(dir, testId + ".result", output.toString))

    if (generateConsole) {
      output.clear
      writer.println(".Try this query live")
      output.append("[console]\n")
      output.append("----\n")
      output.append(consoleData)
      output.append("\n\n")
      output.append(query)
      output.append("\n----")
      writer.println(AsciiDocGenerator.dumpToSeparateFile(dir, testId + ".console", output.toString))
    }
  }

  private def createCypherSnippet(query: String) = {
    val prettifiedQuery = Prettifier(query)
    val result = AsciidocHelper.createAsciiDocSnippet("cypher", prettifiedQuery)
    result
  }
}




