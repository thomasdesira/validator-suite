package controllers

import org.w3.vs.exception._
import org.w3.vs.controllers._
import org.w3.vs.model.{JobId, User}
import org.w3.vs.view.form.LoginForm
import play.Logger.ALogger
import play.api.Play._
import play.api.cache.Cache
import play.api.i18n.Messages
import play.api.libs.iteratee.{Enumeratee, Enumerator, Iteratee}
import play.api.libs.json.JsValue
import play.api.mvc._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.w3.vs.exception.UnknownJob
import play.api.mvc.AsyncResult
import scala.collection.mutable.LinkedHashMap

trait VSController extends Controller {

  val logger: ALogger

  // TODO: make the implicit explicit!!!
  implicit val configuration: org.w3.vs.VSConfiguration = org.w3.vs.Prod.configuration
  implicit val system = configuration.system

  def CloseWebsocket = (Iteratee.ignore[JsValue], Enumerator.eof)

  def isAjax(implicit reqHeader: RequestHeader) = {
    reqHeader.headers get ("x-requested-with") match {
      case Some("XMLHttpRequest") => true
      case _ => false
    }
  }

  sealed trait Format {
    def contentType: String
  }
  case class Html(contentType: String) extends Format
  case object Json extends Format { val contentType = "application/json" }
  case object Rdf extends Format { val contentType = "application/rdf+xml" }
  case object Stream extends Format { val contentType = "text/event-stream" }

  object Format {
    val supported: LinkedHashMap[String, Format] = LinkedHashMap(
      "text/html"             -> Html("text/html"),
      "application/xhtml+xml" -> Html("application/xhtml+xml"),
      "application/json"      -> Json,
      "application/rdf+xml"   -> Rdf,
      "*/*"                   -> Html("text/html"),
      "text/event-stream"     -> Stream
    )
  }

/*  def format(implicit reqHeader: RequestHeader, supportedTypes: Seq[String] = Format.supported.keys.toSeq): Format = {
    // get the first supported content type
    val requestFormat = reqHeader.headers.get("Accept").map(
    _.split(",").map(
    _.trim.replaceAll(";.*$", "") // ignore priority weight
    ).find(Format.supported.values.toList.contains(_))
    ).flatten
    requestFormat match {
    case Some("text/html") => Html("application/xhtml+xml")
    case Some("application/xhtml+xml") => Html("application/xhtml+xml")
    case Some("application/json") => Json
    case Some("application/rdf+xml") => Rdf
    case Some("* / *") => Html("text/html")
    case _ => throw NotAcceptableException(supportedTypes)
    }
    }*/

  def format(f: PartialFunction[Format, Result])(implicit reqHeader: RequestHeader, supportedTypes: Seq[String] = Format.supported.keys.toSeq): Result = {
    // get the first supported content type
    val requestFormat: Option[Format] =
      reqHeader.headers.get("Accept").map(
        _.split(",").map(
          _.trim.replaceAll(";.*$", "") // ignore priority weight
        ).find(Format.supported.keys.toList.contains(_))
      ).flatten.map(Format.supported(_))
    if (requestFormat.isDefined && f.isDefinedAt(requestFormat.get))
      f(requestFormat.get).as(requestFormat.get.contentType)
    else {
      throw new NotAcceptableException(Format.supported.values.filter(f.isDefinedAt(_)).map(_.contentType).toSeq.distinct)
    }
  }

  implicit def callToString(call: Call): String = call.toString()

  def getUser()(implicit reqHeader: RequestHeader): Future[User] = {
    for {
      email <- Future(session.get("email").get) recoverWith { case _ => Future(throw Unauthenticated) }
      user <- Future(Cache.getAs[User](email).get) recoverWith { case _ => User.getByEmail(email) }
    } yield {
      Cache.set(email, user, current.configuration.getInt("cache.user.expire").getOrElse(300))
      user
    }
  }

  /*def AuthAsyncAction(f: Request[AnyContent] => User => Future[Result]): ActionA = Action { implicit req =>
    AsyncResult {
      (for {
        user <- getUser()
        result <- f(req)(user)
      } yield result.withHeaders(("Cache-Control", "no-cache, no-store"))).recover(toError)
    }
  }*/

  def AuthAsyncAction(f: Request[AnyContent] => User => Future[PartialFunction[Format, Result]]): ActionA = Action { implicit req =>
    AsyncResult {
      (for {
        user <- getUser()
        result <- f(req)(user)
      } yield format(result).withHeaders(("Cache-Control", "no-cache, no-store"))).recover(toError)
    }
  }

  /*def AuthAction(f: Request[AnyContent] => User => Result): ActionA = AuthAsyncAction {
    req => user => Future.successful(f(req)(user))
  }*/

  def AuthAction(f: Request[AnyContent] => User => PartialFunction[Format, Result]): ActionA = AuthAsyncAction {
    req => user => Future.successful(f(req)(user))
  }

  def toError(implicit reqHeader: RequestHeader): PartialFunction[Throwable, Result] = {
    // TODO timeout, store exception, etc...
    //case ForceResult(result) => result
    case UnknownJob(id) => {
      if (isAjax) {
        NotFound(Messages("exceptions.job.unknown", id))
      } else {
        SeeOther(routes.Jobs.index).flashing(("error" -> Messages("exceptions.job.unknown", id)))
      }
    }
    case _: UnauthorizedException => {
      format {
        case x: Html => Unauthorized(views.html.login(LoginForm.blank, List(("error", Messages("application.unauthorized"))))).withNewSession.as(x.contentType)
        case _ => Unauthorized
      }
    }
    case NotAcceptableException(supportedTypes) => {
      NotAcceptable("Unable to generate an acceptable response. Available content-types:" + supportedTypes.mkString("\n* ", "\n* ", ""))
    }
    case t: Throwable => {
      logger.error("Unexpected exception: " + t.getMessage, t)
      format {
        case x: Html => InternalServerError(views.html.error(List(("error", Messages("exceptions.unexpected", t.getMessage))))).as(x.contentType)
        case _ => InternalServerError
      }
    }
  }



}