package edu.umass.cs.automan.core.policy.validation

import java.util.UUID
import edu.umass.cs.automan.core.logging._
import edu.umass.cs.automan.core.question.ScalarQuestion
import edu.umass.cs.automan.core.scheduler.{SchedulerState, Task}

object DefaultScalarPolicy {
  // this serializes computation of outcomes using MC, but it
  // ought to be outweighed by the fact that many tasks often
  // compute exactly the same thing
  var cache = Map[(Int,Int,Double), Double]()
}

class DefaultScalarPolicy(question: ScalarQuestion)
  extends ScalarValidationPolicy(question) {
  val NumberOfSimulations = 1000000

  DebugLog("DEFAULTSCALAR strategy loaded.",LogLevel.INFO,LogType.STRATEGY, question.id)

  def bonferroni_confidence(confidence: Double, num_hypotheses: Int) : Double = {
    assert(num_hypotheses > 0)
    1 - (1 - confidence) / num_hypotheses.toDouble
  }
  def current_confidence(tasks: List[Task]): Double = {
    val valid_ts = completed_workerunique_tasks(tasks)
    if (valid_ts.size == 0) return 0.0 // bail if we have no valid responses
    val biggest_answer = valid_ts.groupBy(_.answer).maxBy{ case(sym,ts) => ts.size }._2.size
    MonteCarlo.confidenceOfOutcome(question.num_possibilities.toInt, valid_ts.size, biggest_answer, NumberOfSimulations)
  }
  def is_confident(tasks: List[Task], num_hypotheses: Int): Boolean = {
    if (tasks.size == 0) {
      DebugLog("Have no tasks; confidence is undefined.", LogLevel.INFO, LogType.STRATEGY, question.id)
      false
    } else {
      val conf = current_confidence(tasks)
      val thresh = bonferroni_confidence(question.confidence, num_hypotheses)
      if (conf >= thresh) {
        DebugLog("Reached or exceeded alpha = " + (1 - thresh).toString, LogLevel.INFO, LogType.STRATEGY, question.id)
        true
      } else {
        val valid_ts = completed_workerunique_tasks(tasks)
        if (valid_ts.size > 0) {
          val biggest_answer = valid_ts.groupBy(_.answer).maxBy{ case(sym,ts) => ts.size }._2.size
          DebugLog("Need more tasks for alpha = " + (1 - thresh) + "; have " + biggest_answer + " agreeing tasks.", LogLevel.INFO, LogType.STRATEGY, question.id)
        } else {
          DebugLog("Need more tasks for alpha = " + (1 - thresh) + "; currently have no agreement.", LogLevel.INFO, LogType.STRATEGY, question.id)
        }
        false
      }
    }
  }
  def max_agree(tasks: List[Task]) : Int = {
    val valid_ts = completed_workerunique_tasks(tasks)
    if (valid_ts.size == 0) return 0
    valid_ts.groupBy(_.answer).maxBy{ case(sym,ts) => ts.size }._2.size
  }
  def spawn(tasks: List[Task], had_timeout: Boolean): List[Task] = {
    // determine current round
    val round = if (tasks.nonEmpty) {
      tasks.map(_.round).max
    } else { 0 }

    var nextRound = round

    // determine duration
    val worker_timeout_in_s = question._timeout_policy_instance.calculateWorkerTimeout(tasks, round, had_timeout)
    val task_timeout_in_s = question._timeout_policy_instance.calculateTaskTimeout(worker_timeout_in_s)

    // determine reward
    val reward = question._price_policy_instance.calculateReward(tasks, round, had_timeout)

    // num to spawn
    val num_to_spawn = if (had_timeout) {
      tasks.count { t => t.round == round && t.state == SchedulerState.TIMEOUT }
    } else {
      // (don't spawn more if any are running)
      if (tasks.count(_.state == SchedulerState.RUNNING) == 0) {
        // whenever we need to run MORE, we update the round counter
        nextRound = round + 1
        num_to_run(tasks, round, reward)
      } else {
        return List[Task]() // Be patient!
      }
    }

    DebugLog("You should spawn " + num_to_spawn +
                        " more Tasks at $" + reward + "/task, " +
                          task_timeout_in_s + "s until question timeout, " +
                          worker_timeout_in_s + "s until worker task timeout.", LogLevel.INFO, LogType.STRATEGY,
                          question.id)

    // allocate Task objects
    val new_tasks = (0 until num_to_spawn).map { i =>
      val now = new java.util.Date()
      val t = new Task(
        UUID.randomUUID(),
        question,
        nextRound,
        task_timeout_in_s,
        worker_timeout_in_s,
        reward,
        now,
        SchedulerState.READY,
        from_memo = false,
        None,
        None,
        now
      )
      DebugLog("spawned question_id = " + question.id_string,LogLevel.INFO,LogType.STRATEGY, question.id)
      t
    }.toList

    new_tasks
  }

  def expected_for_agreement(trials: Int, max_agr: Int, confidence: Double) : Int = {
    // do the computation
    var to_run = 0
    var done = false
    while(!done) {
      val min_required = MonteCarlo.requiredForAgreement(question.num_possibilities.toInt, trials + to_run, confidence, 1000000)
      val expected = max_agr + to_run
      if (min_required < 0 || min_required > expected) {
        to_run += 1
      } else {
        done = true
      }
    }
    to_run
  }

  def num_to_run(tasks: List[Task], round: Int, reward: BigDecimal) : Int = {
    // eliminate duplicates from the list of Tasks
    val tasks_no_dupes = tasks.filter(_.state != SchedulerState.DUPLICATE)

    val options: Int = if(question.num_possibilities > BigInt(Int.MaxValue)) 1000 else question.num_possibilities.toInt

    // the number of hypotheses is the current round number + 1, since we count from zero
    val adjusted_conf = bonferroni_confidence(question.confidence, round + 1)

    // cache key
    val key = (tasks_no_dupes.size, max_agree(tasks_no_dupes), adjusted_conf)

    // compute # expected for agreement
    val expected = DefaultScalarPolicy.synchronized {
      if (DefaultScalarPolicy.cache.contains(key)) {
        DefaultScalarPolicy.cache(key)
      } else {
        val efa = expected_for_agreement(key._1, key._2, key._3).toDouble
        DefaultScalarPolicy.cache += key -> efa
        efa
      }
    }

    val biggest_bang =
      math.min(
        math.floor(question.budget.toDouble/reward.toDouble),
        math.floor(question.time_value_per_hour.toDouble/question.wage.toDouble)
      )

    math.max(expected, biggest_bang).toInt
  }

  def pessimism() = {
    val p: Double = math.max((question.time_value_per_hour/question.wage).toDouble, 1.0)
    if (p > 1) {
      DebugLog("Using pessimistic (expensive) strategy.", LogLevel.INFO, LogType.STRATEGY, question.id)
    } else {
      DebugLog("Using Using optimistic (cheap) strategy.", LogLevel.INFO, LogType.STRATEGY, question.id)
    }
    p
  }
}