package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2

class GaitCoreTest {
    private fun frame(view: GaitView, points: Map<MarkerId, MarkerPoint>, w: Int = 1000, h: Int = 2000) =
        GaitFrame(1000, w, h, points, view, true, null)

    @Test fun horizontalAngleUsesPixelAspectAndAnatomicalSide() {
        val f = frame(GaitView.FRONT, mapOf(
            MarkerId.LEFT_SHOULDER to MarkerPoint(.7, .3),
            MarkerId.RIGHT_SHOULDER to MarkerPoint(.3, .25)
        ))
        val expected = Math.toDegrees(atan2(100.0, 400.0))
        assertEquals(expected, GaitMath.angles(f, GaitView.FRONT)[GaitMetric.SHOULDERS]!!, .00001)
        assertEquals(expected, GaitMath.angles(f.copy(width = 500, height = 1000), GaitView.FRONT)[GaitMetric.SHOULDERS]!!, .00001)
    }

    @Test fun backViewPreservesAnatomicalLeftLowerSign() {
        val f = frame(GaitView.BACK, mapOf(
            MarkerId.LEFT_PELVIS to MarkerPoint(.3, .55), MarkerId.RIGHT_PELVIS to MarkerPoint(.7, .5)
        ))
        assertTrue(GaitMath.angles(f, GaitView.BACK).getValue(GaitMetric.PELVIS) > 0)
    }

    @Test fun missingCrossedAndCoincidentContoursDoNotProduceAnAngle() {
        assertTrue(GaitMath.angles(frame(GaitView.FRONT, emptyMap()), GaitView.FRONT).isEmpty())
        for (leftX in listOf(.3, .5, Double.NaN)) {
            val f = frame(GaitView.FRONT, mapOf(
                MarkerId.LEFT_KNEE to MarkerPoint(leftX, .7), MarkerId.RIGHT_KNEE to MarkerPoint(.5, .7)
            ))
            assertFalse(GaitMath.angles(f, GaitView.FRONT).containsKey(GaitMetric.KNEES))
        }
    }

    @Test fun spineRightLeanIsPositiveAndInvertedEndpointsAreRejected() {
        val points = mapOf(MarkerId.C7 to MarkerPoint(.55, .2), MarkerId.COCCYX to MarkerPoint(.5, .6))
        val angle = GaitMath.angles(frame(GaitView.BACK, points), GaitView.BACK).getValue(GaitMetric.SPINE)
        assertEquals(Math.toDegrees(atan2(50.0, 800.0)), angle, .00001)
        assertFalse(GaitMath.angles(frame(GaitView.BACK, points.mapValues { (_,p) -> p.copy(y = 1-p.y) }), GaitView.BACK).containsKey(GaitMetric.SPINE))
    }

    @Test fun absoluteMeanExposesAlternatingTiltWhichSignedMeanCancels() {
        val samples = listOf(-5.0, 5.0).mapIndexed { i,v -> GaitSample(i.toLong(),GaitView.FRONT,mapOf(GaitMetric.SHOULDERS to v),i) }
        val s = GaitSession("id",0,GaitProfile(),samples,2,0,2,0,false,false)
        val summary = GaitMath.summary(s,GaitMetric.SHOULDERS,GaitView.FRONT)!!
        assertEquals(0.0,summary.mean,.0001)
        assertEquals(5.0,summary.meanAbsolute,.0001)
        assertNull(GaitMath.summary(s,GaitMetric.SHOULDERS,GaitView.BACK))
    }

    @Test fun stationaryNoiseDoesNotCountSteps() {
        val c = GaitStepCounter()
        for(i in 0..100) assertFalse(c.update(if(i%2==0) .03 else -.03,i*100L))
    }

