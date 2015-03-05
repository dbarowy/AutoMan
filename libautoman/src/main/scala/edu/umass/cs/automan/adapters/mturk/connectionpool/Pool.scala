package edu.umass.cs.automan.adapters.mturk.connectionpool

import java.text.SimpleDateFormat
import java.util.concurrent.PriorityBlockingQueue
import java.util.{Date, UUID}
import com.amazonaws.mturk.requester._
import com.amazonaws.mturk.service.axis.RequesterService
import edu.umass.cs.automan.adapters.mturk.question.MTurkQuestion
import edu.umass.cs.automan.core.logging.{LogType, LogLevel, DebugLog}
import edu.umass.cs.automan.core.question.Question
import edu.umass.cs.automan.core.scheduler.{SchedulerState, Thunk}
import edu.umass.cs.automan.core.util.Stopwatch

class Pool(backend: RequesterService, sleep_ms: Int) {
  type HITID = String
  type BatchKey = (String,BigDecimal,Int)   // (group_id, cost, timeout); uniquely identifies a batch
  type HITKey = (BatchKey, UUID)            // (BatchKey, question_id); uniquely identifies a HIT

  // worker
  private var _worker_thread: Option[Thread] = None

  // work queue
  private val _work_queue: PriorityBlockingQueue[Message] = new PriorityBlockingQueue[Message]()

  // response data
  private val _responses = scala.collection.mutable.Map[Message, Any]()

  // MTurk-related state

  // key: (group_id,cost,worker_timeout_s)
  // value: a HITTypeId
  private var _hit_types = Map[BatchKey,HITType]()

  // key: HIT ID
  // value: HITState
  private var _hit_states = Map[HITID,HITState]()

  // key: (question_id,cost,worker_timeout_s)
  // value: HIT ID
  private var _hit_ids = Map[HITKey,HITID]()

  // key: (worker_id,group_id)
  // value: HITTypeId
  private var _worker_whitelist = Map[(String,String),String]()

  // key: qualification_id
  // value: HITTypeId
  private var _disqualifications = Map[String,String]()

  // key: group_id
  // value: batch_no
  private var _batch_no = Map[String,Int]()

  // API
  def accept[A](t: Thunk[A]) : Thunk[A] = {
    blocking_enqueue(AcceptReq(t)).asInstanceOf[Thunk[A]]
  }
  def backend_budget: BigDecimal = {
    blocking_enqueue(BudgetReq()).asInstanceOf[BigDecimal]
  }
  def cancel[A](t: Thunk[A]) : Thunk[A] = {
    blocking_enqueue(CancelReq(t)).asInstanceOf[Thunk[A]]
  }
  def cleanup_qualifications[A](mtq: MTurkQuestion) : Unit = {
    nonblocking_enqueue(DisposeQualsReq(mtq))
  }
  def post[A](ts: List[Thunk[A]], exclude_worker_ids: List[String]) : Unit = {
    nonblocking_enqueue(CreateHITReq(ts, exclude_worker_ids))
  }
  def reject[A](t: Thunk[A], correct_answer: String) : Thunk[A] = {
    blocking_enqueue(RejectReq(t, correct_answer)).asInstanceOf[Thunk[A]]
  }
  def retrieve[A](ts: List[Thunk[A]]) : List[Thunk[A]] = {
    blocking_enqueue(RetrieveReq(ts)).asInstanceOf[List[Thunk[A]]]
  }
  def shutdown(): Unit = synchronized {
    nonblocking_enqueue(ShutdownReq())
  }

