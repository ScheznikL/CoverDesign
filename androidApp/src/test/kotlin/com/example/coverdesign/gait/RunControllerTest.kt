package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

/** Production state machine, actual marker geometry and an explicit monotonic clock. */
class RunControllerTest {
    private class Feedback : GaitFeedback {
        val spoken = mutableListOf<String>()
        var bells = 0
        var stops = 0
        override fun say(text: String) { spoken += text }
        override fun bell() { bells++ }
        override fun stop() { stops++ }
    }

    private fun frame(view: GaitView, at: Long, signal: Double? = .2, body: Boolean = true,
                      scale: Double = 1.0): GaitFrame {
        val left = if (view == GaitView.FRONT) .7 else .3
        val right = 1 - left
        val points = linkedMapOf(
            MarkerId.LEFT_SHOULDER to MarkerPoint(left, .2),
            MarkerId.RIGHT_SHOULDER to MarkerPoint(right, .21),
            MarkerId.LEFT_PELVIS to MarkerPoint(left, .5),
            MarkerId.RIGHT_PELVIS to MarkerPoint(right, .5)
        )
        if (view == GaitView.FRONT) {
            points[MarkerId.LEFT_KNEE] = MarkerPoint(.6, .72)
            points[MarkerId.RIGHT_KNEE] = MarkerPoint(.4, .71)
        } else {
            points[MarkerId.C7] = MarkerPoint(.51, .15)
            points[MarkerId.COCCYX] = MarkerPoint(.5, .55)
        }
        return GaitFrame(at, 1000, 2000, points.mapValues { (_, p) ->
            MarkerPoint(.5 + (p.x - .5) * scale, .48 + (p.y - .48) * scale)
        }, view, body, signal)
    }

    private fun hold(run: RunController, view: GaitView, from: Long, duration: Long,
                     signal: Double? = .2, body: Boolean = true): Long {
        var time = from
        while (time <= from + duration) {
            run.receive(frame(view, time, signal, body))
            time += 100
        }
        return time
    }

    private fun newRun(feedback: Feedback = Feedback(), duration: Int = 2) =
        RunController(GaitProfile(passDurationSeconds = duration, targetDurationSecondsPerView = 6), feedback)
            .apply { level = PhoneLevel(true) }

    private fun beginPass(run: RunController, view: GaitView, from: Long,
                          signal: Double? = .2, body: Boolean = true): Long {
        val next = hold(run, view, from, 2000, signal, body)
        assertEquals(RunPhase.COUNTDOWN, run.phase)
        assertEquals(view, run.view)
        val afterCountdown = hold(run, view, next, 3000, signal, body)
        run.beginWalking(afterCountdown)
        assertEquals(if (view == GaitView.FRONT) RunPhase.FRONT else RunPhase.BACK, run.phase)
        return afterCountdown + 100
    }

    private fun movingPass(run: RunController, view: GaitView, from: Long): Long {
        var time = from
        var index = 1
        while (run.walking && index <= 50) {
            val scale = if (view == GaitView.FRONT) 1 + .02 * index else 1 - .015 * index
            run.receive(frame(view, time, signal = null, body = false, scale = scale))
            time += 100; index++
        }
        assertFalse("A pass has a bounded physical walking cue", run.walking)
        return time
    }

    @Test fun levelScreenWaitsForExplicitConfirmationEvenWhenPhoneIsAlreadyLevel() {
        val run = newRun()
        hold(run, GaitView.FRONT, 10_000, 5000)
        assertEquals(RunPhase.LEVEL, run.phase)
        assertTrue(run.setupReady)

        run.confirmSetup()
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
    }

    @Test fun setupConfirmationRejectsTiltUnstableSensorsAndSuspendedRun() {
        val run = newRun()
        run.level = PhoneLevel(true, rollDegrees = 8.0)
        assertFalse(run.setupReady)
        run.confirmSetup()
        assertEquals(RunPhase.LEVEL, run.phase)
        run.level = PhoneLevel(true, stable = false)
        run.confirmSetup()
        assertEquals(RunPhase.LEVEL, run.phase)
        run.level = PhoneLevel(true)
        run.suspendRun()
        run.confirmSetup()
        assertEquals(RunPhase.LEVEL, run.phase)
        run.resumeRun()
        run.confirmSetup()
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
    }

