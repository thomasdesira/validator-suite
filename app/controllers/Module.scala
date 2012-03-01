package org.w3.vs.controllers

import org.w3.vs.model.User
import org.w3.vs.model.Job
import org.w3.vs.prod.configuration.store

import play.api.mvc.Request
import play.api.mvc.AnyContent
import play.api.mvc.Result
import play.api.mvc.Results
import play.api.mvc.Action
import play.api.mvc.WebSocket
import play.api.mvc.RequestHeader

import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsValue

import scalaz._
import Scalaz._
import Validation._
import org.w3.util.Pimps._

trait ActionModule[A] extends Composable[A, ActionReq, ActionRes, Action[AnyContent]]
trait SocketModule[A] extends Composable[A, SocketReq, SocketRes, WebSocket[JsValue]]

object IfAjax extends ActionModule[Boolean] {
  def extract(req: ActionReq) = {
    req.headers.get("x-requested-with") match {
      case Some("xmlhttprequest") => Success(true)
      case _ => Failure(play.api.mvc.Results.BadRequest("This request can only be an Ajax request"))
    }
  }
}
object IfNotAjax extends ActionModule[Boolean] {
  def extract(req: ActionReq) = {
    req.headers.get("x-requested-with") match {
      case None => Success(true)
      case _ => Failure(play.api.mvc.Results.BadRequest("This request cannot be an Ajax request"))
    }
  }
}
object OptionAjax extends ActionModule[Option[Boolean]] {
  def extract(req: ActionReq) = {
    req.headers.get("x-requested-with") match {
      case Some("xmlhttprequest") => Success(Some(true))
      case _ => Success(None)
    }
  }
}

object IfAuth extends ActionModule[User] { 
  def extract(req: Request[AnyContent]) =
    for {
      email <- req.session.get("email") toSuccess Results.Redirect(controllers.routes.Application.login)
      userOpt <- store.getUserByEmail(email) failMap { t => Results.InternalServerError }
      user <- userOpt toSuccess Results.Redirect(controllers.routes.Application.login)
    } yield user
}

object IfJob {
  def apply(id: Job#Id) = new IfJob {
    val jobId = id
  }
}
trait IfJob extends ActionModule[Job] { 
  val jobId: Job#Id  
  def extract(req: Request[AnyContent]): Validation[Result, Job] =
    store.getJobById(jobId).fold(
      f => Failure(Results.InternalServerError("Error not implemented in Module.scala/IfJob")), // TODO
      job => Success(job)
    )
}

//Product with Serializable with 
// Either[(play.api.libs.iteratee.Iteratee[play.api.libs.json.JsValue,Unit], java.lang.Object with 
// play.api.libs.iteratee.Enumerator[Nothing]),Nothing]

object IfAuthSocket extends SocketModule[User] {
  def extract(req: RequestHeader): Validation[SocketRes, User] = {
    def default: SocketRes = (Iteratee.foreach[JsValue](e => println(e)), Enumerator.eof)
    for {
      email <- req.session.get("email") toSuccess default // is this the desired behaviour?
      userOpt <- store.getUserByEmail(email) failMap { t => default }
      user <- userOpt toSuccess default
    } yield user
  }
}

object IfNotAuth extends ActionModule[Boolean] {
  def extract(req: ActionReq) = {
    req.session.get("email") match { 
      case None => Success(true)
      case _ => Failure(Results.Redirect(controllers.routes.Validator.index)) 
    }
  }
}

object OptionAuth extends ActionModule[Option[User]] {
  def extract(req: ActionReq) =
    for {
      email <- req.session.get("email") toSuccess Results.Redirect(controllers.routes.Application.login)
      userOpt <- store.getUserByEmail(email) failMap { t => Results.InternalServerError }
    } yield userOpt
}