package edu.umass.cs.automan.adapters.Mock.question

import java.security.MessageDigest
import edu.umass.cs.automan.core.Utilities
import edu.umass.cs.automan.core.answer.CheckboxAnswer
import edu.umass.cs.automan.core.question.CheckboxQuestion
import org.apache.commons.codec.binary.Hex

class MockCheckboxQuestion extends CheckboxQuestion with MockQuestion[CheckboxAnswer] {
  override type QO = MockOption

  override protected var _options: List[QO] = List.empty

  override def options_=(os: List[QO]) { _options = os }
  override def options: List[QO] = _options
  override def memo_hash: String = {
    val hash_string = this.options.map(_.toString).mkString(",") + this.text + this.image_alt_text + this.image_url + this.title + this.question_type.toString
    val md = MessageDigest.getInstance("md5")
    new String(Hex.encodeHex(md.digest(hash_string.getBytes)))
  }
  override def randomized_options: List[QO] = Utilities.randomPermute(options)
}
