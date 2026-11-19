package com.example.coverdesign.ui.screens.patient.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.components.EventHandler
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.ScannerCyan
import com.example.coverdesign.ui.viewmodel.SensorCameraUiEvent
import com.example.coverdesign.ui.viewmodel.SensorCameraViewModel
import com.example.coverdesign.ui.viewmodel.SubmitStatus
import org.koin.compose.viewmodel.koinViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import androidx.camera.core.Preview as CameraPreview

// Skeleton edges in MediaPipe's 33-landmark body-pose model
private val POSE_CONNECTIONS = listOf(
    11 to 12,             // shoulders
    11 to 13, 13 to 15,  //  left arm
    12 to 14, 14 to 16,  // right arm
    11 to 23, 12 to 24,  // torso sides
    23 to 24,             // hips
    23 to 25, 25 to 27,  // left leg
    24 to 26, 26 to 28   // right leg
)

// Download from:
// https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task
// Place the file at: androidApp/src/main/assets/pose_landmarker_lite.task
private const val MODEL_ASSET = "pose_landmarker_lite.task"

private sealed interface RecordingUiState {
    object Idle : RecordingUiState
    object Recording : RecordingUiState
    data class Reviewing(val file: File, val durationSeconds: Int) : RecordingUiState
    data class Uploading(val file: File, val progress: Float) : RecordingUiState
}

