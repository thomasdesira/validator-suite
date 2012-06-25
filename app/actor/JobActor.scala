package org.w3.vs.actor

import org.w3.vs._
import org.w3.vs.model._
import org.w3.util._
import org.w3.vs.assertor._
import org.w3.vs.actor.message._
import akka.actor._
import play.api.libs.iteratee._
import play.Logger
import System.{ currentTimeMillis => now }
import org.w3.vs.http._
import akka.util.duration._
import org.w3.util.Headers.wrapHeaders
import scalaz.Scalaz._
import org.joda.time.DateTime
import org.w3.util.akkaext._
import org.joda.time.DateTimeZone

// TODO extract all pure function in a companion object
class JobActor(job: Job)(implicit val configuration: VSConfiguration)
extends Actor with FSM[(RunActivity, ExplorationMode), Run] with Listeners {

  import configuration._

  val assertionsActorRef = context.actorOf(Props(new AssertionsActor(job)), "assertions")

  // TODO is it really what we want? I don't think so

  val logger = Logger.of(classOf[JobActor])

  val http = system.actorFor(system / "http")

  lazy val (enumerator, channel) = Concurrent.broadcast[RunUpdate]
  
  /**
   * A shorten id for logs readability
   */
  def shortId: String = job.id.shortId + "/" + stateData.id.shortId

  implicit def strategy = job.strategy

  // at instanciation of this actor

  // TODO
  configuration.system.scheduler.schedule(2 seconds, 2 seconds, self, 'Tick)

  var lastRun =
    Run(job = job, createdAt = DateTime.now(DateTimeZone.UTC), completedAt = None) // TODO get last run data from the db?

  startWith(lastRun.state, lastRun)

  def stateOf(run: Run): State = {
    val currentState = stateName
    val newState = run.state
    val stayOrGoto =
      if (currentState === newState) {
        stay()
      } else {
        logger.debug("%s: transition to new state %s" format (shortId, newState.toString))
        goto(newState)
      }
    val run_ = if (run.noMoreUrlToExplore && run.pendingAssertions == 0) {
      val completed = run.copy(completedAt = Some(DateTime.now(DateTimeZone.UTC)))
      completed.save()
      completed
    } else run
    stayOrGoto using run_
  }

  when((Idle, ProActive))(stateFunction)
  when((Idle, Lazy))(stateFunction)
  when((Running, ProActive))(stateFunction)
  when((Running, Lazy))(stateFunction)

  
  def stateFunction(): StateFunction = {
    case Event(GetRun, run) => {
      // logger.debug("%s: received a GetRun" format shortId)
      sender ! run
      stay()
    }
    case Event(GetJobEnumerator, run) => {
      sender ! enumerator
      stay()
    }
    case Event('Tick, run) => {
      // do it only if necessary (ie. something has changed since last time)
      if (run ne lastRun) {
        // Should really the frequency of broadcast and the frequency of saves be coupled?
        run.save()
        // tell the subscribers about the current run for this run
        val msg = UpdateData(run.jobData, run.activity)
        tellEverybody(msg)
        lastRun = run
      }
      stay()
    }
    case Event(result: AssertorResult, _run) => {
      result.assertions.map{assertionC => 
        assertionC.assertion.save()
        assertionC.contexts.map(_.save())
      }
      if (result.runId === _run.id) {
        tellEverybody(NewAssertorResult(result))
        stateOf(_run.withAssertorResponse(result))
      } else {
        stay()
      }
    }
    case Event(failure: AssertorFailure, _run) => {
      if (failure.runId === _run.id) {
        stateOf(_run.withAssertorResponse(failure))
      } else {
        stay()
      }
    }
    case Event(response: ResourceResponse, _run) => {

      def getAssertors(httpResponse: HttpResponse): List[FromHttpResponseAssertor] =
        for {
          mimetype <- httpResponse.headers.mimetype.toList if httpResponse.action === GET
          assertorName <- strategy.assertorSelector.get(mimetype).flatten
        } yield Assertors.get(assertorName)

      logger.debug("<<< " + response.url)
      response.save()
      tellEverybody(NewResource(response))

      val runWithResponse = _run.withResourceResponse(response)

      (response, runWithResponse.explorationMode) match {
        // we do something only if
        // * we're ProActive
        // * it's an HttpResponse (not a failure)
        // * this is for the current run (btw, it's not an error when it's not)
        case (httpResponse: HttpResponse, ProActive) if response.runId === runWithResponse.id => {
          val (runWithNewURLs, newUrls) = runWithResponse.withNewUrlsToBeExplored(httpResponse.extractedURLs)
          if (!newUrls.isEmpty)
            logger.debug("%s: Found %d new urls to explore. Total: %d" format (shortId, newUrls.size, runWithResponse.numberOfKnownUrls))
          // schedule assertions (why only if it's a GET?)
          val assertors = getAssertors(httpResponse)
          if (httpResponse.action === GET)
            assertors foreach { assertor => assertionsActorRef ! AssertorCall(assertor, httpResponse) }
          // schedule new fetches
          val runWithPendingAssertions =
            runWithNewURLs.copy(pendingAssertions = runWithNewURLs.pendingAssertions + assertors.size)
          stateOf(scheduleNextURLsToFetch(runWithPendingAssertions))
        }
        case (errorResponse: ErrorResponse, ProActive) => stateOf(scheduleNextURLsToFetch(runWithResponse))
        case _ => stateOf(runWithResponse)
      }

    }
    case Event(msg: ListenerMessage, _) => {
      listenerHandler(msg)
      stay()
    }
    case Event(BeProactive(_), run) => stateOf(run.withMode(ProActive))
    case Event(BeLazy(_), run) => stateOf(run.withMode(Lazy))
    case Event(Refresh(_), run) => {
      // logger.debug("%s: received a Refresh" format shortId)
      val firstURLs = List(strategy.entrypoint)
      val freshRun = Run(job = job, createdAt = DateTime.now(DateTimeZone.UTC), completedAt = None)
      val (runData, _) = freshRun.withNewUrlsToBeExplored(firstURLs)
      stateOf(scheduleNextURLsToFetch(runData))
    }
    case Event(Stop(_), run) => {
      assertionsActorRef ! Stop
      stateOf(run.stopMe())
    }
    case Event(a, run) => {
      logger.error("uncatched event: " + a)
      stay()
    }
  }

  onTransition {
    case _ -> _ => {
      val msg = UpdateData(nextStateData.jobData, nextStateData.activity)
      tellEverybody(msg)
      if (nextStateData.noMoreUrlToExplore) {
        logger.info("%s: Exploration phase finished. Fetched %d pages" format (shortId, nextStateData.fetched.size))
        if (nextStateData.pendingAssertions == 0) {
          logger.info("Assertion phase finished.")
        }
      }
    }
  }

  private final def tellEverybody(msg: Any): Unit = {
    // tell the organization
    context.actorFor("../..") ! msg
    // tell all the listeners
    tellListeners(msg)
  }

  /**
   * tries to get the latest snapshot, then replays the events that happened on it
   */
//  private final def initialConditions(): Run = store.latestSnapshotFor(job.id).waitResult() match {
//    case None => Run.makeFresh(jobId = job.id, strategy = strategy)
//    case Some(snapshot) => replayEventsOn(Run(strategy, snapshot), Some(snapshot.createdAt))
//  }

//  final private def replayEventsOn(_run: Run, afterOpt: Option[DateTime]): Run = {
//    var run = _run
//    store.listResourceInfos(job.id, after = afterOpt).waitResult() foreach { resourceInfo =>
//      run = run.withCompletedFetch(resourceInfo.url)
//    }
//    store.listAssertorResponses(job.id, after = afterOpt).waitResult() foreach { assertorResult =>
//      run = run.withAssertorResponse(assertorResult)
//    }
//    run
//  }

  private final def scheduleNextURLsToFetch(run: Run): Run = {
    val (newObservation, explores) = run.takeAtMost(MAX_URL_TO_FETCH)
    val runId = run.id
    explores foreach { explore => fetch(explore, runId) }
    newObservation
  }

  /**
   * Fetch one URL
   *
   * The kind of fetch is decided by the strategy (can be no fetch)
   */
  private final def fetch(url: URL, runId: RunId): Unit = {
    val action = strategy.getActionFor(url)
    action match {
      case GET => {
        logger.debug("%s: GET >>> %s" format (shortId, url))
        http ! Fetch(url, GET, runId, job.id)
      }
      case HEAD => {
        logger.debug("%s: HEAD >>> %s" format (shortId, url))
        http ! Fetch(url, HEAD, runId, job.id)
      }
      case IGNORE => {
        logger.debug("%s: Ignoring %s. If you're here, remember that you have to remove that url is not pending anymore..." format (shortId, url))
      }
    }
  }

}
