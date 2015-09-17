package edu.umass.cs.automan.core.policy.validation

import edu.umass.cs.automan.core.answer.{Answer, LowConfidenceAnswer, OverBudgetAnswer}
import edu.umass.cs.automan.core.logging._
import edu.umass.cs.automan.core.question._
import edu.umass.cs.automan.core.scheduler._

abstract class ScalarValidationPolicy(question: ScalarQuestion)
  extends ValidationPolicy(question) {

  def current_confidence(tasks: List[Task]) : Double
  def is_confident(tasks: List[Task], num_hypotheses: Int) : Boolean
  def is_done(tasks: List[Task]) = {
    val round = if (tasks.nonEmpty) { tasks.map(_.round).max } else { 1 }
    // the number of rounds completed == the number of hypotheses
    is_confident(tasks, round)
  }
  def answer_selector(tasks: List[Task]) : (Question#A,BigDecimal,Double) = {
    val bgrp = biggest_group(tasks)

    // find answer (actually an Option[Question#A]) of the largest group
    val answer_opt: Option[Question#A] = bgrp match { case (group,_) => group }

    // return the top result
    val value = answer_opt.get

    // get the confidence
    val conf = current_confidence(tasks)

    // calculate cost
    val cost = (bgrp match { case (_,ts) => ts.filterNot(_.from_memo) })
      .foldLeft(BigDecimal(0)){ case (acc,t) => acc + t.cost }

    (value, cost, conf)
  }
  private def biggest_group(tasks: List[Task]) : (Option[Question#A], List[Task]) = {
    val rt = completed_workerunique_tasks(tasks)

    assert(rt.size != 0)

    // group by answer (which is actually an Option[Question#A] because Task.answer is Option[Question#A])
    val groups: Map[Option[Question#A], List[Task]] = rt.groupBy(_.answer)

    // find answer of the largest group
    groups.maxBy { case(group, ts) => ts.size }
  }
  def rejection_response(tasks: List[Task]): String = {
    if (tasks.size == 0) {
      "Your answer is incorrect.  " +
      "We value your feedback, so if you think that we are in error, please contact us."
    } else {
      tasks.head.answer match {
        case Some(a) =>
          "Your answer is incorrect.  The correct answer is '" + a + "'.  " + "" +
          "We value your feedback, so if you think that we are in error, please contact us."
        case None =>
          "Your answer is incorrect.  " +
          "We value your feedback, so if you think that we are in error, please contact us."
      }
    }
  }
  def select_answer(tasks: List[Task]) : Question#AA = {
    answer_selector(tasks) match { case (value,cost,conf) =>
      DebugLog("Most popular answer is " + value.toString, LogLevel.INFO, LogType.STRATEGY, question.id)
      Answer(value, cost, conf).asInstanceOf[Question#AA]
    }
  }
  def select_over_budget_answer(tasks: List[Task], need: BigDecimal, have: BigDecimal) : Question#AA = {
    // if we've never scheduled anything,
    // there will be no largest group
    if(completed_workerunique_tasks(tasks).size == 0) {
      OverBudgetAnswer(need, have).asInstanceOf[Question#AA]
    } else {
      answer_selector(tasks) match {
        case (value, cost, conf) =>
          DebugLog("Over budget.  Best answer so far is " + value.toString, LogLevel.INFO, LogType.STRATEGY, question.id)
          LowConfidenceAnswer(value, cost, conf).asInstanceOf[Question#AA]
      }
    }
  }
  def tasks_to_accept(tasks: List[Task]): List[Task] = {
    (biggest_group(tasks) match { case (_, ts) => ts })
      .filter(not_final)
  }

  def tasks_to_reject(tasks: List[Task]): List[Task] = {
    val accepts = tasks_to_accept(tasks).toSet
    tasks.filter { t =>
      !accepts.contains(t) &&
      not_final(t)
    }
  }

  def not_final(task: Task) : Boolean = {
    task.state != SchedulerState.ACCEPTED &&
    task.state != SchedulerState.REJECTED &&
    task.state != SchedulerState.CANCELLED &&
    task.state != SchedulerState.TIMEOUT
  }
}