package com.example.coverdesign.gait

import android.content.Context
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Local, bounded video analysis. UI owns document selection; only numeric results leave this call. */
internal suspend fun analyseGaitVideo(
    context: Context,
    uri: Uri,
    profile: GaitProfile,
    calibration: MarkerColorCalibration,
    views: Set<GaitView>,
    mirrored: Boolean = false,
    onProgress: (GaitVideoProgress) -> Unit = {}
): GaitSession = withContext(Dispatchers.IO) {
    val selectedViews = views.toSet()
    require(profile.validationError() == null) { profile.validationError().orEmpty() }
    require(selectedViews.isNotEmpty()) { "Choose at least one view to analyse." }
    require(selectedViews.all { calibration.readyFor(it) }) { "Select the marker colours for every chosen view first." }
    currentCoroutineContext().ensureActive()
    GaitVideoSource(context, uri, mirrored).use { source ->
        val duration = source.metadata.durationMs
        val total = ((duration + GAIT_VIDEO_FRAME_INTERVAL_MS - 1) / GAIT_VIDEO_FRAME_INTERVAL_MS).toInt()
        val accumulator = GaitVideoAccumulator(profile, selectedViews)
        onProgress(GaitVideoProgress(0, total, 0, duration))
        currentCoroutineContext().ensureActive()
        val model = PoseLandmarker.createFromOptions(context.applicationContext,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.VIDEO).setNumPoses(1)
                .setMinPoseDetectionConfidence(.5f).setMinPosePresenceConfidence(.5f).setMinTrackingConfidence(.5f)
                .setOutputSegmentationMasks(false).build())
        try {
            val detector = MarkerBlobDetector()
            for (index in 0 until total) {
                currentCoroutineContext().ensureActive()
                val atMs = index * GAIT_VIDEO_FRAME_INTERVAL_MS
                val bitmap = source.frameAt(atMs)
                try {
                    currentCoroutineContext().ensureActive()
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val poseBitmap = poseInputBitmap(bitmap)
                    val pose = try {
                        val input = BitmapImageBuilder(poseBitmap).build()
                        try { model.detectForVideo(input, atMs + 1).toPoseLandmarks() }
                        finally { input.close() }
                    } finally { if (poseBitmap !== bitmap) poseBitmap.recycle() }
                    currentCoroutineContext().ensureActive()
                    val frames = selectedViews.map { view ->
                        val blobs = detector.detect(pixels, bitmap.width, bitmap.height, calibration, view)
                        GaitFrameBuilder.build(pose, blobs, bitmap.width, bitmap.height, atMs)
                    }
                    accumulator.receive(frames, atMs)
                } finally { bitmap.recycle() }
                onProgress(GaitVideoProgress(index + 1, total, (atMs + GAIT_VIDEO_FRAME_INTERVAL_MS).coerceAtMost(duration), duration))
                currentCoroutineContext().ensureActive()
            }
            accumulator.finish(duration, mirrored).also { session ->
                require(session.samples.isNotEmpty()) {
                    "No usable walking intervals were found. Choose clearer calibration frames and check that every marker stays visible while walking towards or away from the fixed camera."
                }
            }
        } finally { model.close() }
    }
}
