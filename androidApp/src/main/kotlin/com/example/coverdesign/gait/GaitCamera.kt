package com.example.coverdesign.gait

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Size
import android.util.Rational
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** No frames are retained or written to disk. Coordinates are upright and NOT mirrored. */
@Composable
internal fun GaitCamera(
    frontCamera: Boolean,
    onFrame: (GaitFrame) -> Unit,
    onError: (String?) -> Unit,
    modifier: Modifier = Modifier,
    calibration: MarkerColorCalibration? = null,
    detectionView: GaitView? = null,
    colourSnapshotRequestAtMs: Long? = null,
    onColourSnapshot: (GaitColourSnapshot) -> Unit = {},
    measurementMethod: GaitMethod = GaitMethod.MARKERS
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentFrame by rememberUpdatedState(onFrame)
    val currentError by rememberUpdatedState(onError)
    val currentCalibration by rememberUpdatedState(calibration)
    val currentDetectionView by rememberUpdatedState(detectionView)
    val currentSnapshotRequestAtMs by rememberUpdatedState(colourSnapshotRequestAtMs)
    val currentColourSnapshot by rememberUpdatedState(onColourSnapshot)
    val currentMeasurementMethod by rememberUpdatedState(measurementMethod)
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
    DisposableEffect(lifecycleOwner, frontCamera, previewView) {
        val closed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        // All model creation, synchronous inference and destruction stay on this worker.
        // VIDEO tracks a live sequence just as it tracks decoded video frames.
        var detector: PoseLandmarker? = null
        var retryModelAt = 0L
        var lastInferenceAt = -1L
        val blobs = MarkerBlobDetector()
        val poseRecovery = GaitPoseTrackingRecovery()
        var provider: ProcessCameraProvider? = null
        var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
        var cameraObserver: Observer<CameraState>? = null
        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(Surface.ROTATION_0).build()
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setTargetRotation(Surface.ROTATION_0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        fun report(message: String?) {
            mainExecutor.execute { if (!closed.get()) currentError(message) }
        }
        analysis.setAnalyzer(executor) { image ->
            if (closed.get()) {
                image.close()
            } else {
                var upright: Bitmap? = null
                // Record capture receipt before conversion/inference, so stale results remain stale.
                val capturedAtMs = max(SystemClock.elapsedRealtime(), lastInferenceAt + 1)
                val snapshotRequestAt = currentSnapshotRequestAtMs
                try {
                    val original = image.toBitmap()
                    upright = original
                    val rect = image.cropRect
                    val cropped = if (original.width == image.width && original.height == image.height &&
                        (rect.left != 0 || rect.top != 0 || rect.width() != original.width || rect.height() != original.height)) {
                        Bitmap.createBitmap(original, rect.left, rect.top, rect.width(), rect.height())
                            .also { if (it !== original) original.recycle() }
                    } else original
                    upright = cropped
                    if (image.imageInfo.rotationDegrees != 0) {
                        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                        upright = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
                            .also { if (it !== cropped) cropped.recycle() }
                    }
                    val bitmap = upright
                    val frameWidth = bitmap.width
                    val frameHeight = bitmap.height
                    val method = currentMeasurementMethod
                    // Pose mode neither reads colour samples nor performs RGB segmentation.
                    val pixels = if (method == GaitMethod.MARKERS) IntArray(frameWidth * frameHeight).also {
                        bitmap.getPixels(it, 0, frameWidth, 0, 0, frameWidth, frameHeight)
                    } else null
                    val convertedAtMs = SystemClock.elapsedRealtime()
                    val profile = currentCalibration
                    val view = currentDetectionView
                    val candidates = pixels?.let { blobs.detect(it, frameWidth, frameHeight, profile, view) }.orEmpty()
                    val coloursAtMs = SystemClock.elapsedRealtime()
                    if (poseRecovery.beforeFrame(method, view, capturedAtMs)) {
                        runCatching { detector?.close() }
                        detector = null
                    }
                    if (detector == null) {
                        if (capturedAtMs < retryModelAt) return@setAnalyzer
                        detector = PoseLandmarker.createFromOptions(context.applicationContext,
                            PoseLandmarker.PoseLandmarkerOptions.builder()
                                .setBaseOptions(BaseOptions.builder()
                                    .setModelAssetPath("pose_landmarker_lite.task")
                                    .setDelegate(Delegate.CPU).build())
                                .setRunningMode(RunningMode.VIDEO)
                                .setNumPoses(1)
                                .setMinPoseDetectionConfidence(0.5f)
                                .setMinPosePresenceConfidence(0.5f)
                                .setMinTrackingConfidence(0.5f)
                                .setOutputSegmentationMasks(false).build())
                    }
                    val poseBitmap = poseInputBitmap(bitmap)
                    val pose = try {
                        val input = BitmapImageBuilder(poseBitmap).build()
                        try {
                            lastInferenceAt = capturedAtMs
                            detector!!.detectForVideo(input, capturedAtMs)
                        } finally { input.close() }
                    } finally {
                        if (poseBitmap !== bitmap) poseBitmap.recycle()
                    }
                    val posedAtMs = SystemClock.elapsedRealtime()
                    val observed = pose.toPoseLandmarks()
                    val anchors = listOf(BodyLandmark.LEFT_SHOULDER, BodyLandmark.RIGHT_SHOULDER, BodyLandmark.LEFT_HIP, BodyLandmark.RIGHT_HIP)
                    val confidence = if (method == GaitMethod.POSE) .65f else .55f
                    val poseAvailable = anchors.all { type -> observed.points[type]?.let {
                        it.visibility >= confidence && it.presence >= confidence && it.point.x in 0.0..1.0 && it.point.y in 0.0..1.0
                    } == true }
                    val diagnostics = MarkerDiagnostics(if (method == GaitMethod.MARKERS)
                        MarkerColor.entries.associate { colour -> colour.name to candidates.count { it.color == colour } } else emptyMap(), poseAvailable,
                        if (method == GaitMethod.MARKERS) blobs.lastColourPixelCounts.mapKeys { it.key.name } else emptyMap(),
                        if (method == GaitMethod.MARKERS) blobs.lastWorkingWidth else frameWidth,
                        if (method == GaitMethod.MARKERS) blobs.lastWorkingHeight else frameHeight,
                        convertedAtMs - capturedAtMs, coloursAtMs - convertedAtMs, posedAtMs - coloursAtMs)
                    val frame = (if (method == GaitMethod.POSE)
                        GaitPoseFrameBuilder.build(observed, frameWidth, frameHeight, capturedAtMs)
                    else GaitFrameBuilder.build(observed, candidates, frameWidth, frameHeight, capturedAtMs))
                        .copy(markerDiagnostics = diagnostics)
                    poseRecovery.observe(frame, capturedAtMs)
                    mainExecutor.execute {
                        if (!closed.get() && currentMeasurementMethod == method &&
                            (method == GaitMethod.POSE || currentCalibration == profile) && currentDetectionView == view) {
                            currentError(null)
                            currentFrame(frame)
                            if (method == GaitMethod.MARKERS && pixels != null && snapshotRequestAt != null && snapshotRequestAt == currentSnapshotRequestAtMs &&
                                capturedAtMs >= snapshotRequestAt && frame.bodyVisible &&
                                SystemClock.elapsedRealtime() - capturedAtMs in 0..1200) {
                                currentColourSnapshot(GaitColourSnapshot(pixels, frameWidth, frameHeight,
                                    capturedAtMs, view ?: GaitView.FRONT))
                            }
                        }
                    }
                } catch (_: Exception) {
                    // A failed graph is recreated after a delay, rather than failing every frame forever.
                    runCatching { detector?.close() }; detector = null
                    retryModelAt = capturedAtMs + 3000
                    report("Body tracking was interrupted and will retry. Stop walking until tracking returns.")
                } catch (_: LinkageError) {
                    runCatching { detector?.close() }; detector = null
                    retryModelAt = capturedAtMs + 3000
                    report("Body tracking could not load on this device. Reopen the session or use another device.")
                } finally {
                    upright?.let { if (!it.isRecycled) it.recycle() }
                    image.close()
                }
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!closed.get()) {
                try {
                    provider = future.get()
                    val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    if (provider?.hasCamera(selector) != true) {
                        report("The selected camera is not available. Switch to the other camera.")
                    } else {
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        // A common FIT viewport preserves the same field of view in both outputs.
                        // The analysis bitmap explicitly applies ImageProxy.cropRect above.
                        val group = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(analysis)
                            .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0)
                                .setScaleType(ViewPort.FIT).build())
                            .build()
                        val camera = provider?.bindToLifecycle(lifecycleOwner, selector, group)
                        cameraState = camera?.cameraInfo?.cameraState
                        cameraObserver = Observer { state ->
                            state.error?.let {
                                report("Camera interrupted. Stop walking, close other camera apps and wait for recovery.")
                            }
                        }
                        cameraObserver?.let { cameraState?.observe(lifecycleOwner, it) }
                    }
                } catch (_: Exception) {
                    report("Unable to open the camera. Check permission and close other camera apps.")
                }
            }
        }, mainExecutor)
        onDispose {
            closed.set(true)
            analysis.clearAnalyzer()
            cameraObserver?.let { cameraState?.removeObserver(it) }
            provider?.unbind(preview, analysis)
            // This cleanup is queued behind any in-flight synchronous detection.
            executor.execute { runCatching { detector?.close() }; detector = null }
            executor.shutdown()
        }
    }
}


