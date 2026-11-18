package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class GaitBodyColourSampleTest {
    private val width = 200
    private val height = 300
    private val first = MarkerPoint(40.5 / width, 120.5 / height)
    private val second = MarkerPoint(160.5 / width, 120.5 / height)
    private val green = 0xff448866.toInt()

    private fun snapshot(
        left: IntArray = IntArray(9) { green },
        right: IntArray = IntArray(9) { green },
        view: GaitView = GaitView.FRONT
    ): GaitColourSnapshot {
        val pixels = IntArray(width * height) { 0xff454545.toInt() }
        for ((point, core) in listOf(first to left, second to right)) {
            val cx = (point.x * width).toInt()
            val cy = (point.y * height).toInt()
            for (dy in -1..1) for (dx in -1..1) pixels[(cy + dy) * width + cx + dx] = core[(dy + 1) * 3 + dx + 1]
        }
        return GaitColourSnapshot(pixels, width, height, 10_000, view)
    }

    @Test fun learnsOnlyTheTwoSelectedNinePixelCoresWithoutALargeCentralRegion() {
        val result = MarkerBodyColourSample.capture(snapshot(), MarkerColor.SHOULDERS, listOf(first, second))
        assertTrue(result.message, result.valid)
        val target = requireNotNull(result.target)
        assertEquals(green, result.argb)
        assertEquals(markerHsv(green).hueDegrees, target.hueDegrees, 1e-9)
        assertEquals(.275, target.minSaturation, 1e-9)
    }

    @Test fun realDistantShoulderCoresAcceptThePaleSideAndPreserveMeasuredRgb() {
        // Actual 3x3 RGB cores from the local recording at 10.991 s. No image is a test asset.
        val rightCore = intArrayOf(0xff417768.toInt(), 0xff427767.toInt(), 0xff4a7e6d.toInt(),
            0xff437b6c.toInt(), 0xff417767.toInt(), 0xff447867.toInt(),
            0xff437a68.toInt(), 0xff457b6a.toInt(), 0xff427665.toInt())
        val leftCore = intArrayOf(0xff61937c.toInt(), 0xff50846f.toInt(), 0xff518572.toInt(),
            0xff6f9c86.toInt(), 0xff6d9d86.toInt(), 0xff659782.toInt(),
            0xff7fa791.toInt(), 0xff8eb9a1.toInt(), 0xff77a791.toInt())
        val result = MarkerBodyColourSample.capture(snapshot(rightCore, leftCore), MarkerColor.SHOULDERS, listOf(first, second))
        assertTrue(result.message, result.valid)
        val target = requireNotNull(result.target)
        assertEquals(.25, target.minSaturation, 1e-9)
        assertTrue(target.hueDegrees in 153.0..162.0)
        assertEquals("The swatch averages the 16 retained real pixels", 0xff518572.toInt(), result.argb)
    }

    @Test fun differentlyColouredPaperOnTheTwoSidesIsRejected() {
        val result = MarkerBodyColourSample.capture(snapshot(right = IntArray(9) { 0xff2563eb.toInt() }), MarkerColor.PELVIS, listOf(first, second))
        assertFalse(result.valid)
        assertTrue(result.message.contains("different colours"))
    }

    @Test fun sameHueButStronglyDifferentPaperSaturationIsRejected() {
        val result = MarkerBodyColourSample.capture(
            snapshot(IntArray(9) { 0xff10a060.toInt() }, IntArray(9) { 0xff70b88c.toInt() }),
            MarkerColor.SHOULDERS, listOf(first, second))
        assertFalse(result.valid)
        assertTrue(result.message.contains("differ too much"))
    }

    @Test fun matchingPaperCoresUnderDifferentBrightnessAreLearnedTogether() {
        // Same paper hue and saturation; one side is under the lamp, the other in shadow.
        val lit = 0xff33cc80.toInt()
        val shaded = 0xff104028.toInt()
        assertTrue(markerHsv(lit).value - markerHsv(shaded).value > .40)
        val result = MarkerBodyColourSample.capture(
            snapshot(IntArray(9) { lit }, IntArray(9) { shaded }),
            MarkerColor.SHOULDERS, listOf(first, second))
        assertTrue(result.message, result.valid)
        val target = requireNotNull(result.target)
        assertTrue(markerHueDistance(target.hueDegrees, 150.0) < 1.0)
        assertEquals(markerHsv(shaded).value * .40, target.minValue, 1e-9)
    }

    @Test fun aSingleCoreWithStrongLightGlareTransitionStillRequiresAClearCentre() {
        val mixedExposure = IntArray(9) { if (it < 4) 0xff104028.toInt() else 0xff4dff9f.toInt() }
        val result = MarkerBodyColourSample.capture(snapshot(mixedExposure),
            MarkerColor.SHOULDERS, listOf(first, second))
        assertFalse(result.valid)
        assertTrue(result.message.contains("uneven light"))
    }

    @Test fun aMixedColourCoreIsNotAcceptedAsOnePaperColour() {
        val mixed = IntArray(9) { if (it % 2 == 0) green else 0xffd946ef.toInt() }
        val result = MarkerBodyColourSample.capture(snapshot(mixed), MarkerColor.SHOULDERS, listOf(first, second))
        assertFalse(result.valid)
        assertTrue(result.message.contains("mixed colours"))
    }

    @Test fun blackWhiteAndGreySelectionsCannotBecomeColourTargets() {
        for (neutral in listOf(0xff030303.toInt(), 0xffeeeeee.toInt(), 0xff777777.toInt())) {
            val result = MarkerBodyColourSample.capture(snapshot(IntArray(9) { neutral }), MarkerColor.KNEES, listOf(first, second))
            assertFalse(result.valid)
        }
    }

    @Test fun duplicateAndOverlappingSelectionsRequireTheOtherPhysicalMarker() {
        for (other in listOf(first, first.copy(x = first.x + 1.0 / width), first.copy(x = first.x + 5.0 / width))) {
            val result = MarkerBodyColourSample.capture(snapshot(), MarkerColor.SHOULDERS, listOf(first, other))
            assertFalse(result.valid)
            assertTrue(result.message.contains("opposite side"))
        }
    }

    @Test fun twoSmallSeparateKneePapersThirtyPixelsApartRemainSelectableInA1088Frame() {
        val size = 1088
        val pixels = IntArray(size * size) { 0xff222222.toInt() }
        for (cx in listOf(500, 530)) for (y in 510..520) for (x in cx - 5..cx + 5) {
            pixels[y * size + x] = green
        }
        val data = GaitColourSnapshot(pixels, size, size, 0, GaitView.FRONT)
        val result = MarkerBodyColourSample.capture(data, MarkerColor.KNEES,
            listOf(MarkerPoint(500.5 / size, 515.5 / size), MarkerPoint(530.5 / size, 515.5 / size)))
        assertTrue(result.message, result.valid)
        assertEquals(green, result.argb)
    }

    @Test fun twoDistantTapsOnTheSameContinuousPaperCannotCountAsThePair() {
        val size = 1088
        val pixels = IntArray(size * size) { 0xff222222.toInt() }
        for (y in 500..520) for (x in 490..560) pixels[y * size + x] = green
        val data = GaitColourSnapshot(pixels, size, size, 0, GaitView.FRONT)
        // Fifty pixels exceeded the old image-relative distance guard, but is still one paper.
        val result = MarkerBodyColourSample.capture(data, MarkerColor.SHOULDERS,
            listOf(MarkerPoint(500.5 / size, 510.5 / size), MarkerPoint(550.5 / size, 510.5 / size)))
        assertFalse(result.valid)
        assertTrue(result.message.contains("opposite side"))
    }

    @Test fun anUnresolvedLargeColourRegionCannotEstablishTwoSeparatePapers() {
        val size = 400
        val pixels = IntArray(size * size) { green }
        val data = GaitColourSnapshot(pixels, size, size, 0, GaitView.FRONT)
        val result = MarkerBodyColourSample.capture(data, MarkerColor.PELVIS,
            listOf(MarkerPoint(50.5 / size, 50.5 / size), MarkerPoint(350.5 / size, 350.5 / size)))
        assertFalse(result.valid)
        assertTrue(result.message.contains("opposite side"))
    }

    @Test fun imageEdgesAreRejectedRatherThanClampedIntoAPartialCore() {
        val pixels = IntArray(width * height) { green }
        val data = GaitColourSnapshot(pixels, width, height, 0, GaitView.BACK)
        for (point in listOf(MarkerPoint(0.0, .5), MarkerPoint(1.0, .5), MarkerPoint(.5, 0.0), MarkerPoint(.5, 1.0))) {
            val result = MarkerBodyColourSample.capture(data, MarkerColor.C7, listOf(point))
            assertFalse(result.valid)
            assertTrue(result.message.contains("image edge"))
        }
        assertTrue(MarkerBodyColourSample.capture(data, MarkerColor.C7,
            listOf(MarkerPoint(1.5 / width, 1.5 / height))).valid)
    }

    @Test fun circularHueMeanKeepsRedSamplesNearZeroAcrossTheHueWrap() {
        val result = MarkerBodyColourSample.capture(
            snapshot(IntArray(9) { 0xffff0004.toInt() }, IntArray(9) { 0xffff0400.toInt() }),
            MarkerColor.PELVIS, listOf(first, second))
        assertTrue(result.message, result.valid)
        assertTrue(markerHueDistance(result.target!!.hueDegrees, 0.0) < 1.0)
        assertEquals(0xffff0202.toInt(), result.argb)
    }

    @Test fun singleRearLandmarksUseOneCoreAndDoNotApplyOtherGroupsHueRules() {
        for (color in listOf(MarkerColor.C7, MarkerColor.COCCYX)) {
            val result = MarkerBodyColourSample.capture(snapshot(view = GaitView.BACK), color, listOf(first))
            assertTrue(result.message, result.valid)
            assertEquals(green, result.argb) // A conflicting palette is rejected later, atomically.
            assertFalse(MarkerBodyColourSample.capture(snapshot(), color, listOf(first)).valid)
        }
        assertFalse(MarkerBodyColourSample.capture(snapshot(), MarkerColor.PELVIS, listOf(first)).valid)
        assertFalse(MarkerBodyColourSample.capture(snapshot(view = GaitView.BACK), MarkerColor.KNEES, listOf(first, second)).valid)
    }

    @Test fun nonfiniteAndOutsideSelectionsAreRejectedBeforePixelAccess() {
        for (point in listOf(MarkerPoint(Double.NaN, .5), MarkerPoint(.5, Double.POSITIVE_INFINITY), MarkerPoint(-.01, .5), MarkerPoint(1.01, .5))) {
            assertFalse(MarkerBodyColourSample.capture(snapshot(view = GaitView.BACK), MarkerColor.C7, listOf(point)).valid)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSnapshotDimensionsCannotDescribeAnotherPixelBuffer() {
        GaitColourSnapshot(IntArray(3), 2, 2, 0, GaitView.FRONT)
    }
}