    @Test fun aBadFrameDuringCountdownRequiresNewReadyHoldEvenIfTrackingReturnsImmediately() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        val t = hold(run, GaitView.FRONT, 10_000, 2000)
        assertEquals(RunPhase.COUNTDOWN, run.phase)
        run.receive(frame(GaitView.BACK, t))
        assertEquals(RunPhase.TURN_FRONT, run.phase)
        run.receive(frame(GaitView.FRONT, t + 100))
        assertEquals(RunPhase.TURN_FRONT, run.phase)
        // A delayed UI callback from the cancelled countdown must not bypass the new hold.
        run.beginWalking(t + 200)
        assertEquals(RunPhase.TURN_FRONT, run.phase)
        assertFalse(run.walking)
    }

    @Test fun firstWarningIsSpokenAtLowUptimeAndPersistentWarningRepeatsAfterCooldown() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        feedback.spoken.clear()
        val missing = frame(GaitView.FRONT, 1000).copy(markers = emptyMap())
        run.receive(missing)
        assertEquals("A warning cannot depend on the phone having booted over five seconds ago", 1, feedback.spoken.size)
        for (t in 1500L..9500L step 500) run.receive(missing.copy(timestampMs = t))
        assertTrue("An unchanged problem must be repeated for a user facing away", feedback.spoken.size >= 2)
    }

    @Test fun missingRearMarkerDuringTurnHasSpokenGuidance() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.transition(RunPhase.TURN_BACK, "")
        feedback.spoken.clear()
        val rear = frame(GaitView.BACK, 10_000)
        run.receive(rear.copy(markers = rear.markers - MarkerId.C7))
        assertEquals(RunPhase.TURN_BACK, run.phase)
        assertTrue("Back-facing user cannot read the missing marker warning", feedback.spoken.any { it.contains("C7", ignoreCase = true) })
    }

    @Test fun preparationRequiresBothViewsAndThenReturnsToFront() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        var t = hold(run, GaitView.FRONT, 10_000, 2200)
        assertEquals(RunPhase.CHECK_BACK, run.phase)
        t = hold(run, GaitView.BACK, t, 2200)
        assertEquals(RunPhase.READY, run.phase)
        assertEquals(GaitView.FRONT, run.view)
        assertTrue(feedback.bells >= 12)
        beginPass(run, GaitView.FRONT, t)
        assertEquals(0, run.frontSteps)
        assertEquals(0, run.backSteps)
    }

    @Test fun visibleMarkerGroupsStayConfirmedWhenFeetAreCroppedButPreparationCannotAdvance() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        feedback.spoken.clear()
        for (t in 10_000L..10_600L step 100) run.receive(frame(GaitView.FRONT, t, body = false))

        assertEquals(requiredMarkers(GaitView.FRONT).toSet(), run.found)
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
        assertNotNull("Framing remains an independent readiness problem", run.warning)
        val successes = feedback.spoken.filter { it.contains("Both shoulder markers found", ignoreCase = true) }
        assertEquals("A stable group is announced once", 1, successes.size)
        val combined = successes.single()
        assertTrue(combined.contains("Pelvis markers found", ignoreCase = true))
        assertTrue(combined.contains("Both knee markers found", ignoreCase = true))
        assertTrue("The same utterance explains why green markers have not advanced setup", combined.contains(run.warning!!))
        assertEquals("A warning must not immediately flush the spoken success groups", combined, feedback.spoken.last())

        for (t in 10_700L..13_500L step 100) run.receive(frame(GaitView.FRONT, t, body = false))
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
        assertEquals(0, run.frontSteps + run.backSteps)
    }

    @Test fun detectedPelvisAndKneesAreAnnouncedWhileMissingShouldersRemainActionable() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        feedback.spoken.clear()
        fun withoutShoulders(at: Long) = frame(GaitView.FRONT, at).let { f ->
            f.copy(markers = f.markers - setOf(MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER))
        }
        for (t in 10_000L..10_600L step 100) run.receive(withoutShoulders(t))

        assertEquals(setOf(MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS, MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE), run.found)
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
        assertTrue(feedback.spoken.any { it.contains("Pelvis markers found", ignoreCase = true) && it.contains("Both knee markers found", ignoreCase = true) })
        assertFalse(feedback.spoken.any { it.contains("Both shoulder markers found", ignoreCase = true) })
        for (t in 10_700L..14_500L step 100) run.receive(withoutShoulders(t))
        assertTrue("The user at walking distance hears which group is still missing",
            feedback.spoken.last().contains("shoulder", ignoreCase = true))
    }

    @Test fun oneShoulderCannotProduceABothShouldersSuccessAnnouncement() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        feedback.spoken.clear()
        for (t in 10_000L..10_600L step 100) {
            val f = frame(GaitView.FRONT, t)
            run.receive(f.copy(markers = f.markers - MarkerId.RIGHT_SHOULDER))
        }
        assertTrue(MarkerId.LEFT_SHOULDER in run.found)
        assertFalse(MarkerId.RIGHT_SHOULDER in run.found)
        assertFalse(feedback.spoken.any { it.contains("Both shoulder markers found", ignoreCase = true) })
        assertTrue(run.warning!!.contains("right shoulder", ignoreCase = true))
    }

    @Test fun transientMarkerLossDoesNotRepeatSuccessfulGroupAnnouncements() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        feedback.spoken.clear()
        fun partial(at: Long, withKnees: Boolean): GaitFrame {
            val f = frame(GaitView.FRONT, at)
            val missing = setOf(MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER) +
                if (withKnees) emptySet() else setOf(MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE)
            return f.copy(markers = f.markers - missing)
        }
        for (t in 10_000L..10_600L step 100) run.receive(partial(t, true))
        for (t in 10_700L..11_200L step 100) run.receive(partial(t, false))
        for (t in 11_300L..12_000L step 100) run.receive(partial(t, true))

        assertEquals(RunPhase.CHECK_FRONT, run.phase)
        assertTrue(MarkerId.LEFT_KNEE in run.found)
        assertEquals(1, feedback.spoken.count { it.contains("Pelvis markers found", ignoreCase = true) })
        assertEquals(1, feedback.spoken.count { it.contains("Both knee markers found", ignoreCase = true) })
    }

    @Test fun resumingPreparationReannouncesGroupsAfterANewStableConfirmation() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        feedback.spoken.clear()
        for (t in 10_000L..10_600L step 100) run.receive(frame(GaitView.FRONT, t, body = false))
        assertEquals(1, feedback.spoken.count { it.contains("Both shoulder markers found", ignoreCase = true) })
        run.suspendRun()
        val beforePausedFrames = feedback.spoken.size
        run.receive(frame(GaitView.FRONT, 10_700, body = false))
        assertTrue(run.found.isEmpty())
        assertEquals(beforePausedFrames, feedback.spoken.size)
        run.resumeRun()
        for (t in 10_800L..11_400L step 100) run.receive(frame(GaitView.FRONT, t, body = false))

        assertEquals(requiredMarkers(GaitView.FRONT).toSet(), run.found)
        assertEquals(2, feedback.spoken.count { it.contains("Both shoulder markers found", ignoreCase = true) })
    }

    @Test fun rearPreparationSpeaksSeparateC7AndCoccyxSuccessesInOneUtterance() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.transition(RunPhase.CHECK_BACK, "")
        feedback.spoken.clear()
        for (t in 10_000L..10_600L step 100) run.receive(frame(GaitView.BACK, t, body = false))

        assertEquals(requiredMarkers(GaitView.BACK).toSet(), run.found)
        assertEquals(RunPhase.CHECK_BACK, run.phase)
        val combined = feedback.spoken.single { it.contains("C7 marker found", ignoreCase = true) }
        assertTrue(combined.contains("Coccyx marker found", ignoreCase = true))
        assertTrue(combined.contains("Both shoulder markers found", ignoreCase = true))
        assertTrue(combined.contains("Pelvis markers found", ignoreCase = true))
        assertFalse(combined.contains("knee markers found", ignoreCase = true))
        assertEquals(combined, feedback.spoken.last())
    }

    @Test fun markersFromTheOppositeViewAreNotConfirmedOrAnnouncedAsExpectedGroups() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        feedback.spoken.clear()
        hold(run, GaitView.BACK, 10_000, 1000)

        assertTrue(run.found.isEmpty())
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
        assertFalse(feedback.spoken.any { it.contains("markers found", ignoreCase = true) })
    }

    @Test fun rearCountdownKeepsRearViewAndStartsRearPass() {
        val run = newRun()
        run.transition(RunPhase.TURN_BACK, "")
        beginPass(run, GaitView.BACK, 10_000)
        assertEquals(GaitView.BACK, run.view)
    }

    @Test fun wrongDirectionCannotStartCountdown() {
        val run = newRun()
        run.transition(RunPhase.TURN_BACK, "")
        hold(run, GaitView.FRONT, 10_000, 4000)
        assertEquals(RunPhase.TURN_BACK, run.phase)
        assertEquals(0, run.frontSteps + run.backSteps)
    }

    @Test fun cameraGapCannotBridgeMarkerConfirmationOrReadyHold() {
        val run = newRun()
        run.manualConfirm()
        run.receive(frame(GaitView.FRONT, 10_000))
        run.receive(frame(GaitView.FRONT, 10_600))
        assertEquals(6, run.found.size)
        run.receive(frame(GaitView.FRONT, 20_000))
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
        assertTrue(run.found.isEmpty())
        run.transition(RunPhase.READY, "")
        run.receive(frame(GaitView.FRONT, 21_000))
        run.receive(frame(GaitView.FRONT, 40_000))
        assertEquals(RunPhase.READY, run.phase)
    }

    @Test fun cameraGapCancelsCountdownAndStaleBeginDoesNotStartWalking() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        var t = hold(run, GaitView.FRONT, 10_000, 2000)
        assertEquals(RunPhase.COUNTDOWN, run.phase)
        run.receive(frame(GaitView.FRONT, t + 3000))
        assertEquals(RunPhase.TURN_FRONT, run.phase)
        t = hold(run, GaitView.FRONT, t + 3100, 2000)
        assertEquals(RunPhase.COUNTDOWN, run.phase)
        run.beginWalking(t + 2000)
        assertEquals(RunPhase.TURN_FRONT, run.phase)
        assertFalse(run.walking)
    }

    @Test fun suspensionBlocksAllProgressAndResumeRequiresNewHold() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        hold(run, GaitView.FRONT, 10_000, 1000)
        run.suspendRun()
        hold(run, GaitView.FRONT, 11_100, 3000)
        assertEquals(RunPhase.READY, run.phase)
        run.resumeRun()
        run.receive(frame(GaitView.FRONT, 14_200))
        assertEquals(RunPhase.READY, run.phase)
        hold(run, GaitView.FRONT, 14_300, 2000)
        assertEquals(RunPhase.COUNTDOWN, run.phase)
    }

    @Test fun backgroundWatchdogDoesNotResumeAudioOrCollectMeasurements() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000)
        run.suspendRun()
        val spoken = feedback.spoken.size
        run.checkFreshness(t + 10_000)
        assertEquals(spoken, feedback.spoken.size)
        assertFalse(run.active)
        assertEquals(0, run.frontSteps + run.backSteps)
    }

    @Test fun delayedCountdownCallbackCannotStartWalkingWhileSuspended() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        val t = hold(run, GaitView.FRONT, 10_000, 2000)
        assertEquals(RunPhase.COUNTDOWN, run.phase)
        run.suspendRun()
        run.beginWalking(t)
        assertEquals(RunPhase.COUNTDOWN, run.phase)
        assertFalse(run.walking)
    }

    @Test fun shortPauseRequiresNewMarkerConfirmationAndFullReadyHold() {
        val run = newRun()
        run.manualConfirm()
        hold(run, GaitView.FRONT, 10_000, 1000)
        assertEquals(6, run.found.size)
        assertEquals(RunPhase.CHECK_FRONT, run.phase)

        run.suspendRun()
        assertTrue(run.found.isEmpty())
        run.receive(frame(GaitView.FRONT, 11_100))
        run.resumeRun()
        // Less than the camera-gap threshold: a fresh hold must be required by the pause itself.
        run.receive(frame(GaitView.FRONT, 11_200))
        assertTrue(run.found.isEmpty())
        run.receive(frame(GaitView.FRONT, 11_600))
        assertTrue(run.found.isEmpty())
        run.receive(frame(GaitView.FRONT, 11_700))
        assertEquals(6, run.found.size)
        run.receive(frame(GaitView.FRONT, 12_300))
        run.receive(frame(GaitView.FRONT, 13_000))
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
        run.receive(frame(GaitView.FRONT, 13_100))
        assertEquals(RunPhase.CHECK_BACK, run.phase)
    }

    @Test fun preparationCannotKeepGreenChecksWhenCameraStopsProducingFrames() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.manualConfirm()
        hold(run, GaitView.FRONT, 10_000, 700)
        assertEquals(6, run.found.size)
        run.checkFreshness(12_500)
        assertTrue(run.found.isEmpty())
        assertEquals(RunPhase.CHECK_FRONT, run.phase)
        assertTrue(feedback.spoken.last().contains("Tracking paused"))
        hold(run, GaitView.FRONT, 12_600, 400)
        assertTrue(run.found.isEmpty())
        hold(run, GaitView.FRONT, 13_100, 200)
        assertEquals(6, run.found.size)
    }

    @Test fun croppedFrontAndBackCanStartCountdownAndCaptureWithoutAStepSignal() {
        for (view in GaitView.entries) {
            val run = newRun()
            run.transition(if (view == GaitView.FRONT) RunPhase.READY else RunPhase.TURN_BACK, "")
            val t = beginPass(run, view, 10_000, signal = null, body = false)
            movingPass(run, view, t)
            run.finish(false)
            val saved = run.finished!!
            assertTrue(saved.samples.isNotEmpty())
            assertTrue(saved.samples.all { it.view == view && it.stepIndex == 0 && it.passIndex == 1 })
            assertTrue(if (view == GaitView.FRONT) saved.frontValidDurationMs > 0 else saved.backValidDurationMs > 0)
            assertTrue(if (view == GaitView.FRONT) saved.frontStepsPartial else saved.backStepsPartial)
            assertEquals(0, saved.frontSteps + saved.backSteps)
            assertFalse(saved.completed)
        }
    }

    @Test fun firstObservedFootAlternationCountsButAnglesDoNotDependOnFurtherSteps() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000)
        run.receive(frame(GaitView.FRONT, t, -.2))
        run.receive(frame(GaitView.FRONT, t + 100, -.2))
        assertEquals(1, run.frontSteps)
        assertEquals(1, run.passCount)
        run.receive(frame(GaitView.FRONT, t + 200, signal = null, body = false, scale = 1.03))
        run.receive(frame(GaitView.FRONT, t + 300, signal = null, body = false, scale = 1.06))
        run.finish(false)
        assertTrue(run.finished!!.samples.isNotEmpty())
        assertTrue(run.finished!!.samples.all { it.stepIndex == 0 })
        assertTrue(run.finished!!.frontStepsPartial)
    }

    @Test fun standingStillProducesNoUsableTimeAndRequiresExplicitSafeRetry() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000, null, false)
        hold(run, GaitView.FRONT, t, 2200, null, false)
        assertEquals(RunPhase.TURN_FRONT, run.phase)
        assertTrue(run.retryRequired)
        assertEquals(0L, run.frontValidDurationMs)
        assertTrue(feedback.spoken.any { it.startsWith("Stop. No usable interval.") })
        hold(run, GaitView.FRONT, t + 2300, 5000, null, false)
        assertEquals("A failed pass cannot start another approach automatically", RunPhase.TURN_FRONT, run.phase)
        run.retryCurrentPass()
        assertFalse(run.retryRequired)
        val next = hold(run, GaitView.FRONT, t + 7400, 2000, null, false)
        assertEquals(RunPhase.COUNTDOWN, run.phase)
        assertTrue(next > t)
        run.finish(false)
        assertTrue(run.finished!!.samples.isEmpty())
        assertFalse(run.finished!!.completed)
    }

    @Test fun reverseScaleChangeAndWrongViewCannotProduceWalkingMeasurements() {
        for (view in GaitView.entries) {
            val run = newRun()
            run.transition(if (view == GaitView.FRONT) RunPhase.READY else RunPhase.TURN_BACK, "")
            var t = beginPass(run, view, 10_000, null, false)
            for (i in 1..10) {
                val wrong = if (view == GaitView.FRONT) 1 - .02 * i else 1 + .02 * i
                run.receive(frame(view, t, null, false, wrong)); t += 100
            }
            run.receive(frame(if (view == GaitView.FRONT) GaitView.BACK else GaitView.FRONT, t, null, false))
            run.finish(false)
            assertEquals(0L, run.finished!!.frontValidDurationMs + run.finished!!.backValidDurationMs)
            assertTrue(run.finished!!.samples.isEmpty())
        }
    }

    @Test fun motionConfirmationCannotMakeAWholeStaticTailUsable() {
        val run = newRun(duration = 4)
        run.transition(RunPhase.READY, "")
        var t = beginPass(run, GaitView.FRONT, 10_000, null, false)
        for (i in 1..5) { run.receive(frame(GaitView.FRONT, t, null, false, 1 + i * .025)); t += 100 }
        val lastMoving = t - 100
        repeat(29) { run.receive(frame(GaitView.FRONT, t, null, false, 1.125)); t += 100 }
        run.finish(false)
        assertTrue(run.finished!!.samples.isNotEmpty())
        assertTrue(run.finished!!.samples.all { it.atMs <= lastMoving + 600 })
        assertTrue(run.finished!!.frontValidDurationMs <= 1100)
    }

    @Test fun markerLossDiscardsPendingStaticPrefixAndReacquisitionNeedsFreshMovement() {
        val run = newRun(duration = 4)
        run.transition(RunPhase.READY, "")
        var t = beginPass(run, GaitView.FRONT, 10_000, null, false)
        t = hold(run, GaitView.FRONT, t, 400, null, false)
        val lostAt = t
        val valid = frame(GaitView.FRONT, t, null, false)
        run.receive(valid.copy(markers = valid.markers - MarkerId.LEFT_KNEE)); t += 100
        t = hold(run, GaitView.FRONT, t, 1000, null, false)
        assertEquals(0L, run.frontValidDurationMs)
        for (i in 1..5) { run.receive(frame(GaitView.FRONT, t, null, false, 1 + i * .025)); t += 100 }
        run.finish(false)
        assertTrue(run.finished!!.samples.isNotEmpty())
        assertTrue(run.finished!!.samples.all { it.atMs > lostAt + 900 })
        assertTrue(run.finished!!.rejectedFrames > 0)
        assertTrue(run.finished!!.frontValidDurationMs <= 500)
    }

    @Test fun cameraGapsDoNotBecomeValidDurationAndDeadlineStopsWithoutNewFrames() {
        val feedback = Feedback()
        val run = newRun(feedback, duration = 4)
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000, null, false)
        run.receive(frame(GaitView.FRONT, t, null, false, 1.03))
        run.receive(frame(GaitView.FRONT, t + 100, null, false, 1.06))
        val beforeGap = run.frontValidDurationMs
        run.receive(frame(GaitView.FRONT, t + 1000, null, false, 1.09))
        assertEquals(beforeGap, run.frontValidDurationMs)
        run.checkFreshness(t + 4000)
        assertEquals(RunPhase.TURN_BACK, run.phase)
        assertFalse(run.walking)
        assertEquals(beforeGap, run.frontValidDurationMs)
        assertTrue(feedback.spoken.last().startsWith("Stop walking"))
    }

    @Test fun watchdogStopsAZeroDataPassSafelyEvenWhenCameraCompletelyStops() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000, null, false)
        run.checkFreshness(t + 1900)
        assertEquals(RunPhase.TURN_FRONT, run.phase)
        assertTrue(run.retryRequired)
        assertFalse(run.walking)
        assertEquals(2000L, run.passElapsedMs)
        assertEquals(0L, run.frontValidDurationMs)
    }

    @Test fun endPassEarlyIsAvailableWithZeroObservedStepsAndCanTurnBeforeAnyUsableInterval() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        beginPass(run, GaitView.FRONT, 10_000, null, false)
        assertTrue(run.canEndPassEarly)
        run.endPassEarly()
        assertEquals(RunPhase.TURN_BACK, run.phase)
        assertFalse(run.retryRequired)
        assertEquals(0, run.frontSteps)
        assertEquals(0L, run.frontValidDurationMs)
    }

    @Test fun nearingFrontMeasurementEdgeEndsAUsablePassWhileFeetRemainCropped() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000, null, false)
        run.receive(frame(GaitView.FRONT, t, null, false, 1.03))
        val approach = frame(GaitView.FRONT, t + 100, null, false, 1.06)
        run.receive(approach.copy(markers = approach.markers + mapOf(
            MarkerId.LEFT_KNEE to MarkerPoint(.6, .945), MarkerId.RIGHT_KNEE to MarkerPoint(.4, .945))))
        assertEquals(RunPhase.TURN_BACK, run.phase)
        assertTrue(run.frontValidDurationMs > 0)
        assertTrue(run.passElapsedMs < 2000)
    }

    @Test fun pausePreservesProgressButCannotIncludePauseOrJoinFootPhases() {
        val feedback = Feedback()
        val run = newRun(feedback)
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000)
        run.receive(frame(GaitView.FRONT, t, -.2))
        run.receive(frame(GaitView.FRONT, t + 100, -.2))
        val elapsed = run.passElapsedMs
        val valid = run.frontValidDurationMs
        val pausedAt = t + 200
        run.suspendRun()
        assertEquals(1, feedback.stops)
        run.receive(frame(GaitView.FRONT, t + 600_000, .2))
        run.checkFreshness(t + 610_000)
        assertEquals(elapsed, run.passElapsedMs)
        assertEquals(valid, run.frontValidDurationMs)
        run.resumeRun()
        assertEquals(RunPhase.TURN_FRONT, run.phase)
        val next = beginPass(run, GaitView.FRONT, t + 620_000, signal = .2)
        assertEquals(elapsed, run.passElapsedMs)
        assertEquals(1, run.frontSteps)
        run.receive(frame(GaitView.FRONT, next, .2))
        assertEquals("Opposite paused foot position starts a new baseline", 1, run.frontSteps)
        run.receive(frame(GaitView.FRONT, next + 100, -.2))
        run.receive(frame(GaitView.FRONT, next + 200, -.2))
        assertEquals(2, run.frontSteps)
        run.finish(false)
        assertTrue(run.finished!!.samples.none { it.atMs in pausedAt until next })
        assertTrue(run.finished!!.samples.all { it.passIndex == 1 })
    }

    @Test fun allTimedPassesFinishOnlyAfterBothViewsAccumulateSixUsableSeconds() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        var t = 10_000L
        var passes = 0
        while (run.finished == null && passes < 12) {
            val view = run.view
            val next = beginPass(run, view, t, null, false)
            t = movingPass(run, view, next) + 100
            passes++
            if (run.frontValidDurationMs < 6000 || run.backValidDurationMs < 6000) assertNull(run.finished)
        }
        assertNotNull(run.finished)
        val saved = run.finished!!
        assertTrue(saved.completed)
        assertEquals(6000L, saved.frontValidDurationMs)
        assertEquals(6000L, saved.backValidDurationMs)
        assertEquals(0, saved.frontSteps + saved.backSteps)
        assertTrue(saved.frontStepsPartial && saved.backStepsPartial)
        assertTrue(saved.frontCompletedPasses >= 3 && saved.backCompletedPasses >= 3)
        assertTrue(saved.samples.any { it.view == GaitView.FRONT })
        assertTrue(saved.samples.any { it.view == GaitView.BACK })
        assertTrue(saved.samples.all { it.stepIndex == 0 && it.passIndex > 0 })
        assertEquals(saved.samples.size, saved.acceptedFrames)
    }

    @Test fun completedViewReturnPassCollectsNeitherAnglesNorCoverage() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        var t = 10_000L
        repeat(4) {
            t = movingPass(run, GaitView.FRONT, beginPass(run, GaitView.FRONT, t, null, false)) + 100
            val backStart = beginPass(run, GaitView.BACK, t, null, false)
            if (it < 3) {
                run.receive(frame(GaitView.BACK, backStart, null, false, .97))
                run.receive(frame(GaitView.BACK, backStart + 100, null, false, .94))
                run.endPassEarly()
                t = backStart + 300
            } else t = movingPass(run, GaitView.BACK, backStart) + 100
        }
        assertEquals(6000L, run.frontValidDurationMs)
        val frontPasses = run.frontCompletedPasses
        val returnStart = beginPass(run, GaitView.FRONT, t, null, false)
        assertTrue(run.returnOnly)
        val returnEnd = movingPass(run, GaitView.FRONT, returnStart)
        assertEquals(6000L, run.frontValidDurationMs)
        assertEquals(frontPasses, run.frontCompletedPasses)
        run.finish(false)
        assertTrue(run.finished!!.samples.none { it.atMs in returnStart..returnEnd })
    }

    @Test fun completionCannotBeForcedWhileOnlyOneViewHasData() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        movingPass(run, GaitView.FRONT, beginPass(run, GaitView.FRONT, 10_000, null, false))
        run.finish(true)
        assertFalse(run.finished!!.completed)
        val saved = run.finished
        run.finish(false)
        assertSame(saved, run.finished)
    }

    @Test fun invalidOrStaleGoFrameCannotStartACroppedWalkingPass() {
        for (mode in 0..3) {
            val run = newRun()
            run.transition(RunPhase.READY, "")
            val t = hold(run, GaitView.FRONT, 10_000, 2000, null, false)
            assertEquals(RunPhase.COUNTDOWN, run.phase)
            val last = frame(GaitView.FRONT, t, null, false)
            when (mode) {
                0 -> run.receive(last.copy(markers = last.markers - MarkerId.RIGHT_KNEE))
                1 -> run.receive(last.copy(markers = last.markers +
                    (MarkerId.LEFT_KNEE to MarkerPoint(.6, .99))))
                2 -> run.level = PhoneLevel(true, pitchDegrees = 12.0)
            }
            run.beginWalking(if (mode == 3) t + 1500 else t + 100)
            assertFalse(run.walking)
            assertEquals(RunPhase.TURN_FRONT, run.phase)
        }
    }

    @Test fun aStaleInputFrameCannotBridgeUsableWalkingTime() {
        val feedback = Feedback()
        val run = newRun(feedback, duration = 4)
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000, null, false)
        run.receive(frame(GaitView.FRONT, t, null, false, 1.03))
        run.receive(frame(GaitView.FRONT, t + 100, null, false, 1.06))
        val before = run.frontValidDurationMs
        run.receive(frame(GaitView.FRONT, t + 200, null, false, 1.09), nowMs = t + 1500)
        assertEquals(before, run.frontValidDurationMs)
        assertTrue(feedback.spoken.last().contains("Stop walking"))
        run.finish(false)
        assertTrue(run.finished!!.samples.none { it.atMs == t + 200 })
    }

    @Test fun pendingStaticPrefixIsBoundedBeforeMovementIsConfirmed() {
        val run = newRun(duration = 4)
        run.transition(RunPhase.READY, "")
        var t = beginPass(run, GaitView.FRONT, 10_000, null, false)
        t = hold(run, GaitView.FRONT, t, 1700, null, false)
        run.receive(frame(GaitView.FRONT, t, null, false, 1.04))
        assertTrue(run.frontValidDurationMs in 1L..800L)
        run.finish(false)
        assertTrue(run.finished!!.samples.all { it.atMs >= t - 800 })
    }

    @Test fun markerGeometryFailureCannotBeReplacedByAnOptionalFootStep() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000)
        for (i in 0..4) {
            val bad = frame(GaitView.FRONT, t + 100 * i, -.2)
            run.receive(bad.copy(markers = bad.markers +
                (MarkerId.RIGHT_KNEE to bad.markers.getValue(MarkerId.LEFT_KNEE))))
        }
        run.finish(false)
        assertEquals(0, run.frontSteps)
        assertEquals(0L, run.frontValidDurationMs)
        assertTrue(run.finished!!.samples.isEmpty())
    }

    @Test fun walkingGapOrPauseMarksOnlyTheMeasuredViewsFiniteFootEstimatePartial() {
        for (view in GaitView.entries) for (pause in listOf(false, true)) {
            val run = newRun(duration = 4)
            run.transition(if (view == GaitView.FRONT) RunPhase.READY else RunPhase.TURN_BACK, "")
            val t = beginPass(run, view, 10_000, signal = .2, body = false)
            val firstScale = if (view == GaitView.FRONT) 1.03 else .97
            val nextScale = if (view == GaitView.FRONT) 1.06 else .94
            run.receive(frame(view, t, .2, false, firstScale))
            run.receive(frame(view, t + 100, .2, false, nextScale))
            assertFalse(run.frontStepsPartial || run.backStepsPartial)
            if (pause) run.suspendRun()
            else run.receive(frame(view, t + 1000, .2, false, nextScale))
            run.finish(false)
            val saved = run.finished!!
            assertEquals(view == GaitView.FRONT, saved.frontStepsPartial)
            assertEquals(view == GaitView.BACK, saved.backStepsPartial)
            assertTrue(saved.samples.isNotEmpty())
        }
    }

    @Test fun aBreakBeforeFirstUsableIntervalMarksPartialOnlyWhenMeasurementsLaterExist() {
        val run = newRun(duration = 4)
        run.transition(RunPhase.READY, "")
        var t = beginPass(run, GaitView.FRONT, 10_000, signal = .2, body = false)
        val valid = frame(GaitView.FRONT, t, .2, false)
        run.receive(valid.copy(markers = valid.markers - MarkerId.LEFT_KNEE))
        assertFalse(run.frontStepsPartial)
        t = hold(run, GaitView.FRONT, t + 100, 1000, .2, false)
        assertFalse(run.frontStepsPartial)
        run.receive(frame(GaitView.FRONT, t, .2, false, 1.03))
        run.receive(frame(GaitView.FRONT, t + 100, .2, false, 1.06))
        assertTrue(run.frontValidDurationMs > 0)
        assertTrue(run.frontStepsPartial)
        assertFalse(run.backStepsPartial)
    }

    @Test fun pausingWithNoDataOrBetweenPassesDoesNotLabelAnotherViewPartial() {
        val empty = newRun()
        empty.transition(RunPhase.READY, "")
        beginPass(empty, GaitView.FRONT, 10_000, signal = .2, body = false)
        empty.suspendRun()
        empty.finish(false)
        assertFalse(empty.finished!!.frontStepsPartial || empty.finished!!.backStepsPartial)

        val run = newRun()
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000, signal = .2, body = false)
        run.receive(frame(GaitView.FRONT, t, .2, false, 1.03))
        run.receive(frame(GaitView.FRONT, t + 100, .2, false, 1.06))
        run.endPassEarly()
        assertEquals(RunPhase.TURN_BACK, run.phase)
        run.suspendRun()
        run.finish(false)
        assertFalse(run.finished!!.frontStepsPartial || run.finished!!.backStepsPartial)
    }

    @Test fun deadlineAfterCameraStallMarksFiniteFootEstimatePartial() {
        val run = newRun()
        run.transition(RunPhase.READY, "")
        val t = beginPass(run, GaitView.FRONT, 10_000, signal = .2, body = false)
        run.receive(frame(GaitView.FRONT, t, .2, false, 1.03))
        run.receive(frame(GaitView.FRONT, t + 100, .2, false, 1.06))
        assertFalse(run.frontStepsPartial)
        run.checkFreshness(t + 2000)
        assertEquals(RunPhase.TURN_BACK, run.phase)
        assertTrue(run.frontStepsPartial)
        assertFalse(run.backStepsPartial)
    }

}
