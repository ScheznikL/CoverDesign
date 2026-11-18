package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class GaitPoseRearRecoveryTest {
    private val lowerPoints = setOf(BodyLandmark.LEFT_KNEE, BodyLandmark.RIGHT_KNEE,
        BodyLandmark.LEFT_ANKLE, BodyLandmark.RIGHT_ANKLE, BodyLandmark.LEFT_HEEL,
        BodyLandmark.RIGHT_HEEL, BodyLandmark.LEFT_FOOT_INDEX, BodyLandmark.RIGHT_FOOT_INDEX)

    private fun pose(back: Boolean = true, lowerConfidence: Float = .99f): PoseLandmarks {
        val points = linkedMapOf(
            BodyLandmark.LEFT_SHOULDER to MarkerPoint(.65, .25), BodyLandmark.RIGHT_SHOULDER to MarkerPoint(.35, .25),
            BodyLandmark.LEFT_HIP to MarkerPoint(.60, .50), BodyLandmark.RIGHT_HIP to MarkerPoint(.40, .50),
            BodyLandmark.LEFT_KNEE to MarkerPoint(.58, .70), BodyLandmark.RIGHT_KNEE to MarkerPoint(.42, .70),
            BodyLandmark.LEFT_ANKLE to MarkerPoint(.58, .85), BodyLandmark.RIGHT_ANKLE to MarkerPoint(.42, .85),
            BodyLandmark.LEFT_HEEL to MarkerPoint(.58, .87), BodyLandmark.RIGHT_HEEL to MarkerPoint(.42, .87),
            BodyLandmark.LEFT_FOOT_INDEX to MarkerPoint(.60, .90), BodyLandmark.RIGHT_FOOT_INDEX to MarkerPoint(.40, .90))
        return PoseLandmarks(points.mapValues { (type, p) ->
            val confidence = if (type in lowerPoints) lowerConfidence else .99f
            ObservedBodyPoint(if (back) p.copy(x = 1 - p.x) else p, confidence, confidence)
        })
    }

    private fun frame(back: Boolean = true, time: Long = 1000L, lowerConfidence: Float = .99f) =
        GaitPoseFrameBuilder.build(pose(back, lowerConfidence), 1000, 2000, time)

    private class Feedback : GaitFeedback {
        val spoken = mutableListOf<String>()
        override fun say(text: String) { spoken += text }
        override fun bell() {}
    }

    @Test fun rearFramingCanUsePartiallyVisibleLowerPointsWithoutWeakeningMeasuredJoints() {
        val p = pose(lowerConfidence = .45f)
        val hiddenKnees = p.copy(points = p.points - setOf(BodyLandmark.LEFT_KNEE, BodyLandmark.RIGHT_KNEE))
        val f = GaitPoseFrameBuilder.build(hiddenKnees, 1000, 2000, 1000)
        assertEquals(GaitView.BACK, f.observedView)
        assertTrue(f.bodyVisible)
        assertTrue(f.measurementVisible(GaitView.BACK))
        assertNull("Lower-confidence framing must not become a step estimate", f.stepSignal)
        assertEquals(setOf(GaitMetric.SHOULDERS, GaitMetric.PELVIS), GaitMath.angles(f, GaitView.BACK).keys)
        val lowHip = p.points.getValue(BodyLandmark.LEFT_HIP).copy(visibility = .64f)
        assertNull(GaitPoseFrameBuilder.build(p.copy(points = p.points + (BodyLandmark.LEFT_HIP to lowHip)),
            1000, 2000, 1000).observedView)
    }

    @Test fun rearFramingStillNeedsActualFeetInsideThePicture() {
        val p = pose(lowerConfidence = .45f)
        val noLeftShoe = p.copy(points = p.points - setOf(BodyLandmark.LEFT_HEEL, BodyLandmark.LEFT_FOOT_INDEX))
        assertFalse(GaitPoseFrameBuilder.build(noLeftShoe, 1000, 2000, 1000).bodyVisible)
        val outsideToe = p.points.getValue(BodyLandmark.LEFT_FOOT_INDEX).copy(point = MarkerPoint(.4, 1.02))
        assertFalse(GaitPoseFrameBuilder.build(p.copy(points = p.points + (BodyLandmark.LEFT_FOOT_INDEX to outsideToe)),
            1000, 2000, 1000).bodyVisible)
        val missingAllFeet = p.copy(points = p.points - lowerPoints)
        val cropped = GaitPoseFrameBuilder.build(missingAllFeet, 1000, 2000, 1000)
        assertFalse(cropped.bodyVisible)
        assertTrue("Cropping feet still leaves a valid rear walking angle window", cropped.measurementVisible(GaitView.BACK))
    }

    @Test fun rearSetupRequiresFreshConsistentRearFramesAndHasNoMarkerGuidance() {
        val feedback = Feedback()
        val run = RunController(GaitProfile(), feedback, GaitMethod.POSE).apply { manual = true }
        run.transition(RunPhase.CHECK_BACK, "")
        for (i in 0..30) run.receive(frame(back = false, time = 1000L + i * 100))
        assertEquals(RunPhase.CHECK_BACK, run.phase)
        assertTrue(run.found.isEmpty())
        // A side-on or conflicting-label frame between rear frames must restart the hold.
        for (i in 0..8) run.receive(frame(time = 5000L + i * 100, lowerConfidence = .45f))
        run.receive(frame(time = 5900).copy(observedView = null, markers = emptyMap()))
        for (i in 0..10) run.receive(frame(time = 6000L + i * 100, lowerConfidence = .45f))
        assertEquals(RunPhase.CHECK_BACK, run.phase)
        run.receive(frame(time = 7100).copy(timestampMs = 5000), nowMs = 7100)
        assertEquals(RunPhase.CHECK_BACK, run.phase)
        for (i in 0..23) run.receive(frame(time = 7200L + i * 100, lowerConfidence = .45f))
        assertEquals(RunPhase.READY, run.phase)
        assertFalse(feedback.spoken.joinToString(" ").contains("marker", ignoreCase = true))
        assertFalse(feedback.spoken.any { it.contains("C7") || it.contains("coccyx", ignoreCase = true) })
        run.finish(false)
        assertTrue(run.finished!!.samples.isEmpty())
    }

    @Test fun freshDetectionOnViewChangeIsBoundedAndNeverRelabelsTheFrame() {
        val recovery = GaitPoseTrackingRecovery()
        assertTrue(recovery.beforeFrame(GaitMethod.POSE, GaitView.FRONT, 1000))
        recovery.observe(frame(back = false, time = 1000), 1000)
        assertFalse(recovery.beforeFrame(GaitMethod.POSE, GaitView.FRONT, 1500))
        assertTrue(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, 3000))
        val stillFront = frame(back = false, time = 3000)
        recovery.observe(stillFront, 3000)
        assertEquals("The requested rear view must not relabel the actual front frame", GaitView.FRONT, stillFront.observedView)
        for (now in listOf(3100L, 4000L, 5000L, 5499L)) {
            assertFalse(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, now))
            recovery.observe(stillFront.copy(timestampMs = now), now)
        }
        assertTrue(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, 5500))
        recovery.observe(frame(time = 5500), 5500)
        assertFalse(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, 9000))
        assertFalse("Marker tracking must never use pose recovery resets", recovery.beforeFrame(GaitMethod.MARKERS, GaitView.BACK, 10000))
    }

    @Test fun rapidViewRequestsAreThrottledAndMarkerFramesCannotSatisfyPoseRecovery() {
        val recovery = GaitPoseTrackingRecovery()
        assertTrue(recovery.beforeFrame(GaitMethod.POSE, GaitView.FRONT, 1000))
        assertFalse(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, 1100))
        assertFalse(recovery.beforeFrame(GaitMethod.POSE, GaitView.FRONT, 1200))
        assertFalse(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, 1300))
        assertTrue(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, 2500))
        recovery.observe(frame(time = 2500).copy(measurementMethod = GaitMethod.MARKERS), 2500)
        recovery.observe(frame(back = false, time = 2600), 2600)
        assertFalse(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, 5099))
        assertTrue(recovery.beforeFrame(GaitMethod.POSE, GaitView.BACK, 5100))
    }
}