  // IMPLEMENTATIONS
  def nonblocking_enqueue[M <: Message, T](req: M) = {
    // put job in queue
    _work_queue.add(req)

    initWorkerIfNeeded()
  }
  def blocking_enqueue[M <: Message, T](req: M) = {
    nonblocking_enqueue(req)

    // wait for response
    // while loop is because the JVM is
    // permitted to send spurious wakeups
    while(synchronized { !_responses.contains(req) }) {
      // Note that the purpose of this second lock
      // is to provide blocking semantics
      req.synchronized {
        req.wait() // block until cancelled thunk is available
      }
    }

    // return output
    synchronized {
      val ret = _responses(req)
      _responses.remove(req)
      ret
    }
  }
  private def initWorkerIfNeeded() : Unit = {
    // if there's no thread already servicing the queue,
    // lock and start one up
    synchronized {
      _worker_thread match {
        case Some(thread) => Unit // do nothing
        case None =>
          val t = initWorkerThread()
          _worker_thread = Some(t)
          t.start()
      }
    }
  }
  private def initWorkerThread(): Thread = {
    DebugLog("No worker thread; starting one up.", LogLevel.INFO, LogType.ADAPTER, null)
    new Thread(new Runnable() {
      override def run() {
        while (true) {

          val time = Stopwatch {
            _work_queue.take() match {
              case req: ShutdownReq => return
              case req: AcceptReq[_] => do_sync_action(req, () => scheduled_accept(req.t))
              case req: BudgetReq => do_sync_action(req, () => scheduled_get_budget())
              case req: CancelReq[_] => do_sync_action(req, () => scheduled_cancel(req.t))
              case req: DisposeQualsReq => do_sync_action(req, () => scheduled_cleanup_qualifications(req.q))
              case req: CreateHITReq[_] => do_sync_action(req, () => scheduled_post(req.ts, req.exclude_worker_ids))
              case req: RejectReq[_] => do_sync_action(req, () => scheduled_reject(req.t, req.answer))
              case req: RetrieveReq[_] => do_sync_action(req, () => scheduled_retrieve(req.ts))
            }
          }

          // rate-limit
          Thread.sleep(Math.max(sleep_ms - time.duration_ms, 0))
        } // exit loop
      }
    })
  }
  private def do_sync_action[T](message: Message, action: () => T) : Unit = {
    message.synchronized {
      // do request
      val response = action()
      // store response
      synchronized {
        _responses += (message -> response)
      }
      // send end-wait notification
      message.notifyAll()
    }
  }
  private def scheduled_accept[A](t: Thunk[A]) : Thunk[A] = {
    DebugLog(
      String.format("Accepting task for question_id = %s",
      t.question.id), LogLevel.INFO, LogType.ADAPTER, null)

    _hit_states(_hit_ids(HITKey(t))).getAssignmentOption(t) match {
      case Some(assignment) =>
        backend.approveAssignment(assignment.getAssignmentId, "Thanks!")
        t.copy_as_accepted()
      case None =>
        throw new Exception("Cannot accept non-existent assignment.")
    }
  }
  private def scheduled_cancel[A](t: Thunk[A]) : Thunk[A] = {
    DebugLog(String.format("Cancelling task for question_id = %s",
      t.question.id), LogLevel.INFO, LogType.ADAPTER, null)

    val hit_id = _hit_ids(HITKey(t))
    val hit_state = _hit_states(hit_id)
    backend.forceExpireHIT(hit_state.HITId)
    _hit_states += (hit_id -> hit_state.cancel())

    t.copy_as_cancelled()
  }

  /**
   * Create a new HITType on MTurk, with a disqualification if applicable.
   * @param question An AutoMan Question[_]
   * @param batch_key Batch parameters
   */
  private def mturk_registerHITType(question: Question[_], batch_key: BatchKey) : Unit = {
    val (group_id, cost, worker_timeout) = batch_key

    // get current batch number
    val batch_no = _batch_no(group_id)

    // create disqualification for batch
    val disqualification = mturk_createQualification(question.asInstanceOf[MTurkQuestion], question.text, question.id, batch_no)

    // whenever we create a new group, we need to add the disqualification to the HITType
    // EXCEPT if it's the very first time the group is posted
    // AND we weren't specifically asked to blacklist any workers
    val quals = if (question.blacklisted_workers.size > 0 || batch_no != 1) {
      disqualification :: question.asInstanceOf[MTurkQuestion].qualifications
    } else {
      question.asInstanceOf[MTurkQuestion].qualifications
    }

    val hit_type_id = backend.registerHITType(
      (30 * 24 * 60 * 60).toLong,                                   // 30 days
      worker_timeout.toLong,                                        // amount of time the worker has to complete the task
      cost.toDouble,                                                // cost in USD
      question.title,                                               // title
      question.asInstanceOf[MTurkQuestion].keywords.mkString(","),  // keywords
      question.asInstanceOf[MTurkQuestion].description,             // description
      quals.toArray                                                 // no quals initially
    )
    val hittype = HITType(hit_type_id, quals, disqualification, group_id)

    // update disqualification map
    _disqualifications += disqualification.getQualificationTypeId -> hittype.id

    // update hittype map
    _hit_types = _hit_types + (batch_key -> hittype)
  }

