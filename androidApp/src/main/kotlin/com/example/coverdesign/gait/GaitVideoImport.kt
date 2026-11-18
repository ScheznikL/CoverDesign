package com.example.coverdesign.gait

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.theme.GaitPhotoBackground
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local-only import. The document URI, decoded pictures and palettes exist only in this route. */
@Composable
internal fun GaitVideoImport(profile: GaitProfile, onFinished: (GaitSession) -> Unit, onBack: () -> Unit,
    initialUri: Uri? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var metadata by remember { mutableStateOf<GaitVideoMetadata?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var metadataJob by remember { mutableStateOf<Job?>(null) }
    var snapshotJob by remember { mutableStateOf<Job?>(null) }
    var analysisJob by remember { mutableStateOf<Job?>(null) }
    var analysisGeneration by remember { mutableIntStateOf(0) }
    var previewGeneration by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingSnapshot by remember { mutableStateOf(false) }
    var loadingPreview by remember { mutableStateOf(false) }
    var analysing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<GaitVideoProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var views by remember { mutableStateOf(setOf(GaitView.FRONT, GaitView.BACK)) }
    var calibration by remember { mutableStateOf(MarkerColorCalibration()) }
    var calibratedFrames by remember { mutableStateOf<Map<GaitView, Long>>(emptyMap()) }
    var snapshot by remember { mutableStateOf<GaitColourSnapshot?>(null) }
    var mirrored by remember { mutableStateOf(false) }
    var cameraChecked by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<VideoView?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    fun pausePlayer(updatePosition: Boolean = true) {
        val native = player
        if (updatePosition && playerReady && native != null) {
            positionMs = native.currentPosition.toLong().coerceIn(0L, metadata?.durationMs ?: 0L)
        }
        native?.pause()
        playing = false
    }
    fun cancelAnalysis(message: String? = "Analysis cancelled. You can start it again; no result was saved.") {
        analysisGeneration++
        analysisJob?.cancel(); analysisJob = null
        analysing = false; progress = null
        notice = message
    }
    fun requestBack() {
        when {
            analysing || loadingSnapshot -> confirmLeave = true
            snapshot != null -> snapshot = null
            else -> onBack()
        }
    }
    fun loadVideo(selected: Uri) {
        pausePlayer(false)
        generation++
        val ticket = generation
        metadataJob?.cancel(); snapshotJob?.cancel(); cancelAnalysis(null)
        uri = selected; metadata = null; snapshot = null; preview = null
        calibration = MarkerColorCalibration(); calibratedFrames = emptyMap()
        positionMs = 0L; mirrored = false; cameraChecked = false
        playerReady = false; loadingSnapshot = false; loading = true; error = null; notice = null
        metadataJob = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { GaitVideoSource(context, selected).use { it.metadata } }
                if (generation == ticket) metadata = info
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == ticket) error = failure.message ?: "This video could not be opened. Try a standard MP4 recorded by the phone camera."
            } finally { if (generation == ticket) loading = false }
        }
    }
    val chooseVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) loadVideo(selected)
    }
    // Internal callers/tests can supply a URI; normal app entry always uses the document picker.
    LaunchedEffect(initialUri) { initialUri?.let(::loadVideo) }
    fun chooseFrame(view: GaitView) {
        val selected = uri ?: return
        if (metadata == null || loadingSnapshot || analysing) return
        if (playing && playerReady) player?.let { positionMs = it.currentPosition.toLong() }
        pausePlayer(false)
        val at = positionMs.coerceIn(0L, requireNotNull(metadata).durationMs - 1L)
        val ticket = generation
        val correction = mirrored
        loadingSnapshot = true; error = null; snapshotJob?.cancel()
        snapshotJob = scope.launch {
            try {
                val still = withContext(Dispatchers.IO) {
                    GaitVideoSource(context, selected, mirrored = correction).use { it.snapshotAt(at, view) }
                }
                if (generation == ticket) snapshot = still
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == ticket) error = failure.message ?: "That frame could not be decoded. Choose another point in the video."
            } finally { if (generation == ticket) loadingSnapshot = false }
        }
    }
    fun analyse() {
        val selected = uri ?: return
        if (!cameraChecked || !views.all { calibration.readyFor(it) } || analysing) return
        pausePlayer(false)
        val ticket = ++analysisGeneration
        val selectedViews = views.toSet()
        val selectedCalibration = calibration
        val correction = mirrored
        analysing = true; progress = null; error = null; notice = null
        analysisJob = scope.launch {
            try {
                val result = analyseGaitVideo(context, selected, profile, selectedCalibration, selectedViews,
                    mirrored = correction, onProgress = { update ->
                        scope.launch {
                            if (analysisGeneration == ticket && analysing) progress = update
                        }
                    })
                if (analysisGeneration == ticket) onFinished(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (analysisGeneration == ticket) error = failure.message ?: "Video analysis could not finish. Check the recording and try again."
            } finally {
                if (analysisGeneration == ticket) { analysing = false; analysisJob = null }
            }
        }
    }

    BackHandler(onBack = ::requestBack)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                pausePlayer()
                if (analysing) cancelAnalysis("Analysis stopped because the app left the screen. Keep the app open and start it again.")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.stopPlayback() }
    }
    val activity = remember(context) { context.videoImportActivity() }
    DisposableEffect(activity, analysing) {
        val window = activity?.window
        val alreadyAwake = (window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) ?: 0) != 0
        if (analysing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (analysing && !alreadyAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val ownedPreview = preview
    DisposableEffect(ownedPreview) { onDispose { ownedPreview?.let { if (!it.isRecycled) it.recycle() } } }
    LaunchedEffect(playing, player) {
        while (playing) {
            delay(120)
            val native = player ?: break
            positionMs = native.currentPosition.toLong().coerceIn(0L, metadata?.durationMs ?: 0L)
            if (!native.isPlaying) playing = false
        }
    }
    // A decoded still makes paused scrubbing precise and also works when the player cannot play.
    val previewAt = positionMs.takeIf { !playing }
    LaunchedEffect(uri, metadata, previewAt, mirrored, snapshot == null, analysing) {
        val selected = uri
        val info = metadata
        if (selected != null && info != null && previewAt != null && snapshot == null && !analysing) {
            val ticket = ++previewGeneration
            val correction = mirrored
            var owned: Bitmap? = null
            try {
                delay(100)
                loadingPreview = true
                withContext(Dispatchers.IO) {
                    owned = GaitVideoSource(context, selected, mirrored = correction).use {
                        it.frameAt(previewAt.coerceIn(0L, info.durationMs - 1L))
                    }
                }
                ensureActive()
                preview = owned; owned = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                notice = "This frame could not be previewed. Move the timeline to another point."
            } finally {
                owned?.let { if (!it.isRecycled) it.recycle() }
                if (ticket == previewGeneration) loadingPreview = false
            }
        }
    }

    Scaffold(topBar = { AppTopBar("Analyse recorded video", onBack = ::requestBack) }) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)
        val still = snapshot
        when {
            still != null -> GaitBodyColourSetup(still, calibration,
                onApply = { palette, _ ->
                    if (palette.readyFor(still.view)) {
                        calibration = palette
                        calibratedFrames = calibratedFrames + (still.view to still.timestampMs)
                        snapshot = null
                        notice = "${videoViewName(still.view)} marker colours captured. Choose the other view or analyse the video."
                    }
                }, onRetake = { snapshot = null }, onCancel = { snapshot = null }, modifier = content,
                retakeLabel = "Choose another frame")
            analysing -> Column(content.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Analysing on this phone", style = MaterialTheme.typography.headlineSmall)
                Text("Keep the app open. The video is read locally and is not uploaded.")
                LinearProgressIndicator(progress = { progress?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                progress?.let {
                    Text("${(it.fraction * 100).toInt()}% · ${it.processedFrames}/${it.totalFrames} frames")
                    Text("Video position: ${videoTime(it.positionMs)} / ${videoTime(it.durationMs)}")
                } ?: Text("Opening the video and preparing marker analysis…")
                Text("Missing markers, standing and turns are excluded. The result may contain only part of a view.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { cancelAnalysis() }, modifier = Modifier.testTag("video-cancel-analysis")) { Text("Cancel analysis") }
            }
            uri == null -> GaitVideoRecordingGuide({ chooseVideo.launch(arrayOf("video/*")) }, onBack, content)
            else -> Column(content.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) {
                    CircularProgressIndicator()
                    Text("Opening selected video…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("video-error")) }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                val info = metadata
                if (info != null) {
                    Text("Choose a clear frame for each view", style = MaterialTheme.typography.titleLarge)
                    Text("${videoTime(info.durationMs)} · ${info.width} × ${info.height}", style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.fillMaxWidth().height(280.dp).background(GaitPhotoBackground), contentAlignment = Alignment.Center) {
                        GaitVideoPlayer(uri = requireNotNull(uri), mirrored = mirrored, positionMs = positionMs,
                            modifier = Modifier.fillMaxSize(), onCreated = { player = it },
                            onReady = { native ->
                                if (player === native) {
                                    playerReady = true
                                    native.seekTo(positionMs.toInt().coerceAtLeast(1))
                                }
                            }, onCompleted = { native ->
                                if (player === native) {
                                    playing = false
                                    positionMs = info.durationMs.coerceAtLeast(1L) - 1L
                                }
                            }, onError = { native ->
                                if (player === native) {
                                    playing = false; playerReady = false
                                    notice = "Playback is unavailable for this file. You can still browse decoded frames with the timeline."
                                }
                            }, onReleased = { native -> if (player === native) { player = null; playerReady = false } })
                        preview?.takeIf { !playing && !it.isRecycled }?.let {
                            Image(it.asImageBitmap(), contentDescription = "Selected video frame",
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                        if (loadingPreview || loadingSnapshot) CircularProgressIndicator()
                    }
                    Text("${videoTime(positionMs)} / ${videoTime(info.durationMs)}", modifier = Modifier.testTag("video-position"))
                    Slider(value = positionMs.toFloat() / 1000f, onValueChange = {
                        pausePlayer(false)
                        positionMs = (it * 1000).toLong().coerceIn(0L, info.durationMs - 1L)
                    }, onValueChangeFinished = { player?.seekTo(positionMs.toInt()) },
                        valueRange = 0f..(info.durationMs.toFloat() / 1000f).coerceAtLeast(.001f),
                        modifier = Modifier.testTag("video-timeline"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            pausePlayer(false); positionMs = (positionMs - 200).coerceAtLeast(0L); player?.seekTo(positionMs.toInt())
                        }, modifier = Modifier.weight(1f)) { Text("−0.2 s") }
                        Button(onClick = {
                            if (playing) pausePlayer()
                            else if (playerReady) {
                                if (positionMs >= info.durationMs - 100) positionMs = 0L
                                player?.seekTo(positionMs.toInt()); player?.start(); playing = true
                            }
                        }, enabled = playerReady, modifier = Modifier.weight(1f).testTag("video-play-pause")) {
                            Text(if (playing) "Pause" else "Play")
                        }
                        OutlinedButton(onClick = {
                            pausePlayer(false); positionMs = (positionMs + 200).coerceAtMost(info.durationMs - 1L); player?.seekTo(positionMs.toInt())
                        }, modifier = Modifier.weight(1f)) { Text("+0.2 s") }
                    }
                    Text("Pause or move the timeline to a clear on-body frame, then choose marker colours. Zoom and tap the attached marker centres in the next screen.", style = MaterialTheme.typography.bodySmall)
                    Text("Views in this video", style = MaterialTheme.typography.titleSmall)
                    listOf("Front & back" to setOf(GaitView.FRONT, GaitView.BACK),
                        "Front only" to setOf(GaitView.FRONT), "Back only" to setOf(GaitView.BACK)).forEach { (label, value) ->
                        FilterChip(selected = views == value, onClick = { views = value }, enabled = !loadingSnapshot, label = { Text(label) },
                            modifier = Modifier.fillMaxWidth().testTag("video-views-${if (value.size == 2) "both" else value.single().name.lowercase() }"))
                    }
                    if (views.size == 1) Text(if (GaitView.FRONT in views)
                        "Front only: back-view and C7–tailbone measurements will be unavailable."
                    else "Back only: front-view and knee-line measurements will be unavailable.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = mirrored, onCheckedChange = {
                            pausePlayer(false); mirrored = it
                            generation++; snapshotJob?.cancel(); loadingSnapshot = false
                            calibration = MarkerColorCalibration(); calibratedFrames = emptyMap(); snapshot = null; preview = null
                            notice = "Mirror correction changed. Select marker colours again from the corrected video."
                        }, modifier = Modifier.testTag("video-mirror"))
                        Text("Correct mirrored recording")
                    }
                    Text("Use this if text or logos in the saved video are reversed. It changes both the preview and measured left/right sides.", style = MaterialTheme.typography.bodySmall)
                    views.sortedBy { it.ordinal }.forEach { view ->
                        val ready = calibration.readyFor(view)
                        OutlinedButton(onClick = { chooseFrame(view) }, enabled = !loadingSnapshot && !loadingPreview,
                            modifier = Modifier.fillMaxWidth().testTag("video-calibrate-${view.name.lowercase()}")) {
                            Text("${if (ready) "✓ " else ""}Choose ${videoViewName(view).lowercase()} marker colours")
                        }
                        calibratedFrames[view]?.let { Text("${videoViewName(view)} colours: frame at ${videoTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cameraChecked, onCheckedChange = { cameraChecked = it },
                            modifier = Modifier.testTag("video-camera-checked"))
                        Text("The recording phone was fixed, upright and level")
                    }
                    Text("A saved file cannot verify the phone's level. Camera tilt changes every measured angle.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = ::analyse, enabled = cameraChecked && views.all { calibration.readyFor(it) } && !loadingSnapshot,
                        modifier = Modifier.fillMaxWidth().testTag("video-analyse")) { Text("Analyse video") }
                    if (!views.all { calibration.readyFor(it) }) Text("Choose marker colours for every selected view before analysis.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { chooseVideo.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose another video") }
                Text("Video stays on this device. Saved numerical session reports sync to Firebase.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text("Stop video analysis?") },
        text = { Text("Leaving stops processing. No partial video result will be saved.") },
        confirmButton = { TextButton(onClick = {
            confirmLeave = false; generation++; snapshotJob?.cancel(); cancelAnalysis(null); onBack()
        }) { Text("Stop and leave") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep analysing") } })
}

@Composable
private fun GaitVideoPlayer(uri: Uri, mirrored: Boolean, positionMs: Long, modifier: Modifier,
    onCreated: (VideoView) -> Unit, onReady: (VideoView) -> Unit, onCompleted: (VideoView) -> Unit,
    onError: (VideoView) -> Unit, onReleased: (VideoView) -> Unit) {
    androidx.compose.runtime.key(uri) {
        AndroidView(factory = { context ->
            VideoView(context).apply {
                setOnPreparedListener { media ->
                    media.setVolume(0f, 0f)
                    onReady(this)
                }
                setOnCompletionListener { onCompleted(this) }
                setOnErrorListener { _, _, _ -> onError(this); true }
                scaleX = if (mirrored) -1f else 1f
                setVideoURI(uri)
                seekTo(positionMs.toInt().coerceAtLeast(1))
                onCreated(this)
            }
        }, update = { it.scaleX = if (mirrored) -1f else 1f }, modifier = modifier,
            onRelease = { it.stopPlayback(); onReleased(it) })
    }
}

@Composable
internal fun GaitVideoRecordingGuide(onChooseVideo: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp).testTag("video-recording-guide"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Record a walking video", style = MaterialTheme.typography.headlineSmall)
        Text("Record first, then choose clear front and back frames and mark the paper centres. The app analyses the rest locally.")
        Text("1 · Fix the phone upright", style = MaterialTheme.typography.titleMedium)
        Text("Use a stable support and a level camera. Start with the lens around half your height above the floor. Use the rear camera when possible; keep one person in view. Keep the phone fixed for the whole recording.")
        Text("2 · Keep the marker region visible", style = MaterialTheme.typography.titleMedium)
        Text("Use matching size and shade within each pair. Front: both shoulders, both pelvic papers and both knees. Back: both shoulders, both pelvic papers, C7 and tailbone. Keep every paper visible with room at the frame edges. Head and feet may leave the frame.")
        Text("3 · Walk naturally in both directions", style = MaterialTheme.typography.titleMedium)
        Text("Record several short passes towards and away from the camera, with slow turns between them. Leave safe space to stop. Aim for at least six seconds of clearly visible walking for each view. You can also analyse a front-only or back-only recording and receive the available measurements.")
        Text("4 · Use the original recording", style = MaterialTheme.typography.titleMedium)
        Text("Choose a normal-speed video up to two minutes, ideally 1080p at 30 fps with even light. Avoid filters, digital zoom, slow motion, cuts and a moving camera. If the saved file is mirrored, enable Correct mirrored recording before selecting colours.")
        Text("Video stays on this device. Saved numerical session reports sync to Firebase.", style = MaterialTheme.typography.bodySmall)
        Text("Angles describe the attached marker lines. They do not establish a diagnosis or verify correct anatomical placement.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onChooseVideo, modifier = Modifier.fillMaxWidth().testTag("video-choose")) { Text("Choose video") }
        TextButton(onClick = onBack) { Text("Back to menu") }
    }
}

private fun videoViewName(view: GaitView) = if (view == GaitView.FRONT) "Front" else "Back"
private fun videoTime(milliseconds: Long): String = String.format(Locale.US, "%d:%04.1f", milliseconds / 60_000, milliseconds % 60_000 / 1000.0)
private tailrec fun Context.videoImportActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.videoImportActivity() else null
    else -> null
}
