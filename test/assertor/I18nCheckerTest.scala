package org.w3.vs.assertor

import org.scalatest._
import org.scalatest.matchers.MustMatchers
import org.w3.vs.http._
import org.w3.vs.model._
import org.w3.util.URL
import org.w3.vs.view.Helper
import java.io.File

object I18nCheckerTest {

  val cacheDirectory = new File("test/resources/cache")
  val cache = Cache(cacheDirectory)

}

class I18nCheckerTest extends WordSpec with MustMatchers with AssertionsMatchers with BeforeAndAfterAll {

  import I18nCheckerTest.cache

  override def beforeAll(): Unit = {
    cache.setAsDefaultCache()
  }
  
  override def afterAll(): Unit = {
    cache.restorePreviousCache()
  }

  "http://www.w3.org/International/tests/i18n-checker/utf16/utf16be-html.html should have at least one error" in {
    val url = URL("http://www.w3.org/International/tests/i18n-checker/utf16/utf16be-html.html")
    val assertions = I18nChecker.assert(url, Map.empty)
    assertions must (haveErrors)
  }

  "http://www.w3.com/ should have at least one error" in {
    val url = URL("http://www.w3.org/")
    val assertions = I18nChecker.assert(url, Map.empty)
    assertions must not (haveErrors)
  }


//  "http://www.w3.org/2008/MW4D/ should not be valid because it's using HTML5" in {
//    MarkupValidator.configuration match {
//      case _: Distant => ()
//      case _: Local =>
//        val url = URL("http://www.w3.org/2008/MW4D/")
//        val assertions: Iterable[Assertion] = MarkupValidator.assert(url, Map.empty)
//        assertions must have size(1)
//        val assertion = assertions.head
//        assertion.title must be(MarkupValidator.UsesHtml5Syntax)
//    }
//  }


}