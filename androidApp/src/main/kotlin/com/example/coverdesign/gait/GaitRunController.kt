package com.example.coverdesign.gait

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/** Guided short passes. Observed-point angles and visible-step estimates have independent validity. */
internal class RunController(val profile: GaitProfile, private val voice: GaitFeedback,
    val measurementMethod: GaitMethod = GaitMethod.MARKERS) {
    var phase by mutableStateOf(RunPhase.LEVEL)
    var latest by mutableStateOf<GaitFrame?>(null)
    var level = PhoneLevel(false)
    var manual by mutableStateOf(false)
    var active by mutableStateOf(true)
    var cameraError by mutableStateOf<String?>(null)
    var warning by mutableStateOf<String?>(null)
    var frontSteps by mutableIntStateOf(0)
    var backSteps by mutableIntStateOf(0)
    var passCount by mutableIntStateOf(0)
    var countdown by mutableIntStateOf(3)
    var found by mutableStateOf<Set<MarkerId>>(emptySet())
    var finished by mutableStateOf<GaitSession?>(null)
    var frontValidDurationMs by mutableLongStateOf(0L)
    var backValidDurationMs by mutableLongStateOf(0L)
    var frontCompletedPasses by mutableIntStateOf(0)
    var backCompletedPasses by mutableIntStateOf(0)
    var frontStepsPartial by mutableStateOf(false)
    var backStepsPartial by mutableStateOf(false)
    var passElapsedMs by mutableLongStateOf(0L)
    var passValidDurationMs by mutableLongStateOf(0L)
    var retryRequired by mutableStateOf(false)
        private set

    private val started = System.currentTimeMillis()
    private val id = UUID.randomUUID().toString()
    private val samples = mutableListOf<GaitSample>()
    private val counter = GaitStepCounter()
    private val movement = MovementEvidence()
    private data class Pending(val atMs: Long, val deltaMs: Long, val sample: GaitSample?, val stepsVisible: Boolean)
    private val pending = ArrayDeque<Pending>()
    private val markerSince = mutableMapOf<MarkerId, Long>()
    private val announced = mutableSetOf<MarkerId>()
    private val announcedGroups = mutableSetOf<String>()
    private var accepted = 0
    private var rejected = 0
    private var stableSince: Long? = null
    private var lastFrameAt: Long? = null
    private var lastEligibleAt: Long? = null
    private var lastAttemptAt: Long? = null
    private var lastWarningAt: Long? = null
    private var lastWarning: String? = null
    private var recovering = false
    private var passClockAt: Long? = null
    private var passCaptureStartAt = 0L
    private var passIndex = 0
    private var passClosed = true
    private var passStepsIncomplete = false
    private var countdownView = GaitView.FRONT
    private var resumePassView: GaitView? = null
    private val durationMs get() = profile.passDurationSeconds * 1000L
    private val targetMs get() = profile.targetDurationSecondsPerView * 1000L
    private val poseMode get() = measurementMethod == GaitMethod.POSE
    val walking get() = phase == RunPhase.FRONT || phase == RunPhase.BACK
    val setupReady get() = active && phase == RunPhase.LEVEL && levelGood()
    val returnOnly get() = walking && validDuration(view) >= targetMs
    val canEndPassEarly get() = active && walking
    val view get() = when (phase) {
        RunPhase.CHECK_BACK, RunPhase.TURN_BACK, RunPhase.BACK -> GaitView.BACK
        RunPhase.COUNTDOWN -> countdownView
        else -> GaitView.FRONT
    }
    val title get() = when (phase) {
        RunPhase.LEVEL -> "1 · Position your phone"
        RunPhase.CHECK_FRONT -> if (poseMode) "2 · Check front body points" else "2 · Check front markers"
        RunPhase.CHECK_BACK -> if (poseMode) "3 · Check back body points" else "3 · Check back markers"
        RunPhase.READY -> "4 · Face the camera"
        RunPhase.COUNTDOWN -> "Starting in $countdown"
        RunPhase.FRONT -> if (returnOnly) "Return towards the camera" else "5 · Walk towards the camera"
        RunPhase.TURN_BACK -> "Turn to face away"
        RunPhase.BACK -> if (returnOnly) "Return away from the camera" else "6 · Walk away from the camera"
        RunPhase.TURN_FRONT -> "Turn to face the camera"
        RunPhase.FINISHED -> "Session finished"
    }

    private fun validDuration(view: GaitView) = if (view == GaitView.FRONT) frontValidDurationMs else backValidDurationMs
    private fun bothTargetsMet() = frontValidDurationMs >= targetMs && backValidDurationMs >= targetMs
    private fun levelGood() = manual || level.available && level.stable && abs(level.rollDegrees) <= 2.0 && abs(level.pitchDegrees) <= 5.0
    private fun discardPending() { rejected += pending.count { it.sample != null }; pending.clear() }
    private fun resetHold() {
        stableSince = null; markerSince.clear(); announced.clear(); announcedGroups.clear(); found = emptySet()
        lastFrameAt = null; lastEligibleAt = null; counter.reset(); movement.clear(); discardPending()
    }
    private fun enter(next: RunPhase, text: String, preserveStepPhase: Boolean = false) {
        phase = next; stableSince = null; markerSince.clear(); announced.clear(); announcedGroups.clear(); found = emptySet()
        lastFrameAt = null; lastEligibleAt = null; movement.clear(); discardPending()
        if (!preserveStepPhase) counter.reset()
        if (!walking) passClockAt = null
        warning = null; lastWarning = null; lastWarningAt = null
        if (text.isNotBlank()) voice.say(text)
    }
    fun transition(next: RunPhase, text: String, preserveStepPhase: Boolean = false) {
        resumePassView = null; retryRequired = false
        enter(next, text, preserveStepPhase)
    }
    fun suspendRun() {
        if (walking) { markStepDiscontinuity(); resumePassView = view }
        active = false; passClockAt = null; resetHold(); voice.stop()
    }
    fun resumeRun() {
        resetHold(); active = true
        if (walking || phase == RunPhase.COUNTDOWN) {
            val wanted = resumePassView ?: view
            enter(turnPhase(wanted), "Hold position. Wait for a new countdown before walking.")
        }
    }
    fun confirmSetup() {
        if (setupReady) enter(RunPhase.CHECK_FRONT, "Phone ready. Stand facing the camera. Keep your whole body and feet visible for setup.")
    }
    fun manualConfirm() {
        if (!active || phase != RunPhase.LEVEL) return
        manual = true
        enter(RunPhase.CHECK_FRONT, "Stand facing the camera. Keep your whole body and feet visible for setup.")
    }
    private fun turnPhase(view: GaitView) = if (view == GaitView.FRONT) RunPhase.TURN_FRONT else RunPhase.TURN_BACK
    private fun opposite(view: GaitView) = if (view == GaitView.FRONT) GaitView.BACK else GaitView.FRONT
    private fun steady(ok: Boolean, now: Long, duration: Long): Boolean {
        if (!ok) { stableSince = null; return false }
        if (stableSince == null) stableSince = now
        return now - requireNotNull(stableSince) >= duration
    }
    private fun issue(frame: GaitFrame, wanted: GaitView, fullBody: Boolean = false): String? = when {
        cameraError != null -> cameraError
        frame.measurementMethod != measurementMethod -> if (poseMode) "Waiting for body-point tracking." else "Waiting for marker tracking."
        !levelGood() -> "Phone moved. Level and secure it before continuing."
        frame.observedView != wanted -> if (poseMode) {
            if (wanted == GaitView.FRONT) "Face the camera squarely. Keep shoulders and hips clearly visible."
            else "Turn fully away from the camera. Keep shoulders and hips clearly visible."
        } else if (wanted == GaitView.FRONT) "Face the camera so the knee markers are visible." else "Turn away so both back markers are visible."
        fullBody && !frame.bodyVisible -> "Keep your whole body and feet in the frame for setup."
        requiredMarkers(wanted, measurementMethod).any { it !in frame.markers } -> {
            val missing = requiredMarkers(wanted, measurementMethod).first { it !in frame.markers }
            if (poseMode) "${posePointLabel(missing)} is not clearly visible. Stop and reposition."
            else missingMarkerMessage(frame, missing)
        }
        !frame.measurementVisible(wanted, measurementMethod) -> if (poseMode) {
            if (wanted == GaitView.FRONT) "Keep shoulders, hips and both knees inside the frame."
            else "Keep both shoulders and hips inside the frame."
        } else if (wanted == GaitView.FRONT) "Keep shoulders, pelvis and both knee markers inside the frame." else "Keep shoulders, pelvis, C7 and coccyx markers inside the frame."
        GaitMath.angles(frame, wanted).keys != expectedGaitMetrics(wanted, measurementMethod).toSet() ->
            if (poseMode) "Body points are overlapping or ambiguous. Stop and face the camera squarely."
            else "Markers are too close or overlapping. Stop and reposition."
        else -> null
    }
    private fun posePointLabel(id: MarkerId): String = when (id) {
        MarkerId.LEFT_SHOULDER -> "Left shoulder"
        MarkerId.RIGHT_SHOULDER -> "Right shoulder"
        MarkerId.LEFT_PELVIS -> "Left hip"
        MarkerId.RIGHT_PELVIS -> "Right hip"
        MarkerId.LEFT_KNEE -> "Left knee"
        MarkerId.RIGHT_KNEE -> "Right knee"
        MarkerId.C7, MarkerId.COCCYX -> "Required body point"
    }
    private fun attempt(now: Long): Boolean {
        if (lastAttemptAt != null && now - requireNotNull(lastAttemptAt) < 120) return false
        lastAttemptAt = now
        return true
    }
    private fun rejectFrame(now: Long) { if (walking && !returnOnly && attempt(now)) rejected++ }
    private fun advancePassClock(now: Long): Boolean {
        if (!walking || !active) return false
        val previous = passClockAt
        if (previous != null && now >= previous) {
            passElapsedMs = (passElapsedMs + now - previous).coerceAtMost(durationMs)
            passClockAt = now
        }
        val deadline = passElapsedMs >= durationMs
        if (deadline && lastFrameAt?.let { now - it > 700 } == true) markStepDiscontinuity()
        return deadline
    }
    private fun markStepDiscontinuity() {
        if (!walking || returnOnly) return
        passStepsIncomplete = true
        if (passValidDurationMs > 0) markStepsPartial(view)
    }
    private fun markStepsPartial(view: GaitView) {
        if (view == GaitView.FRONT) frontStepsPartial = true else backStepsPartial = true
    }
    private fun breakContinuity() {
        markStepDiscontinuity()
        stableSince = null; markerSince.clear(); announced.clear(); announcedGroups.clear(); found = emptySet()
        counter.reset(); movement.clear(); lastEligibleAt = null; discardPending(); recovering = walking
    }

    fun receive(frame: GaitFrame, nowMs: Long = frame.timestampMs) {
        latest = frame
        if (!active || phase == RunPhase.FINISHED) return
        // The physical walking cue has a wall-clock deadline even if camera processing stalls.
        if (advancePassClock(nowMs)) { completePass(); return }
        if (nowMs - frame.timestampMs !in 0..1200) {
            warning = "Camera processing is too slow. Stop walking and improve the lighting."
            breakContinuity(); rejectFrame(nowMs); speakWarning(requireNotNull(warning), nowMs, urgent = true); return
        }
        val now = frame.timestampMs
        if (walking && now < passCaptureStartAt) return
        val previous = lastFrameAt
        lastFrameAt = now
        if (previous != null && (now <= previous || now - previous > 700)) {
            breakContinuity()
            if (phase == RunPhase.COUNTDOWN) {
                enter(turnPhase(countdownView), "Tracking was interrupted. Hold position until the camera is ready.")
                return
            }
            if (now <= previous) return
        }
        if (phase == RunPhase.LEVEL) return
        val wanted = view
        val preparing = phase == RunPhase.CHECK_FRONT || phase == RunPhase.CHECK_BACK
        val problem = issue(frame, wanted, fullBody = preparing)
        if (preparing) { prepare(frame, wanted, now, problem); return }
        if (phase == RunPhase.READY || phase == RunPhase.TURN_FRONT || phase == RunPhase.TURN_BACK) {
            if (retryRequired) {
                warning = if (poseMode) "Return to the starting position, check body tracking, then tap Repeat pass."
                    else "Return to the starting position, check markers, then tap Repeat pass."
                return
            }
            warning = problem
            if (problem != null) speakWarning(problem, now)
            if (steady(problem == null, now, 1800)) {
                if (resumePassView == null) { passElapsedMs = 0; passValidDurationMs = 0; passCount = 0 }
                countdownView = wanted
                enter(RunPhase.COUNTDOWN, "Hold position for the countdown.")
            }
            return
        }
        if (phase == RunPhase.COUNTDOWN) {
            if (problem != null) {
                enter(turnPhase(wanted), problem)
                return
            }
            counter.update(frame.stepSignal, now)
            return
        }
        if (!walking) return
        if (problem != null) {
            warning = problem; rejectFrame(now); breakContinuity(); speakWarning(problem, now)
            return
        }
        if (recovering) {
            counter.update(frame.stepSignal, now)
            if (steady(true, now, 900)) {
                recovering = false; lastEligibleAt = null; movement.seed(frame, now)
                voice.say("Tracking ready. Walk at your normal pace.")
            }
            return
        }
        val collect = !returnOnly
        val step = counter.update(frame.stepSignal, now)
        val motion = movement.observe(frame, now, wanted, step)
        if (motion.wrongDirection) {
            warning = if (wanted == GaitView.FRONT) "Walk towards the camera after the next cue." else "Walk away from the camera after the next cue."
            rejectFrame(now); lastEligibleAt = null; discardPending()
            return
        }
        if (step) {
            passCount++
            if (collect) { if (wanted == GaitView.FRONT) frontSteps++ else backSteps++ }
        }
        warning = if (!motion.recent && passElapsedMs >= 1000) "Walking movement is not confirmed yet." else null
        val delta = lastEligibleAt?.let { (now - it).takeIf { d -> d in 1..700 } } ?: 0L
        lastEligibleAt = now
        if (collect) {
            val sample = if (attempt(now)) GaitSample(now, wanted, GaitMath.angles(frame, wanted), 0, passIndex = passIndex) else null
            pending.addLast(Pending(now, delta, sample, frame.stepSignal?.isFinite() == true))
            while (pending.isNotEmpty() && now - (pending.first().atMs - pending.first().deltaMs) > 800) {
                if (pending.removeFirst().sample != null) rejected++
            }
            if (motion.recent) commitPending(wanted)
        }
        if (bothTargetsMet()) { closeMeasuredPass(); finish(true); return }
        if (collect && validDuration(wanted) >= targetMs) { completePass(); return }
        if (wanted == GaitView.FRONT && frame.nearMeasurementEdge(wanted) && motion.recent &&
            (passValidDurationMs > 0 || !collect)) {
            completePass(); return
        }
        if (samples.size >= 5000) finish(false)
    }

    private fun prepare(frame: GaitFrame, wanted: GaitView, now: Long, problem: String?) {
        val current = requiredMarkers(wanted, measurementMethod).filter {
            it in frame.markers && frame.observedView == wanted && frame.measurementMethod == measurementMethod
        }.toSet()
        markerSince.keys.toList().filter { it !in current }.forEach { markerSince.remove(it) }
        current.forEach { markerSince.putIfAbsent(it, now) }
        found = current.filter { now - (markerSince[it] ?: now) >= 450 }.toSet()
        found.filter { it !in announced }.forEach { announced.add(it); voice.bell() }
        val preparationProblem = if (poseMode) problem else markerPreparationMessage(frame, wanted, problem)
        warning = preparationProblem ?: if (poseMode) "Body points visible. Hold still for confirmation."
            else "Markers visible. Hold still for confirmation."
        val groups = listOf(
            (if (poseMode) "Both shoulder points found" else "Both shoulder markers found") to listOf(MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER),
            (if (poseMode) "Both hip points found" else "Pelvis markers found") to listOf(MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS)
        ) + if (wanted == GaitView.FRONT) listOf((if (poseMode) "Both knee points found" else "Both knee markers found") to listOf(MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE))
            else if (!poseMode) listOf("C7 marker found" to listOf(MarkerId.C7), "Coccyx marker found" to listOf(MarkerId.COCCYX))
            else emptyList()
        val newlyFound = groups.filter { (name, ids) -> name !in announcedGroups && ids.all { it in found } }
        if (newlyFound.isNotEmpty()) {
            newlyFound.forEach { announcedGroups.add(it.first) }
            voice.say((newlyFound.map { it.first } + listOfNotNull(preparationProblem)).joinToString(". "))
            lastWarning = preparationProblem; lastWarningAt = now
        } else if (preparationProblem != null) speakWarning(preparationProblem, now)
        if (steady(problem == null && found.size == requiredMarkers(wanted, measurementMethod).size, now, 1400)) {
            if (phase == RunPhase.CHECK_FRONT) enter(RunPhase.CHECK_BACK,
                if (poseMode) "Front body points checked. Turn around slowly to check your back view."
                else "Front markers checked. Turn around slowly to check your back markers.")
            else enter(RunPhase.READY,
                if (poseMode) "Back body points checked. Face the camera again. Wait for the walking cue."
                else "Back markers checked. Face the camera again. Wait for the walking cue.")
        }
    }
    private fun commitPending(wanted: GaitView) {
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            val remaining = (targetMs - validDuration(wanted)).coerceAtLeast(0)
            if (remaining == 0L) { pending.clear(); return }
            val counted = min(next.deltaMs, remaining)
            passValidDurationMs += counted
            if (wanted == GaitView.FRONT) frontValidDurationMs += counted else backValidDurationMs += counted
            if (passValidDurationMs > 0 && (!next.stepsVisible || passStepsIncomplete)) markStepsPartial(wanted)
            next.sample?.let { samples += it; accepted++ }
        }
    }
    private fun closeMeasuredPass() {
        if (!passClosed && passValidDurationMs > 0) {
            if (view == GaitView.FRONT) frontCompletedPasses++ else backCompletedPasses++
        }
        passClosed = true; passClockAt = null; discardPending()
    }
    private fun completePass(forceTurn: Boolean = false) {
        if (!walking) return
        val wanted = view
        val usable = passValidDurationMs > 0
        val returnPass = returnOnly
        closeMeasuredPass()
        resumePassView = null
        voice.bell()
        if (bothTargetsMet()) { finish(true); return }
        if (!usable && !returnPass && !forceTurn) {
            retryRequired = true
            enter(turnPhase(wanted), if (poseMode) "Stop. No usable interval. Return to the starting position, check body tracking, then tap Repeat pass."
                else "Stop. No usable interval. Return to the starting position, check markers, then tap Repeat pass.")
        } else {
            enter(turnPhase(opposite(wanted)), if (wanted == GaitView.FRONT)
                "Stop walking. Turn slowly to face away from the camera and wait for the next cue."
                else "Stop walking. Turn slowly to face the camera and wait for the next cue.")
        }
    }
    fun retryCurrentPass() {
        if (!active || !retryRequired || finished != null) return
        retryRequired = false; resumePassView = null
        passElapsedMs = 0; passValidDurationMs = 0; passCount = 0
        enter(turnPhase(view), "Check your starting position and hold still for a new countdown.")
    }
    fun endPassEarly() { if (canEndPassEarly) completePass(forceTurn = true) }
    fun beginWalking(nowMs: Long) {
        if (!active || phase != RunPhase.COUNTDOWN || retryRequired) return
        val frame = latest
        if (frame == null || nowMs - frame.timestampMs !in 0..1200 || issue(frame, countdownView) != null) {
            enter(turnPhase(countdownView), if (poseMode) "Position changed. Hold still until body tracking is ready."
                else "Position changed. Hold still until marker tracking is ready.")
            return
        }
        val wanted = countdownView
        val resuming = resumePassView == wanted
        enter(if (wanted == GaitView.FRONT) RunPhase.FRONT else RunPhase.BACK,
            if (wanted == GaitView.FRONT) "Go. Walk towards the camera." else "Go. Walk away from the camera.", preserveStepPhase = true)
        if (!resuming) {
            passElapsedMs = 0; passValidDurationMs = 0; passCount = 0; passIndex++; passStepsIncomplete = false
        }
        resumePassView = null; passClosed = false; recovering = false
        passCaptureStartAt = nowMs; passClockAt = nowMs; lastAttemptAt = null; lastEligibleAt = null
        movement.seed(frame, nowMs)
    }
    fun checkFreshness(now: Long) {
        if (!active || phase == RunPhase.LEVEL || phase == RunPhase.FINISHED) return
        if (advancePassClock(now)) { completePass(); return }
        if (latest == null || now - requireNotNull(latest).timestampMs > 1500) {
            breakContinuity()
            if (phase == RunPhase.COUNTDOWN) enter(turnPhase(countdownView), "Tracking interrupted. Stop and wait for the camera.")
            else {
                warning = "Tracking paused. Stop walking until the camera is ready."
                speakWarning(requireNotNull(warning), now, urgent = true)
            }
        }
    }
    private fun speakWarning(text: String, now: Long, urgent: Boolean = false) {
        val previous = lastWarningAt
        if (previous == null || urgent && text != lastWarning || now - previous >= 8000 || text != lastWarning && now - previous >= 3500) {
            voice.say(text); lastWarning = text; lastWarningAt = now
        }
    }
    fun finish(complete: Boolean) {
        if (finished != null) return
        discardPending(); passClockAt = null
        val completed = complete && bothTargetsMet()
        phase = RunPhase.FINISHED
        voice.say(if (completed) "Stop walking. Analysis is complete." else "Session stopped. Saving available measurements.")
        finished = GaitSession(id = id, createdAt = started, profile = profile, samples = samples.toList(),
            frontSteps = frontSteps, backSteps = backSteps, acceptedFrames = accepted, rejectedFrames = rejected,
            manualLevel = manual, completed = completed, frontValidDurationMs = frontValidDurationMs,
            backValidDurationMs = backValidDurationMs, frontCompletedPasses = frontCompletedPasses,
            backCompletedPasses = backCompletedPasses, frontStepsPartial = frontStepsPartial, backStepsPartial = backStepsPartial,
            protocolVersion = if (poseMode) 9 else 7, measurementMethod = measurementMethod)
    }

    /** Scale change is visible approach/recession evidence, not proof of a clinical gait cycle. */
    private class MovementEvidence {
        data class Result(val recent: Boolean, val wrongDirection: Boolean)
        private data class Scale(val at: Long, val shoulder: Double, val pelvis: Double, val torso: Double)
        private var anchor: Scale? = null
        private var lastMovementAt: Long? = null
        private var wrongWayUntil: Long? = null
        fun clear() { anchor = null; lastMovementAt = null; wrongWayUntil = null }
        private fun scale(frame: GaitFrame, now: Long): Scale? {
            val ls = frame.markers[MarkerId.LEFT_SHOULDER] ?: return null
            val rs = frame.markers[MarkerId.RIGHT_SHOULDER] ?: return null
            val lp = frame.markers[MarkerId.LEFT_PELVIS] ?: return null
            val rp = frame.markers[MarkerId.RIGHT_PELVIS] ?: return null
            val shoulder = abs(ls.x - rs.x)
            val pelvis = abs(lp.x - rp.x)
            val torso = (lp.y + rp.y - ls.y - rs.y) / 2
            return Scale(now, shoulder, pelvis, torso).takeIf { shoulder > .02 && pelvis > .02 && torso > .04 }
        }
        fun seed(frame: GaitFrame, now: Long) { clear(); anchor = scale(frame, now) }
        fun observe(frame: GaitFrame, now: Long, wanted: GaitView, step: Boolean): Result {
            val current = scale(frame, now)
            val baseline = anchor
            if (current != null && baseline != null) {
                val elapsed = now - baseline.at
                val pose = frame.measurementMethod == GaitMethod.POSE
                val minimumElapsed = if (pose) 250L else 80L
                val minimumChange = if (pose) .03 else .015
                if (elapsed in minimumElapsed..1000) {
                    val changes = listOf(current.shoulder / baseline.shoulder - 1,
                        current.pelvis / baseline.pelvis - 1, current.torso / baseline.torso - 1)
                    val direction = if (wanted == GaitView.FRONT) 1 else -1
                    if (changes.all { it * direction >= minimumChange }) {
                        lastMovementAt = now; wrongWayUntil = null; anchor = current
                    } else if (changes.all { it * direction <= -minimumChange }) {
                        lastMovementAt = null; wrongWayUntil = now + 600; anchor = current
                    }
                } else if (elapsed > 1000 || elapsed < 0) anchor = current
            } else if (current != null) anchor = current
            val wrong = wrongWayUntil?.let { now <= it } == true
            // Pose-joint jitter can alternate foot heights without a walk. In pose mode,
            // require visible approach/recession rather than treating a step estimate as proof.
            if (step && !wrong && frame.measurementMethod == GaitMethod.MARKERS) lastMovementAt = now
            return Result(lastMovementAt?.let { now - it in 0..600 } == true && !wrong, wrong)
        }
    }
}
