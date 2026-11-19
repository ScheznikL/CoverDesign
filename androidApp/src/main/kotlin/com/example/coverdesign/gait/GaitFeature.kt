package com.example.coverdesign.gait

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.coverdesign.domain.model.GaitProfileInput
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.usecase.UpsertGaitProfileUseCase
import com.example.coverdesign.domain.util.currentEpochMillis
import com.example.coverdesign.ui.components.AppTopBar
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** Maps the on-device gait profile to the server contract (biometrics + capture settings). */
private fun GaitProfile.toInput() = GaitProfileInput(
    heightCm = heightCm,
    weightKg = weightKg,
    sex = sex,
    age = age,
    passSteps = passSteps,
    targetStepsPerView = targetStepsPerView,
    passDurationSeconds = passDurationSeconds,
    targetDurationSecondsPerView = targetDurationSecondsPerView,
    updatedAt = currentEpochMillis()
)

/** Feature entry point. All local state is keyed by the authenticated account. */
@Composable
fun GaitFeature(onBack: () -> Unit, openProfile: Boolean = false, openHistory: Boolean = false) {
    val context = LocalContext.current
    val auth: FireAuthRepository = koinInject()
    val upsertGaitProfile: UpsertGaitProfileUseCase = koinInject()
    val scope = rememberCoroutineScope()
    val uid = auth.getCurrentUserId()
    if (uid == null) {
        Column(Modifier.padding(24.dp)) {
            Text("Sign in to use gait analysis.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    // Keep orientation stable throughout the mode, including results and failed-save recovery.
    val modeActivity = context.findActivity()
    DisposableEffect(modeActivity) {
        val previous = modeActivity?.requestedOrientation
        modeActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { previous?.let { modeActivity?.requestedOrientation = it } }
    }
    // Feedback belongs to the whole mode so the final stop cue survives the results transition.
    val voice = remember(uid) { GaitVoice(context.applicationContext) }
    var muted by remember(uid) { mutableStateOf(false) }
    DisposableEffect(voice) { onDispose { voice.close() } }
    var reload by remember { mutableIntStateOf(0) }
    val loaded = remember(uid, reload) { runCatching {
        val store = GaitStore(context.applicationContext, uid)
        LocalGaitData(store, store.profile(), store.sessions(), store.instructionSeen)
    } }
    if (loaded.isFailure) {
        Scaffold(topBar = { AppTopBar("Gait analysis", onBack) }) { padding ->
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved gait data could not be opened. Your existing data has been preserved.")
                Button(onClick = { reload++ }) { Text("Retry") }
            }
        }
        return
    }
    val data = loaded.getOrThrow()
    val store = data.store
    var seen by remember(uid) { mutableStateOf(data.seen) }
    var profile by remember(uid) { mutableStateOf(data.profile) }
    var page by remember(uid) { mutableStateOf(if (!data.seen) "instructions" else if (profile == null || openProfile) "profile" else if (openHistory) "history" else "menu") }
    var sessions by remember(uid) { mutableStateOf(data.sessions) }
    var selected by remember(uid) { mutableStateOf<GaitSession?>(null) }
    var measurementMethod by rememberSaveable(uid) { mutableStateOf(GaitMethod.MARKERS) }
    DisposableEffect(measurementMethod) { voice.stop(); onDispose { voice.stop() } }
    var error by remember { mutableStateOf<String?>(null) }
    var retryWrite by remember { mutableStateOf<(() -> Unit)?>(null) }
    var unsaved by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var leaveUnsaved by remember { mutableStateOf(false) }
    fun accessData(operation: () -> Unit) {
        try { operation(); error = null; retryWrite = null }
        catch (_: Exception) { error = "Could not access local gait data. Existing saved data has been preserved. Please retry."; retryWrite = operation }
    }
    fun backToMenu() {
        if (unsaved && selected != null) leaveUnsaved = true
        else page = if (profile == null) "profile" else "menu"
    }
    BackHandler(enabled = page != "capture" && page != "video") { if (page == "menu" || page == "instructions" && !seen) onBack() else backToMenu() }
    when (page) {
        "instructions" -> GaitInstructions(onContinue = { accessData {
            store.instructionSeen = true
            seen = true
            page = if (profile == null) "profile" else "menu"
        } }, onBack = { if (seen) backToMenu() else onBack() })
        "profile" -> GaitProfileEditor(initial = profile, onSave = { updated -> accessData {
            store.saveProfile(updated); profile = updated; page = "menu"
            // Best-effort sync of the profile to the rehab server; local save is the source of truth.
            scope.launch { upsertGaitProfile(updated.toInput()) }
        } }, onBack = { if (profile == null) onBack() else backToMenu() })
        "menu" -> profile?.let { p -> GaitMenu(p, onStart = { measurementMethod = GaitMethod.MARKERS; selected = null; unsaved = false; page = "capture" }, onHistory = { accessData { sessions = store.sessions(); page = "history" } }, onProfile = { page = "profile" }, onInstructions = { page = "instructions" }, onBack = onBack,
            onVideo = { selected = null; unsaved = false; page = "video" },
            onStatistics = { accessData { sessions = store.sessions(); page = "statistics" } },
            onPoseStart = { measurementMethod = GaitMethod.POSE; selected = null; unsaved = false; page = "capture" },
            onCloud = { page = "cloud" }) }
        "cloud" -> GaitCloudScreen(onBack = { backToMenu() })
        "video" -> profile?.let { p -> GaitVideoImport(p, onFinished = { session ->
            selected = session; unsaved = true
            accessData { store.saveSession(session); unsaved = false; sessions = store.sessions(); page = "results" }
        }, onBack = { backToMenu() }) }
        "capture" -> profile?.let { p -> GaitCapture(p, voice, muted, onMutedChange = {
            muted = it
            voice.enabled = !it
            if (it) voice.stop()
        }, onCancel = { backToMenu() }, onFinished = { session, exitToMenu ->
            selected = session; unsaved = true
            accessData { store.saveSession(session); unsaved = false; sessions = store.sessions(); page = if (exitToMenu) "menu" else "results" }
        }, measurementMethod = measurementMethod) }
        "history" -> GaitHistory(sessions, onOpen = { selected = it; unsaved = false; page = "results" }, onBack = { backToMenu() },
            onStatistics = { page = "statistics" })
        "statistics" -> GaitStatistics(sessions, onOpen = { selected = it; unsaved = false; page = "results" }, onBack = { backToMenu() })
        "results" -> selected?.let { s ->
            Column(Modifier.fillMaxSize()) {
                if (!unsaved) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { deleteId = s.id }) { Text("Delete session") }
                }
                if (unsaved) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("This result is not saved yet.", Modifier.weight(1f))
                        TextButton(onClick = { accessData { store.saveSession(s); unsaved = false; sessions = store.sessions() } }) { Text("Retry save") }
                    }
                }
                Box(Modifier.weight(1f)) { GaitResults(s, GaitInsights.previousComparableSession(sessions, s), onBack = { backToMenu() }, history = sessions,
                    onStatistics = if (unsaved) null else ({ page = "statistics" })) }
            }
        }
    }
    if (leaveUnsaved) AlertDialog(onDismissRequest = { leaveUnsaved = false },
        title = { Text("Keep this unsaved result?") },
        text = { Text("This measurement has not been saved. Keep it open to retry, or choose to discard it.") },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Button(onClick = { leaveUnsaved = false }, modifier = Modifier.fillMaxWidth()) { Text("Keep result open") }
                TextButton(onClick = {
                    leaveUnsaved = false
                    selected?.let { session -> accessData { store.saveSession(session); sessions = store.sessions(); unsaved = false; page = "menu" } }
                }, modifier = Modifier.fillMaxWidth()) { Text("Retry save and leave") }
                TextButton(onClick = { leaveUnsaved = false; unsaved = false; selected = null; page = "menu" }, modifier = Modifier.fillMaxWidth()) { Text("Discard result") }
            }
        })
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete this session?") },
        text = { Text("This removes the measurements from this phone and queues deletion of its Firebase report. An offline deletion will sync when the app reconnects.") },
        confirmButton = { TextButton(onClick = { accessData { store.deleteSession(id); sessions = store.sessions(); selected = null; deleteId = null; page = "history" } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } }) }

    error?.let { message -> AlertDialog(onDismissRequest = {}, title = { Text("Local data unavailable") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { retryWrite?.let { accessData(it) } }) { Text("Retry") } }, dismissButton = { TextButton(onClick = { error = null; if (unsaved && selected != null) page = "results" }) { Text(if (unsaved) "View without saving" else "Close") } }) }
}