/** Match visible blobs near actual pose anchors without imposing a shared body midline. */
internal class MarkerMatcher(
    private val blobs: List<MarkerBlob>,
    private val width: Int,
    private val height: Int,
    private val bodyPixels: Double
) {
    fun match(
        color: MarkerColor,
        anchor: MarkerPoint,
        radius: Double,
        side: Int = 0,
        pairCenterX: Double = 0.5
    ): MarkerPoint? {
        val candidates = blobs.filter { blob ->
            blob.color == color &&
                hypot((blob.point.x - anchor.x) * width, (blob.point.y - anchor.y) * height) <= radius * bodyPixels &&
                (side == 0 || if (side < 0) blob.point.x < pairCenterX else blob.point.x > pairCenterX)
        }
        // Multiple candidates in the same region are ambiguous, not a successful check.
        return candidates.singleOrNull()?.point
    }

    /** More vertical leeway for self-applied rear papers, with the same lateral limit. */
    fun matchEllipse(
        color: MarkerColor,
        anchor: MarkerPoint,
        horizontalRadius: Double,
        verticalRadius: Double,
        side: Int = 0,
        pairCenterX: Double = 0.5,
        minY: Double = Double.NEGATIVE_INFINITY,
        maxY: Double = Double.POSITIVE_INFINITY
    ): MarkerPoint? {
        val candidates = blobs.filter { blob ->
            val dx = (blob.point.x - anchor.x) * width / (horizontalRadius * bodyPixels)
            val dy = (blob.point.y - anchor.y) * height / (verticalRadius * bodyPixels)
            blob.color == color && blob.point.y in minY..maxY && dx * dx + dy * dy <= 1.0 &&
                (side == 0 || if (side < 0) blob.point.x < pairCenterX else blob.point.x > pairCenterX)
        }
        // Never choose the nearest paper from an ambiguous region or synthesize a point.
        return candidates.singleOrNull()?.point
    }

    /** Equally sized knee papers may differ in perspective, but a >10x fragment is unreliable. */
    fun rejectTinyKneeFragment(points: List<MarkerPoint?>): List<MarkerPoint?> {
        if (points.size != 2 || points.any { it == null }) return points
        val areas = points.map { point ->
            blobs.singleOrNull { it.color == MarkerColor.KNEES && it.point == point }?.areaFraction
        }
        // Missing/invalid area is never filled with an assumed paper size.
        val first = areas[0]?.takeIf { it.isFinite() && it > 0 } ?: return points
        val second = areas[1]?.takeIf { it.isFinite() && it > 0 } ?: return points
        return when {
            first < second * .10 -> listOf(null, points[1])
            second < first * .10 -> listOf(points[0], null)
            else -> points
        }
    }
}


