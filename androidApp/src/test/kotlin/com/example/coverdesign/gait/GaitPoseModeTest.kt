package com.example.coverdesign.gait

import java.time.ZoneOffset
import kotlin.math.atan2
import org.junit.Assert.*
import org.junit.Test

class GaitPoseModeTest {
    private fun pose(back: Boolean = false): PoseLandmarks {
        val points = linkedMapOf(
            BodyLandmark.LEFT_SHOULDER to MarkerPoint(.65, .25), BodyLandmark.RIGHT_SHOULDER to MarkerPoint(.35, .25),
            BodyLandmark.LEFT_HIP to MarkerPoint(.60, .50), BodyLandmark.RIGHT_HIP to MarkerPoint(.40, .50),
            BodyLandmark.LEFT_KNEE to MarkerPoint(.58, .70), BodyLandmark.RIGHT_KNEE to MarkerPoint(.42, .70),
            BodyLandmark.LEFT_ANKLE to MarkerPoint(.58, .85), BodyLandmark.RIGHT_ANKLE to MarkerPoint(.42, .85),
            BodyLandmark.LEFT_HEEL to MarkerPoint(.58, .87), BodyLandmark.RIGHT_HEEL to MarkerPoint(.42, .87),
            BodyLandmark.LEFT_FOOT_INDEX to MarkerPoint(.60, .90), BodyLandmark.RIGHT_FOOT_INDEX to MarkerPoint(.40, .90))
        return PoseLandmarks(points.mapValues { (_, p) -> ObservedBodyPoint(
            if (back) p.copy(x = 1 - p.x) else p, .99f, .99f) })
    }
    private fun frame(back: Boolean = false, time: Long = 1_000) = GaitPoseFrameBuilder.build(pose(back), 1000, 2000, time)

    @Test fun poseFrontAndBackHaveOnlyTheirSupportedAnglesAndNoSpinalEndpoints() {
        val front = frame()
        val back = frame(true)
        assertEquals(GaitMethod.POSE, front.measurementMethod)
        assertEquals(GaitView.FRONT, front.observedView)
        assertEquals(GaitView.BACK, back.observedView)
        assertTrue(front.bodyVisible)
        assertTrue(back.bodyVisible)
        assertEquals(3, GaitMath.angles(front, GaitView.FRONT).size)
        assertEquals(setOf(GaitMetric.SHOULDERS, GaitMetric.PELVIS), GaitMath.angles(back, GaitView.BACK).keys)
        assertFalse(back.markers.containsKey(MarkerId.C7))
        assertFalse(back.markers.containsKey(MarkerId.COCCYX))
    }

    @Test fun poseAnglesUsePixelAspectAndKeepTheAnatomicalSign() {
        val p = pose().points.toMutableMap()
        p[BodyLandmark.LEFT_SHOULDER] = ObservedBodyPoint(MarkerPoint(.65, .28), .99f, .99f)
        val f = GaitPoseFrameBuilder.build(PoseLandmarks(p), 1000, 2000, 1000)
        assertEquals(Math.toDegrees(atan2(60.0, 300.0)),
            GaitMath.angles(f, GaitView.FRONT).getValue(GaitMetric.SHOULDERS), .00001)
        val b = pose(true).points.toMutableMap()
        b[BodyLandmark.LEFT_HIP] = ObservedBodyPoint(MarkerPoint(.40, .52), .99f, .99f)
        assertTrue(GaitMath.angles(GaitPoseFrameBuilder.build(PoseLandmarks(b), 1000, 2000, 1000),
            GaitView.BACK).getValue(GaitMetric.PELVIS) > 0)
    }

    @Test fun lowConfidenceNonFiniteAndConflictingSidesCannotInventAView() {
        val original = pose().points
        for (bad in listOf(original.getValue(BodyLandmark.LEFT_SHOULDER).copy(visibility = .3f),
            original.getValue(BodyLandmark.LEFT_SHOULDER).copy(presence = Float.NaN),
            ObservedBodyPoint(MarkerPoint(Double.NaN, .25), .99f, .99f))) {
            val f = GaitPoseFrameBuilder.build(PoseLandmarks(original + (BodyLandmark.LEFT_SHOULDER to bad)), 1000, 2000, 1000)
            assertNull(f.observedView)
            assertTrue(f.markers.isEmpty())
        }
        val crossed = original + mapOf(
            BodyLandmark.LEFT_HIP to original.getValue(BodyLandmark.RIGHT_HIP),
            BodyLandmark.RIGHT_HIP to original.getValue(BodyLandmark.LEFT_HIP))
        assertNull(GaitPoseFrameBuilder.build(PoseLandmarks(crossed), 1000, 2000, 1000).observedView)
        val narrow = original.mapValues { (_, p) -> p.copy(point = p.point.copy(x = .5 + (p.point.x - .5) * .1)) }
        assertNull(GaitPoseFrameBuilder.build(PoseLandmarks(narrow), 1000, 2000, 1000).observedView)
    }

    @Test fun croppedFeetRemoveSetupAndStepEvidenceButNotTheAngleWindow() {
        val removed = pose().points.filterKeys { it !in setOf(BodyLandmark.LEFT_ANKLE, BodyLandmark.RIGHT_ANKLE,
            BodyLandmark.LEFT_HEEL, BodyLandmark.RIGHT_HEEL, BodyLandmark.LEFT_FOOT_INDEX, BodyLandmark.RIGHT_FOOT_INDEX) }
        val f = GaitPoseFrameBuilder.build(PoseLandmarks(removed), 1000, 2000, 1000)
        assertFalse(f.bodyVisible)
        assertNull(f.stepSignal)
        assertTrue(f.measurementVisible(GaitView.FRONT))
        assertFalse(f.measurementVisible(GaitView.FRONT, GaitMethod.MARKERS))
        assertFalse(f.copy(markers = f.markers - MarkerId.LEFT_KNEE).measurementVisible(GaitView.FRONT))
    }