  private def mturk_createHIT(ts: List[Thunk[_]], batch_key: BatchKey, question: Question[_]) : HITState = {
    // get hit_type for batch
    val hit_type = get_or_create_hittype(batch_key, question)

    val hit = backend.createHIT(
      hit_type.id,                                                  // hitTypeId
      null,                                                         // title; defined by HITType
      null,                                                         // description
      null,                                                         // keywords; defined by HITType
      question.asInstanceOf[MTurkQuestion].toXML(true).toString(),  // question
      null,                                                         // reward; defined by HITType
      null,                                                         // assignmentDurationInSeconds; defined by HITType
      null,                                                         // autoApprovalDelayInSeconds; defined by HITType
      ts.head.timeout_in_s.toLong,                                  // lifetimeInSeconds
      ts.size,                                                      // maxAssignments
      "automan",                                                    // requesterAnnotation
      Array[QualificationRequirement](),                            // qualificationRequirements; defined by HITType
      Array[String]())                                              // responseGroup
    // we immediately query the backend for the HIT's complete details
    // because the HIT structure returned by createHIT has a number
    // of uninitialized fields; return new HITState
    val hs = HITState(backend.getHIT(hit.getHITId), ts, hit_type)

    // calculate new HIT key
    val hit_key = (batch_key, question.id)

    // update HIT key -> HIT ID map
    _hit_ids = _hit_ids + (hit_key -> hs.HITId)

    // update HIT ID -> HITState map
    _hit_states = _hit_states + (hs.HITId -> hs)

    hs
  }

  private def mturk_extendHIT(ts: List[Thunk[_]], timeout_in_s: Int, hitstate: HITState) : Unit = {
    backend.extendHIT(hitstate.HITId, ts.size, timeout_in_s.toLong)
    // we immediately query the backend for the HIT's complete details
    // to update our cached data

    // update HITState and return
    val hs = hitstate.addNewThunks(backend.getHIT(hitstate.HITId), ts)

    // update hit states with new object
    _hit_states = _hit_states + (hs.HITId -> hs)

    hs
  }

  /**
   * Returns true if this group_id has never been associated with
   * any work on MTurk.
   * @param group_id The group_id.
   * @return True if the group_id has already scheduled tasks on MTurk.
   */
  private def first_run(group_id: String) : Boolean = {
    !_hit_types.map{ case (gid, _, _) => gid }.toSet.contains(group_id)
  }

  /**
   * Checks that a HITType already exists for the task group;
   * if it does, it returns the associated HITType object,
   * otherwise it creates a HITType on MTurk.
   * @param batch_key A GroupKey tuple that uniquely identifies a batch round.
   * @param question An AutoMan question.
   * @return A HITType
   */
  private def get_or_create_hittype(batch_key: BatchKey, question: Question[_]) : HITType = {
    // when these properties change from what we've seen before
    // (including the possibility that we've never seen any of these
    // thunks before) we need to create a new HITType;
    // Note that simply adding blacklisted/excluded workers to an existing group
    // is not sufficient to trigger the creation of a new HITType, nor do we want
    // it to, because MTurk's extendHIT is sufficient to prevent re-participation
    // for a given HIT.
    val (group_id, _, worker_timeout) = batch_key

    val firstrun = first_run(group_id)

    if (!_hit_types.contains(batch_key)) {
      // update batch counter
      _batch_no += (if (firstrun) group_id -> 1 else group_id -> (_batch_no(group_id) + 1))

      // request new HITTypeId from MTurk
      mturk_registerHITType(question, batch_key)
    }
    _hit_types(batch_key)
  }

  /**
   * This call marshals data to MTurk, updating local state
   * where necessary.
   * @param ts  A List of Thunks to post.
   * @param exclude_worker_ids  A list of worker_ids to exclude (via disqualifications)
   */
  private def scheduled_post(ts: List[Thunk[_]], exclude_worker_ids: List[String]) : Unit = {
    // One consequence of dealing with groups of thunks is that
    // they may each be associated with a different question; although
    // automan never calls post with heterogeneous set of thunks, we
    // have to allow for the possibility that it does.
    ts.groupBy(_.question).foreach { case (q, qts) =>
      // Our questions are *always* MTurkQuestions
      val mtq = q.asInstanceOf[MTurkQuestion]

      // also, we need to ensure that all the thunks have the same properties
      qts.groupBy{ t => (t.cost,t.worker_timeout)}.foreach { case ((cost,worker_timeout), tz) =>
        // The batch is uniquely determined by group_id, cost, and worker_timeout
        val batch_key: BatchKey = (mtq.group_id, cost, worker_timeout)

        // A HIT is uniquely determined by question_id, cost, and worker_timeout
        val hit_key: HITKey = (batch_key, q.id)

        // have we already posted a HIT for these thunks?
        if (_hit_ids.contains(hit_key)) {
          // if so, get HITState and extend it
          mturk_extendHIT(tz, tz.head.timeout_in_s, _hit_states(_hit_ids(hit_key)))
        } else {
          // if not, post a new HIT on MTurk
          mturk_createHIT(tz, batch_key, q)
        }
      }
    }
  }

