package com.example.coverdesign.gait

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** Calibrate once, then vary actual RGB camera pixels before the production detector. */
class GaitLightingRegressionTest {
    private data class Paper(val role: MarkerColor, val centre: MarkerPoint, val rgb: Int)
    private val papers = listOf(
        Paper(MarkerColor.SHOULDERS, MarkerPoint(.30, .22), 0xffa8cc20.toInt()),
        Paper(MarkerColor.SHOULDERS, MarkerPoint(.70, .22), 0xffa8cc20.toInt()),
        Paper(MarkerColor.PELVIS, MarkerPoint(.35, .50), 0xff2888aa.toInt()),
        Paper(MarkerColor.PELVIS, MarkerPoint(.65, .50), 0xff2888aa.toInt()),
        Paper(MarkerColor.KNEES, MarkerPoint(.40, .74), 0xffaa2860.toInt()),
        Paper(MarkerColor.KNEES, MarkerPoint(.60, .74), 0xffaa2860.toInt())
    )

    /** Pixel-centred sampling of the same distant, ten-pixel papers at each camera resolution. */
    private fun scene(height: Int, colour: (Paper) -> Int = { it.rgb }): IntArray {
        val width = height * 3 / 4
        val pixels = IntArray(width * height) { 0xff282828.toInt() }
        val half = 5.0 * height / 640
        for (paper in papers) {
            val cx = paper.centre.x * width
            val cy = paper.centre.y * height
            for (y in floor(cy - half).toInt()..ceil(cy + half).toInt()) {
                for (x in floor(cx - half).toInt()..ceil(cx + half).toInt()) {
                    if (kotlin.math.abs(x + .5 - cx) <= half && kotlin.math.abs(y + .5 - cy) <= half)
                        pixels[y * width + x] = colour(paper)
                }
            }
        }
        return pixels
    }

    /** Independent RGB exposure, channel gains and neutral light mixing, not classifier HSV. */
    private fun light(rgb: Int, exposure: Double = 1.0, gains: List<Double> = listOf(1.0, 1.0, 1.0),
                      whiteMix: Double = 0.0): Int {
        var result = 0xff000000.toInt()
        for ((i, shift) in listOf(16, 8, 0).withIndex()) {
            val source = (rgb shr shift) and 255
            val value = (source * exposure * gains[i] * (1 - whiteMix) + 255 * whiteMix)
                .roundToInt().coerceIn(0, 255)
            result = result or (value shl shift)
        }
        return result
    }

    private fun calibration(): MarkerColorCalibration {
        val snapshot = GaitColourSnapshot(scene(640), 480, 640, 0, GaitView.FRONT)
        val targets = papers.groupBy { it.role }.mapValues { (role, pair) ->
            val result = MarkerBodyColourSample.capture(snapshot, role, pair.map { it.centre })
            assertTrue("Initial on-body $role sample: ${result.message}", result.valid)
            requireNotNull(result.target)
        }
        return MarkerColorCalibration().withViewTargets(GaitView.FRONT, targets)
            .withLightingTolerance(MarkerLightingTolerance.VARIABLE)
    }

    private fun assertActualPapers(result: List<MarkerBlob>, context: String) {
        assertEquals(context, papers.size, result.size)
        for (role in listOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.KNEES)) {
            val expected = papers.filter { it.role == role }.sortedBy { it.centre.x }
            val actual = result.filter { it.color == role }.sortedBy { it.point.x }
            assertEquals("$context $role count", 2, actual.size)
            expected.zip(actual).forEach { (paper, found) ->
                assertEquals("$context $role x", paper.centre.x, found.point.x, 1.5 / 480)
                assertEquals("$context $role y", paper.centre.y, found.point.y, 1.5 / 640)
            }
        }
    }

    @Test fun oneCalibrationSurvivesExposureChangesAcrossInputResolutions() {
        val calibration = calibration()
        for (height in listOf(640, 1088)) for (exposure in listOf(1.0, .5, .25)) {
            val pixels = scene(height) { light(it.rgb, exposure = exposure) }
            assertActualPapers(MarkerBlobDetector().detect(pixels, height * 3 / 4, height,
                calibration, GaitView.FRONT), "height=$height exposure=$exposure")
        }
        val dim = scene(640) { light(it.rgb, exposure = .25) }
        assertTrue("The regression exercises a shadow excluded by the previous standard floor",
            MarkerBlobDetector().detect(dim, 480, 640,
                calibration.withLightingTolerance(MarkerLightingTolerance.STANDARD), GaitView.FRONT).isEmpty())
    }

    @Test fun oppositeSidesCanHaveDifferentExposureAndBoundedWarmCoolOrPaleLight() {
        val calibration = calibration()
        for (height in listOf(640, 1088)) {
            val pixels = scene(height) { paper ->
                when {
                    paper.role == MarkerColor.SHOULDERS && paper.centre.x < .5 -> light(paper.rgb, exposure = .25)
                    paper.role == MarkerColor.SHOULDERS -> light(paper.rgb, whiteMix = .20)
                    paper.role == MarkerColor.PELVIS && paper.centre.x < .5 -> light(paper.rgb, gains = listOf(1.0, .90, .80))
                    paper.role == MarkerColor.PELVIS -> light(paper.rgb, gains = listOf(.85, .90, 1.0))
                    paper.centre.x < .5 -> light(paper.rgb, exposure = .35)
                    else -> paper.rgb
                }
            }
            assertActualPapers(MarkerBlobDetector().detect(pixels, height * 3 / 4, height,
                calibration, GaitView.FRONT), "independent lighting height=$height")
        }
    }

    @Test fun neutralGlareDarkPapersAndWrongHueDoNotBecomeMeasuredMarkers() {
        val calibration = calibration()
        for ((name, transform) in listOf<Pair<String, (Paper) -> Int>>(
            "white glare" to { light(it.rgb, whiteMix = .97) },
            "dark" to { light(it.rgb, exposure = .03) },
            "grey" to { 0xff888888.toInt() },
            "unrelated red" to { 0xffcc2020.toInt() }
        )) {
            val result = MarkerBlobDetector().detect(scene(640, transform), 480, 640, calibration, GaitView.FRONT)
            assertTrue("$name must not invent a colour group: $result", result.isEmpty())
        }
    }

    @Test fun illuminationToleranceDoesNotTurnNeutralRoomPixelsIntoColourBlobs() {
        val calibration = calibration()
        val height = 640
        val width = 480
        val background = IntArray(width * height) { index ->
            val shade = 20 + (index / width) * 210 / height
            0xff000000.toInt() or (shade shl 16) or (shade shl 8) or shade
        }
        assertTrue(MarkerBlobDetector().detect(background, width, height, calibration, GaitView.FRONT).isEmpty())
    }
}