/** MediaPipe's documented 33-point indices; no Android or inference types enter frame matching. */
internal enum class BodyLandmark(val index: Int) {
    NOSE(0), LEFT_EAR(7), RIGHT_EAR(8), LEFT_SHOULDER(11), RIGHT_SHOULDER(12),
    LEFT_HIP(23), RIGHT_HIP(24), LEFT_KNEE(25), RIGHT_KNEE(26),
    LEFT_ANKLE(27), RIGHT_ANKLE(28), LEFT_HEEL(29), RIGHT_HEEL(30),
    LEFT_FOOT_INDEX(31), RIGHT_FOOT_INDEX(32)
}

internal data class ObservedBodyPoint(val point: MarkerPoint, val visibility: Float, val presence: Float)
internal data class PoseLandmarks(val points: Map<BodyLandmark, ObservedBodyPoint>)

internal fun PoseLandmarkerResult.toPoseLandmarks(): PoseLandmarks {
    val detected = landmarks().singleOrNull() ?: return PoseLandmarks(emptyMap())
    return PoseLandmarks(BodyLandmark.entries.mapNotNull { type ->
        detected.getOrNull(type.index)?.let {
            type to ObservedBodyPoint(MarkerPoint(it.x().toDouble(), it.y().toDouble()),
                it.visibility().orElse(0f), it.presence().orElse(0f))
        }
    }.toMap())
}