    @Test fun backPoseSetupCompletesWithoutC7OrCoccyxAndSavesMethod() {
        val said = mutableListOf<String>()
        val feedback = object : GaitFeedback { override fun say(text: String) { said += text }; override fun bell() {} }
        val run = RunController(GaitProfile(), feedback, GaitMethod.POSE)
        run.manual = true
        run.transition(RunPhase.CHECK_BACK, "")
        for (i in 0..24) run.receive(frame(true, 1000L + i * 100))
        assertEquals(RunPhase.READY, run.phase)
        assertFalse(said.any { it.contains("C7") || it.contains("coccyx", true) || it.contains("marker", true) })
        run.finish(false)
        assertEquals(GaitMethod.POSE, run.finished!!.measurementMethod)
        assertEquals(9, run.finished!!.protocolVersion)
    }

    @Test fun poseWalkingCollectsAnglesWithCroppedFeetAndRejectsMarkerFrames() {
        val feedback = object : GaitFeedback { override fun say(text: String) {}; override fun bell() {} }
        val run = RunController(GaitProfile(), feedback, GaitMethod.POSE)
        run.manual = true
        run.transition(RunPhase.COUNTDOWN, "")
        run.receive(frame())
        run.beginWalking(1000)
        for (i in 1..18) {
            val f = frame(time = 1000L + i * 100)
            val scale = 1 + i * .006
            run.receive(f.copy(bodyVisible = false, stepSignal = null, markers = f.markers.mapValues { (_, p) ->
                MarkerPoint(.5 + (p.x - .5) * scale, .5 + (p.y - .5) * scale)
            }))
        }
        run.finish(false)
        assertTrue(run.finished!!.samples.isNotEmpty())
        assertTrue(run.finished!!.frontValidDurationMs > 0)
        assertTrue(run.finished!!.frontStepsPartial)
        assertTrue(run.finished!!.samples.all { GaitMetric.SPINE !in it.angles })
        val other = RunController(GaitProfile(), feedback, GaitMethod.POSE)
        other.manual = true
        other.transition(RunPhase.CHECK_FRONT, "")
        for (i in 0..30) other.receive(frame(time = 1000L + i * 100).copy(measurementMethod = GaitMethod.MARKERS))
        assertEquals(RunPhase.CHECK_FRONT, other.phase)
        assertTrue(other.found.isEmpty())
    }

    private fun session(method: GaitMethod) = GaitSession("id-${method.name}", 1000, GaitProfile(),
        (0..2).flatMap { i -> listOf(
            GaitSample(i * 200L, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 2.0, GaitMetric.PELVIS to 1.0, GaitMetric.KNEES to 3.0), 0),
            GaitSample(i * 200L + 100, GaitView.BACK, mapOf(GaitMetric.SHOULDERS to 2.0, GaitMetric.PELVIS to 1.0), 0)) },
        0, 0, 6, 0, true, true, protocolVersion = 9, frontValidDurationMs = 6000,
        backValidDurationMs = 6000, measurementMethod = method)

    @Test fun smallPoseScaleJitterCannotAccumulateWalkingTime() {
        val feedback = object : GaitFeedback { override fun say(text: String) {}; override fun bell() {} }
        val run = RunController(GaitProfile(), feedback, GaitMethod.POSE)
        run.manual = true
        run.transition(RunPhase.COUNTDOWN, "")
        run.receive(frame())
        run.beginWalking(1000)
        for (i in 1..19) {
            val f = frame(time = 1000L + i * 100)
            val scale = if (i % 4 < 2) 1.012 else .988
            run.receive(f.copy(stepSignal = if (i % 6 < 3) .15 else -.15,
                markers = f.markers.mapValues { (_, p) ->
                    MarkerPoint(.5 + (p.x - .5) * scale, .5 + (p.y - .5) * scale)
                }))
        }
        run.finish(false)
        assertEquals(0L, run.finished!!.frontValidDurationMs)
        assertTrue(run.finished!!.samples.isEmpty())
    }

    @Test fun poseOverviewUsesFiveLinesAndCannotExposeMalformedSpineData() {
        val good = session(GaitMethod.POSE)
        val overview = GaitInsights.overview(good)
        assertEquals(5, overview.expectedMetricCount)
        assertEquals(5, overview.availableMetricCount)
        assertTrue(overview.fullCoverage)
        val malformed = good.copy(samples = good.samples.map { it.copy(angles = it.angles + (GaitMetric.SPINE to 99.0)) })
        assertNull(GaitMath.summary(malformed, GaitMetric.SPINE, GaitView.BACK))
        assertTrue(GaitInsights.overview(malformed).metrics.none { it.key.metric == GaitMetric.SPINE })
    }

    @Test fun equalProtocolAndSettingsDoNotPoolPoseAndMarkerTrends() {
        val pose = session(GaitMethod.POSE)
        val marker = session(GaitMethod.MARKERS)
        assertFalse(GaitInsights.compatible(pose, marker))
        assertEquals(2, GaitInsights.groups(listOf(pose, marker)).size)
        val trend = GaitInsights.dailyTrend(listOf(pose, marker), pose,
            GaitMetricKey(GaitView.FRONT, GaitMetric.SHOULDERS), ZoneOffset.UTC)
        assertEquals(1, trend.compatibleSessionCount)
        assertEquals(1, trend.includedSessionCount)
    }
}
