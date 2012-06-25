package org.w3.vs.model

import java.nio.channels.ClosedChannelException
import org.joda.time.{ DateTime, DateTimeZone }
import org.w3.util.akkaext._
import org.w3.vs.actor.message._
import org.w3.vs.VSConfiguration
import akka.actor._
import play.api.libs.iteratee._
import play.Logger
import org.w3.util._
import scalaz.Scalaz._
import scalaz._
import org.w3.banana._

// closed with its strategy
case class Job(
    id: JobId = JobId(),
    name: String,
    createdOn: DateTime = DateTime.now(DateTimeZone.UTC),
    creatorId: UserId,
    organizationId: OrganizationId,
    strategy: Strategy)(implicit conf: VSConfiguration) {

  import conf.system
  implicit def timeout = conf.timeout
  private val logger = Logger.of(classOf[Job])
  
  def toValueObject: JobVO = 
    JobVO(id, name, createdOn, creatorId, organizationId, strategy.id)
  
  def getCreator(): FutureVal[Exception, User] = User.get(creatorId)
  
  def getOrganization(): FutureVal[Exception, Organization] = 
    Organization.get(organizationId)
  
  def getRun(): FutureVal[Throwable, Run] = {
    implicit def ec = conf.webExecutionContext
    (PathAware(organizationsRef, path) ? GetRun).mapTo[Run]
  }
  
  // Get all runVos for this job, group by id, and for each runId take the latest completed jobData if any
  def getHistory(): FutureVal[Exception, Iterable[JobData]] =
    Run.getRunVOs(id) map { runVOs => {
      runVOs groupBy (_.id) map { case (id, datas) => {
        val completed = datas filter ( _.completedAt.isDefined )
        completed.isEmpty fold (
          None,
          Some(completed maxBy ( _.completedAt.get ))
        )
      }} collect {case Some(runVO) => JobData(runVO)}
    }}

  def getLastCompleted(): FutureVal[Exception, Option[DateTime]] = {
    //getHistory() map { times => times.isEmpty.fold(None, times.maxBy(_.completedAt.get).completedAt) }
    Job.getLastCompleted(this)
  }
    
  def save(): FutureVal[Exception, Job] = Job.save(this) map { _ => this }
  
  def delete(): FutureVal[Exception, Unit] = {
    cancel()
    Job.delete(id)
  }
  
  // couldn't run return a FutureVal[F, Run]?
  def run(): Unit = 
    PathAware(organizationsRef, path) ! Refresh()
  
  def cancel(): Unit = 
    PathAware(organizationsRef, path) ! Stop()

  def on(): Unit = 
    PathAware(organizationsRef, path) ! BeProactive()

  def off(): Unit = 
    PathAware(organizationsRef, path) ! BeLazy()

  lazy val enumerator: Enumerator[RunUpdate] = {
    val (_enumerator, channel) = Concurrent.broadcast[RunUpdate]
    val subscriber: ActorRef = system.actorOf(Props(new Actor {
      def receive = {
        case msg: RunUpdate =>
          try {
            channel.push(msg)
          } catch { 
            case e: ClosedChannelException => {
              logger.error("ClosedChannel exception: ", e)
              channel.eofAndEnd()
            }
            case e => {
              logger.error("Enumerator exception: ", e)
              channel.eofAndEnd()
            }
          }
        case msg => logger.error("subscriber got " + msg)
      }
    }))
    listen(subscriber)
    _enumerator
  }

  def listen(implicit listener: ActorRef): Unit =
    PathAware(organizationsRef, path).tell(Listen(listener), listener)
  
  def deafen(implicit listener: ActorRef): Unit =
    PathAware(organizationsRef, path).tell(Deafen(listener), listener)
  
  private val organizationsRef = system.actorFor(system / "organizations")
  
  private val path = system / "organizations" / organizationId.toString / "jobs" / id.toString
  
  def !(message: Any)(implicit sender: ActorRef = null): Unit =
    PathAware(organizationsRef, path) ! message

}

object Job {