// ── Entry point ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SensorCameraScreen(
    onSessionReady: () -> Unit = {}
) {
    val permissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )
    val cameraGranted = permissions.permissions.any {
        it.permission == Manifest.permission.CAMERA && it.status.isGranted
    }
    val audioGranted = permissions.permissions.any {
        it.permission == Manifest.permission.RECORD_AUDIO && it.status.isGranted
    }

    Scaffold(topBar = { AppTopBar(title = "Sensor Scanner") }) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (cameraGranted) {
                // ViewModel is only resolved on the camera path, so the permission-gate
                // path renders without a Koin context (e.g. in UI tests).
                val viewModel: SensorCameraViewModel = koinViewModel()
                val submitState by viewModel.uiState.collectAsStateWithLifecycle()

                EventHandler(viewModel.events) { event ->
                    when (event) {
                        is SensorCameraUiEvent.SessionReady -> onSessionReady()
                    }
                }

                SensorCameraContent(
                    hasAudio = audioGranted,
                    submitStatus = submitState.status,
                    submitError = submitState.errorMessage,
                    // SCAN records a single MediaPipe-pose video; views are not distinguished here.
                    onVideoUploaded = { path, duration ->
                        viewModel.submitVideo(
                            videoPath = path,
                            durationSeconds = duration,
                            measurementMethod = "POSE",
                            acquisitionSource = "VIDEO"
                        )
                    },
                    onDismissSubmit = viewModel::reset
                )
            } else {
                CameraPermissionGate(
                    onRequest = { permissions.launchMultiplePermissionRequest() },
                    modifier  = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

// ── Live camera + MediaPipe inference + recording ─────────────────────────────

@SuppressLint("MissingPermission")
@Composable
private fun SensorCameraContent(
    hasAudio: Boolean,
    submitStatus: SubmitStatus,
    submitError: String?,
    onVideoUploaded: (videoPath: String, durationSeconds: Int) -> Unit,
    onDismissSubmit: () -> Unit
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler    = remember { Handler(Looper.getMainLooper()) }

    // Pose state — only ever written from mainHandler.post { }
    var poseResult  by remember { mutableStateOf<PoseLandmarkerResult?>(null) }
    var imageWidth  by remember { mutableStateOf(1) }
    var imageHeight by remember { mutableStateOf(1) }
    var initError   by remember { mutableStateOf<String?>(null) }
    var isReady     by remember { mutableStateOf(false) }

    // Recording state
    var recordingState  by remember { mutableStateOf<RecordingUiState>(RecordingUiState.Idle) }
    var recordingSeconds by remember { mutableStateOf(0) }

    val landmarkerRef     = remember { AtomicReference<PoseLandmarker?>(null) }
    val executor          = remember { Executors.newSingleThreadExecutor() }
    val activeRecordingRef = remember { AtomicReference<Recording?>(null) }

    val videoCapture: VideoCapture<Recorder> = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))
                )
                .build()
        )
    }

    // MediaPipe init — runs after composition to avoid crashing the layout pass
    LaunchedEffect(context) {
        try {
            val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    mainHandler.post { poseResult = result }
                }
                .build()
            landmarkerRef.set(PoseLandmarker.createFromOptions(context, opts))
            isReady = true
        } catch (e: Throwable) {
            initError = when (e) {
                is UnsatisfiedLinkError ->
                    "MediaPipe native library not found.\n\n" +
                    "Run on a physical device or an arm64 emulator.\n" +
                    "x86/x86_64 emulators are not supported by MediaPipe Tasks."
                else ->
                    "Pose model not found.\n\n" +
                    "Download pose_landmarker_lite.task and place it in " +
                    "androidApp/src/main/assets/."
            }
        }
    }

    // Recording duration counter — runs while in Recording state only
    val isRecordingActive = recordingState == RecordingUiState.Recording
    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            recordingSeconds = 0
            while (true) {
                delay(1_000)
                recordingSeconds++
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeRecordingRef.getAndSet(null)?.stop()
            landmarkerRef.getAndSet(null)?.close()
            executor.shutdown()
        }
    }

    fun startRecording() {
        val file = File(context.cacheDir, "pose_${System.currentTimeMillis()}.mp4")
        val pending = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (hasAudio) pending.withAudioEnabled()
        val recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recordingState = if (!event.hasError()) {
                    RecordingUiState.Reviewing(file, recordingSeconds)
                } else {
                    file.delete()
                    RecordingUiState.Idle
                }
            }
        }
        activeRecordingRef.set(recording)
        recordingState = RecordingUiState.Recording
    }

    fun stopRecording() {
        activeRecordingRef.getAndSet(null)?.stop()
    }

    // ── Main content ──────────────────────────────────────────────────────────

    when {
        initError != null -> ModelMissingCard(
            message  = initError!!,
            modifier = Modifier.fillMaxSize()
        )

        !isReady -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        else -> Box(modifier = Modifier.fillMaxSize()) {

            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = CameraPreview.Builder().build()
                            .also { it.surfaceProvider = previewView.surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            val lm = landmarkerRef.get()
                            if (lm == null) { proxy.close(); return@setAnalyzer }
                            try {
                                val raw     = proxy.toBitmap()
                                val degrees = proxy.imageInfo.rotationDegrees
                                val bmp     = if (degrees != 0) {
                                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                                        Matrix().apply { postRotate(degrees.toFloat()) }, true)
                                } else raw
                                lm.detectAsync(
                                    BitmapImageBuilder(bmp).build(),
                                    proxy.imageInfo.timestamp / 1_000_000L
                                )
                                mainHandler.post { imageWidth = bmp.width; imageHeight = bmp.height }
                            } catch (_: Exception) {
                            } finally { proxy.close() }
                        }
                        // Try binding all three use cases; fall back to Preview+VideoCapture
                        // on devices that can't handle concurrent ImageAnalysis + VideoCapture.
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, analysis, videoCapture
                            )
                        }.onFailure {
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview, videoCapture
                                )
                            }
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            PoseOverlay(
                result      = poseResult,
                imageWidth  = imageWidth,
                imageHeight = imageHeight,
                modifier    = Modifier.fillMaxSize()
            )

            if (recordingState == RecordingUiState.Recording) {
                RecordingIndicator(
                    seconds  = recordingSeconds,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                )
            }

            val detected = poseResult?.landmarks()?.isNotEmpty() == true
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (detected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                ) {
                    Text(
                        text     = if (detected) "Pose detected" else "Point camera at a person…",
                        style    = MaterialTheme.typography.labelMedium,
                        color    = if (detected) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                RecordButton(
                    isRecording = recordingState == RecordingUiState.Recording,
                    onClick = {
                        when (recordingState) {
                            RecordingUiState.Idle      -> startRecording()
                            RecordingUiState.Recording -> stopRecording()
                            else                       -> {}
                        }
                    }
                )
            }
        }
    }

    // ── Dialogs — overlay on top of whatever content is shown ─────────────────

    when (val state = recordingState) {
        is RecordingUiState.Reviewing -> ReviewDialog(
            file            = state.file,
            durationSeconds = state.durationSeconds,
            onUpload        = {
                recordingState = RecordingUiState.Uploading(state.file, 0f)
                uploadToFirebase(
                    file       = state.file,
                    onProgress = { p ->
                        recordingState = RecordingUiState.Uploading(state.file, p)
                    },
                    onSuccess  = { storagePath ->
                        recordingState = RecordingUiState.Idle
                        // Hand the Firebase path to the backend for gait processing.
                        onVideoUploaded(storagePath, state.durationSeconds)
                    },
                    onError    = {
                        // Return to review so the user can retry or discard
                        recordingState = RecordingUiState.Reviewing(state.file, state.durationSeconds)
                    }
                )
            },
            onDiscard = {
                state.file.delete()
                recordingState = RecordingUiState.Idle
            }
        )
        is RecordingUiState.Uploading -> UploadProgressDialog(progress = state.progress)
        else -> {}
    }

    // ── Backend processing of the uploaded session ────────────────────────────
    when (submitStatus) {
        SubmitStatus.Submitting, SubmitStatus.Processing -> ProcessingDialog()
        SubmitStatus.Error -> SubmitErrorDialog(
            message   = submitError ?: "Something went wrong",
            onDismiss = onDismissSubmit
        )
        else -> {}
    }
}

