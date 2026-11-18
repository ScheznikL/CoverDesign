package com.example.coverdesign.gait

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, numeric-only local video diagnostic. No recording, decoded frame, crop or input video is
 * saved by this test or packaged in either APK. The caller supplies/removes an app-readable file.
 *
 * Instrumentation arguments:
 * - gaitVideoPath: temporary local video file.
 * - gaitVideoCalibrationPoints: "x,y;x,y;x,y" in upright ORIGINAL-frame normalised coordinates,
 *   ordered shoulders, pelvis, knees. These select paper crops only, never model/body coordinates.
 * - OR gaitVideoTargets: "SHOULDERS,hue,tolerance,minS,minV;PELVIS,...;KNEES,..." from an actual
 *   detection report. Optional C7/COCCYX entries are retained. This skips paper points and decoding
 *   the calibration frame; minimum tolerance is 6 degrees (or the supplied tolerance, if smaller).
 * Optional arguments (milliseconds): gaitVideoCalibrationAtMs (default start), gaitVideoStartMs (0),
 * gaitVideoEndMs (duration, exclusive), gaitVideoStepMs (500).
 * gaitVideoAnalysisHeight bounds analysis height in pixels (640; never upscales).
 * gaitVideoReportName optionally saves only these numeric logs under external-files/gait-video-diagnostics.
 *
 * This establishes a baseline, not six-marker expectations: cropping, occlusion and absent pose
 * remain logged production outcomes. Native VIDEO inference uses strictly increasing timestamps.
 */
@RunWith(AndroidJUnit4::class)
class GaitRealVideoReplayTest {
    private data class PaperPoint(val x: Double, val y: Double)
    private data class VideoInfo(val width: Int, val height: Int, val rotation: Int, val durationMs: Long)
    private data class DecodedFrame(
        val bitmap: Bitmap,
        val decodedWidth: Int,
        val decodedHeight: Int,
        val appliedRotation: Int
    )

