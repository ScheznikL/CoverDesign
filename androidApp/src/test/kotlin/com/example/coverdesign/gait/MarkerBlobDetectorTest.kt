package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class MarkerBlobDetectorTest {
    private data class ScenePatch(val center: MarkerPoint, val halfWidthAt640: Double, val halfHeightAt640: Double,
        val color: Int, val round: Boolean = false)

    /** Same physical scene sampled at different input resolutions, rather than duplicating pixels. */
    private fun scene(width: Int, height: Int, patches: List<ScenePatch>): IntArray {
        val pixels = IntArray(width * height) { 0xff222222.toInt() }
        val scale = maxOf(width, height) / 640.0
        patches.forEach { patch ->
            val cx = patch.center.x * width
            val cy = patch.center.y * height
            val rx = patch.halfWidthAt640 * scale
            val ry = patch.halfHeightAt640 * scale
            val left = kotlin.math.floor(cx - rx).toInt().coerceAtLeast(0)
            val right = kotlin.math.ceil(cx + rx).toInt().coerceAtMost(width - 1)
            val top = kotlin.math.floor(cy - ry).toInt().coerceAtLeast(0)
            val bottom = kotlin.math.ceil(cy + ry).toInt().coerceAtMost(height - 1)
            for (y in top..bottom) for (x in left..right) {
                val dx = (x + .5 - cx) / rx
                val dy = (y + .5 - cy) / ry
                val inside = if (patch.round) dx * dx + dy * dy <= 1.0
                    else kotlin.math.abs(dx) <= 1.0 && kotlin.math.abs(dy) <= 1.0
                if (inside) pixels[y * width + x] = patch.color
            }
        }
        return pixels
    }

    private fun image(vararg patches: Triple<Int, Int, Int>): IntArray {
        val pixels = IntArray(200 * 300) { 0xff222222.toInt() }
        for ((cx, cy, color) in patches) for (y in cy - 5..cy + 5) for (x in cx - 5..cx + 5) pixels[y * 200 + x] = color
        return pixels
    }

    @Test fun detectsDistinctColorCentersWithoutInventingOtherPoints() {
        val result = MarkerBlobDetector().detect(image(
            Triple(40, 50, 0xff00cc88.toInt()), Triple(160, 50, 0xff00cc88.toInt()),
            Triple(60, 150, 0xff0066ff.toInt()), Triple(140, 150, 0xff0066ff.toInt()),
            Triple(100, 30, 0xffffdd00.toInt()), Triple(100, 180, 0xffff7700.toInt())
        ), 200, 300)
        assertEquals(6, result.size)
        assertEquals(2, result.count { it.color == MarkerColor.SHOULDERS })
        assertEquals(2, result.count { it.color == MarkerColor.PELVIS })
        assertEquals(0, result.count { it.color == MarkerColor.KNEES })
        val c7 = result.single { it.color == MarkerColor.C7 }
        assertEquals(0.5025, c7.point.x, 0.01)
        assertEquals(0.1017, c7.point.y, 0.01)
    }

    @Test fun rejectsSparseNoiseGrayAndLargeColoredClothing() {
        val pixels = IntArray(200 * 300) { 0xff777777.toInt() }
        for (i in 0 until 200) pixels[i * 251 % pixels.size] = 0xffff00ff.toInt()
        for (y in 80..170) for (x in 50..150) pixels[y * 200 + x] = 0xff0066ff.toInt()
        assertTrue(MarkerBlobDetector().detect(pixels, 200, 300).isEmpty())
    }

    @Test fun malformedInputReturnsNoMarkers() {
        assertTrue(MarkerBlobDetector().detect(IntArray(4), 200, 300).isEmpty())
    }

    @Test fun shoulderObliqueProjectionIsAcceptedWithoutLooseningOtherColorShapes() {
        val pixels = IntArray(200 * 300) { 0xff222222.toInt() }
        for (y in 80..91) for (x in 39..41) pixels[y * 200 + x] = 0xff1c4a32.toInt()
        for (y in 150..161) for (x in 159..161) pixels[y * 200 + x] = 0xff0066ff.toInt()
        val result = MarkerBlobDetector().detect(pixels, 200, 300)
        assertEquals(1, result.size)
        assertEquals(MarkerColor.SHOULDERS, result.single().color)
        assertEquals(40.5 / 200, result.single().point.x, 1e-9)
        assertEquals(86.0 / 300, result.single().point.y, 1e-9)
    }

    @Test fun fivePixelSquareSurvivesButIsolatedGreenNoiseStillFails() {
        val pixels = IntArray(480 * 640) { 0xff222222.toInt() }
        for (y in 300..304) for (x in 200..204) pixels[y * 480 + x] = 0xff99cc22.toInt()
        for (i in 0..100) pixels[(i * 2713) % pixels.size] = 0xff99cc22.toInt()
        val result = MarkerBlobDetector().detect(pixels, 480, 640)
        assertEquals(1, result.size)
        assertEquals(MarkerColor.SHOULDERS, result.single().color)
    }

    @Test fun smallRoundAndRotatedMarkersSurviveCameraResolutionAndDownsampling() {
        for (scale in listOf(1, 2)) {
            val width = 480 * scale
            val height = 640 * scale
            val pixels = IntArray(width * height) { 0xff303030.toInt() }
            val colors = listOf(0xff14b8a6, 0xff2563eb, 0xffd946ef, 0xfffacc15, 0xfffb923c)
            colors.forEachIndexed { i, color ->
                val cx = (80 + i * 75) * scale
                val cy = 320 * scale
                for (dy in -6 * scale..6 * scale) for (dx in -6 * scale..6 * scale) {
                    val inside = if (i % 2 == 0) dx * dx + dy * dy <= 25 * scale * scale
                    else kotlin.math.abs(dx) + kotlin.math.abs(dy) <= 6 * scale
                    if (inside) pixels[(cy + dy) * width + cx + dx] = color.toInt()
                }
            }
            val detected = MarkerBlobDetector().detect(pixels, width, height)
            assertEquals("scale=$scale", 5, detected.size)
            assertEquals(MarkerColor.entries.toSet(), detected.map { it.color }.toSet())
            assertTrue(detected.all { kotlin.math.abs(it.point.y - .5) < .01 })
        }
    }

    @Test fun sameSceneRetainsThinAndRoundMarkersAcrossCameraResolutions() {
        val patches = listOf(
            ScenePatch(MarkerPoint(.30, .28), 5.0, 5.0, 0xffd946ef.toInt()),
            ScenePatch(MarkerPoint(.70, .28), 5.0, 5.0, 0xff2563eb.toInt(), round = true),
            // A shoulder paper seen obliquely. The old 1088/3 grid reduced this to about 1 pixel wide.
            ScenePatch(MarkerPoint(.50, .70), 1.5, 6.0, 0xff14b8a6.toInt())
        )
        for (side in listOf(640, 1088, 1280, 1920)) {
            for (py in 0..2) for (px in 0..2) {
                val shifted = patches.map { it.copy(center = MarkerPoint(it.center.x + px / 640.0, it.center.y + py / 640.0)) }
                val detector = MarkerBlobDetector()
                val result = detector.detect(scene(side, side, shifted), side, side)
                assertEquals("input=$side phase=$px,$py", 3, result.size)
                assertEquals(640, detector.lastWorkingWidth)
                assertEquals(640, detector.lastWorkingHeight)
                val colors = listOf(MarkerColor.KNEES, MarkerColor.PELVIS, MarkerColor.SHOULDERS)
                shifted.zip(colors).forEach { (patch, color) ->
                    val actual = result.single { it.color == color }.point
                    assertEquals("input=$side,$color x", patch.center.x, actual.x, 1.5 / 640)
                    assertEquals("input=$side,$color y", patch.center.y, actual.y, 1.5 / 640)
                }
            }
        }
    }

    @Test fun portraitResamplingPreservesAspectAndMeasuredNormalizedCoordinates() {
        val patch = ScenePatch(MarkerPoint(.31, .63), 5.0, 5.0, 0xff14b8a6.toInt())
        for (height in listOf(640, 1088, 1280, 1920)) {
            val width = height * 3 / 4
            val detector = MarkerBlobDetector()
            val result = detector.detect(scene(width, height, listOf(patch)), width, height).single()
            assertEquals(480, detector.lastWorkingWidth)
            assertEquals(640, detector.lastWorkingHeight)
            assertEquals(patch.center.x, result.point.x, 1.5 / 480)
            assertEquals(patch.center.y, result.point.y, 1.5 / 640)
        }
    }

    @Test fun twoNearbyActualMarkersStaySeparateAtEveryWorkingScale() {
        // Two 8 pixel papers with a 3 pixel gap at the 640 working scale: they remain resolvable.
        val patches = listOf(
            ScenePatch(MarkerPoint(.5 - 5.5 / 640, .5), 4.0, 4.0, 0xff14b8a6.toInt()),
            ScenePatch(MarkerPoint(.5 + 5.5 / 640, .5), 4.0, 4.0, 0xff14b8a6.toInt())
        )
        for (side in listOf(640, 1088, 1280, 1920)) {
            val result = MarkerBlobDetector().detect(scene(side, side, patches), side, side).sortedBy { it.point.x }
            assertEquals("input=$side", 2, result.size)
            patches.zip(result).forEach { (patch, actual) ->
                assertEquals(patch.center.x, actual.point.x, 1.0 / 640)
                assertEquals(patch.center.y, actual.point.y, 1.0 / 640)
            }
        }
    }

    @Test fun resamplingDoesNotPromoteNoiseOrUpscaleSmallerFrames() {
        for (side in listOf(640, 1088, 1280, 1920)) {
            val tinyPatches = (1..8).map {
                ScenePatch(MarkerPoint(it / 10.0, .4), .9, .9, 0xff14b8a6.toInt())
            }
            val pixels = scene(side, side, tinyPatches)
            for (i in 0..200) pixels[(i * 2713) % pixels.size] = 0xff14b8a6.toInt()
            assertTrue("input=$side", MarkerBlobDetector().detect(pixels, side, side).isEmpty())
        }
        val detector = MarkerBlobDetector()
        assertTrue(detector.detect(IntArray(200 * 300) { 0xff222222.toInt() }, 200, 300).isEmpty())
        assertEquals(200, detector.lastWorkingWidth)
        assertEquals(300, detector.lastWorkingHeight)
    }

    @Test fun preparedDetectionPreservesReferenceColourCountsAndActualMarkerCentres() {
        val pixels = image(Triple(40, 50, 0xff44aa80.toInt()), Triple(100, 150, 0xffb83c68.toInt()),
            Triple(160, 240, 0xff37708c.toInt()))
        val profile = MarkerColorCalibration().withViewTargets(GaitView.FRONT, mapOf(
            MarkerColor.SHOULDERS to MarkerColorTarget(155.0, 14.0, .25, .18),
            MarkerColor.PELVIS to MarkerColorTarget(338.0, 14.0, .25, .18),
            MarkerColor.KNEES to MarkerColorTarget(198.0, 14.0, .25, .18)))
        val expectedCounts = MarkerColor.entries.associateWith { color ->
            pixels.count { MarkerColorClassifier.classify(it, profile, GaitView.FRONT) == color }
        }
        val detector = MarkerBlobDetector()
        val markers = detector.detect(pixels, 200, 300, profile, GaitView.FRONT)
        assertEquals(expectedCounts, detector.lastColourPixelCounts)
        assertEquals(3, markers.size)
        assertEquals(40.5 / 200, markers.single { it.color == MarkerColor.SHOULDERS }.point.x, 1e-9)
        assertEquals(150.5 / 300, markers.single { it.color == MarkerColor.PELVIS }.point.y, 1e-9)
    }

    @Test fun incompleteViewClearsPreviousCountsWithoutProducingOrRetainingMarkers() {
        val detector = MarkerBlobDetector()
        val pixels = image(Triple(40, 50, 0xff00cc88.toInt()))
        assertEquals(1, detector.detect(pixels, 200, 300).size)
        assertTrue(detector.lastColourPixelCounts.getValue(MarkerColor.SHOULDERS) > 0)
        val incomplete = MarkerColorCalibration().withViewTargets(GaitView.BACK,
            mapOf(MarkerColor.SHOULDERS to MarkerColorTarget(155.0, 14.0, .25, .18)))
        assertTrue(detector.detect(pixels, 200, 300, incomplete, GaitView.BACK).isEmpty())
        assertEquals(MarkerColor.entries.associateWith { 0 }, detector.lastColourPixelCounts)
        assertEquals(200, detector.lastWorkingWidth)
        assertEquals(300, detector.lastWorkingHeight)
    }
}