  private def scheduled_reject[A](t: Thunk[A], correct_answer: String) : Thunk[A] = {
    DebugLog(String.format("Rejecting task for question_id = %s",
      t.question.id), LogLevel.INFO, LogType.ADAPTER, null)

    _hit_states(_hit_ids(HITKey(t))).getAssignmentOption(t) match {
      case Some(assignment) =>
        backend.rejectAssignment(assignment.getAssignmentId,
          "AutoMan determined that the correct answer to this question is: \"" + correct_answer +
          "\".  If you believe this result to be in error, please contact us."
        )
        t.copy_as_rejected()
      case None =>
        throw new Exception("Cannot accept non-existent assignment.")
    }
  }

  private def BatchKey(t: Thunk[_]) : BatchKey = (t.question.asInstanceOf[MTurkQuestion].group_id, t.cost, t.worker_timeout)
  private def HITKeyForBatch(batch_key: BatchKey, t: Thunk[_]) : HITKey = (batch_key, t.question.id)
  private def HITKey(t: Thunk[_]) : HITKey = HITKeyForBatch(BatchKey(t), t)
  private def HITIDsForBatch(batch_key: BatchKey) : List[HITID] = _hit_ids.flatMap { case ((bkey, _), hit_id) => if (bkey == batch_key) Some(hit_id) else None }.toList

  private def scheduled_retrieve[A](ts: List[Thunk[A]]): List[Thunk[A]] = {
    // 1. eagerly get all HIT assignments
    // 2. pair HIT Assignments with Thunks
    // 3. update Thunks with answers
    // 4. timeout Thunks, where appropriate
    val ts2 = ts.groupBy(BatchKey).map { case (batch_key, bts) =>
      // get HITType for BatchKey
      val hittype = _hit_types(batch_key)

      // iterate through all HITs for this HITType
      // pair all assignments with thunks, yielding a new collection of HITStates
      val updated_hss = HITIDsForBatch(batch_key).map { hit_id =>
        val hit_state = _hit_states(hit_id)

        // get all of the assignments for this HIT
        val assns = backend.getAllAssignmentsForHIT(hit_state.HITId)

        // pair with the HIT's thunks and return new HITState
        hit_state.matchAssignments(assns)
      }

      // update HITState map all at once
      _hit_states ++= updated_hss.map { hs => hs.HITId -> hs }

      // return answered thunks
      answer_thunks(bts, batch_key)
    }.flatten.toList

    // unanswered Thunks past their expiration dates are timed-out here
    timeout_thunks_as_needed(ts2)
  }

  private def answer_thunks[A](ts: List[Thunk[A]], batch_key: BatchKey) : List[Thunk[A]] = {
    val group_id = batch_key._1

    // group by HIT
    ts.groupBy(HITKeyForBatch(batch_key,_)).map { case (hit_key, hts) =>
      // get HITState for this set of Thunks
      val hs = _hit_states(_hit_ids(hit_key))

      // start by granting Qualifications, where appropriate
      mturk_grantQualifications(hs)

      hts.map { t =>
        hs.getAssignmentOption(t) match {
          // when a Thunk is paired with an answer
          case Some(assignment) =>
            // only update Thunk object if the Thunk isn't already answered
            if (t.state != SchedulerState.ANSWERED) {
              // get worker_id
              val worker_id = assignment.getWorkerId

              // update the worker whitelist and grant qualification (disqualifiaction)
              // if this is the first time we've ever seen this worker
              if (!_worker_whitelist.contains(worker_id, group_id)) {
                _worker_whitelist += ((worker_id, group_id) -> hs.hittype.id)
                val disqualification_id = hs.hittype.disqualification.getQualificationTypeId
                backend.assignQualification(disqualification_id, worker_id, _batch_no(hs.hittype.group_id), false)
              }

              // process answer
              val xml = scala.xml.XML.loadString(assignment.getAnswer)
              val answer = t.question.asInstanceOf[MTurkQuestion].fromXML(xml)

              // it is possible, although unlikely, that a worker could submit
              // work twice for the same HIT, if the following scenario occurs:
              // 1. HIT A in HITGroup #1 times-out, causing AutoMan to post HITGroup #2 containing a second round of HIT A
              // 2. Worker w asks for and receives a Qualification for HITGroup #2
              // 3. Worker w submits work to HITGroup #1 for HIT B (not HIT A).
              // 4. HIT B times out, causing AutoMan to post a second round of HIT B to HITGroup #2.
              // 5. Worker w submits work for HITGroup #2.
              // Since this is unlikely, and violates the i.i.d. guarantee that
              // the Scheduler requires, we consider this a duplicate
              val whitelisted_ht_id = _worker_whitelist(worker_id, group_id)
              if (whitelisted_ht_id != hs.hittype.id) {
                // immediately revoke the qualification in the other group;
                // we'll deal with duplicates later
                backend.revokeQualification(whitelisted_ht_id, worker_id,
                  "For quality control purposes, qualification " + whitelisted_ht_id +
                    " was revoked because you submitted related work for HIT " + hs.HITId +
                    ".  This is for our own bookkeeping purposes and not a reflection on the quality of your work. " +
                    "We apologize for the inconvenience that this may cause and we encourage you to continue " +
                    "your participation in our HITs."
                )
              }
              t.copy_with_answer(answer.asInstanceOf[A], worker_id)
            } else {
              t
            }
          // when a Thunk is not paired with an answer
          case None => t
        }
      }
    }.flatten.toList
  }

