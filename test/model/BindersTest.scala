package org.w3.vs.model

import org.scalatest._
import org.scalatest.matchers._
import scalaz._
import org.w3.banana._
import org.w3.banana.jena._
import org.joda.time.{ DateTime, DateTimeZone }
import org.w3.util.URL

class BindersTest extends WordSpec with MustMatchers {

  val binders = Binders(JenaOperations, JenaGraphUnion, JenaGraphTraversal)
  import binders._

  def testSerializeDeserialize[T](binder: PointedGraphBinder[Jena, T])(t: T) = {
    import binder._
    fromPointedGraph(toPointedGraph(t)) must be === (Success(t))
  }

  "OrganizationVO" in {
    testSerializeDeserialize(OrganizationVOBinder) {
      OrganizationVO(name = "foo", admin = UserId())
    }
  }

  "JobVO" in {
    testSerializeDeserialize(JobVOBinder) {
      JobVO(
        name = "foo",
        createdOn = DateTime.now(DateTimeZone.UTC),
        creatorId = UserId(),
        organizationId = OrganizationId(),
        strategyId = StrategyId())
    }
  }

  "AssertionVO no description" in {
    testSerializeDeserialize(AssertionVOBinder) {
      AssertionVO(
        url = URL("http://example.com/foo"),
        lang = "fr",
        title = "bar",
        severity = Warning,
        description = None,
        assertorResponseId = AssertorResponseId())
    }
  }

  "AssertionVO with description" in {
    testSerializeDeserialize(AssertionVOBinder) {
      AssertionVO(
        url = URL("http://example.com/foo"),
        lang = "fr",
        title = "bar",
        severity = Warning,
        description = Some("some desc"),
        assertorResponseId = AssertorResponseId())
    }
  }

  "ContextVO" in {
    testSerializeDeserialize(ContextVOBinder) {
      ContextVO(
        content = "foo",
        line = Some(42),
        column = None,
        assertionId = AssertionId())
    }
  }

  "AssertorResultVO" in {
    testSerializeDeserialize(AssertorResultVOBinder) {
      AssertorResultVO(
        jobId = JobId(),
        runId = RunId(),
        assertorId = AssertorId(),
        sourceUrl = URL("http://example.com/foo"),
        timestamp = DateTime.now(DateTimeZone.UTC))

    }
  }

  "JobDataVO" in {
    testSerializeDeserialize(JobDataVOBinder) {
      JobDataVO(
        jobId = JobId(),
        resources = 42,
        errors = 43,
        warnings = 44,
        timestamp = DateTime.now(DateTimeZone.UTC))
    }
  }

}