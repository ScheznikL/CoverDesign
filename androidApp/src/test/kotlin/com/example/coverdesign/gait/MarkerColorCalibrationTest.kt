package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class MarkerColorCalibrationTest {
    // These baseline assertions retain the exact 1.0.6 bands. Changing-light cases below
    // exercise the new default separately, so compatibility and expansion are both checked.
    private fun standardCalibration(
        targets: Map<MarkerColor, MarkerColorTarget> = emptyMap(),
        viewTargets: Map<GaitView, Map<MarkerColor, MarkerColorTarget>> = emptyMap()
    ) = MarkerColorCalibration(targets, viewTargets, MarkerLightingTolerance.STANDARD)

    private fun hsvPixel(hue: Double, saturation: Double, value: Double): Int {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60.0) % 2 - 1))
        val m = value - c
        val rgb = when (hue.toInt() / 60) {
            0 -> doubleArrayOf(c, x, 0.0)
            1 -> doubleArrayOf(x, c, 0.0)
            2 -> doubleArrayOf(0.0, c, x)
            3 -> doubleArrayOf(0.0, x, c)
            4 -> doubleArrayOf(x, 0.0, c)
            else -> doubleArrayOf(c, 0.0, x)
        }.map { kotlin.math.round((it + m) * 255).toInt().coerceIn(0, 255) }
        return (0xff shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    private fun changingLightPalette() = MarkerColorCalibration().withViewTargets(GaitView.FRONT, mapOf(
        MarkerColor.SHOULDERS to MarkerColorTarget(120.0, 14.0, .45, .22),
        MarkerColor.PELVIS to MarkerColorTarget(220.0, 14.0, .45, .22),
        MarkerColor.KNEES to MarkerColorTarget(320.0, 14.0, .45, .22)))

    @Test fun changingLightIsTheDefaultAndStandardRestoresTheCapturedBounds() {
        val changing = changingLightPalette()
        assertEquals(MarkerLightingTolerance.VARIABLE, changing.lightingTolerance)
        val target = changing.targetsFor(GaitView.FRONT).getValue(MarkerColor.SHOULDERS)
        assertEquals(18.0, target.hueToleranceDegrees, 1e-9)
        assertEquals(.36, target.minSaturation, 1e-9)
        assertEquals(.132, target.minValue, 1e-9)
        val standard = changing.withLightingTolerance(MarkerLightingTolerance.STANDARD)
        assertEquals(MarkerColorTarget(120.0, 14.0, .45, .22), standard.targetsFor(GaitView.FRONT)[MarkerColor.SHOULDERS])
        assertEquals(changing, standard.withLightingTolerance(MarkerLightingTolerance.VARIABLE))
        assertEquals(changing.targetsFor(GaitView.FRONT), changing.withLightingTolerance(MarkerLightingTolerance.VARIABLE).targetsFor(GaitView.FRONT))
    }

    @Test fun changingLightAcceptsBoundedShadowAndHueShiftButStillRejectsGlareAndDeepDark() {
        val changing = changingLightPalette()
        val standard = changing.withLightingTolerance(MarkerLightingTolerance.STANDARD)
        for (pixel in listOf(hsvPixel(120.0, .40, .16), hsvPixel(137.0, .70, .70), hsvPixel(103.0, .70, .70))) {
            assertNull(MarkerColorClassifier.classify(pixel, standard, GaitView.FRONT))
            assertEquals(MarkerColor.SHOULDERS, MarkerColorClassifier.classify(pixel, changing, GaitView.FRONT))
            assertEquals(MarkerColor.SHOULDERS.ordinal + 1, MarkerColorClassifier.forFrame(changing, GaitView.FRONT).classifyCode(pixel))
        }
        // Brighter paper was already accepted: no upper V bound is imposed in either mode.
        assertEquals(MarkerColor.SHOULDERS, MarkerColorClassifier.classify(hsvPixel(120.0, .65, 1.0), standard, GaitView.FRONT))
        for (pixel in listOf(hsvPixel(143.0, .7, .7), hsvPixel(120.0, .10, .95), hsvPixel(120.0, .8, .07),
            0xff555555.toInt(), 0xffffffff.toInt(), 0xff020202.toInt())) {
            assertNull(MarkerColorClassifier.classify(pixel, changing, GaitView.FRONT))
            assertEquals(0, MarkerColorClassifier.forFrame(changing, GaitView.FRONT).classifyCode(pixel))
        }
    }

    @Test fun changingLightKeepsRealHueSeparationAndMeasuredSpreadConflicts() {
        val captured = mapOf(
            MarkerColor.SHOULDERS to MarkerColorTarget(120.0, 14.0, .4, .2),
            MarkerColor.PELVIS to MarkerColorTarget(146.0, 14.0, .4, .2),
            MarkerColor.KNEES to MarkerColorTarget(260.0, 14.0, .4, .2))
        val calibration = MarkerColorCalibration().withViewTargets(GaitView.FRONT, captured)
        assertTrue(calibration.problemFor(GaitView.FRONT), calibration.readyFor(GaitView.FRONT))
        val shoulder = calibration.targetsFor(GaitView.FRONT).getValue(MarkerColor.SHOULDERS)
        val pelvis = calibration.targetsFor(GaitView.FRONT).getValue(MarkerColor.PELVIS)
        assertTrue(shoulder.hueToleranceDegrees + pelvis.hueToleranceDegrees <= 24.0 + 1e-9)
        assertNull(MarkerColorClassifier.classify(hsvPixel(133.0, .8, .8), calibration, GaitView.FRONT))
        val conflict = calibration.withViewTargets(GaitView.FRONT, captured +
            (MarkerColor.PELVIS to MarkerColorTarget(130.0, 14.0, .4, .2)))
        assertFalse(conflict.readyFor(GaitView.FRONT))
        assertFalse(MarkerColorClassifier.forFrame(conflict, GaitView.FRONT).hasTargets)
    }

    @Test fun changingLightDoesNotConvertAPaleBentOffHuePaperIntoAMarker() {
        val calibration = MarkerColorCalibration().withViewTargets(GaitView.FRONT, mapOf(
            MarkerColor.SHOULDERS to MarkerColorTarget(155.0, 14.0, .25, .22),
            MarkerColor.PELVIS to MarkerColorTarget(338.0, 14.0, .4, .22),
            MarkerColor.KNEES to MarkerColorTarget(198.0, 14.0, .45, .22)))
        // Numeric colour range of the unreliable bent/desaturated knee in the local video.
        // Expanded lighting tolerance must not borrow the shoulder role or invent a knee.
        val paleOffHue = hsvPixel(176.0, .23, .60)
        assertNull(MarkerColorClassifier.classify(paleOffHue, calibration, GaitView.FRONT))
        assertEquals(0, MarkerColorClassifier.forFrame(calibration, GaitView.FRONT).classifyCode(paleOffHue))
        assertEquals(.25, calibration.targetsFor(GaitView.FRONT).getValue(MarkerColor.SHOULDERS).minSaturation, 1e-9)
    }

    @Test fun lightingModeSurvivesAllPaletteEditsAndPreparedFramesStayImmutable() {
        val changing = changingLightPalette()
        val original = MarkerColorClassifier.forFrame(changing, GaitView.FRONT)
        val dim = hsvPixel(120.0, .4, .16)
        val standard = changing.withLightingTolerance(MarkerLightingTolerance.STANDARD)
            .withTarget(MarkerColor.C7, MarkerColorTarget(50.0, 14.0, .4, .2))
            .withViewTargets(GaitView.BACK, mapOf(MarkerColor.COCCYX to MarkerColorTarget(25.0, 14.0, .4, .2)))
        assertEquals(MarkerLightingTolerance.STANDARD, standard.lightingTolerance)
        assertNotEquals(changing, changing.withLightingTolerance(MarkerLightingTolerance.STANDARD))
        assertEquals(MarkerColor.SHOULDERS.ordinal + 1, original.classifyCode(dim))
        assertEquals(0, MarkerColorClassifier.forFrame(standard, GaitView.FRONT).classifyCode(dim))
        assertFalse(standard.readyFor(GaitView.BACK))
    }

    @Test fun changingLightNeverTightensAnyOtherwiseValidCapturedBoundary() {
        for (saturation in listOf(.20, .23, .25, .40, 1.0)) {
            val selected = MarkerColorTarget(120.0, 30.0, saturation, .08, 30.0)
            val calibration = changingLightPalette().withViewTargets(GaitView.FRONT, mapOf(
                MarkerColor.SHOULDERS to selected,
                MarkerColor.PELVIS to MarkerColorTarget(220.0, 14.0, .4, .2),
                MarkerColor.KNEES to MarkerColorTarget(320.0, 14.0, .4, .2)))
            assertTrue(calibration.problemFor(GaitView.FRONT), calibration.readyFor(GaitView.FRONT))
            val effective = calibration.targetsFor(GaitView.FRONT).getValue(MarkerColor.SHOULDERS)
            assertTrue(effective.isValid())
            assertTrue(effective.minSaturation <= selected.minSaturation)
            assertTrue(effective.minValue <= selected.minValue)
            assertTrue(effective.hueToleranceDegrees >= selected.hueToleranceDegrees)
        }
    }

    @Test fun preparedChangingLightClassifierMatchesReferenceForBothModesAndViews() {
        val calibration = changingLightPalette().withViewTargets(GaitView.BACK, backViewTargets())
        for (mode in MarkerLightingTolerance.entries) for (view in GaitView.entries) {
            val profile = calibration.withLightingTolerance(mode)
            val prepared = MarkerColorClassifier.forFrame(profile, view)
            var state = 0x248abcdeL
            repeat(30_000) {
                state = (state * 1664525 + 1013904223) and 0xffffffffL
                val pixel = 0xff000000.toInt() or (state.toInt() and 0xffffff)
                assertEquals(MarkerColorClassifier.classify(pixel, profile, view)?.ordinal?.plus(1) ?: 0, prepared.classifyCode(pixel))
            }
        }
    }

    private fun sample(pixel: Int, color: MarkerColor, existing: MarkerColorCalibration? = null) =
        MarkerColorSample.capture(IntArray(200 * 300) { pixel }, 200, 300, color, existing)

    private fun patch(pixel: Int): IntArray = IntArray(200 * 300) { 0xff222222.toInt() }.also {
        for (y in 140..150) for (x in 95..105) it[y * 200 + x] = pixel
    }

    private fun completeProfile(color: MarkerColor, target: MarkerColorTarget): MarkerColorCalibration {
        val targets = mapOf(
            MarkerColor.SHOULDERS to MarkerColorTarget(150.0, 14.0, .4, .2),
            MarkerColor.PELVIS to MarkerColorTarget(220.0, 14.0, .4, .2),
            MarkerColor.KNEES to MarkerColorTarget(300.0, 14.0, .4, .2),
            MarkerColor.C7 to MarkerColorTarget(60.0, 14.0, .4, .2),
            MarkerColor.COCCYX to MarkerColorTarget(25.0, 14.0, .4, .2)
        )
        return standardCalibration(targets).withTarget(color, target)
    }

    @Test fun defaultAcceptsLimeAndDimEmeraldWhileKeepingYellowSeparate() {
        // Lime H=78 was outside the previous 100..185 shoulder band.
        assertEquals(MarkerColor.SHOULDERS, MarkerColorClassifier.classify(0xff99cc22.toInt()))
        // This emerald has V=74/255, below the previous .32 value floor.
        assertEquals(MarkerColor.SHOULDERS, MarkerColorClassifier.classify(0xff1c4a32.toInt()))
        assertEquals(MarkerColor.C7, MarkerColorClassifier.classify(0xffffdd00.toInt()))
        assertEquals(MarkerColor.COCCYX, MarkerColorClassifier.classify(0xffff7700.toInt()))
        assertNull(MarkerColorClassifier.classify(0xff454545.toInt()))
        assertNull(MarkerColorClassifier.classify(0xff0a190d.toInt()))
    }

    @Test fun learnsDimWarmGreenAndDetectsActualPixelsWithoutPoseSubstitution() {
        val sampledPaper = 0xff2f4c33.toInt()
        val paperInShadow = 0xff1b2c1d.toInt()
        assertNull(MarkerColorClassifier.classify(sampledPaper)) // Low saturation.
        assertNull(MarkerColorClassifier.classify(paperInShadow)) // Low value and saturation.
        val result = sample(sampledPaper, MarkerColor.SHOULDERS)
        assertTrue(result.message, result.valid)
        assertEquals(sampledPaper, result.argb)
        val profile = completeProfile(result.color, result.target!!)
        assertTrue(profile.problem, profile.readyForTracking)
        val blobs = MarkerBlobDetector().detect(patch(paperInShadow), 200, 300, profile)
        assertEquals(1, blobs.size)
        assertEquals(MarkerColor.SHOULDERS, blobs.single().color)
        assertEquals(100.5 / 200, blobs.single().point.x, 1e-9)
        assertEquals(145.5 / 300, blobs.single().point.y, 1e-9)
    }

    @Test fun explicitMagentaCalibrationToleratesShadowAndSaturationChange() {
        val result = sample(0xffd946ef.toInt(), MarkerColor.KNEES)
        assertTrue(result.message, result.valid)
        val profile = completeProfile(MarkerColor.KNEES, result.target!!)
        val shadedPaper = 0xff3f2542.toInt()
        assertNull(MarkerColorClassifier.classify(shadedPaper))
        assertEquals(MarkerColor.KNEES, MarkerColorClassifier.classify(shadedPaper, profile))
        assertEquals(MarkerColor.KNEES, MarkerBlobDetector().detect(patch(shadedPaper), 200, 300, profile).single().color)
        assertNull(MarkerColorClassifier.classify(0xff414141.toInt(), profile))
    }

    @Test fun supportsAllFiveIndividuallySampledPaperColors() {
        val colors = mapOf(
            MarkerColor.SHOULDERS to 0xff14b8a6.toInt(), MarkerColor.PELVIS to 0xff2563eb.toInt(),
            MarkerColor.KNEES to 0xffd946ef.toInt(), MarkerColor.C7 to 0xfffacc15.toInt(),
            MarkerColor.COCCYX to 0xfffb923c.toInt()
        )
        var profile = standardCalibration()
        colors.forEach { (color, pixel) ->
            val result = sample(pixel, color, profile)
            assertTrue("$color: ${result.message}", result.valid)
            profile = profile.withTarget(color, result.target!!)
        }
        assertEquals(5, profile.targets.size)
        assertTrue(profile.problem, profile.readyForTracking)
        colors.forEach { (color, pixel) -> assertEquals(color, MarkerColorClassifier.classify(pixel, profile)) }
    }

    @Test fun onlyCentralRoiIsLearnedRatherThanBackgroundOrOtherMarkers() {
        val pixels = IntArray(200 * 300) { 0xff0066ff.toInt() }
        val side = (200 * MarkerColorSample.ROI_FRACTION).toInt()
        for (y in (300 - side) / 2 until (300 + side) / 2) {
            for (x in (200 - side) / 2 until (200 + side) / 2) pixels[y * 200 + x] = 0xff1c4a32.toInt()
        }
        val result = MarkerColorSample.capture(pixels, 200, 300, MarkerColor.SHOULDERS)
        assertTrue(result.message, result.valid)
        assertEquals(0xff1c4a32.toInt(), result.argb)
    }

    @Test fun blackGrayWhiteMixedAndMalformedSamplesAreRejected() {
        listOf(0xff050505, 0xff888888, 0xffffffff, 0xff101a12).forEach {
            assertFalse(sample(it.toInt(), MarkerColor.SHOULDERS).valid)
        }
        val mixed = IntArray(200 * 300) { i -> if (i % 200 < 100) 0xff14b8a6.toInt() else 0xff0066ff.toInt() }
        assertFalse(MarkerColorSample.capture(mixed, 200, 300, MarkerColor.SHOULDERS).valid)
        assertFalse(MarkerColorSample.capture(IntArray(3), 200, 300, MarkerColor.SHOULDERS).valid)
    }

    @Test fun unusedDefaultsCannotRejectActualPaperButCapturedCollisionsDo() {
        val yellowLookingShoulder = 0xffd5ff00.toInt()
        val result = sample(yellowLookingShoulder, MarkerColor.SHOULDERS)
        assertTrue(result.message, result.valid)
        val partial = standardCalibration().withTarget(result.color, result.target!!)
        assertFalse(partial.readyForTracking)
        assertEquals(4, partial.missingColors.size)
        assertNull(MarkerColorClassifier.classify(yellowLookingShoulder, partial))
        assertNull(MarkerColorClassifier.classify(0xff0066ff.toInt(), partial)) // No default fill-in.
        val conflictingC7 = sample(yellowLookingShoulder, MarkerColor.C7, partial)
        assertFalse(conflictingC7.valid)
        assertTrue(conflictingC7.message.contains("similar"))
        val conflict = completeProfile(MarkerColor.SHOULDERS, MarkerColorTarget(70.0, 14.0, .4, .2))
        assertFalse(conflict.readyForTracking)
        assertTrue(conflict.problem!!.contains("similar"))
        assertNull(MarkerColorClassifier.classify(yellowLookingShoulder, conflict))
    }

    @Test fun cameraObservedLimeCyanAndWarmRedAreLearnedAsBodyRoles() {
        // Representative RGB cases outside the old semantic gates, including hue wrap at red.
        val palette = mapOf(
            MarkerColor.SHOULDERS to 0xffc5c63a.toInt(), // H ~60, below old shoulder minimum65.
            MarkerColor.PELVIS to 0xff40beb5.toInt(), // H ~176, below old pelvis minimum180.
            MarkerColor.KNEES to 0xffb63b42.toInt(), // H ~357, above old knee maximum350.
            MarkerColor.C7 to 0xffab55d5.toInt(), // A distinct arbitrary C7 paper.
            MarkerColor.COCCYX to 0xfff08026.toInt()
        )
        var profile = standardCalibration()
        palette.forEach { (color, pixel) ->
            val result = sample(pixel, color, profile)
            assertTrue("$color: ${result.message}", result.valid)
            profile = profile.withTarget(color, result.target!!)
        }
        assertTrue(profile.problem, profile.readyForTracking)
        palette.forEach { (color, pixel) ->
            assertEquals(color, MarkerColorClassifier.classify(pixel, profile))
            assertEquals(color, MarkerBlobDetector().detect(patch(pixel), 200, 300, profile).single().color)
        }
        assertEquals(MarkerColor.KNEES, MarkerColorClassifier.classify(0xffb6423b.toInt(), profile)) // H~3.
    }

    @Test fun actualFrontPaletteNeedsOnlyItsThreeCapturedRolesAndNeverDefaultRearColors() {
        val colors = mapOf(
            MarkerColor.SHOULDERS to 0xffc5c63a.toInt(),
            MarkerColor.PELVIS to 0xff40beb5.toInt(),
            MarkerColor.KNEES to 0xffb63b42.toInt()
        )
        var profile = standardCalibration()
        colors.forEach { (role, pixel) ->
            val result = sample(pixel, role, profile)
            assertTrue(result.message, result.valid)
            profile = profile.withTarget(role, result.target!!)
        }
        assertTrue(profile.problemFor(GaitView.FRONT), profile.readyFor(GaitView.FRONT))
        assertTrue(profile.missingColorsFor(GaitView.FRONT).isEmpty())
        assertFalse(profile.readyFor(GaitView.BACK))
        assertEquals(setOf(MarkerColor.C7, MarkerColor.COCCYX), profile.missingColorsFor(GaitView.BACK))
        assertFalse(profile.readyForTracking)
        assertNull(profile.targetAt(MarkerColor.C7.ordinal, GaitView.FRONT))
        assertNull(profile.targetAt(MarkerColor.COCCYX.ordinal, GaitView.FRONT))
        colors.forEach { (role, pixel) ->
            assertEquals(role, MarkerColorClassifier.classify(pixel, profile, GaitView.FRONT))
            assertNull(MarkerColorClassifier.classify(pixel, profile, GaitView.BACK))
        }
    }

    @Test fun reportedRgbMediansFromFourLocalPhotographsFitOneFrontPalette() {
        // Numeric RGB medians from the local13x13 marker-interior audit, in photograph order.
        // No photograph pixels or image files are embedded in this test or the application.
        fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        val medians = mapOf(
            MarkerColor.SHOULDERS to listOf(rgb(195,206,82), rgb(198,212,72), rgb(166,179,85), rgb(178,191,85),
                rgb(168,179,88), rgb(179,189,92), rgb(165,177,75), rgb(187,206,114)),
            MarkerColor.PELVIS to listOf(rgb(102,187,216), rgb(96,184,217), rgb(71,154,196), rgb(84,153,184),
                rgb(60,155,198), rgb(85,151,180), rgb(63,155,198), rgb(69,177,222)),
            MarkerColor.KNEES to listOf(rgb(187,36,79), rgb(197,34,92), rgb(153,40,75), rgb(174,40,83),
                rgb(152,41,80), rgb(174,41,87), rgb(162,29,68), rgb(190,31,97))
        )
        var profile = standardCalibration()
        medians.forEach { (role, pixels) ->
            val result = sample(pixels.first(), role, profile)
            assertTrue("$role: ${result.message}", result.valid)
            profile = profile.withTarget(role, result.target!!)
        }
        assertTrue(profile.problemFor(GaitView.FRONT), profile.readyFor(GaitView.FRONT))
        assertFalse(profile.readyFor(GaitView.BACK))
        val prepared = MarkerColorClassifier.forFrame(profile, GaitView.FRONT)
        medians.forEach { (role, pixels) -> pixels.forEach { pixel ->
            assertEquals("$role RGB=${pixel.toUInt().toString(16)}", role,
                MarkerColorClassifier.classify(pixel, profile, GaitView.FRONT))
            assertEquals("Prepared $role RGB=${pixel.toUInt().toString(16)}", role.ordinal + 1,
                prepared.classifyCode(pixel))
        } }
    }

    @Test fun frontKneesCanShareARearCoccyxHueButShoulderC7CollisionStillFails() {
        val colors = mapOf(
            MarkerColor.SHOULDERS to 0xff14b8a6.toInt(), MarkerColor.PELVIS to 0xff2563eb.toInt(),
            MarkerColor.KNEES to 0xfffb923c.toInt(), MarkerColor.C7 to 0xfffacc15.toInt(),
            MarkerColor.COCCYX to 0xfffb923c.toInt()
        )
        var profile = standardCalibration()
        colors.forEach { (role, pixel) ->
            val result = sample(pixel, role, profile)
            assertTrue("$role: ${result.message}", result.valid)
            profile = profile.withTarget(role, result.target!!)
        }
        assertTrue(profile.problemFor(GaitView.FRONT), profile.readyFor(GaitView.FRONT))
        assertTrue(profile.problemFor(GaitView.BACK), profile.readyFor(GaitView.BACK))
        assertEquals(MarkerColor.KNEES, MarkerColorClassifier.classify(0xfffb923c.toInt(), profile, GaitView.FRONT))
        assertEquals(MarkerColor.COCCYX, MarkerColorClassifier.classify(0xfffb923c.toInt(), profile, GaitView.BACK))
        assertNull(MarkerColorClassifier.classify(0xfffacc15.toInt(), profile, GaitView.FRONT))
        assertFalse(profile.readyForTracking) // The deliberately view-agnostic compatibility path stays conservative.
        val conflictingC7 = sample(colors.getValue(MarkerColor.SHOULDERS), MarkerColor.C7, profile)
        assertFalse(conflictingC7.valid)
        assertTrue(conflictingC7.message.contains("similar"))
    }

    @Test fun negotiationNarrowsBothRealBandsAndCanExpandThemAfterReplacement() {
        val profile = completeProfile(MarkerColor.SHOULDERS, MarkerColorTarget(75.0, 14.0, .4, .2))
        assertTrue(profile.problem, profile.readyForTracking)
        assertEquals(6.5, profile.targets.getValue(MarkerColor.SHOULDERS).hueToleranceDegrees, 1e-9)
        assertEquals(6.5, profile.targets.getValue(MarkerColor.C7).hueToleranceDegrees, 1e-9)
        val replaced = profile.withTarget(MarkerColor.SHOULDERS, MarkerColorTarget(150.0, 14.0, .4, .2))
        assertEquals(14.0, replaced.targets.getValue(MarkerColor.C7).hueToleranceDegrees, 1e-9)
        assertEquals(6.5, profile.targets.getValue(MarkerColor.C7).hueToleranceDegrees, 1e-9)
    }

    @Test fun negotiationCannotShrinkBelowMeasuredHueVariationOrEnableInvalidProfiles() {
        val broad = completeProfile(MarkerColor.SHOULDERS, MarkerColorTarget(77.0, 16.0, .4, .2, 10.0))
        assertFalse(broad.readyForTracking) // 17 degrees cannot fit10+6 degree radii plus a2 degree gap.
        assertNull(MarkerColorClassifier.classify(0xffc5c63a.toInt(), broad))
        val invalid = standardCalibration(mapOf(MarkerColor.SHOULDERS to MarkerColorTarget(Double.NaN, 14.0, .4, .2)))
        assertFalse(invalid.readyForTracking)
        assertNull(MarkerColorClassifier.classify(0xff14b8a6.toInt(), invalid))
    }

    @Test fun calibrationSnapshotsInputMapAndPreservesEarlierTargets() {
        val target = MarkerColorTarget(150.0, 14.0, .4, .2)
        val source = mutableMapOf(MarkerColor.SHOULDERS to target)
        val profile = standardCalibration(source)
        source.clear()
        assertEquals(target, profile.targets[MarkerColor.SHOULDERS])
        val next = profile.withTarget(MarkerColor.KNEES, MarkerColorTarget(300.0, 14.0, .4, .2))
        assertEquals(1, profile.targets.size)
        assertEquals(2, next.targets.size)
    }

    private fun viewTarget(hue: Double) = MarkerColorTarget(hue, 14.0, .25, .18)
    private fun frontViewTargets() = mapOf(
        MarkerColor.SHOULDERS to viewTarget(155.0),
        MarkerColor.PELVIS to viewTarget(338.0),
        MarkerColor.KNEES to viewTarget(198.0)
    )
    private fun backViewTargets() = mapOf(
        MarkerColor.SHOULDERS to viewTarget(180.0),
        MarkerColor.PELVIS to viewTarget(310.0),
        MarkerColor.C7 to viewTarget(3.0),
        MarkerColor.COCCYX to viewTarget(56.0)
    )

    @Test fun bodySamplesKeepDifferentFrontAndBackLightingWithoutChangingTheFrontPalette() {
        val front = standardCalibration().withViewTargets(GaitView.FRONT, frontViewTargets())
        val originalFront = front.targetsFor(GaitView.FRONT)
        val both = front.withViewTargets(GaitView.BACK, backViewTargets())
        assertTrue(both.readyFor(GaitView.FRONT))
        assertTrue(both.readyFor(GaitView.BACK))
        assertEquals(originalFront, both.targetsFor(GaitView.FRONT))
        assertEquals(155.0, both.targetAt(MarkerColor.SHOULDERS.ordinal, GaitView.FRONT)!!.hueDegrees, 1e-9)
        assertEquals(180.0, both.targetAt(MarkerColor.SHOULDERS.ordinal, GaitView.BACK)!!.hueDegrees, 1e-9)
        assertEquals(MarkerColor.SHOULDERS, MarkerColorClassifier.classify(0xff44aa80.toInt(), both, GaitView.FRONT))
        assertEquals(MarkerColor.SHOULDERS, MarkerColorClassifier.classify(0xff44aaaa.toInt(), both, GaitView.BACK))
        assertNull(MarkerColorClassifier.classify(0xff44aaaa.toInt(), both, GaitView.FRONT))
        assertNull(MarkerColorClassifier.classify(0xff44aa80.toInt(), both, GaitView.BACK))
        assertTrue(both.targets.isEmpty()) // View observations do not become a view-agnostic palette.
        assertFalse(both.readyForTracking)
        assertNull(MarkerColorClassifier.classify(0xff44aa80.toInt(), both))
    }

    @Test fun replacingAnIncompleteBackViewDoesNotReuseItsOldC7OrInvalidateTheFront() {
        val complete = standardCalibration().withViewTargets(GaitView.FRONT, frontViewTargets())
            .withViewTargets(GaitView.BACK, backViewTargets())
        val incomplete = complete.withViewTargets(GaitView.BACK, backViewTargets() - MarkerColor.C7)
        assertTrue(incomplete.readyFor(GaitView.FRONT))
        assertFalse(incomplete.readyFor(GaitView.BACK))
        assertEquals(setOf(MarkerColor.C7), incomplete.missingColorsFor(GaitView.BACK))
        assertFalse(incomplete.targetsFor(GaitView.BACK).containsKey(MarkerColor.C7))
        assertEquals(viewTarget(180.0), incomplete.targetsFor(GaitView.BACK)[MarkerColor.SHOULDERS])
        assertNull(incomplete.targetAt(MarkerColor.SHOULDERS.ordinal, GaitView.BACK))
        assertEquals(complete.targetsFor(GaitView.FRONT), incomplete.targetsFor(GaitView.FRONT))
    }

    @Test fun constructorAndViewReplacementSnapshotBothLevelsOfInputMaps() {
        val mutableFront = frontViewTargets().toMutableMap()
        val mutableViews = mutableMapOf<GaitView, Map<MarkerColor, MarkerColorTarget>>(GaitView.FRONT to mutableFront)
        val profile = standardCalibration(emptyMap(), mutableViews)
        val unchangedHash = profile.hashCode()
        mutableFront.clear()
        mutableViews.clear()
        assertTrue(profile.readyFor(GaitView.FRONT))
        assertEquals(frontViewTargets(), profile.targetsFor(GaitView.FRONT))
        assertEquals(unchangedHash, profile.hashCode())
        assertEquals(profile, standardCalibration().withViewTargets(GaitView.FRONT, frontViewTargets()))

        val mutableBack = backViewTargets().toMutableMap()
        val replacement = profile.withViewTargets(GaitView.BACK, mutableBack)
        mutableBack.clear()
        assertTrue(replacement.readyFor(GaitView.BACK))
        assertEquals(backViewTargets(), replacement.targetsFor(GaitView.BACK))
        assertFalse(profile.readyFor(GaitView.BACK))
        assertNotEquals(profile, replacement)
    }

    @Test fun legacyPaperSampleClearsOnlyItsColourOverridesInBothViews() {
        val original = standardCalibration().withViewTargets(GaitView.FRONT, frontViewTargets())
            .withViewTargets(GaitView.BACK, backViewTargets())
        val newShoulder = viewTarget(135.0)
        val replaced = original.withTarget(MarkerColor.SHOULDERS, newShoulder)
        assertEquals(mapOf(MarkerColor.SHOULDERS to newShoulder), replaced.targets)
        assertEquals(newShoulder, replaced.targetsFor(GaitView.FRONT)[MarkerColor.SHOULDERS])
        assertEquals(newShoulder, replaced.targetsFor(GaitView.BACK)[MarkerColor.SHOULDERS])
        assertEquals(viewTarget(338.0), replaced.targetsFor(GaitView.FRONT)[MarkerColor.PELVIS])
        assertEquals(viewTarget(310.0), replaced.targetsFor(GaitView.BACK)[MarkerColor.PELVIS])
        assertEquals(viewTarget(155.0), original.targetsFor(GaitView.FRONT)[MarkerColor.SHOULDERS])
        assertEquals(viewTarget(180.0), original.targetsFor(GaitView.BACK)[MarkerColor.SHOULDERS])
        assertTrue(replaced.readyFor(GaitView.FRONT))
        assertTrue(replaced.readyFor(GaitView.BACK))

        val clearedFront = replaced.withViewTargets(GaitView.FRONT, emptyMap())
        assertFalse(clearedFront.readyFor(GaitView.FRONT))
        assertTrue(clearedFront.readyFor(GaitView.BACK))
        assertEquals(mapOf(MarkerColor.SHOULDERS to newShoulder), clearedFront.targetsFor(GaitView.FRONT))
    }

    @Test fun preparedClassifierMatchesTheReferenceAcrossProfilesAndDeterministicRgbSamples() {
        val both = standardCalibration().withViewTargets(GaitView.FRONT, frontViewTargets())
            .withViewTargets(GaitView.BACK, backViewTargets())
        val wrap = completeProfile(MarkerColor.KNEES, viewTarget(357.0))
        val incomplete = standardCalibration().withTarget(MarkerColor.SHOULDERS, viewTarget(150.0))
        val conflict = completeProfile(MarkerColor.SHOULDERS, viewTarget(60.0))
        val profiles = listOf(
            null to null, standardCalibration() to GaitView.FRONT,
            both to GaitView.FRONT, both to GaitView.BACK, both to null,
            wrap to null, incomplete to GaitView.FRONT, conflict to null
        )
        for ((profile, view) in profiles) {
            val prepared = MarkerColorClassifier.forFrame(profile, view)
            var state = 0x1248abcdL
            repeat(20_000) {
                state = (state * 1664525 + 1013904223) and 0xffffffffL
                val pixel = 0xff000000.toInt() or (state.toInt() and 0xffffff)
                val expected = MarkerColorClassifier.classify(pixel, profile, view)?.ordinal?.plus(1) ?: 0
                assertEquals("view=$view RGB=${pixel.toUInt().toString(16)}", expected, prepared.classifyCode(pixel))
            }
        }
    }

    @Test fun preparedClassifierKeepsExactHueSaturationAndBrightnessBoundaryArithmetic() {
        val pixels = listOf(0xffff0004.toInt(), 0xff448866.toInt(), 0xff61937c.toInt(),
            0xff37708c.toInt(), 0xffc5c63a.toInt(), 0xffb83c68.toInt())
        val epsilon = 1e-9
        for (pixel in pixels) {
            val hsv = markerHsv(pixel)
            for (hueOffset in listOf(-14.0 - epsilon, -14.0, -14.0 + epsilon, 14.0 - epsilon, 14.0, 14.0 + epsilon)) {
                for (saturationOffset in listOf(-epsilon, 0.0, epsilon)) for (valueOffset in listOf(-epsilon, 0.0, epsilon)) {
                    val selected = MarkerColorTarget((hsv.hueDegrees + hueOffset + 360.0) % 360.0, 14.0,
                        (hsv.saturation + saturationOffset).coerceIn(.20, 1.0),
                        (hsv.value + valueOffset).coerceIn(.08, 1.0))
                    val profile = standardCalibration().withViewTargets(GaitView.FRONT, mapOf(
                        MarkerColor.SHOULDERS to selected,
                        MarkerColor.PELVIS to viewTarget((hsv.hueDegrees + 120.0) % 360.0),
                        MarkerColor.KNEES to viewTarget((hsv.hueDegrees + 240.0) % 360.0)))
                    assertTrue(profile.problemFor(GaitView.FRONT), profile.readyFor(GaitView.FRONT))
                    val expected = MarkerColorClassifier.classify(pixel, profile, GaitView.FRONT)?.ordinal?.plus(1) ?: 0
                    assertEquals(expected, MarkerColorClassifier.forFrame(profile, GaitView.FRONT).classifyCode(pixel))
                }
            }
        }
    }

    @Test fun preparedFrameKeepsItsOriginalViewAndCannotUseAnIncompleteOtherView() {
        val front = standardCalibration().withViewTargets(GaitView.FRONT, frontViewTargets())
        val prepared = MarkerColorClassifier.forFrame(front, GaitView.FRONT)
        val back = front.withViewTargets(GaitView.BACK, backViewTargets())
        assertEquals(MarkerColor.SHOULDERS.ordinal + 1, prepared.classifyCode(0xff44aa80.toInt()))
        assertEquals(0, prepared.classifyCode(0xff44aaaa.toInt()))
        assertEquals(MarkerColor.SHOULDERS.ordinal + 1,
            MarkerColorClassifier.forFrame(back, GaitView.BACK).classifyCode(0xff44aaaa.toInt()))
        assertFalse(MarkerColorClassifier.forFrame(front, GaitView.BACK).hasTargets)
        assertEquals(0, MarkerColorClassifier.forFrame(front, GaitView.BACK).classifyCode(0xff44aaaa.toInt()))
        assertFalse(MarkerColorClassifier.forFrame(front).hasTargets)
    }
}