/** Actual colored blobs supply every output marker. Pose only bounds plausible search regions. */
internal object GaitFrameBuilder {
    fun build(pose: PoseLandmarks, blobs: List<MarkerBlob>, width: Int, height: Int, time: Long): GaitFrame {
        if (width <= 0 || height <= 0) return GaitFrame(time, width, height, emptyMap(), null, false, null)
        fun landmark(type: BodyLandmark, confidence: Float = 0.55f): MarkerPoint? = pose.points[type]?.takeIf {
            it.visibility >= confidence && it.presence >= confidence &&
                it.point.x.isFinite() && it.point.y.isFinite() && it.point.x in 0.0..1.0 && it.point.y in 0.0..1.0
        }?.point
        val shoulders = listOfNotNull(landmark(BodyLandmark.LEFT_SHOULDER), landmark(BodyLandmark.RIGHT_SHOULDER)).sortedBy { it.x }
        val hips = listOfNotNull(landmark(BodyLandmark.LEFT_HIP), landmark(BodyLandmark.RIGHT_HIP)).sortedBy { it.x }
        val knees = listOfNotNull(landmark(BodyLandmark.LEFT_KNEE), landmark(BodyLandmark.RIGHT_KNEE)).sortedBy { it.x }
        val ankles = listOfNotNull(landmark(BodyLandmark.LEFT_ANKLE, 0.65f), landmark(BodyLandmark.RIGHT_ANKLE, 0.65f)).sortedBy { it.x }
        if (shoulders.size != 2 || hips.size != 2) return GaitFrame(time, width, height, emptyMap(), null, false, null)
        val shoulderMid = MarkerPoint(shoulders.map { it.x }.average(), shoulders.map { it.y }.average())
        val hipMid = MarkerPoint(hips.map { it.x }.average(), hips.map { it.y }.average())
        val torsoHeight = max(0.08, hipMid.y - shoulderMid.y)
        val bodyPixels = torsoHeight * height * 2.8
        val matcher = MarkerMatcher(blobs, width, height, bodyPixels)
        val kneePoints = if (knees.size == 2) {
            val kneeCenterX = knees.map { it.x }.average()
            matcher.rejectTinyKneeFragment(listOf(
                matcher.match(MarkerColor.KNEES, knees[0], 0.12, -1, kneeCenterX),
                matcher.match(MarkerColor.KNEES, knees[1], 0.12, 1, kneeCenterX)
            ))
        } else listOf(null, null)
        val frontPattern = kneePoints.any { it != null }
        val c7Anchor = MarkerPoint(shoulderMid.x, shoulderMid.y - torsoHeight * 0.22)
        val coccyxAnchor = MarkerPoint(hipMid.x, hipMid.y + torsoHeight * 0.12)
        val strictC7 = matcher.match(MarkerColor.C7, c7Anchor, 0.13)
        val strictCoccyx = matcher.match(MarkerColor.COCCYX, coccyxAnchor, 0.15)
        val rearPelvis = hips.mapIndexed { index, anchor ->
            matcher.matchEllipse(MarkerColor.PELVIS, anchor, .17, .20,
                side = if (index == 0) -1 else 1, pairCenterX = hipMid.x,
                minY = shoulderMid.y + torsoHeight * .40, maxY = hipMid.y + torsoHeight * .50)
        }
        var c7 = strictC7
        var coccyx = strictCoccyx
        if (!frontPattern) {
            val rearC7 = matcher.matchEllipse(MarkerColor.C7, c7Anchor, .13, .16,
                maxY = shoulderMid.y + torsoHeight * .15)
            val pelvisY = rearPelvis.filterNotNull().takeIf { it.size == 2 }?.map { it.y }?.average()
            val rearCoccyx = matcher.matchEllipse(MarkerColor.COCCYX, coccyxAnchor, .15, .20,
                minY = max(hipMid.y - torsoHeight * .25, (pelvisY ?: hipMid.y) - torsoHeight * .08))
            // One newly admitted background patch cannot establish the rear view by itself.
            val rearEvidence = (strictC7 != null && rearC7 == strictC7) ||
                (strictCoccyx != null && rearCoccyx == strictCoccyx) ||
                (rearC7 != null && rearCoccyx != null && rearCoccyx.y - rearC7.y >= torsoHeight * .60)
            c7 = rearC7.takeIf { rearEvidence }
            coccyx = rearCoccyx.takeIf { rearEvidence }
        }
        val backPattern = c7 != null || coccyx != null
        val view = when {
            frontPattern && !backPattern -> GaitView.FRONT
            !frontPattern && backPattern -> GaitView.BACK
            else -> null
        }
        val markers = mutableMapOf<MarkerId, MarkerPoint>()
        if (view != null) {
            fun pair(color: MarkerColor, anchors: List<MarkerPoint>, left: MarkerId, right: MarkerId, radius: Double) {
                if (anchors.size != 2) return
                val pairCenterX = anchors.map { it.x }.average()
                val imageLeft = matcher.match(color, anchors[0], radius, -1, pairCenterX)
                val imageRight = matcher.match(color, anchors[1], radius, 1, pairCenterX)
                val leftPoint = if (view == GaitView.FRONT) imageRight else imageLeft
                val rightPoint = if (view == GaitView.FRONT) imageLeft else imageRight
                leftPoint?.let { markers[left] = it }; rightPoint?.let { markers[right] = it }
            }
            pair(MarkerColor.SHOULDERS, shoulders, MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER, 0.13)
            if (view == GaitView.FRONT) {
                pair(MarkerColor.PELVIS, hips, MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS, 0.17)
                kneePoints[1]?.let { markers[MarkerId.LEFT_KNEE] = it }
                kneePoints[0]?.let { markers[MarkerId.RIGHT_KNEE] = it }
            } else {
                rearPelvis[0]?.let { markers[MarkerId.LEFT_PELVIS] = it }
                rearPelvis[1]?.let { markers[MarkerId.RIGHT_PELVIS] = it }
                c7?.let { markers[MarkerId.C7] = it }
                coccyx?.let { markers[MarkerId.COCCYX] = it }
            }
        }
        // A nose/ear is below the crown: reserve headroom rather than declaring the head visible
        // merely because a facial point remains in-frame. The torso bound also works from behind.
        val torsoPixels = torsoHeight * height
        val headY = shoulderMid.y - max(abs(shoulders[1].x - shoulders[0].x) * width * 0.85,
            torsoPixels * 0.65) / height
        val headHalfWidth = max(abs(shoulders[1].x - shoulders[0].x) * 0.32,
            torsoPixels / width * 0.22)
        val knownHeadInside = listOf(BodyLandmark.NOSE, BodyLandmark.LEFT_EAR, BodyLandmark.RIGHT_EAR)
            .mapNotNull { pose.points[it] }.filter { it.visibility >= .55f && it.presence >= .55f }
            .all { it.point.x in .025.. .975 && it.point.y in .025.. .975 }
        val headInFrame = headY > .025 && shoulderMid.x - headHalfWidth > .025 &&
            shoulderMid.x + headHalfWidth < .975 && knownHeadInside
        val footTypes = listOf(BodyLandmark.LEFT_HEEL, BodyLandmark.RIGHT_HEEL,
            BodyLandmark.LEFT_FOOT_INDEX, BodyLandmark.RIGHT_FOOT_INDEX)
        val feet = footTypes.mapNotNull { landmark(it, 0.55f) }
        // An ankle above the bottom edge does not prove that the shoe/toes are visible.
        val feetInFrame = ankles.size == 2 && feet.size == footTypes.size && (ankles + feet).all {
            it.x in 0.025..0.975 && it.y in 0.025..0.97
        }
        val legTrackingReady = feetInFrame && knees.size == 2 && shoulderMid.y < hipMid.y &&
            ankles.all { it.y > hipMid.y } && abs(shoulders[1].x - shoulders[0].x) * width > bodyPixels * 0.12
        val bodyVisible = legTrackingReady && headInFrame
        // Spatial sides survive anatomical label swaps in rear views. With overlapping feet,
        // neither a reliable side identity nor a walking phase is available, so suppress it.
        val separatedFeet = ankles.size == 2 && (ankles[1].x - ankles[0].x) * width >= bodyPixels * 0.025
        // Head framing is irrelevant to an observed foot phase. Missing or clipped feet still
        // suppress the estimate; visible torso markers never substitute for missing leg data.
        val stepSignal = if (legTrackingReady && view != null && separatedFeet) {
            val legLength = (ankles.map { it.y }.average() - hipMid.y).coerceAtLeast(0.12)
            ((ankles[0].y - ankles[1].y) / legLength).coerceIn(-0.7, 0.7)
        } else null
        return GaitFrame(time, width, height, markers, view, bodyVisible, stepSignal)
    }
}

