package edu.umass.cs.automan.core.grammar

import edu.umass.cs.automan.core.info.QuestionType
import edu.umass.cs.automan.core.info.QuestionType.QuestionType

import scala.collection.mutable
import scala.util.Random

trait Production {
  def sample(): String
  def count(g: Grammar, counted: Set[String]): Int
  def toChoiceArr(g: Grammar): Option[Array[Range]]
  def isLeafProd(): Boolean // Certain prods can terminate
  def isLeafNT(counted: Set[String], name: String): Boolean // Checking if prod and everything it maps to are LNTs
}

trait TextProduction extends Production{}

// A set of choices, options for a value
class Choices(options: List[Production]) extends TextProduction {
  override def sample(): String = {
    val ran = new Random()
    options(ran.nextInt(options.length)).sample()
  }

  override def count(g: Grammar, counted: Set[String]): Int = {
    var c: Int = 0
    for (e <- options) {
      c = c + e.count(g, counted)
    } // choices are additive
    c
  }

  // return specific terminal given by index i
  def sampleSpec(i: Int): String = {
    assert(i < options.length)
    assert(i >= 0)
    options(i).sample() //TODO: should probably ensure array bounds
  }

  override def toChoiceArr(g: Grammar): Option[Array[Range]] = {
    var n: Int = 0
    for (e <- options) {
      e.toChoiceArr(g) match {
        case Some(arr) => n += arr.length
        case None => {}
        //case Some(e) => n = n + (e.asInstanceOf[Array[Range]]).toChoiceArr(g).length // counting ranges
      }
    }
    Some(Array(0 to n - 1))
  }

  def getOptions(): List[Production] = options

  override def isLeafProd(): Boolean = false

  // A leafNonTerminal must map to all LNTs or counted NTs
  def isLeafNT(counted: Set[String], name: String): Boolean = {
    var allNT = true

    for(e <- options) {
      if(!(e.isLeafNT(counted, name) || counted.contains(e.sample()))) {
        if(!(e.sample() == name)) allNT = false
      } // pass in name. If not contained, check if name is same as choices name. if yes, still LNT (but will need to count as 1 later)
    } // checking if counted contains should be irrelevant bc checking if names are contained in Name.isLeafNT
    allNT
  }
}

// A terminal production
class Terminal(word: String) extends TextProduction {
  override def sample(): String = {
    word
  }
  override def count(g: Grammar, counted: Set[String]): Int = 1

  override def toChoiceArr(g: Grammar): Option[Array[Range]] = {
    Some(Array(0 to 0))
    //toRet = toRet + (0 to 0)
  }

  override def isLeafProd(): Boolean = true

  override def isLeafNT(counted: Set[String], name: String): Boolean = true
}

// A sequence, aka a combination of other terminals/choices and the ordering structure of each problem
class Sequence(sentence: List[Production]) extends TextProduction {
  override def sample(): String = {
    val ran = new Random()
    sentence(ran.nextInt(sentence.length)).sample()
  }
  override def count(g: Grammar, counted: Set[String]): Int = {
    var c: Int = 1 // TODO: is this ok?
    for (e <- sentence){
      c = c*e.count(g, counted)
    } // nonterminals are multiplicative
    c
  }

  // return specific terminal given by index i
  def sampleSpec(i: Int): String = {
    sentence(i).sample()
  }
  def getList(): List[Production] = sentence

  override def toChoiceArr(g: Grammar): Option[Array[Range]] = {
    var choiceArr: Array[Range] = new Array[Range](sentence.length)
    for (e <- sentence) {
      e.toChoiceArr(g) match {
        case Some(arr) => {
          val newArr: Array[Range] = choiceArr ++ arr
          choiceArr = newArr
        }
        case None => {}
      }
      //choiceArr ++ newArr
    }
    Some(choiceArr)
  }

  override def isLeafProd(): Boolean = false

  override def isLeafNT(counted: Set[String], name: String): Boolean = false
}

// A name associated with a edu.umass.cs.automan.core.grammar.Production
class Name(n: String) extends TextProduction {
  override def sample(): String = n // sample returns name for further lookup
  def count(g: Grammar, counted: Set[String]): Int = {
    var c: Set[String] = counted

    if(!counted.contains(n)){
      c = c + n
      g.rules(this.sample()).count(g, counted) // TODO: get rid of this? null issues?
    } else 1
  }