// ── Firebase upload ───────────────────────────────────────────────────────────

private fun uploadToFirebase(
    file: File,
    onProgress: (Float) -> Unit,
    onSuccess: (storagePath: String) -> Unit,
    onError: () -> Unit
) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { onError(); return }
    val storagePath = "sessions/$uid/${file.name}"
    val ref = FirebaseStorage.getInstance().reference.child(storagePath)
    ref.putFile(Uri.fromFile(file))
        .addOnProgressListener { snap ->
            if (snap.totalByteCount > 0)
                onProgress(snap.bytesTransferred.toFloat() / snap.totalByteCount)
        }
        .addOnSuccessListener { file.delete(); onSuccess(storagePath) }
        .addOnFailureListener { onError() }
}

private fun formatDuration(seconds: Int): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)

// ── Skeleton overlay canvas ───────────────────────────────────────────────────

@Composable
private fun PoseOverlay(
    result      : PoseLandmarkerResult?,
    imageWidth  : Int,
    imageHeight : Int,
    modifier    : Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val landmarks = result?.landmarks()?.firstOrNull() ?: return@Canvas

        val scale   = maxOf(size.width / imageWidth.toFloat(), size.height / imageHeight.toFloat())
        val offsetX = (size.width  - imageWidth  * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f

        fun pt(index: Int): Offset? {
            val lm = landmarks.getOrNull(index) ?: return null
            return Offset(
                offsetX + lm.x() * imageWidth  * scale,
                offsetY + lm.y() * imageHeight * scale
            )
        }

        val lineColor = ScannerCyan.copy(alpha = 0.7f)
        for ((s, e) in POSE_CONNECTIONS) {
            val a = pt(s) ?: continue
            val b = pt(e) ?: continue
            drawLine(lineColor, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        for (lm in landmarks) {
            drawCircle(
                color  = ScannerCyan,
                radius = 5.dp.toPx(),
                center = Offset(
                    offsetX + lm.x() * imageWidth  * scale,
                    offsetY + lm.y() * imageHeight * scale
                )
            )
        }
    }
}

// ── Private composables ───────────────────────────────────────────────────────

@Composable
private fun RecordButton(
    isRecording : Boolean,
    onClick     : () -> Unit,
    modifier    : Modifier = Modifier
) {
    FloatingActionButton(
        onClick        = onClick,
        containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer
                         else MaterialTheme.colorScheme.error,
        contentColor   = if (isRecording) MaterialTheme.colorScheme.onErrorContainer
                         else Color.White,
        modifier       = modifier.size(64.dp)
    ) {
        Icon(
            imageVector        = if (isRecording) Icons.Filled.Stop
                                 else Icons.Filled.FiberManualRecord,
            contentDescription = if (isRecording) "Stop recording" else "Start recording",
            modifier           = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun RecordingIndicator(seconds: Int, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec-pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 0.2f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "dot-alpha"
    )
    Surface(
        shape    = MaterialTheme.shapes.small,
        color    = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
        modifier = modifier
    ) {
        Row(
            modifier            = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(dotAlpha)
                    .background(Color.White, CircleShape)
            )
            Text(
                text  = "REC  ${formatDuration(seconds)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ReviewDialog(
    file            : File,
    durationSeconds : Int,
    onUpload        : () -> Unit,
    onDiscard       : () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        icon  = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
        title = { Text("Recording ready") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Duration: ${formatDuration(durationSeconds)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Size: ${"%.1f".format(file.length() / 1_048_576f)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text("Upload this session recording?", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton  = { Button(onClick = onUpload) { Text("Upload") } },
        dismissButton  = { OutlinedButton(onClick = onDiscard) { Text("Discard") } }
    )
}

@Composable
private fun UploadProgressDialog(progress: Float) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Uploading…") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ProcessingDialog() {
    AlertDialog(
        onDismissRequest = {},
        icon  = { CircularProgressIndicator(modifier = Modifier.size(28.dp)) },
        title = { Text("Analyzing your session…") },
        text  = {
            Text(
                "Your recording was uploaded. We're processing your gait and posture — " +
                "this can take a few minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {}
    )
}

@Composable
private fun SubmitErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Processing failed") },
        text  = {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
private fun ModelMissingCard(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape    = MaterialTheme.shapes.large,
            color    = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text     = message,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

@Composable
internal fun CameraPermissionGate(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector        = Icons.Outlined.CameraAlt,
            contentDescription = null,
            modifier           = Modifier.size(48.dp),
            tint               = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text("Camera access needed", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "The sensor scanner uses your camera to detect body pose and " +
                    "your microphone to optionally record audio alongside video.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant access") }
    }
}

@Preview(showBackground = true)
@Composable
private fun SensorCameraPermissionPreview() {
    AppTheme {
        CameraPermissionGate(onRequest = {})
    }
}
