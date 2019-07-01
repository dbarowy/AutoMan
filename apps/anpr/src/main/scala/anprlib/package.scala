import edu.umass.cs.automan.adapters.mturk.DSL._
import edu.umass.cs.automan.adapters.mturk._
import edu.umass.cs.automan.core.policy.aggregation.UserDefinableSpawnPolicy
import edu.umass.cs.automan.core.util.Utilities

import scala.io.Source

package object anprlib {
  def plateTxt(image_url: String)(implicit a: MTurkAdapter) = freetext (
    budget = 5.00,
    text = "What are the characters printed on this license plate? NO DASHES, DOTS OR SPACES! If the plate is unreadable, enter: NA",
    image_url = image_url,
    allow_empty_pattern = true,
    pattern = "XXXXXYYY",
    pattern_error_text = "Answers may only be letters or numeric digits, no more than 8 characters long, no spaces, or the special two-letter code: NA",
    dont_reject = true,
    before_filter = normalize_chars,
    minimum_spawn_policy = UserDefinableSpawnPolicy(0)
  )

  def my_optparse(args: Array[String], invoked_as_name: String) : Utilities.OptionMap = {
    val usage = "Usage: " + invoked_as_name + " -k [key] -s [secret] -b [sandbox]" +
      "\n  NOTE: passing key and secret this way will expose your" +
      "\n  credentials to users on this system."
    if (args.length != 6) {
      println(usage)
      sys.exit(1)
    }
    val arglist = args.toList
    nextOption(Map(),arglist)
  }

  def nextOption(map : Utilities.OptionMap, list: List[String]) : Utilities.OptionMap = {
    list match {
      case Nil => map
      case "-k" :: value :: tail => nextOption(map ++ Map('key -> value), tail)
      case "-s" :: value :: tail => nextOption(map ++ Map('secret -> value), tail)
      case "-b" :: value :: tail => nextOption(map ++ Map('sandbox -> value), tail)
      case option :: tail => println("Unknown option "+option)
        sys.exit(1)
    }
  }

  def normalize_chars(s: String) : String = {
    s.replaceAll("o","0").replaceAll("O","0").toUpperCase
  }

  def getURLsFromDisk : Array[String] = {
    Source.fromURL(getClass.getResource("/urls.txt")).mkString.split("\n")
  }
}
