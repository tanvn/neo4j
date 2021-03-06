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
package org.neo4j.cypher.internal.parser.v2_0

import org.neo4j.cypher.internal.commands._
import expressions._
import org.neo4j.cypher.SyntaxException
import org.neo4j.cypher.internal.parser.{No, Maybe, Yes, AbstractPattern}
import org.neo4j.cypher.internal.HasOptionalDefault
import org.neo4j.cypher.internal.commands.values.TokenType.PropertyKey

trait Expressions extends Base with ParserPattern with Predicates with StringLiteral {
  def expression: Parser[Expression] = term ~ rep("+" ~ term | "-" ~ term) ^^ {
    case head ~ rest =>
      var result = head
      rest.foreach {
        case "+" ~ f => result = Add(result, f)
        case "-" ~ f => result = Subtract(result, f)
      }

    result
  }

  def term: Parser[Expression] = factor ~ rep("*" ~ factor | "/" ~ factor | "%" ~ factor | "^" ~ factor) ^^ {
    case head ~ rest =>
      var result = head
      rest.foreach {
        case "*" ~ f => result = Multiply(result, f)
        case "/" ~ f => result = Divide(result, f)
        case "%" ~ f => result = Modulo(result, f)
        case "^" ~ f => result = Pow(result, f)
      }

      result
  }

  def factor: Parser[Expression] =
  (     NULL ^^^ Literal(null)
      | TRUE ^^^ True()
      | FALSE ^^^ Not(True())
      | simpleCase
      | genericCase
      | extract
      | reduce
      | function
      | aggregateExpression
      | percentileFunction
      | coalesceFunc
      | filterFunc
      | shortestPathFunc
      | property
      | stringLit
      | numberLiteral
      | collectionLiteral
      | parameter
      | entity
      | parens(expression)
      | failure("illegal value"))

  def exprOrPred: Parser[Expression] = predicate | expression

  def numberLiteral: Parser[Expression] = number ^^ (x => {
    val value: Any = if (x.contains("."))
      x.toDouble
    else
      x.toLong

    Literal(value)
  })

  def entity: Parser[Identifier] = identity ^^ (x => Identifier(x))

  def collectionLiteral: Parser[Expression] = "[" ~> repsep(expression, ",") <~ "]" ^^ (seq => Collection(seq: _*))

  def property: Parser[Expression] = identity ~ "." ~ escapableString ^^ {
    case v ~ "." ~ p => Property(Identifier(v), PropertyKey(p))
  }

  private val message = "Cypher does not support != for inequality comparisons. " +
    "It's used for nullable properties instead.\n" +
    "You probably meant <> instead. Read more about this in the operators chapter in the manual."

  def extract: Parser[Expression] = EXTRACT ~> parens(identity ~ IN ~ expression ~ (":" | "|") ~ expression) ^^ {
    case (id ~ _ ~ iter ~ _ ~ expression) => ExtractFunction(iter, id, expression)
  }

  def reduce: Parser[Expression] = REDUCE ~> parens(identity ~ "=" ~ expression ~ "," ~ identity ~ IN ~ expression ~ (":" | "|") ~ expression) ^^ {
    case (acc ~ _ ~ init ~ _ ~ id ~ _ ~ iter ~ _ ~ expression) => ReduceFunction(iter, id, expression, acc, init)
  }

  def coalesceFunc: Parser[Expression] = COALESCE ~> parens(commaList(expression)) ^^ {
    case expressions => CoalesceFunction(expressions: _*)
  }

  def filterFunc: Parser[Expression] = FILTER ~> parens(identity ~ IN ~ expression ~ (WHERE | ":") ~ predicate) ^^ {
    case symbol ~ in ~ collection ~ where ~ pred => FilterFunction(collection, symbol, pred)
  }

