package org.w3.vs.model

import org.w3.banana._
import org.w3.banana.diesel._
import scalaz._
import scalaz.Scalaz._
import scalaz.Validation._
import org.w3.util._

trait LiteralBinders[Rdf <: RDF] {

  val ops: RDFOperations[Rdf]

  import ops._

  private val xsd = XSDPrefix(ops)
  private val anyURI = xsd("anyURI")

  implicit val urlBinder = new LiteralBinder[Rdf, URL] {

    def fromLiteral(literal: Rdf#Literal): Validation[BananaException, URL] = {
      Literal.fold(literal)(
        {
          case TypedLiteral(lexicalForm, datatype) =>
            if (datatype == anyURI)
              try {
                Success(URL(lexicalForm))
              } catch {
                case t => Failure(FailedConversion(literal.toString + " is of type xsd:anyURI but its lexicalForm could not be made a URL: " + lexicalForm))
              }
            else
              Failure(FailedConversion(lexicalForm + " has datatype " + datatype))
        },
        langLiteral => Failure(FailedConversion(langLiteral + " is a langLiteral, you want to access its lexical form"))
      )
    }

    def toLiteral(t: URL): Rdf#Literal = TypedLiteral(t.toString, anyURI)

  }

  // TODO decide if it's a uri or a datatype (if the latter, use a real datatype)
  implicit val assertionSeverityBinder = new LiteralBinder[Rdf, AssertionSeverity] {

    def fromLiteral(literal: Rdf#Literal): Validation[BananaException, AssertionSeverity] = {
      Literal.fold(literal)(
        {
          case TypedLiteral(lexicalForm, datatype) =>
            if (datatype == xsd.string)
              try {
                Success(AssertionSeverity(lexicalForm))
              } catch {
                case t => Failure(FailedConversion(literal.toString + " is of type xsd:string but its lexicalForm could not be made a AssertionSeverity: " + lexicalForm))
              }
            else
              Failure(FailedConversion(lexicalForm + " has datatype " + datatype))
        },
        langLiteral => Failure(FailedConversion(langLiteral + " is a langLiteral, you want to access its lexical form"))
      )
    }

    def toLiteral(t: AssertionSeverity): Rdf#Literal = t match {
      case Error => "error"
      case Warning => "warning"
      case Info => "info"
    }

  }


}