    @Test fun alternatingSustainedPhasesCountButBaselineAndSamePhaseDoNot() {
        val c = GaitStepCounter()
        assertFalse(c.update(.15,0))
        assertFalse(c.update(.15,100))
        assertFalse(c.update(-.15,300))
        assertTrue(c.update(-.15,400))
        assertFalse(c.update(-.15,500))
        assertFalse(c.update(.15,700))
        assertTrue(c.update(.15,800))
    }

    @Test fun missingFramesAndLongGapsDoNotInventAStep() {
        val c = GaitStepCounter()
        c.update(.2,0);c.update(.2,100)
        assertFalse(c.update(-.2,1000))
        assertFalse(c.update(-.2,1100))
        assertFalse(c.update(null,1200))
        assertFalse(c.update(.2,1300))
        assertFalse(c.update(.2,1400))
    }

    @Test fun timedDefaultsKeepSecondsSeparateFromLegacyStepSettings() {
        val profile = GaitProfile()
        assertEquals(2, profile.passDurationSeconds)
        assertEquals(6, profile.targetDurationSecondsPerView)
        assertEquals(4, profile.passSteps)
        assertEquals(12, profile.targetStepsPerView)
        assertNull(profile.validationError())
        assertEquals(7, GaitSession("timed", 0, profile, emptyList(), 0, 0, 0, 0, false, false).protocolVersion)
        assertEquals(0, GaitSample(0, GaitView.FRONT, emptyMap(), 0).passIndex)
    }

    @Test fun timedProfileValidationRejectsUnsupportedPassesAndViewTargets() {
        for (seconds in listOf(Int.MIN_VALUE, 0, 1, 5, Int.MAX_VALUE)) {
            assertNotNull("Invalid pass duration $seconds", GaitProfile(passDurationSeconds = seconds).validationError())
        }
        for (seconds in listOf(Int.MIN_VALUE, 0, 5, 61, Int.MAX_VALUE)) {
            assertNotNull("Invalid view target $seconds", GaitProfile(targetDurationSecondsPerView = seconds).validationError())
        }
        for (seconds in 2..4) assertNull(GaitProfile(passDurationSeconds = seconds).validationError())
        assertNull(GaitProfile(passDurationSeconds = 4, targetDurationSecondsPerView = 60).validationError())
    }

    @Test fun timedComparisonsIgnoreUnusedLegacyStepControls() {
        val original = GaitProfile(181, 79, "Male", 37, 4, 12, 2, 6)
        val changedStepsOnly = original.copy(passSteps = 6, targetStepsPerView = 20)
        assertTrue(original.matchesCaptureSettings(changedStepsOnly, 6))
        assertTrue(original.matchesCaptureSettings(changedStepsOnly, 7))
        assertFalse(original.matchesCaptureSettings(original.copy(passDurationSeconds = 3), 6))
        assertFalse(original.matchesCaptureSettings(original.copy(targetDurationSecondsPerView = 9), 6))
    }

    @Test fun legacyComparisonsIgnoreNewTimedControlsWithoutRewritingTheOldSettings() {
        val original = GaitProfile(181, 79, "Male", 37, 6, 20, 2, 6)
        val changedTimingOnly = original.copy(passDurationSeconds = 4, targetDurationSecondsPerView = 12)
        for (protocol in 1..5) assertTrue(original.matchesCaptureSettings(changedTimingOnly, protocol))
        assertFalse(original.matchesCaptureSettings(original.copy(passSteps = 4), 5))
        assertFalse(original.matchesCaptureSettings(original.copy(targetStepsPerView = 12), 5))
        assertEquals(6, original.passSteps)
        assertEquals(20, original.targetStepsPerView)
    }

    @Test fun bothComparisonProtocolsRequireMatchingAnthropometricCharacteristics() {
        val original = GaitProfile()
        val differentPeople = listOf(original.copy(heightCm = 171), original.copy(weightKg = 71),
            original.copy(sex = "Other"), original.copy(age = 31))
        for (profile in differentPeople) for (protocol in listOf(5, 6)) {
            assertFalse(original.matchesCaptureSettings(profile, protocol))
        }
    }

}