private data class LocalGaitData(val store: GaitStore, val profile: GaitProfile?, val sessions: List<GaitSession>, val seen: Boolean)

internal enum class RunPhase { LEVEL, CHECK_FRONT, CHECK_BACK, READY, COUNTDOWN, FRONT, TURN_BACK, BACK, TURN_FRONT, FINISHED }

internal interface GaitFeedback { fun say(text: String); fun bell(); fun stop() {} }

private class GaitVoice(context: Context) : GaitFeedback {
    private var ready = false
    private var pending: String? = null
    var problem by mutableStateOf<String?>(null)
        private set
    var enabled = true
    private var tts: TextToSpeech? = null
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 65) }.getOrNull()
    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                ready = result != null && result >= TextToSpeech.LANG_AVAILABLE
                if (!ready) problem = "English voice guidance is unavailable. Install an English text-to-speech voice in your phone settings."
                pending?.let { say(it) }; pending = null
            } else problem = "Voice guidance could not start. Check text-to-speech in your phone settings."
        }
    }
    override fun say(text: String) {
        if (!enabled) return
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gait-guidance") else pending = text
    }
    override fun bell() { if (enabled) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 100) }
    override fun stop() { tts?.stop(); pending = null }
    fun close() { stop(); tts?.shutdown(); tone?.release() }
}

