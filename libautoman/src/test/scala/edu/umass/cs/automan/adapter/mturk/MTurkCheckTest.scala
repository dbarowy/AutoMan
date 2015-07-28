package edu.umass.cs.automan.adapter.mturk

import org.scalatest._
import java.util.UUID
import edu.umass.cs.automan.test._
import edu.umass.cs.automan.adapters.mturk._
import edu.umass.cs.automan.adapters.mturk.mock.MockSetup

class MTurkCheckTest extends FlatSpec with Matchers {

  "A checkbox program" should "work" in {
    val confidence = 0.95

    val a = MTurkAdapter { mt =>
      mt.access_key_id = UUID.randomUUID().toString
      mt.secret_access_key = UUID.randomUUID().toString
      mt.use_mock = MockSetup(budget = 8.00)
      mt.logging = LogConfig.NO_LOGGING
      mt.poll_interval = 2
    }

    automan(a) {
      def which_ones() = a.CheckboxQuestion { q =>
        q.confidence = confidence
        q.budget = 8.00
        q.text = "Which characters are not Oscar, Kermit, or Cookie Monster?"
        q.options = List(
          a.Option('oscar, "Oscar the Grouch"),
          a.Option('kermit, "Kermit the Frog"),
          a.Option('spongebob, "Spongebob Squarepants"),
          a.Option('cookie, "Cookie Monster"),
          a.Option('count, "The Count")
        )
        q.mock_answers = makeMocksNow(List(Set('spongebob,'count),Set('spongebob),Set('count,'spongebob),Set('count,'spongebob)))
      }

      which_ones().answer match {
        case Answer(value, _, conf) =>
          println("Answer: '" + value + "', confidence: " + conf)
          (value == Set('spongebob,'count)) should be (true)
          (conf >= confidence) should be (true)
        case _ =>
          fail()
      }
    }
  }
}
