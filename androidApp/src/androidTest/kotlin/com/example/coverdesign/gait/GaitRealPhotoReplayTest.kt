package com.example.coverdesign.gait

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Opt-in local replay. No photographs are packaged in either APK. The caller supplies a temporary
 * app-readable directory with -e gaitPhotoDir PATH and removes that directory after the replay.
 * Manual ROIs are test expectations and calibration-paper crops only; they never seed pose,
 * production marker matching or output coordinates.
 */
@RunWith(AndroidJUnit4::class)
class GaitRealPhotoReplayTest {
    private data class Paper(val marker: MarkerId, val color: MarkerColor, val x: Int, val y: Int)
    private data class Photo(val fileName: String, val papers: List<Paper>)

    // The supplied originals are upright 960x1280 front photographs. Anatomical LEFT is image-right.
    private val photos = listOf(
        Photo("2026-11-03 20.44.29.jpg", listOf(
            Paper(MarkerId.RIGHT_SHOULDER, MarkerColor.SHOULDERS, 315, 455),
            Paper(MarkerId.LEFT_SHOULDER, MarkerColor.SHOULDERS, 562, 454),
            Paper(MarkerId.RIGHT_PELVIS, MarkerColor.PELVIS, 372, 710),
            Paper(MarkerId.LEFT_PELVIS, MarkerColor.PELVIS, 513, 716),
            Paper(MarkerId.RIGHT_KNEE, MarkerColor.KNEES, 379, 984),
            Paper(MarkerId.LEFT_KNEE, MarkerColor.KNEES, 507, 991))),
        Photo("2026-11-03 20.45.00.jpg", listOf(
            Paper(MarkerId.RIGHT_SHOULDER, MarkerColor.SHOULDERS, 317, 389),
            Paper(MarkerId.LEFT_SHOULDER, MarkerColor.SHOULDERS, 548, 383),
            Paper(MarkerId.RIGHT_PELVIS, MarkerColor.PELVIS, 379, 628),
            Paper(MarkerId.LEFT_PELVIS, MarkerColor.PELVIS, 512, 627),
            Paper(MarkerId.RIGHT_KNEE, MarkerColor.KNEES, 392, 894),
            Paper(MarkerId.LEFT_KNEE, MarkerColor.KNEES, 515, 894))),
        Photo("2026-11-03 20.45.07.jpg", listOf(
            Paper(MarkerId.RIGHT_SHOULDER, MarkerColor.SHOULDERS, 323, 355),
            Paper(MarkerId.LEFT_SHOULDER, MarkerColor.SHOULDERS, 556, 350),
            Paper(MarkerId.RIGHT_PELVIS, MarkerColor.PELVIS, 386, 599),
            Paper(MarkerId.LEFT_PELVIS, MarkerColor.PELVIS, 518, 599),
            Paper(MarkerId.RIGHT_KNEE, MarkerColor.KNEES, 396, 863),
            Paper(MarkerId.LEFT_KNEE, MarkerColor.KNEES, 522, 866))),
        Photo("2026-11-03 20.45.24.jpg", listOf(
            Paper(MarkerId.RIGHT_SHOULDER, MarkerColor.SHOULDERS, 320, 426),
            Paper(MarkerId.LEFT_SHOULDER, MarkerColor.SHOULDERS, 565, 421),
            Paper(MarkerId.RIGHT_PELVIS, MarkerColor.PELVIS, 385, 680),
            Paper(MarkerId.LEFT_PELVIS, MarkerColor.PELVIS, 528, 682),
            Paper(MarkerId.RIGHT_KNEE, MarkerColor.KNEES, 399, 959),
            Paper(MarkerId.LEFT_KNEE, MarkerColor.KNEES, 533, 956)))
    )