  def shortestPathFunc: Parser[Expression] = {
    def translate(abstractPattern: AbstractPattern): Maybe[ShortestPath] =

      matchTranslator(abstractPattern) match {
      case Yes(p@Seq(pattern: ShortestPath)) => Yes(p.asInstanceOf[Seq[ShortestPath]])
      case _                                 => No(Seq("This should not be here, how do I make this only match on shortest path?"))
    }

    // We don't want to try parsing anything but shortest path patterns here
    // Added the dontConsume so we see the pattern error messages here
    dontConsume(SHORTESTPATH|ALLSHORTESTPATHS) ~> usePath(translate) ^^ {
      case patterns:Seq[ShortestPath] => ShortestPathExpression(patterns.head)
    }
  }

  def function: Parser[Expression] = Parser {
    case in => {
      val inner = identity ~ parens(opt(commaList(expression | entity)))

      val innerResult = inner(in)

      if (!innerResult.successful) {
        innerResult.asInstanceOf[ParseResult[Nothing]]
      } else {
        val (name ~ args) = innerResult.get
        val arguments: List[Expression] = args.toList.flatten
        val funcOption = functions.get(name.toLowerCase)

        if (funcOption.isEmpty) {
          failure("unknown function", innerResult.next)
        } else {
          val func = funcOption.get
          if (!func.acceptsTheseManyArguments(arguments.size)) {
            failure("Wrong number of parameters for function " + name, innerResult.next)
          }

          Success(func.create(arguments), innerResult.next)
        }
      }

    }
  }

  private def func(numberOfArguments: Int, create: List[Expression] => Expression) = new Function(x => x == numberOfArguments, create)

  case class Function(acceptsTheseManyArguments: Int => Boolean, create: List[Expression] => Expression)

  val functions = Map(
    "labels" -> func(1, args => LabelsFunction(args.head)),
    "type" -> func(1, args => RelationshipTypeFunction(args.head)),
    "id" -> func(1, args => IdFunction(args.head)),
    "length" -> func(1, args => LengthFunction(args.head)),
    "nodes" -> func(1, args => NodesFunction(args.head)),
    "rels" -> func(1, args => RelationshipFunction(args.head)),
    "relationships" -> func(1, args => RelationshipFunction(args.head)),
    "abs" -> func(1, args => AbsFunction(args.head)),
    "acos" -> func(1, args => AcosFunction(args.head)),
    "asin" -> func(1, args => AsinFunction(args.head)),
    "atan" -> func(1, args => AtanFunction(args.head)),
    "atan2" -> func(1, args => Atan2Function(args(0), args(1))),
    "ceil" -> func(1, args => CeilFunction(args.head)),
    "cos" -> func(1, args => CosFunction(args.head)),
    "cot" -> func(1, args => CotFunction(args.head)),
    "degrees" -> func(1, args => DegreesFunction(args.head)),
    "e" -> func(0, args => EFunction()),
    "exp" -> func(1, args => ExpFunction(args.head)),
    "floor" -> func(1, args => FloorFunction(args.head)),
    "log" -> func(1, args => LogFunction(args.head)),
    "log10" -> func(1, args => Log10Function(args.head)),
    "pi" -> func(0, args => PiFunction()),
    "radians" -> func(1, args => RadiansFunction(args.head)),
    "rand" -> func(0, args => RandFunction()),
    "round" -> func(1, args => RoundFunction(args.head)),
    "sqrt" -> func(1, args => SqrtFunction(args.head)),
    "sign" -> func(1, args => SignFunction(args.head)),
    "sin" -> func(1, args => SinFunction(args.head)),
    "tan" -> func(1, args => TanFunction(args.head)),
    "head" -> func(1, args => HeadFunction(args.head)),
    "last" -> func(1, args => LastFunction(args.head)),
    "tail" -> func(1, args => TailFunction(args.head)),
    "replace" -> func(3, args => ReplaceFunction(args(0), args(1), args(2))),
    "left" -> func(2, args => LeftFunction(args(0), args(1))),
    "right" -> func(2, args => RightFunction(args(0), args(1))),
    "substring" -> Function(x => x == 2 || x == 3, args =>
      if(args.size == 2) SubstringFunction(args(0), args(1), None)
      else SubstringFunction(args(0), args(1), Some(args(2)))
    ),
    "lower" -> func(1, args => LowerFunction(args.head)),
    "upper" -> func(1, args => UpperFunction(args.head)),
    "ltrim" -> func(1, args => LTrimFunction(args.head)),
    "rtrim" -> func(1, args => RTrimFunction(args.head)),
    "trim" -> func(1, args => TrimFunction(args.head)),
    "str" -> func(1, args => StrFunction(args.head)),
    "timestamp" -> func(0, args => TimestampFunction()),
    "startnode" -> func(1, args => RelationshipEndPoints(args.head, start = true)),
    "endnode" -> func(1, args => RelationshipEndPoints(args.head, start = false)),
    "shortestpath" -> Function(x => false, args => null),
    "range" -> Function(x => x == 2 || x == 3, args => {
      val step = if (args.size == 2) Literal(1) else args(2)
      RangeFunction(args(0), args(1), step)
    })
  )