  private def timeout_thunks_as_needed[A](ts: List[Thunk[A]]) : List[Thunk[A]] = {
    ts.map { t =>
      if (t.is_timedout) {
        t.copy_as_timeout()
      } else {
        t
      }
    }
  }

  private def scheduled_get_budget(): BigDecimal = {
    DebugLog("Getting budget.", LogLevel.INFO, LogType.ADAPTER, null)
    backend.getAccountBalance
  }

  private def scheduled_cleanup_qualifications(q: MTurkQuestion) : Unit = {
    q.qualifications.foreach { qual =>
      backend.disposeQualificationType(qual.getQualificationTypeId)
    }
  }

  private def mtquestion_for_thunks(ts: List[Thunk[_]]) : MTurkQuestion = {
    // determine which MT question we've been asked about
    question_for_thunks(ts) match {
      case mtq: MTurkQuestion => mtq
      case _ => throw new Exception("MTurkAdapter can only operate on Thunks for MTurkQuestions.")
    }
  }
  private[mturk] def question_for_thunks(ts: List[Thunk[_]]) : Question[_] = {
    // determine which question we've been asked about
    val tg = ts.groupBy(_.question)
    if(tg.size != 1) {
      throw new Exception("MTurkAdapter can only process groups of Thunks for the same Question.")
    }
    tg.head._1
  }

  private def mturk_grantQualifications(hitstate: HITState) : Unit = {
    // get all requests for this HIT's group qualification
    val requests = hitstate.hittype.quals.map { qual =>
      backend.getAllQualificationRequests(qual.getQualificationTypeId)
    }.flatten

    requests.foreach { request =>
      // "SubjectId" === "WorkerId"
      val worker_id = request.getSubjectId

      // the HITType being requested
      val hit_type_id = if(_disqualifications.contains(request.getQualificationTypeId)) {
        _disqualifications(request.getQualificationTypeId)
      } else {
        throw new Exception("User-defined qualifications not yet supported.")
      }

      // the group_id for this HITType
      val group_id = hitstate.hittype.group_id

      if (_worker_whitelist.contains(worker_id, group_id)) {
        if (_worker_whitelist(worker_id, group_id) != hit_type_id) {
          backend.rejectQualificationRequest(request.getQualificationRequestId,
            "You have already requested a qualification or submitted work for an associated HITType " +
              "that disqualifies you from participating in this HITType."
          )
        } else {
          backend.rejectQualificationRequest(request.getQualificationRequestId,
            "You cannot request this qualification more than once."
          )
        }
      } else {
        _worker_whitelist += ((worker_id,group_id) -> hit_type_id)
        backend.grantQualification(request.getQualificationRequestId, _batch_no(hitstate.hittype.group_id))
      }
    }
  }

  private def mturk_createQualification(q: MTurkQuestion, title: String, question_id: UUID, batch_no: Int) : QualificationRequirement = {
    // get a simply-formatted date
    val sdf = new SimpleDateFormat("yyyy-MM-dd:z")
    val datestr = sdf.format(new Date())

    DebugLog("Creating disqualification.",LogLevel.INFO,LogType.ADAPTER,question_id)
    val qualtxt = String.format("AutoMan automatically generated Disqualification (title: %s, date: %s)", title, datestr)
    val qual : QualificationType = backend.createQualificationType("AutoMan " + UUID.randomUUID(), "automan", qualtxt)
    new QualificationRequirement(qual.getQualificationTypeId, Comparator.EqualTo, batch_no, null, false)
  }
}