  def getJobVO(id: JobId)(implicit conf: VSConfiguration): FutureVal[Exception, JobVO] = {
    import conf.binders._
    implicit val context = conf.webExecutionContext
    val uri = JobUri(id)
    FutureVal(conf.store.getNamedGraph(uri)) flatMap { graph => 
      FutureVal.pureVal[Throwable, JobVO]{
        val pointed = PointedGraph(uri, graph)
        JobVOBinder.fromPointedGraph(pointed)
      }(t => t)
    }
  }


  def get(id: JobId)(implicit conf: VSConfiguration): FutureVal[Exception, Job] = {
    for {
      vo <- getJobVO(id)
      strategy <- Strategy.get(vo.strategyId)
    } yield {
      Job(id, vo.name, vo.createdOn, vo.creatorId, vo.organizationId, strategy)
    }
  }
  
  def getFor(userId: UserId)(implicit conf: VSConfiguration): FutureVal[Exception, Iterable[Job]] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders.{ xsd => _, _ }
    val query = """
CONSTRUCT {
  ?jobUri ?p ?o .
  ?s2 ?p2 ?o2
} WHERE {
  graph <#userUri> {
    <#userUri> ont:organizationId ?organizationUri
  } .
  graph ?g {
    ?jobUri ont:organization ?organizationUri .
    ?jobUri ont:strategy ?strategyUri .
    ?jobUri ?p ?o .
  } .
  graph ?strategyUri {
    ?s2 ?p2 ?o2
  }
}
""".replaceAll("#userUri", UserUri(userId).toString)
    val construct = SparqlOps.ConstructQuery(query, ont)
    FutureVal(store.executeConstruct(construct)) flatMapValidation { graph => fromGraph(conf)(graph) }
  }
  
  def getFor(organizationId: OrganizationId)(implicit conf: VSConfiguration): FutureVal[Exception, Iterable[Job]] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders.{ xsd => _, _ }
    val query = """
CONSTRUCT {
  ?jobUri ?p ?o .
  ?s2 ?p2 ?o2
} WHERE {
  graph ?g {
    ?jobUri ont:organization <#organizationUri> .
    ?jobUri ont:strategy ?strategyUri .
    ?jobUri ?p ?o .
  } .
  graph ?strategyUri {
    ?s2 ?p2 ?o2
  }
}
""".replaceAll("#organizationUri", OrganizationUri(organizationId).toString)
    val construct = SparqlOps.ConstructQuery(query, ont)
    FutureVal(store.executeConstruct(construct)) flatMapValidation { graph => fromGraph(conf)(graph) }
  }
  
  def getFor(strategyId: StrategyId)(implicit conf: VSConfiguration): FutureVal[Exception, Iterable[Job]] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders.{ xsd => _, _ }
    val query = """
CONSTRUCT {
  ?jobUri ?p ?o .
  ?s2 ?p2 ?o2
} WHERE {
  graph ?g {
    ?jobUri ont:strategy <#strategyUri> .
    ?jobUri ?p ?o .
  } .
  graph <#strategyUri> {
    ?s2 ?p2 ?o2
  }
}
""".replaceAll("#strategyUri", StrategyUri(strategyId).toString)
    val construct = SparqlOps.ConstructQuery(query, ont)
    FutureVal(store.executeConstruct(construct)) flatMapValidation { graph => fromGraph(conf)(graph) }
  }
  
  def fromPointedGraph(conf: VSConfiguration)(pointed: PointedGraph[conf.Rdf]): Validation[BananaException, Job] = {
    implicit val c = conf
    import conf.diesel._
    import conf.binders._
    for {
      vo <- JobVOBinder.fromPointedGraph(pointed)
      strategyVO <- (pointed / ont.strategy).exactlyOnePointedGraph.flatMap(StrategyVOBinder.fromPointedGraph(_))
    } yield {
      val strategy = Strategy(strategyVO)
      Job(vo.id, vo.name, vo.createdOn, vo.creatorId, vo.organizationId, strategy)
    }
  }

  def fromGraph(conf: VSConfiguration)(graph: conf.Rdf#Graph): Validation[BananaException, Iterable[Job]] = {
    import conf.diesel._
    import conf.binders._
    val jobsVal: Iterable[Validation[BananaException, Job]] =
      graph.getAllInstancesOf(ont.Job) map { pointed => fromPointedGraph(conf)(pointed) }
    jobsVal.toList.sequence[({type l[X] = Validation[BananaException, X]})#l, Job]
  }

  def getCreatedBy(creator: UserId)(implicit conf: VSConfiguration): FutureVal[Exception, Iterable[Job]] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders.{ xsd => _, _ }
    import conf.diesel._
    val query = """
CONSTRUCT {
  ?jobUri ?p ?o .
  ?s2 ?p2 ?o2
} WHERE {
  graph ?g {
    ?jobUri ont:creator <#creatorUri> .
    ?jobUri ?p ?o .
    ?jobUri ont:strategy ?strategyUri .
  } .
  graph ?strategyUri {
    ?s2 ?p2 ?o2
  }
}
""".replaceAll("#creatorUri", UserUri(creator).toString)
    val construct = SparqlOps.ConstructQuery(query, xsd, ont)
    FutureVal(store.executeConstruct(construct)) flatMapValidation { graph => fromGraph(conf)(graph) }
  }

  def getLast(conf: VSConfiguration)(jobId: JobId, property: conf.Rdf#URI): FutureVal[Exception, Option[(RunId, DateTime)]] = {
    implicit val context = conf.webExecutionContext
    import conf._
    import conf.binders.{ xsd => _, _ }
    import conf.diesel._
    val query = """
SELECT ?run ?last WHERE {
  {
    SELECT (MAX(?timestamp) AS ?last) WHERE {
      graph ?run {
        ?run ont:jobId <#jobUri> ;
             <#property> ?timestamp
      }
    }
  }
  graph ?run {
    ?run ont:jobId <#jobUri> ;
          <#property> ?last
  }
}
""".replaceAll("#jobUri", JobUri(jobId).toString).replaceAll("#property", property.toString)
    import SparqlOps._
    val select = SelectQuery(query, xsd, ont)
    implicit val binder = UriToNodeBinder(RunUri)
    FutureVal(store.executeSelect(select)) flatMapValidation { rows =>
      // it's an aggregate query (MAX), so there is always one row
      rows.toIterable.headOption match {
        case Some(row) =>
          for {
            run <- row("run") flatMap (_.as[RunId])
            last <- row("last") flatMap (_.as[DateTime])
          } yield Some(run, last)
        case None => Success[Exception, Option[(RunId, DateTime)]](None)
      }
    }
  }

  def getLastCreated(jobId: JobId)(implicit conf: VSConfiguration): FutureVal[Exception, Option[(RunId, DateTime)]] = {
    import conf.binders.ont
    getLast(conf)(jobId, ont.createdAt)
  }

  def getLastCompleted(jobId: JobId)(implicit conf: VSConfiguration): FutureVal[Exception, Option[DateTime]] = {
    import conf.binders.ont
    getLast(conf)(jobId, ont.completedAt) map { _ map { _._2 } }
  }
  
  def saveJobVO(vo: JobVO)(implicit conf: VSConfiguration): FutureVal[Exception, Unit] = {
    import conf.binders._
    implicit val context = conf.webExecutionContext
    val graph = JobVOBinder.toPointedGraph(vo).graph
    val result = conf.store.addNamedGraph(JobUri(vo.id), graph)
    FutureVal(result)
  }

  def save(job: Job)(implicit conf: VSConfiguration): FutureVal[Exception, Unit] =
    for {
      _ <- saveJobVO(job.toValueObject)
      _ <- Strategy.save(job.strategy)
    } yield ()
  
  def delete(id: JobId)(implicit conf: VSConfiguration): FutureVal[Exception, Unit] = {
    import conf.binders._
    implicit val context = conf.webExecutionContext
    FutureVal(conf.store.removeGraph(JobUri(id)))
  }

}