    private val frontRoles = listOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.KNEES)
    private var reportWriter: BufferedWriter? = null

    private fun report(message: String) {
        reportWriter?.appendLine(message)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "VIDEO_REPLAY $message\n")
        })
    }

    private fun number(value: Double) = String.format(Locale.US, "%.6f", value)

    private fun milliseconds(arguments: Bundle, name: String, default: Long): Long =
        arguments.getString(name)?.let { requireNotNull(it.toLongOrNull()) { "$name must be an integer in milliseconds" } } ?: default

    private fun paperPoints(argument: String?, count: Int = frontRoles.size): List<PaperPoint> {
        require(!argument.isNullOrBlank()) { "Provide gaitVideoCalibrationPoints=x,y;x,y;x,y in upright original-frame coordinates" }
        val points = argument.split(';').map { pair ->
            val values = pair.split(',').map { it.trim().toDoubleOrNull() }
            require(values.size == 2 && values.all { it != null && it.isFinite() && it in 0.0..1.0 }) {
                "Each calibration point must contain two finite normalised coordinates" }
            PaperPoint(requireNotNull(values[0]), requireNotNull(values[1]))
        }
        require(points.size == count) { "Provide $count calibration-paper points" }
        return points
    }

    private fun reportedCalibration(argument: String): MarkerColorCalibration {
        require(argument.isNotBlank()) { "gaitVideoTargets must contain the reported front marker targets" }
        val targets = linkedMapOf<MarkerColor, MarkerColorTarget>()
        argument.split(';').forEach { entry ->
            val fields = entry.split(',').map(String::trim)
            require(fields.size == 5) { "Each target must contain ROLE,hue,tolerance,minS,minV" }
            val role = requireNotNull(MarkerColor.entries.firstOrNull { it.name == fields[0].uppercase(Locale.ROOT) }) {
                "Target role must be SHOULDERS, PELVIS, KNEES, C7 or COCCYX" }
            require(role !in targets) { "Each marker role can have only one reported target" }
            val values = fields.drop(1).map { requireNotNull(it.toDoubleOrNull()) { "Reported target values must be numbers" } }
            val target = MarkerColorTarget(values[0], values[1], values[2], values[3], min(6.0, values[1]))
            require(target.isValid()) { "Reported hue, tolerance, saturation and brightness must form a valid production target" }
            targets[role] = target
            report("reportedTarget role=$role hue=${number(target.hueDegrees)} tolerance=${number(target.hueToleranceDegrees)} minTolerance=${number(target.minHueToleranceDegrees)} minS=${number(target.minSaturation)} minV=${number(target.minValue)}")
        }
        require(targets.keys.containsAll(frontRoles)) { "Reported targets must include shoulders, pelvis and knees" }
        val calibration = MarkerColorCalibration(targets)
        assertTrue("The reported targets must form a usable front palette: ${calibration.problemFor(GaitView.FRONT)}", calibration.readyFor(GaitView.FRONT))
        reportTargets(calibration)
        return calibration
    }

    private fun reportTargets(calibration: MarkerColorCalibration) {
        frontRoles.forEach { role ->
            val target = requireNotNull(calibration.targetAt(role.ordinal, GaitView.FRONT))
            report("target role=$role hue=${number(target.hueDegrees)} tolerance=${number(target.hueToleranceDegrees)} minTolerance=${number(target.minHueToleranceDegrees)} minS=${number(target.minSaturation)} minV=${number(target.minValue)}")
        }
        reportWriter?.flush()
    }

    /** Avoid rotating an already upright retriever frame a second time. */
    private fun decode(retriever: MediaMetadataRetriever, info: VideoInfo, atMs: Long): DecodedFrame {
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = requireNotNull(retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, params)) {
            "No video frame decoded at requested time $atMs ms" }
        val decodedWidth = raw.width
        val decodedHeight = raw.height
        var owned = raw
        var applied = 0
        try {
            val swapsAxes = info.rotation == 90 || info.rotation == 270
            val expectedWidth = if (swapsAxes) info.height else info.width
            val expectedHeight = if (swapsAxes) info.width else info.height
            if (swapsAxes && raw.width == info.width && raw.height == info.height &&
                (raw.width != expectedWidth || raw.height != expectedHeight)) {
                val rotation = Matrix().apply { postRotate(info.rotation.toFloat()) }
                val upright = Bitmap.createBitmap(owned, 0, 0, owned.width, owned.height, rotation, true)
                if (upright !== owned) owned.recycle()
                owned = upright
                applied = info.rotation
            }
            if (owned.config != Bitmap.Config.ARGB_8888) {
                val argb = requireNotNull(owned.copy(Bitmap.Config.ARGB_8888, false))
                if (argb !== owned) owned.recycle()
                owned = argb
            }
            return DecodedFrame(owned, decodedWidth, decodedHeight, applied)
        } catch (failure: Throwable) {
            if (!owned.isRecycled) owned.recycle()
            throw failure
        }
    }

    private fun calibrate(
        retriever: MediaMetadataRetriever,
        info: VideoInfo,
        atMs: Long,
        points: List<PaperPoint>
    ): MarkerColorCalibration {
        val decoded = decode(retriever, info, atMs)
        val bitmap = decoded.bitmap
        try {
            report("calibrationFrame requestedMs=$atMs decodedW=${decoded.decodedWidth} decodedH=${decoded.decodedHeight} uprightW=${bitmap.width} uprightH=${bitmap.height} rotationApplied=${decoded.appliedRotation}")
            var calibration = MarkerColorCalibration()
            frontRoles.zip(points).forEach { (role, point) ->
                val cx = (point.x * bitmap.width).roundToInt()
                val cy = (point.y * bitmap.height).roundToInt()
                val side = 32
                require(cx - side / 2 >= 0 && cy - side / 2 >= 0 && cx + side / 2 <= bitmap.width && cy + side / 2 <= bitmap.height) {
                    "Calibration paper crop must fit inside the upright original frame" }
                val pixels = IntArray(side * side)
                bitmap.getPixels(pixels, 0, side, cx - side / 2, cy - side / 2, side, side)
                val sample = MarkerColorSample.capture(pixels, side, side, role, calibration)
                report("calibration role=$role originalX=$cx originalY=$cy patchW=$side patchH=$side samplerSide=${(side * MarkerColorSample.ROI_FRACTION).toInt().coerceAtLeast(3)} valid=${if (sample.valid) 1 else 0} r=${sample.argb shr 16 and 255} g=${sample.argb shr 8 and 255} b=${sample.argb and 255}")
                assertTrue("$role paper sample rejected: ${sample.message}", sample.valid)
                calibration = calibration.withTarget(role, requireNotNull(sample.target))
            }
            assertTrue("The three supplied papers must form a usable front palette: ${calibration.problemFor(GaitView.FRONT)}", calibration.readyFor(GaitView.FRONT))
            reportTargets(calibration)
            return calibration
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun calibrateOnBody(retriever: MediaMetadataRetriever, info: VideoInfo, atMs: Long,
        points: List<PaperPoint>, existing: MarkerColorCalibration): MarkerColorCalibration {
        val decoded = decode(retriever, info, atMs)
        val bitmap = decoded.bitmap
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val snapshot = GaitColourSnapshot(pixels, bitmap.width, bitmap.height, atMs, GaitView.FRONT)
            val capturedTargets = mutableMapOf<MarkerColor, MarkerColorTarget>()
            frontRoles.forEachIndexed { index, role ->
                val centres = points.subList(index * 2, index * 2 + 2).map { MarkerPoint(it.x, it.y) }
                val sample = MarkerBodyColourSample.capture(snapshot, role, centres)
                report("bodyCalibration role=$role valid=${if (sample.valid) 1 else 0} r=${sample.argb shr 16 and 255} g=${sample.argb shr 8 and 255} b=${sample.argb and 255}")
                assertTrue("Actual on-body $role sample rejected: ${sample.message}", sample.valid)
                capturedTargets[role] = requireNotNull(sample.target)
            }
            val candidate = existing.withViewTargets(GaitView.FRONT, capturedTargets)
            assertTrue("On-body palette must be ready: ${candidate.problemFor(GaitView.FRONT)}", candidate.readyFor(GaitView.FRONT))
            report("profileSource=bodySnapshot")
            reportTargets(candidate)
            return candidate
        } finally { bitmap.recycle() }
    }

    @Test fun diagnoseLocalVideoWithRealCpuPoseAndCapturedPaperColours() {
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("gaitVideoPath")
        assumeTrue("Video replay is opt-in; no private media is packaged in the APK", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        assertTrue("Provide an app-readable temporary local video file", file.isFile)
        val reportedTargets = arguments.getString("gaitVideoTargets")
        val bodyPoints = arguments.getString("gaitVideoBodyCalibrationPoints")?.let { paperPoints(it, 6) }
        val points = if (reportedTargets == null && bodyPoints == null) paperPoints(arguments.getString("gaitVideoCalibrationPoints")) else null
        val retriever = MediaMetadataRetriever()
        try {
            arguments.getString("gaitVideoReportName")?.let { name ->
                require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..") { "Report name must be a simple file name" }
                val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-video-diagnostics"))
                require(directory.isDirectory || directory.mkdirs()) { "Cannot create the optional numeric report directory" }
                reportWriter = File(directory, name).bufferedWriter(Charsets.UTF_8)
            }
            retriever.setDataSource(file.absolutePath)
            val info = VideoInfo(
                requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()),
                requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()),
                ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 360 + 360) % 360,
                requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
            )
            require(info.width > 0 && info.height > 0 && info.durationMs > 0) { "Video dimensions and duration must be positive" }
            val startMs = milliseconds(arguments, "gaitVideoStartMs", 0)
            val endMs = milliseconds(arguments, "gaitVideoEndMs", info.durationMs)
            val stepMs = milliseconds(arguments, "gaitVideoStepMs", 500)
            val calibrationAtMs = if (reportedTargets == null || bodyPoints != null) milliseconds(arguments, "gaitVideoCalibrationAtMs", startMs) else -1L
            val analysisHeight = arguments.getString("gaitVideoAnalysisHeight")?.let {
                requireNotNull(it.toIntOrNull()) { "gaitVideoAnalysisHeight must be an integer in pixels" }
            } ?: 640
            require(startMs >= 0 && startMs < endMs && endMs <= info.durationMs) { "Require 0 <= start < end <= video duration" }
            require(stepMs > 0 && stepMs <= Long.MAX_VALUE - info.durationMs) { "Video step must be positive and finite" }
            require(reportedTargets != null && bodyPoints == null || calibrationAtMs in 0L until info.durationMs) { "Calibration time must be inside the video" }
            require(analysisHeight > 0) { "Analysis height must be positive" }
            report("metadata encodedW=${info.width} encodedH=${info.height} rotation=${info.rotation} durationMs=${info.durationMs} startMs=$startMs endExclusiveMs=$endMs stepMs=$stepMs calibrationAtMs=$calibrationAtMs analysisMaxH=$analysisHeight")
            var calibration = if (reportedTargets != null) {
                report("profileSource=reportedTargets")
                reportedCalibration(reportedTargets)
            } else if (bodyPoints != null) {
                MarkerColorCalibration()
            } else {
                report("profileSource=paperCrops")
                calibrate(retriever, info, calibrationAtMs, requireNotNull(points))
            }
            if (bodyPoints != null) calibration = calibrateOnBody(retriever, info, calibrationAtMs, bodyPoints, calibration)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val model = PoseLandmarker.createFromOptions(context,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.VIDEO).setNumPoses(1)
                    .setMinPoseDetectionConfidence(.5f).setMinPosePresenceConfidence(.5f).setMinTrackingConfidence(.5f)
                    .setOutputSegmentationMasks(false).build())
            try {
                val detector = MarkerBlobDetector()
                var requestedMs = startMs
                var lastModelMs = -1L
                var index = 0
                val expectedSixAt = arguments.getString("gaitVideoExpectSixAtMs")?.split(',')?.map { it.toLong() }?.toSet().orEmpty()
                val verifiedTimes = mutableSetOf<Long>()
                val expectedMissing = arguments.getString("gaitVideoExpectMissingAtMs")?.split(';')?.associate { item ->
                    val fields = item.split(':')
                    require(fields.size == 2)
                    fields[0].toLong() to MarkerId.valueOf(fields[1])
                }.orEmpty()
                val verifiedMissingTimes = mutableSetOf<Long>()
                val expectedCroppedAt = arguments.getString("gaitVideoExpectCroppedAtMs")?.split(',')?.map { it.toLong() }?.toSet().orEmpty()
                val verifiedCroppedTimes = mutableSetOf<Long>()
                val timedPassStart = arguments.getString("gaitVideoTimedPassStartMs")?.toLong()
                val timedPass = timedPassStart?.let {
                    RunController(GaitProfile(), object : GaitFeedback {
                        override fun say(text: String) = Unit
                        override fun bell() = Unit
                    }).apply { manual = true; transition(RunPhase.COUNTDOWN, "") }
                }
                val replayFailures = mutableListOf<String>()
                while (requestedMs < endMs) {
                    val decoded = decode(retriever, info, requestedMs)
                    val original = decoded.bitmap
                    var resized: Bitmap? = null
                    try {
                        val factor = min(1.0, analysisHeight.toDouble() / original.height)
                        val width = max(1, (original.width * factor).roundToInt())
                        val height = max(1, (original.height * factor).roundToInt())
                        val bitmap = if (width == original.width && height == original.height) original
                            else Bitmap.createScaledBitmap(original, width, height, true)
                        resized = bitmap
                        val pixels = IntArray(width * height)
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        val candidates = detector.detect(pixels, width, height, calibration, GaitView.FRONT)
                        val modelMs = max(requestedMs, lastModelMs + 1)
                        require(modelMs > lastModelMs) { "Native VIDEO timestamps must strictly increase" }
                        val poseBitmap = poseInputBitmap(bitmap)
                        val poseWidth = poseBitmap.width
                        val poseHeight = poseBitmap.height
                        val inferenceAt = SystemClock.elapsedRealtime()
                        val result = try {
                            val input = BitmapImageBuilder(poseBitmap).build()
                            try { model.detectForVideo(input, modelMs) } finally { input.close() }
                        } finally {
                            if (poseBitmap !== bitmap) poseBitmap.recycle()
                        }
                        val inferenceMs = SystemClock.elapsedRealtime() - inferenceAt
                        lastModelMs = modelMs
                        report("frame=$index requestedMs=$requestedMs modelMs=$modelMs decodedW=${decoded.decodedWidth} decodedH=${decoded.decodedHeight} uprightW=${original.width} uprightH=${original.height} rotationApplied=${decoded.appliedRotation} analysisW=$width analysisH=$height poseW=$poseWidth poseH=$poseHeight portrait=${if (height >= width) 1 else 0} inferenceMs=$inferenceMs poses=${result.landmarks().size}")
                        frontRoles.forEach { role ->
                            report("frame=$index role=$role colourPixels=${detector.lastColourPixelCounts[role] ?: 0} blobs=${candidates.count { it.color == role }}")
                        }
                        candidates.forEachIndexed { blobIndex, blob ->
                            report("frame=$index blob=$blobIndex role=${blob.color} x=${number(blob.point.x)} y=${number(blob.point.y)} areaFraction=${number(blob.areaFraction)} areaPixels=${number(blob.areaFraction * width * height)}")
                        }
                        val pose = result.toPoseLandmarks()
                        BodyLandmark.entries.forEach { type ->
                            val point = pose.points[type]
                            report("frame=$index anchor=${type.index} present=${if (point != null) 1 else 0} x=${number(point?.point?.x ?: Double.NaN)} y=${number(point?.point?.y ?: Double.NaN)} visibility=${number(point?.visibility?.toDouble() ?: 0.0)} presence=${number(point?.presence?.toDouble() ?: 0.0)}")
                        }
                        val frame = GaitFrameBuilder.build(pose, candidates, width, height, modelMs)
                        if (timedPass != null && timedPassStart != null && requestedMs >= timedPassStart && timedPass.finished == null) {
                            timedPass.receive(frame)
                            if (requestedMs == timedPassStart) {
                                timedPass.beginWalking(modelMs)
                                if (!timedPass.walking) replayFailures += "Real video marker region could not start the timed pass"
                            }
                            if (requestedMs >= timedPassStart + 2000) timedPass.finish(false)
                        }
                        report("frame=$index observedView=${frame.observedView} bodyVisible=${if (frame.bodyVisible) 1 else 0} measurementVisible=${frame.measurementVisible(GaitView.FRONT)} foundCount=${frame.markers.size} foundIds=${frame.markers.keys.joinToString(",")} stepSignal=${number(frame.stepSignal ?: Double.NaN)}")
                        requiredMarkers(GaitView.FRONT).forEach { marker ->
                            val point = frame.markers[marker]
                            report("frame=$index marker=$marker found=${if (point != null) 1 else 0} x=${number(point?.x ?: Double.NaN)} y=${number(point?.y ?: Double.NaN)}")
                        }
                        if (requestedMs in expectedSixAt) {
                            if (frame.markers.keys != requiredMarkers(GaitView.FRONT).toSet())
                                replayFailures += "Visible papers at $requestedMs ms: ${frame.markers.keys}"
                            verifiedTimes += requestedMs
                        }
                        if (requestedMs in expectedCroppedAt) {
                            if (frame.bodyVisible || !frame.measurementVisible(GaitView.FRONT) || GaitMath.angles(frame, GaitView.FRONT).size != 3)
                                replayFailures += "Cropped but visible real marker region was lost at $requestedMs ms"
                            if (frame.stepSignal != null) replayFailures += "Feet were invented in cropped frame $requestedMs ms"
                            verifiedCroppedTimes += requestedMs
                        }
                        expectedMissing[requestedMs]?.let { missing ->
                            if (missing in frame.markers) replayFailures += "Unreliable partial paper $missing was confirmed at $requestedMs ms"
                            if (missing in listOf(MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE) && GaitMetric.KNEES in GaitMath.angles(frame, GaitView.FRONT))
                                replayFailures += "A knee angle was produced despite missing $missing at $requestedMs ms"
                            verifiedMissingTimes += requestedMs
                        }
                        reportWriter?.flush()
                    } finally {
                        resized?.let { if (it !== original && !it.isRecycled) it.recycle() }
                        if (!original.isRecycled) original.recycle()
                    }
                    index++
                    requestedMs += stepMs
                }
                org.junit.Assert.assertEquals("Every annotated time must be replayed", expectedSixAt, verifiedTimes)
                org.junit.Assert.assertEquals("Every negative annotation must be replayed", expectedMissing.keys, verifiedMissingTimes)
                org.junit.Assert.assertEquals("Every cropped annotation must be replayed", expectedCroppedAt, verifiedCroppedTimes)
                if (timedPass != null && timedPassStart != null) {
                    val session = requireNotNull(timedPass.finished) { "Timed replay window must finish inside the video" }
                    val croppedSamples = session.samples.count { it.atMs in expectedCroppedAt }
                    report("timedPass startMs=$timedPassStart usableMs=${session.frontValidDurationMs} samples=${session.samples.size} croppedSamples=$croppedSamples observedSteps=${session.frontSteps} stepsPartial=${session.frontStepsPartial}")
                    assertTrue("Actual video motion with cropped feet must yield usable measured time", session.frontValidDurationMs in 1..2000)
                    assertTrue("Cropped real-video frames must reach saved angle samples", croppedSamples > 0)
                    assertTrue("Cropped feet must leave the step count partial", session.frontStepsPartial)
                    assertTrue("Real marker samples use pass 1 independently of steps", session.samples.all { it.passIndex == 1 && it.stepIndex == 0 && it.angles.size == 3 })
                }
                report("complete frames=$index lastModelMs=$lastModelMs verifiedTimes=$verifiedTimes verifiedMissingTimes=$verifiedMissingTimes verifiedCroppedTimes=$verifiedCroppedTimes")
                assertTrue(replayFailures.joinToString("\n"), replayFailures.isEmpty())
            } finally {
                model.close()
            }
        } finally {
            try { retriever.release() } finally {
                reportWriter?.close()
                reportWriter = null
            }
        }
    }
}