  override def toChoiceArr(g: Grammar): Option[Array[Range]] = {
    g.rules(this.sample()).toChoiceArr(g)
  }

  override def isLeafProd(): Boolean = false

  override def isLeafNT(counted: Set[String], name: String): Boolean = {
    if(counted.contains(n)) true
    else false
  }
}

// param is name of the Choices that this function applies to
// fun maps those choices to the function results
class Function(fun: Map[String,String], param: String, capitalize: Boolean) extends TextProduction {
  override def sample(): String = param
  override def count(g: Grammar, counted: Set[String]): Int = 1
  def runFun(s: String): String = { // "call" the function on the string
    if(capitalize) fun(s).capitalize
    else fun(s)
  }

  override def toChoiceArr(g: Grammar): Option[Array[Range]] = Some(Array(0 to 0)) //None //Option[Array[Range]()] //Array(null) //TODO: right?
  override def isLeafProd(): Boolean = true // todo not sure
  override def isLeafNT(counted: Set[String], name: String): Boolean = {
    if(counted.contains(param)) true
    else false
  }
}

/**
  * QUESTION PRODUCTIONS
  */

abstract class QuestionProduction(g: Grammar) extends Production { // TODO make prods take grammars?
  var _questionType: QuestionType

  override def sample(): String

  override def count(g: Grammar, counted: Set[String]): Int

  override def toChoiceArr(g: Grammar): Option[Array[Range]] = Some(Array(0 to 0))

  // returns tuple (body text, options list)
  def toQuestionText(variation: Int): (String, List[String])

  def questionType: QuestionType = _questionType

  def isLeafNT(counted: Set[String], name: String): Boolean = false
}

class OptionProduction(text: TextProduction) extends Production {
  override def sample(): String = text.sample()

  override def count(g: Grammar, counted: Set[String]): Int = text.count(g, counted)

  def getText() = text

  override def toChoiceArr(g: Grammar): Option[Array[Range]] = text.toChoiceArr(g)

  override def isLeafProd(): Boolean = false

  override def isLeafNT(counted: Set[String], name: String): Boolean = false
}

class EstimateQuestionProduction(g: Grammar, body: TextProduction) extends QuestionProduction(g) {
  override var _questionType: QuestionType = QuestionType.EstimationQuestion

  override def sample(): String = body.sample()

  override def count(g: Grammar, counted: Set[String]): Int = ???

  // todo grammar necessary?
  override def toQuestionText(variation: Int): (String, List[String]) = {
    //val body: String = Ranking.buildInstance(g, variation) // todo where does body come in?
    //(body, List[String]()) // no options for estimation
    //val bod: (StringBuilder, List[StringBuilder]) = Ranking.buildInstance(g, variation)
    val (bod, opts): (StringBuilder, List[StringBuilder]) = Ranking.buildInstance(g, variation)
    val bodS = bod.toString()
    val optsS = opts.map(_.toString())//{for(e <- opts) e.toString()} // todo just ""?
    (bodS, optsS)
    //bod.toString()
  }

  override def isLeafProd(): Boolean = false

  //override def isLeafNT(counted: Set[String]): Boolean = false
}

// todo we can actually get rid of the body param now...
class CheckboxQuestionProduction(g: Grammar, body: TextProduction) extends QuestionProduction(g) {
  override var _questionType: QuestionType = QuestionType.CheckboxQuestion

  override def sample(): String = {
    body.sample()
  }

  override def count(g: Grammar, counted: Set[String]): Int = ???

  override def toQuestionText(variation: Int): (String, List[String]) = {
//    val body: String = Ranking.buildInstance(g, variation) // todo where does body come in?
//    val options: List[String] =
//    (body, List[String]()) // no options for estimation
    //Ranking.buildInstance(g, variation)
    val (bod, opts): (StringBuilder, List[StringBuilder]) = Ranking.buildInstance(g, variation)
    val bodS = bod.toString()
    val optsS = opts.map(_.toString())//{for(e <- opts) e.toString()}
    (bodS, optsS)
  }

  override def isLeafProd(): Boolean = false
}