@Composable
private fun GaitCapture(
    profile: GaitProfile,
    voice: GaitVoice,
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onFinished: (GaitSession, Boolean) -> Unit,
    measurementMethod: GaitMethod = GaitMethod.MARKERS
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val run = remember(profile, voice, measurementMethod) { RunController(profile, voice, measurementMethod) }
    val markerMode = measurementMethod == GaitMethod.MARKERS
    val level = rememberPhoneLevel()
    SideEffect { run.level = level }
    var cameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraPermission = it }
    var frontCamera by remember { mutableStateOf(true) }
    var cameraRevision by remember { mutableIntStateOf(0) }
    var colourCalibration by remember { mutableStateOf(MarkerColorCalibration()) }
    var calibrationSource by remember { mutableStateOf("not captured") }
    var bodyViewColours by remember { mutableStateOf<Map<GaitView, Map<MarkerColor, Color>>>(emptyMap()) }
    var diagnosticFrame by remember { mutableStateOf<GaitFrame?>(null) }
    var exit by remember { mutableStateOf(false) }
    var saveAndExit by remember { mutableStateOf(false) }
    var bodyCalibrating by remember { mutableStateOf(false) }
    var colourSnapshot by remember { mutableStateOf<GaitColourSnapshot?>(null) }
    var colourSnapshotRequestAtMs by remember { mutableStateOf<Long?>(null) }
    var bodyCaptureCountdown by remember { mutableIntStateOf(10) }
    var bodyCaptureRevision by remember { mutableIntStateOf(0) }
    val displayColours = bodyViewColours[run.view].orEmpty()
    val paletteReady = !markerMode || colourCalibration.readyFor(run.view)
    val paletteReadyNow by rememberUpdatedState(paletteReady)
    fun clearBodySetup() { colourSnapshotRequestAtMs = null; colourSnapshot = null; bodyCalibrating = false }
    fun pause() { clearBodySetup(); run.suspendRun(); exit = true }
    fun continueAnalysis() {
        exit = false
        if ((!markerMode || colourCalibration.readyFor(run.view)) && !bodyCalibrating && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) run.resumeRun()
    }
    fun openBodySetup() {
        if (!markerMode) return
        run.suspendRun()
        colourSnapshot = null; colourSnapshotRequestAtMs = null; diagnosticFrame = null
        bodyCaptureCountdown = 10; bodyCaptureRevision++; bodyCalibrating = true
    }
    fun closeBodySetup() {
        clearBodySetup()
        if ((!markerMode || colourCalibration.readyFor(run.view)) && !exit && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) run.resumeRun()
    }
    fun retryCamera() {
        run.cameraError = null; cameraRevision++
        if ((!markerMode || colourCalibration.readyFor(run.view)) && !bodyCalibrating && !exit) run.resumeRun()
    }
    val bodySetupPending by rememberUpdatedState(bodyCalibrating)
    val hasCameraPermission by rememberUpdatedState(cameraPermission)
    val exitPending by rememberUpdatedState(exit)
    DisposableEffect(run, voice, lifecycle) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                run.suspendRun()
                colourSnapshotRequestAtMs = null
                if (bodySetupPending && colourSnapshot == null) bodyCalibrating = false
                if (hasCameraPermission && run.finished == null) exit = true
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (paletteReadyNow && !exitPending && !bodySetupPending) run.resumeRun()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (run.finished == null) voice.stop()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(markerMode, paletteReady, run.view, bodyCalibrating, exit) {
        if (!paletteReady || bodyCalibrating || exit) {
            if (run.active) run.suspendRun()
            if (markerMode && !paletteReady && !bodyCalibrating && !exit) voice.say("Secure the phone upright. Capture a photo of the markers on your body for this view before continuing.")
        } else if (!run.active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) run.resumeRun()
    }
    LaunchedEffect(markerMode, bodyCalibrating, colourSnapshot, bodyCaptureRevision) {
        if (markerMode && bodyCalibrating && colourSnapshot == null) {
            voice.say((if (run.view == GaitView.FRONT) "Face the camera." else "Face away from the camera so your back markers are visible.") + " Stand at your walking distance. The phone will capture a temporary calibration image in ten seconds.")
            for (n in 10 downTo 1) {
                bodyCaptureCountdown = n
                if (n in 1..3) voice.say(n.toString())
                kotlinx.coroutines.delay(1000)
            }
            bodyCaptureCountdown = 0; colourSnapshotRequestAtMs = SystemClock.elapsedRealtime()
            voice.say("Hold still with your whole body and feet visible.")
            kotlinx.coroutines.delay(20_000)
            if (colourSnapshot == null) {
                colourSnapshotRequestAtMs = null; bodyCaptureCountdown = -1
                voice.say("A full body image was not captured. Check the framing and try again.")
            }
        }
    }
    LaunchedEffect(run.phase, run.active) {
        if (run.phase == RunPhase.COUNTDOWN && run.active) {
            for (n in 3 downTo 1) { run.countdown = n; voice.say(n.toString()); kotlinx.coroutines.delay(1000) }
            run.beginWalking(SystemClock.elapsedRealtime())
        } else if (run.walking && run.active) {
            // A short pass must stop on its own timer, including when no new camera frame arrives.
            val remaining = (profile.passDurationSeconds * 1000L - run.passElapsedMs).coerceAtLeast(0L)
            kotlinx.coroutines.delay(remaining)
            run.checkFreshness(SystemClock.elapsedRealtime())
        }
    }
    LaunchedEffect(run.finished) { run.finished?.let { onFinished(it, saveAndExit) } }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val now = SystemClock.elapsedRealtime()
            run.checkFreshness(now)
        }
    }
    BackHandler { if (bodyCalibrating) closeBodySetup() else pause() }
    Scaffold(topBar = { AppTopBar(if (markerMode) "Gait analysis" else "Live without markers", onBack = { if (bodyCalibrating) closeBodySetup() else pause() }, actions = {
        TextButton(onClick = { onMutedChange(!muted) }) { Text(if (muted) "Sound off" else "Sound on") }
    }) }) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        if (!cameraPermission) {
            Column(contentModifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(run.title, style = MaterialTheme.typography.titleMedium)
                Text(cameraHeightGuidance(profile.heightCm))
                Text(if (markerMode) "Camera access is needed for live marker tracking. No video is recorded." else "Camera access is needed to track body landmarks. No video is recorded.")
                Button(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }) { Text("Open app settings") }
                TextButton(onClick = onCancel) { Text("Back to menu") }
            }
        } else if (markerMode && colourSnapshot != null) {
            GaitBodyColourSetup(snapshot = requireNotNull(colourSnapshot), calibration = colourCalibration,
                onApply = { candidate, colours ->
                    if (candidate.readyFor(run.view)) {
                        colourCalibration = candidate; bodyViewColours = bodyViewColours + (run.view to colours)
                        calibrationSource = "body image at walking distance"
                        diagnosticFrame = null; closeBodySetup()
                        voice.bell(); voice.say("Marker colours captured at walking distance. Stand in the camera view to check your markers.")
                    }
                }, onRetake = ::openBodySetup, onCancel = ::closeBodySetup, modifier = contentModifier)
        } else {
            GaitCaptureLayout(title = if (bodyCalibrating) "Calibrate at walking distance" else run.title, onPause = ::pause, modifier = contentModifier,
                preview = {
                    key(frontCamera, cameraRevision) {
                        GaitCamera(frontCamera, onFrame = {
                            // Check the current view at delivery too: a front/back transition can
                            // invalidate a palette between camera inference and recomposition.
                            if (markerMode && !colourCalibration.readyFor(run.view) && run.active) run.suspendRun()
                            run.receive(it, SystemClock.elapsedRealtime())
                            diagnosticFrame = chooseMarkerDiagnosticFrame(diagnosticFrame, it)
                        }, onError = { run.cameraError = it }, modifier = Modifier.fillMaxSize(),
                            calibration = colourCalibration, detectionView = run.view,
                            colourSnapshotRequestAtMs = colourSnapshotRequestAtMs,
                            onColourSnapshot = { snapshot ->
                                if (bodyCalibrating && colourSnapshotRequestAtMs != null && snapshot.timestampMs >= requireNotNull(colourSnapshotRequestAtMs) && snapshot.view == run.view &&
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !exit) {
                                    colourSnapshotRequestAtMs = null; colourSnapshot = snapshot
                                    voice.bell(); voice.say("Image captured. Come back to the phone. Zoom in and tap the centres of your markers.")
                                }
                            }, measurementMethod = measurementMethod)
                    }
                    GaitCameraOverlay(run.latest?.takeIf { (run.active || bodyCalibrating) && SystemClock.elapsedRealtime() - it.timestampMs < 1500 }, run.view, frontCamera, capturedColours = displayColours, measurementMethod = measurementMethod)
                },
                guidance = {
                    run.cameraError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = ::retryCamera) { Text("Retry camera") }
                    }
                    if (bodyCalibrating) {
                        Text(if (run.view == GaitView.FRONT) "Front view: face the camera." else "Back view: face away from the camera, with C7 and coccyx markers visible.", style = MaterialTheme.typography.titleMedium)
                        Text("Stand at the furthest position you will use during this walk. Keep your whole body and feet visible, with hands away from the markers.", style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            bodyCaptureCountdown > 0 -> "Capturing in $bodyCaptureCountdown seconds"
                            bodyCaptureCountdown == 0 -> "Waiting for a clear full-body view. Hold still."
                            else -> "No full-body image captured. Check your position and try again."
                        })
                        Text("After the sound, return to the phone and select the marker centres on the temporary image. The image stays in memory and is not saved.", style = MaterialTheme.typography.bodySmall)
                        if (bodyCaptureCountdown < 0) Button(onClick = ::openBodySetup) { Text("Try capture again") }
                        OutlinedButton(onClick = ::closeBodySetup) { Text("Cancel colour capture") }
                    } else {
                    if (!muted) voice.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (markerMode && !paletteReady) Text(colourCalibration.problemFor(run.view) ?: "Capture the marker colours for this view.", color = MaterialTheme.colorScheme.error)
                    if (!markerMode) GaitPoseCaptureGuidance(run.view, run.found,
                        initialSetup = run.phase in listOf(RunPhase.LEVEL, RunPhase.CHECK_FRONT, RunPhase.CHECK_BACK),
                        bodyVisible = run.latest?.bodyVisible == true,
                        showPointChecks = run.phase == RunPhase.CHECK_FRONT || run.phase == RunPhase.CHECK_BACK)
                    if (markerMode && paletteReady) {
                        if (run.phase in listOf(RunPhase.LEVEL, RunPhase.CHECK_FRONT, RunPhase.CHECK_BACK) && run.latest?.bodyVisible != true)
                            Text("For setup, fit your whole body and feet inside the camera view. Stand naturally.")
                        else if (run.latest?.measurementVisible(run.view) != true)
                            Text(if (run.view == GaitView.FRONT) "Keep both shoulders, pelvic markers and knees visible." else "Keep both shoulders, pelvic markers, C7 and coccyx visible.")
                    }
                    if (markerMode && !run.walking && run.phase != RunPhase.COUNTDOWN && run.phase != RunPhase.FINISHED) {
                        GaitLightingControls(colourCalibration.lightingTolerance, {
                            colourCalibration = colourCalibration.withLightingTolerance(it)
                            diagnosticFrame = null
                        })
                        GaitBodyColourCaptureControls(
                            view = run.view, capturedColours = displayColours, paletteReady = paletteReady,
                            paletteProblem = colourCalibration.problemFor(run.view), onCapture = ::openBodySetup,
                            onReset = {
                                run.suspendRun(); colourCalibration = MarkerColorCalibration(); bodyViewColours = emptyMap()
                                calibrationSource = "not captured"; diagnosticFrame = null
                                voice.say("Marker colours cleared. Capture a new photo with the markers attached to your body.")
                            })
                    }
                    if (run.phase == RunPhase.LEVEL) {
                        Text(cameraHeightGuidance(profile.heightCm), style = MaterialTheme.typography.titleSmall)
                        Text(if (markerMode) "Measure from the floor to the lens. Keep the phone upright; move farther away instead of tilting it. Your whole body is needed for setup. During walking only the required marker region must remain visible."
                            else "Measure from the floor to the lens. Keep the phone upright; move farther away instead of tilting it. Show your whole body for setup. During walking keep the shoulders and hips visible, plus the knees when facing the camera.", style = MaterialTheme.typography.bodySmall)
                        if (level.available) {
                            Text("Side tilt: ${String.format(Locale.US, "%.1f", level.rollDegrees)}° · Forward/back: ${String.format(Locale.US, "%.1f", level.pitchDegrees)}°")
                            Text(if (abs(level.rollDegrees) > 2) "Rotate the phone ${if (level.rollDegrees > 0) "clockwise" else "counter-clockwise"} by about ${String.format(Locale.US, "%.0f", abs(level.rollDegrees))}°." else "Side alignment ready. Keep the phone upright and still.")
                            if (abs(level.pitchDegrees) > 5) Text("Tilt the top edge ${if (level.pitchDegrees > 0) "towards you" else "away from you"} by about ${String.format(Locale.US, "%.0f", abs(level.pitchDegrees))}°.")
                        } else {
                            Text("Automatic leveling is unavailable. Align the grid with a plumb line or a checked vertical edge. Use a spirit level to check forward/back tilt, then secure the phone upright.")
                            Button(onClick = run::manualConfirm, enabled = paletteReady) { Text("I checked the phone level") }
                        }
                        if (level.available) Button(onClick = run::confirmSetup, enabled = paletteReady && run.setupReady) { Text(if (markerMode) "Phone positioned — check markers" else "Phone positioned — check body landmarks") }
                        TextButton(onClick = { voice.bell(); voice.say("Sound check. Follow the spoken instructions during your walk.") }) { Text("Test sound") }
                        TextButton(onClick = {
                            frontCamera = !frontCamera; run.cameraError = null; diagnosticFrame = null
                            colourCalibration = MarkerColorCalibration(); bodyViewColours = emptyMap(); calibrationSource = "not captured"
                            voice.say(if (markerMode) "Camera changed. Marker colours cleared. Capture colours again for this camera." else "Camera changed. Stand in full view and check the phone level again.")
                        }) { Text("Switch camera") }
                    } else {
                        Text("Front: ${gaitSeconds(run.frontValidDurationMs)}/${profile.targetDurationSecondsPerView} s · Back: ${gaitSeconds(run.backValidDurationMs)}/${profile.targetDurationSecondsPerView} s", style = MaterialTheme.typography.titleSmall)
                        Text("This pass: ${gaitSeconds(run.passElapsedMs)}/${profile.passDurationSeconds} s · ${gaitSeconds(run.passValidDurationMs)} s accepted", style = MaterialTheme.typography.bodySmall)
                        Text("Observed estimated steps: front ${run.frontSteps} · back ${run.backSteps}. Counts may be partial.", style = MaterialTheme.typography.bodySmall)
                        if (run.walking && run.latest?.stepSignal == null) Text(if (markerMode) "Feet are outside reliable view. Inclination capture continues while all markers remain visible; the total step count is unavailable."
                            else "Feet are outside reliable view. Inclination capture continues while all required body landmarks remain visible; the total step count is unavailable.", style = MaterialTheme.typography.bodySmall)
                        if (run.returnOnly) Text("This view target is complete. Return naturally for the next pass; no extra measurements are added for this view.", style = MaterialTheme.typography.bodySmall)
                        run.warning?.takeIf { paletteReady }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (run.retryRequired) Button(onClick = run::retryCurrentPass) { Text("Repeat pass") }
                        if (markerMode && (run.phase == RunPhase.CHECK_FRONT || run.phase == RunPhase.CHECK_BACK)) {
                            if (run.phase == RunPhase.CHECK_BACK) Text("Back markers allow small height offsets. Keep each paper near its anatomical landmark; the check confirms visibility, not exact placement.", style = MaterialTheme.typography.bodySmall)
                            GaitMarkerDetectionCards(run.view, run.found, capturedColours = displayColours)
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val frame = diagnosticFrame ?: run.latest
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Marker detection", markerDiagnosticReport(frame, colourCalibration, frame?.let { SystemClock.elapsedRealtime() - it.timestampMs }, calibrationSource)))
                                android.widget.Toast.makeText(context, "Detection report copied. No images included.", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("Copy detection report") }
                            val missing = requiredMarkers(run.view).firstOrNull { it !in run.found }
                            if (paletteReady) Text(if (missing == null) "All markers visible. Hold still." else "Checking: ${missing.label}")
                            Text("${run.found.size}/${requiredMarkers(run.view).size} markers confirmed. This checks visibility, not anatomical placement.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (run.manual) Text("Phone alignment checked manually", style = MaterialTheme.typography.labelSmall)
                    if (run.canEndPassEarly) OutlinedButton(onClick = run::endPassEarly) { Text("End pass and turn") }
                    }
                }
            )
        }
    }
    if (exit) GaitPauseDialog(
        onContinue = ::continueAnalysis,
        onSaveAndExit = { saveAndExit = true; exit = false; run.finish(false) },
        onDiscardAndExit = { voice.stop(); onCancel() }
    )
}


private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.findActivity()
    else -> null
}