    private fun report(message: String) {
        // Code 2 is an in-progress diagnostic; code 0 is reserved for a completed test result.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "PHOTO_REPLAY $message\n")
        })
    }

    private fun number(value: Double) = String.format(Locale.US, "%.5f", value)

    private fun decode(file: File): Bitmap {
        assertTrue("An opted-in replay requires all supplied files", file.isFile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) { "Cannot decode local replay input" }
        try {
            assertEquals("Manual diagnostic ROIs use the upright original width", 960, bitmap.width)
            assertEquals("Manual diagnostic ROIs use the upright original height", 1280, bitmap.height)
            return bitmap
        } catch (failure: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw failure
        }
    }

    private fun calibrationFromFirstPhoto(directory: File): MarkerColorCalibration {
        val original = decode(File(directory, photos.first().fileName))
        try {
            var calibration = MarkerColorCalibration()
            // One real paper sample per front role is reused unchanged across all four photographs.
            for (color in listOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.KNEES)) {
                val paper = photos.first().papers.first { it.color == color }
                val pixels = IntArray(32 * 32)
                original.getPixels(pixels, 0, 32, paper.x - 16, paper.y - 16, 32, 32)
                val sample = MarkerColorSample.capture(pixels, 32, 32, color, calibration)
                assertTrue("Real paper sample $color was rejected: ${sample.message}", sample.valid)
                val target = requireNotNull(sample.target)
                calibration = calibration.withTarget(color, target)
                report("calibration color=$color hue=${number(target.hueDegrees)} tolerance=${number(target.hueToleranceDegrees)} minS=${number(target.minSaturation)} minV=${number(target.minValue)}")
            }
            assertEquals(setOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.KNEES), calibration.targets.keys)
            assertTrue("The actual three front papers must form a usable front palette", calibration.readyFor(GaitView.FRONT))
            assertFalse("No rear colours are invented to satisfy completeness", calibration.readyFor(GaitView.BACK))
            assertEquals(setOf(MarkerColor.C7, MarkerColor.COCCYX), calibration.missingColorsFor(GaitView.BACK))
            return calibration
        } finally {
            if (!original.isRecycled) original.recycle()
        }
    }

    @Test
    fun realPaperCalibrationAndCpuPoseFindAllSixMarkersAcrossFourLocalPhotographs() {
        val directoryArgument = InstrumentationRegistry.getArguments().getString("gaitPhotoDir")
        assumeTrue("Private photographs are optional local inputs, never APK assets", !directoryArgument.isNullOrBlank())
        val directory = File(requireNotNull(directoryArgument))
        assertTrue("The caller must supply an app-readable temporary directory", directory.isDirectory)
        val calibration = calibrationFromFirstPhoto(directory)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = PoseLandmarker.createFromOptions(context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(.5f)
                .setMinPosePresenceConfidence(.5f)
                .setOutputSegmentationMasks(false)
                .build())
        val failures = mutableListOf<String>()
        try {
            photos.forEachIndexed { index, photo ->
                val original = decode(File(directory, photo.fileName))
                var resized: Bitmap? = null
                try {
                    val bitmap = Bitmap.createScaledBitmap(original, 480, 640, true)
                    resized = bitmap
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val detector = MarkerBlobDetector()
                    val blobs = detector.detect(pixels, bitmap.width, bitmap.height, calibration, GaitView.FRONT)
                    val input = BitmapImageBuilder(bitmap).build()
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = try { model.detect(input) } finally { input.close() }
                    val inferenceMs = SystemClock.elapsedRealtime() - startedAt
                    report("photo=${index + 1} width=480 height=640 inferenceMs=$inferenceMs poses=${result.landmarks().size} rawPixels=${detector.lastColourPixelCounts} components=${blobs.groupingBy { it.color }.eachCount()}")
                    result.landmarks().forEachIndexed { poseIndex, points ->
                        points.forEachIndexed { landmarkIndex, point ->
                            report("photo=${index + 1} pose=$poseIndex landmark=$landmarkIndex x=${number(point.x().toDouble())} y=${number(point.y().toDouble())} visibility=${number(point.visibility().orElse(0f).toDouble())} presence=${number(point.presence().orElse(0f).toDouble())}")
                        }
                    }
                    val pose = result.toPoseLandmarks()
                    val frame = GaitFrameBuilder.build(pose, blobs, bitmap.width, bitmap.height, index.toLong() + 1)
                    report("photo=${index + 1} view=${frame.observedView} bodyVisible=${frame.bodyVisible} stepSignal=${frame.stepSignal} markers=${frame.markers.keys}")
                    for (blob in blobs) report("photo=${index + 1} blobColor=${blob.color} x=${number(blob.point.x)} y=${number(blob.point.y)} area=${number(blob.areaFraction)}")
                    for (paper in photo.papers) {
                        val actual = frame.markers[paper.marker]
                        report("photo=${index + 1} marker=${paper.marker} actualX=${actual?.x?.let(::number)} actualY=${actual?.y?.let(::number)} expectedX=${number(paper.x / 960.0)} expectedY=${number(paper.y / 1280.0)}")
                    }
                    assertEquals("Photo ${index + 1}: the real pose graph must produce one 33-point body", 33, result.landmarks().singleOrNull()?.size ?: 0)
                    assertEquals("Photo ${index + 1}: marker colours must identify the front view", GaitView.FRONT, frame.observedView)
                    assertEquals("Photo ${index + 1}: require two shoulders, two pelvic points and two knees", requiredMarkers(GaitView.FRONT).toSet(), frame.markers.keys)
                    for (paper in photo.papers) {
                        val actual = frame.markers.getValue(paper.marker)
                        assertTrue("Photo ${index + 1}: ${paper.marker} must be a measured ${paper.color} blob, not a pose fallback", blobs.any { it.color == paper.color && it.point == actual })
                        assertTrue("Photo ${index + 1}: ${paper.marker} horizontal centre mismatch", abs(actual.x - paper.x / 960.0) <= .025)
                        assertTrue("Photo ${index + 1}: ${paper.marker} vertical centre mismatch", abs(actual.y - paper.y / 1280.0) <= .025)
                    }
                    // Cropped shoes or conservative head framing may reject walking readiness;
                    // these still photographs only assert actual colour + pose marker matching.
                } catch (failure: AssertionError) {
                    // Report every photograph before failing the batch, so one missing marker
                    // cannot hide the other three actual model/matching outcomes.
                    val detail = "photo=${index + 1} failure=${failure.message}"
                    failures += detail
                    report(detail)
                } finally {
                    resized?.let { if (it !== original && !it.isRecycled) it.recycle() }
                    if (!original.isRecycled) original.recycle()
                }
            }
        } finally {
            model.close()
        }
        assertTrue("Real photo replay failed:\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}