data class PhoneLevel(
    val available: Boolean,
    val rollDegrees: Double = 0.0,
    val pitchDegrees: Double = 0.0,
    val stable: Boolean = true
)

/** Gravity is preferred; a low-pass accelerometer supplies gravity when needed. */
@Composable
fun rememberPhoneLevel(): PhoneLevel {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gravity = remember(manager) { manager.getDefaultSensor(Sensor.TYPE_GRAVITY) }
    val sensor = remember(manager, gravity) { gravity ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    var level by remember(sensor) { mutableStateOf(PhoneLevel(available = sensor != null, stable = false)) }
    DisposableEffect(manager, sensor, owner) {
        val filtered = DoubleArray(3)
        var initialized = false
        var stableSince = 0L
        var lastUpdate = 0L
        var registered = false
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val now = SystemClock.elapsedRealtime()
                for (i in 0..2) {
                    filtered[i] = if (gravity != null || !initialized) event.values[i].toDouble()
                    else 0.85 * filtered[i] + 0.15 * event.values[i]
                }
                initialized = true
                if (now - lastUpdate < 60) return
                lastUpdate = now
                val roll = Math.toDegrees(atan2(filtered[0], filtered[1]))
                val pitch = Math.toDegrees(atan2(filtered[2], hypot(filtered[0], filtered[1])))
                val magnitude = hypot(hypot(filtered[0], filtered[1]), filtered[2])
                val calm = abs(roll - level.rollDegrees) < 0.5 && abs(pitch - level.pitchDegrees) < 0.5 && abs(magnitude - 9.81) < 0.7
                if (!calm) stableSince = now
                if (stableSince == 0L) stableSince = now
                level = PhoneLevel(true, roll, pitch, now - stableSince >= 900)
            }
        }
        fun register() {
            if (sensor != null && !registered) {
                stableSince = 0L
                registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                if (!registered) level = PhoneLevel(false, stable = false)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> {
                    manager.unregisterListener(listener)
                    registered = false
                    level = level.copy(stable = false)
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            manager.unregisterListener(listener)
        }
    }
    return level
}