class CheckboxesQuestionProduction(g: Grammar, body: TextProduction) extends QuestionProduction(g) {
  override var _questionType: QuestionType = QuestionType.CheckboxDistributionQuestion

  override def sample(): String = {
    body.sample()
  }

  override def count(g: Grammar, counted: Set[String]): Int = ???

  override def toQuestionText(variation: Int): (String, List[String]) = {
    val (bod, opts): (StringBuilder, List[StringBuilder]) = Ranking.buildInstance(g, variation)
    val bodS = bod.toString()
    val optsS = opts.map(_.toString())//{for(e <- opts) e.toString()}
    (bodS, optsS)
  }

  override def isLeafProd(): Boolean = false
}

class FreetextQuestionProduction(g: Grammar, body: TextProduction) extends QuestionProduction(g) {
  override var _questionType: QuestionType = QuestionType.FreeTextQuestion

  override def sample(): String = body.sample()

  override def count(g: Grammar, counted: Set[String]): Int = ???

  override def toQuestionText(variation: Int): (String, List[String]) = {
    val (bod, opts): (StringBuilder, List[StringBuilder]) = Ranking.buildInstance(g, variation)
    val bodS = bod.toString()
    val optsS = opts.map(_.toString())//{for(e <- opts) e.toString()} //todo get rid of opts
    (bodS, optsS)
  }

  override def isLeafProd(): Boolean = false
}

class FreetextsQuestionProduction(g: Grammar, body: TextProduction) extends QuestionProduction(g) {
  override var _questionType: QuestionType = QuestionType.FreeTextQuestion

  override def sample(): String = body.sample()

  override def count(g: Grammar, counted: Set[String]): Int = ???

  override def toQuestionText(variation: Int): (String, List[String]) = {
    val (bod, opts): (StringBuilder, List[StringBuilder]) = Ranking.buildInstance(g, variation)
    val bodS = bod.toString()
    val optsS = opts.map(_.toString())//{for(e <- opts) e.toString()}
    (bodS, optsS)
  }

  override def isLeafProd(): Boolean = false
}

class RadioQuestionProduction(g: Grammar, body: TextProduction) extends QuestionProduction(g) {
  override var _questionType: QuestionType = QuestionType.RadioButtonQuestion

  override def sample(): String = body.sample()

  override def count(g: Grammar, counted: Set[String]): Int = ???

  override def toQuestionText(variation: Int): (String, List[String]) = {
    val (bod, opts): (StringBuilder, List[StringBuilder]) = Ranking.buildInstance(g, variation)
    val bodS = bod.toString()
    val optsS = opts.map(_.toString())//{for(e <- opts) e.toString()}
    (bodS, optsS)
  }

  override def isLeafProd(): Boolean = false
}

class RadiosQuestionProduction(g: Grammar, body: TextProduction) extends QuestionProduction(g) {
  override var _questionType: QuestionType = QuestionType.RadioButtonDistributionQuestion

  override def sample(): String = body.sample()

  override def count(g: Grammar, counted: Set[String]): Int = ???

  override def toQuestionText(variation: Int): (String, List[String]) = {
    val (bod, opts): (StringBuilder, List[StringBuilder]) = Ranking.buildInstance(g, variation)
    val bodS = bod.toString()
    val optsS = opts.map(_.toString())//{for(e <- opts) e.toString()}
    (bodS, optsS)
  }

  override def isLeafProd(): Boolean = false
}


//class QuestionBodyProduction(g: Grammar, variation: Int) extends Production(){
//  private val _body = Ranking.buildInstance(g, variation)
//
//  override def sample(): String = _body
//
//  override def count(g: Grammar, counted: mutable.HashSet[String]): Int = ???
//
//  override def toChoiceArr(g: Grammar): Option[Array[Range]] = ???
//}
//
//// todo make this generate like QBP
//class OptionsProduction(opts: List[String]) extends Production(){
//  override def sample(): String = {
//    val toRet: StringBuilder = new StringBuilder()
//    for(i <- 0 until opts.length){
//      toRet.addString(new mutable.StringBuilder(opts(i) + "\n"))
//    }
//    toRet.toString()
//  }
//
//  override def count(g: Grammar, counted: mutable.HashSet[String]): Int = ???
//
//  override def toChoiceArr(g: Grammar): Option[Array[Range]] = ???
//
//  def getOpts() = opts
//}