  def aggregateExpression: Parser[Expression] = countStar | aggregationFunction

  def aggregateFunctionNames: Parser[String] = COUNT | SUM | MIN | MAX | AVG | COLLECT | STDEV | STDEVP

  def aggregationFunction: Parser[Expression] = aggregateFunctionNames ~ parens(opt(DISTINCT) ~ expression) ^^ {
    case function ~ (distinct ~ inner) => {

      val aggregateExpression = function match {
        case "count" => Count(inner)
        case "sum" => Sum(inner)
        case "min" => Min(inner)
        case "max" => Max(inner)
        case "avg" => Avg(inner)
        case "collect" => Collect(inner)
        case "stdev" => Stdev(inner)
        case "stdevp" => StdevP(inner)
      }

      if (distinct.isEmpty) {
        aggregateExpression
      }
      else {
        Distinct(aggregateExpression, inner)
      }
    }
  }

  def percentileFunctionNames: Parser[String] = PERCENTILE_CONT | PERCENTILE_DISC

  def percentileFunction: Parser[Expression] = percentileFunctionNames ~ parens(expression ~ "," ~ expression) ^^ {
    case function ~ (property ~ "," ~ percentile) => function match {
      case "percentile_cont" => PercentileCont(property, percentile)
      case "percentile_disc" => PercentileDisc(property, percentile)
    } 
  }

  def countStar: Parser[Expression] = COUNT ~> parens("*") ^^^ CountStar()

  private def caseDefault: Parser[Expression] = ELSE ~> expression

  def simpleCase:Parser[Expression] = {
    def alternative: Parser[(Expression, Expression)] = WHEN ~ expression ~ THEN ~ expression ^^ {
      case when ~ e1 ~ then ~ e2 => e1 -> e2
    }

    CASE ~ expression ~ rep1(alternative) ~ opt(caseDefault) ~ END ^^ {
      case c ~ in ~ alternatives ~ default ~ end => SimpleCase(in, alternatives, default)
    }
  }

  def genericCase:Parser[Expression] = {
    def alternative: Parser[(Predicate, Expression)] = WHEN ~ predicate ~ THEN ~ expression ^^ {
      case when ~ e1 ~ then ~ e2 => e1 -> e2
    }

    CASE ~ rep1(alternative) ~ opt(caseDefault) ~ END ^^ {
      case c ~ alternatives ~ default ~ end => GenericCase(alternatives, default)
    }
  }
}

trait DefaultTrue {
  self: HasOptionalDefault[Boolean] =>

  override def default = Some(true)
}

trait DefaultFalse {
  self: HasOptionalDefault[Boolean] =>

  override def default = Some(false)
}












