package com.example.coverdesign.gait

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.theme.StatusGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** UI for measured surface-marker inclinations. These screens do not diagnose posture. */
@Composable
fun GaitInstructions(onContinue: () -> Unit, onBack: () -> Unit) {
    GaitPage("Gait analysis", onBack) {
        Text("Understand your movement", style = MaterialTheme.typography.headlineSmall)
        Text("Measure the inclination of your shoulders, pelvis, knees and the C7–coccyx line during short walks.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GaitCard {
            Text("Choose your analysis mode", style = MaterialTheme.typography.titleMedium)
            Text("Live analysis guides phone setup, marker checks and short walks with spoken cues. Analyse a video lets you choose an existing recording, pause on clear front/back frames and select the marker colours before local analysis. Recording instructions are shown in the video mode.")
            Text("Live without markers uses the camera's estimated shoulder, hip and knee landmarks. Skip marker attachment and colour capture in this mode. It measures three front-view lines and two back-view lines; it does not measure C7–coccyx. The same phone setup, short passes, pause actions and local history are available.")
        }
        GaitCard {
            Text("1  Prepare the space", style = MaterialTheme.typography.titleMedium)
            Text("Start with the camera lens around half your height above the floor. Measure to the lens, not the bottom of the phone. Secure the phone upright on a stable support and aim it straight along the walking path.")
            Text("Use an uncluttered, well-lit background and leave enough clear space to walk naturally and turn safely. Keep your whole body visible for calibration and the initial marker checks. During walking, keep all required markers visible: shoulders, pelvis and knees from the front; shoulders, pelvis, C7 and coccyx from the back. Your head and feet may leave the frame. Keep the phone level instead of tilting it.")
            Text("This height is a starting guide for framing. The phone checks its tilt, not its height above the floor.", style = MaterialTheme.typography.bodySmall)
        }
        GaitCard {
            Text("2  Attach the markers", style = MaterialTheme.typography.titleMedium)
            Text("Use small matte coloured markers. Use matching size and shade for both markers in a pair, and distinct colours for groups visible together. The diagram colours are examples: shoulder paper may look yellow under warm light. Set colours only from a photo with the markers attached: use Calibrate on your body at your walking distance. After the countdown, return to the phone and zoom in to select the paper centres. Choose plain clothing and a background without similar bright colours.")
            var back by rememberSaveable { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !back, onClick = { back = false }, label = { Text("Front") })
                FilterChip(selected = back, onClick = { back = true }, label = { Text("Back") })
            }
            MarkerGuide(if (back) GaitView.BACK else GaitView.FRONT)
            Text("The diagram shows approximate locations. A visible marker does not prove correct anatomical placement. C7 and the coccyx are separate small markers; the line between them is drawn by the app.",
                style = MaterialTheme.typography.bodySmall)
            Text("Accurate C7, pelvic and coccyx placement may need help from a trained person. Keep the landmarks visible; loose fabric can move independently of the body.",
                style = MaterialTheme.typography.bodySmall)
            Text("Attaching back markers yourself: small height offsets are allowed by the visibility check. Place each paper as close as possible to its landmark. Approximate placement measures the paper lines and can add error to the anatomical inclination.", style = MaterialTheme.typography.bodySmall)
        }
        GaitCard {
            Text("3  Follow the guided session", style = MaterialTheme.typography.titleMedium)
            Text("Set the phone level. Use Calibrate on your body, move to your walking distance and wait for the capture sound. Return to the phone and select both shoulder, pelvic and knee papers on the temporary image. For the back view, face away during capture and select both shoulders, both pelvic markers, C7 and coccyx. The image stays in memory and is not saved. The app checks front/back markers, then guides short passes towards and away from the camera. Each short pass lasts two seconds by default. Follow Go, stop and turn only at the spoken instruction. Keep your usual step length; end a pass early if your clear path ends.")
            Text("Step counts are an extra estimate when the feet are visible. Cropped feet do not block marker-angle capture. Pose detection supports framing and counting only; measured angles come from the coloured markers.", style = MaterialTheme.typography.bodySmall)
            Text("Changing light is enabled by default. It allows moderate brightness and shade changes along the path. Standard keeps narrower limits. Capture colours in the walking area; glare or very deep shadow can still prevent detection.", style = MaterialTheme.typography.bodySmall)
        }
        GaitCard {
            Text("4  Review the results", style = MaterialTheme.typography.titleMedium)
            Text("Angles describe the marker lines in the camera image. They do not show the full curve of the spine or establish a diagnosis. Repeat measurements with the same marker locations, camera setup and walking conditions.")
            Text("The short-pass protocol is a research prototype. Its duration settings and measurement accuracy still need real-world validation.", style = MaterialTheme.typography.bodySmall)
        }
        GaitCard {
            Text("Live analysis · local history", style = MaterialTheme.typography.titleMedium)
            Text("Video is processed live and is not recorded. Your profile and numerical results are stored on this phone for the signed-in account. You can stop the session at any time.")
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Continue")
        }
    }
}

@Composable
fun GaitProfileEditor(initial: GaitProfile?, onSave: (GaitProfile) -> Unit, onBack: () -> Unit) {
    var height by rememberSaveable(initial?.heightCm) { mutableStateOf(initial?.heightCm?.toString() ?: "") }
    var weight by rememberSaveable(initial?.weightKg) { mutableStateOf(initial?.weightKg?.toString() ?: "") }
    var age by rememberSaveable(initial?.age) { mutableStateOf(initial?.age?.toString() ?: "") }
    var sex by rememberSaveable(initial?.sex) { mutableStateOf(initial?.sex ?: "Prefer not to say") }
    var passSeconds by rememberSaveable(initial?.passDurationSeconds) { mutableStateOf(initial?.passDurationSeconds ?: 2) }
    var targetSeconds by rememberSaveable(initial?.targetDurationSecondsPerView) { mutableStateOf(initial?.targetDurationSecondsPerView ?: 6) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val h = height.toIntOrNull()
    val w = weight.toIntOrNull()
    val a = age.toIntOrNull()
    val valid = h != null && h in 80..250 && w != null && w in 20..300 && a != null && a in 5..110

    GaitPage("Your measurements", onBack) {
        Text(if (initial == null) "Set up your gait profile" else "Profile & walking settings",
            style = MaterialTheme.typography.headlineSmall)
        Text("These details are saved with each session. They do not set diagnostic thresholds.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        GaitCard {
            NumberField("Height (cm)", height, { height = it }, attempted && (h == null || h !in 80..250), "Enter 80–250 cm")
            NumberField("Weight (kg)", weight, { weight = it }, attempted && (w == null || w !in 20..300), "Enter 20–300 kg")
            NumberField("Age (years)", age, { age = it }, attempted && (a == null || a !in 5..110), "Enter 5–110 years")
            Text("Sex", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Female", "Male", "Other").forEach { option ->
                    FilterChip(selected = sex == option, onClick = { sex = option }, label = { Text(option) })
                }
            }
            FilterChip(selected = sex == "Prefer not to say", onClick = { sex = "Prefer not to say" }, label = { Text("Prefer not to say") })
        }
        GaitCard {
            Text("Short passes for home", style = MaterialTheme.typography.titleMedium)
            Text("Seconds per pass", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3, 4).forEach { n -> FilterChip(selected = passSeconds == n, onClick = { passSeconds = n }, label = { Text("$n seconds") }) }
            }
            Text("Target for each view", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 9, 12).forEach { n -> FilterChip(selected = targetSeconds == n, onClick = { targetSeconds = n }, label = { Text("$n usable seconds") }) }
            }
            Text("Short front and back passes accumulate usable measurement time. Walk at your normal pace after Go, then stop at the spoken instruction. These are provisional research settings. End a pass early if your clear path ends.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            attempted = true
            if (valid) onSave(GaitProfile(h!!, w!!, sex, a!!, initial?.passSteps ?: 4, initial?.targetStepsPerView ?: 12, passSeconds, targetSeconds))
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save profile") }
    }
}

@Composable
fun GaitMenu(profile: GaitProfile, onStart: () -> Unit, onHistory: () -> Unit, onProfile: () -> Unit,
             onInstructions: () -> Unit, onBack: () -> Unit, onVideo: () -> Unit, onStatistics: (() -> Unit)? = null,
             onPoseStart: (() -> Unit)? = null, onCloud: (() -> Unit)? = null) {
    GaitPage("Gait analysis", onBack) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.DirectionsWalk, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("See how you move", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("A guided check of body inclination during walking.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                onPoseStart?.let { start ->
                    Button(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Live without markers") }
                    Text("No papers or colour setup. Shoulder, hip and knee landmarks are estimated on the phone; C7–coccyx is not measured.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Live with markers") }
                Text("Coloured markers · live camera with setup guidance and spoken walking cues.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onVideo, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Analyse a video") }
                Text("Choose a recording, pause on clear frames and select front/back marker colours.", style = MaterialTheme.typography.bodySmall)
            }
        }
        onStatistics?.let { MenuCard(Icons.Outlined.Analytics, "Daily statistics", "Angles by day and change from your starting measurements", it) }
        MenuCard(Icons.Outlined.History, "Measurement history", "Review your results and compare sessions", onHistory)
        onCloud?.let { MenuCard(Icons.Outlined.Person, "Results & doctor access", "Firebase sync and sharing with your doctor", it) }
        MenuCard(Icons.Outlined.Person, "Your measurements", "${profile.heightCm} cm · ${profile.weightKg} kg · ${profile.age} years", onProfile)
        Text("${profile.passDurationSeconds} seconds per pass · target ${profile.targetDurationSecondsPerView} usable seconds per view",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GaitCard {
            Text("Camera setup", style = MaterialTheme.typography.titleSmall)
            Text(cameraHeightGuidance(profile.heightCm))
            Text("Keep the phone upright and aim it straight along the walking path. Keep your whole body visible for setup. During a pass, keep the required points for your selected method inside the frame; the head and feet may leave it. Stop at the command or earlier if the clear path ends.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onInstructions) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp))
            Text("  Instructions & marker placement")
        }
        GaitCard {
            Text("Research measurement", style = MaterialTheme.typography.titleSmall)
            Text("Results describe image-plane inclinations from the selected measurement method. They are not a diagnosis or a treatment recommendation. Live video is not recorded; selected videos are processed locally and are not copied into history.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun GaitHistory(sessions: List<GaitSession>, onOpen: (GaitSession) -> Unit, onBack: () -> Unit, onStatistics: (() -> Unit)? = null) {
    GaitPage("Measurement history", onBack) {
        Text("Your movement over time", style = MaterialTheme.typography.headlineSmall)
        Text("Compare sessions recorded with the same measurement method and camera conditions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        onStatistics?.let { Button(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("View daily statistics") } }
        if (sessions.isEmpty()) {
            GaitCard {
                Icon(Icons.Outlined.Analytics, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No measurements yet", style = MaterialTheme.typography.titleMedium)
                Text("Complete your first guided walk to see its numerical results here. Live video is never stored.")
            }
        }
        sessions.sortedByDescending { it.createdAt }.forEach { session ->
            Card(onClick = { onOpen(session) }, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(gaitDate(session.createdAt), style = MaterialTheme.typography.titleSmall)
                        Text(if (session.completed) "Completed session" else "Partial session", style = MaterialTheme.typography.bodyMedium)
                        Text(if (session.acquisitionSource == "VIDEO") "Recorded video" else "Live analysis", style = MaterialTheme.typography.labelSmall)
                        Text(gaitMethodLabel(session.measurementMethod), style = MaterialTheme.typography.labelSmall)
                        Text(if (session.protocolVersion >= 6) "Front ${gaitSeconds(session.frontValidDurationMs)} s · back ${gaitSeconds(session.backValidDurationMs)} s measured" else "Front ${session.frontSteps} · back ${session.backSteps} estimated steps", style = MaterialTheme.typography.bodySmall)
                        Text("${session.acceptedFrames} accepted frames · ${if (session.manualLevel) "manual" else "sensor"} levelling",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ArrowForward, "View measurement")
                }
            }
        }
    }
}

@Composable
fun GaitResults(session: GaitSession, previous: GaitSession?, onBack: () -> Unit,
                history: List<GaitSession> = emptyList(), onStatistics: (() -> Unit)? = null,
                sharedPatientName: String? = null, comparisonNotice: String? = null) {
    val availableHistory = history.ifEmpty { listOfNotNull(previous) }
    val comparable = GaitInsights.previousComparableSession(availableHistory, session)
    GaitPage("Gait results", onBack) {
        val fromVideo = session.acquisitionSource == "VIDEO"
        val poseMode = session.measurementMethod == GaitMethod.POSE
        Text(if (fromVideo) "Video analysis" else if (session.completed) {
            if (sharedPatientName == null) "Your walking measurement" else "Walking measurement"
        } else "Partial measurement", style = MaterialTheme.typography.headlineSmall)
        sharedPatientName?.let { name ->
            Text("Patient: ${name.ifBlank { "Patient" }}", style = MaterialTheme.typography.titleMedium)
            Text("Shared session report · read only", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        comparisonNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(gaitDate(session.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
        GaitSessionSummary(session, availableHistory)
        onStatistics?.let { OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("View daily statistics") } }
        GaitCard {
            Text(if (fromVideo) "Recorded video analysed" else if (session.completed) {
                if (sharedPatientName == null) "Session saved" else "Completed session"
            } else "Session stopped before completion", style = MaterialTheme.typography.titleMedium)
            if (fromVideo) {
                Text("Recording: ${gaitSeconds(session.sourceDurationMs)} seconds · ${session.videoViews.joinToString { if (it == GaitView.FRONT) "front" else "back" }} selected", style = MaterialTheme.typography.bodySmall)
                Text("Camera alignment was confirmed manually for this recording; phone sensors cannot verify its original tilt.", style = MaterialTheme.typography.bodySmall)
                if (session.sourceMirrored) Text("Mirrored recording corrected before measurement.", style = MaterialTheme.typography.bodySmall)
            }
            if (session.protocolVersion >= 6) {
                if (fromVideo) {
                    Text("Front: ${gaitSeconds(session.frontValidDurationMs)} usable seconds\nBack: ${gaitSeconds(session.backValidDurationMs)} usable seconds")
                    Text("Full two-view coverage target: at least ${session.profile.targetDurationSecondsPerView} usable seconds per view.", style = MaterialTheme.typography.bodySmall)
                } else Text("Front: ${gaitSeconds(session.frontValidDurationMs)} / ${session.profile.targetDurationSecondsPerView} usable seconds · ${session.frontCompletedPasses} passes\nBack: ${gaitSeconds(session.backValidDurationMs)} / ${session.profile.targetDurationSecondsPerView} usable seconds · ${session.backCompletedPasses} passes")
                if (fromVideo) Text("Step counts are not estimated from imported video. Seconds describe accepted intervals with visible markers and movement evidence.", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Front: ${observedStepText(session.frontSteps, session.frontStepsPartial)}\nBack: ${observedStepText(session.backSteps, session.backStepsPartial)}", style = MaterialTheme.typography.bodySmall)
                    Text("Seconds describe accepted intervals during the guided walk. Steps are estimated only while the feet are reliably visible; a partial count is not the total walked.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Front: ${session.frontSteps} / ${session.profile.targetStepsPerView} estimated steps\nBack: ${session.backSteps} / ${session.profile.targetStepsPerView} estimated steps")
            }
            val total = session.acceptedFrames + session.rejectedFrames
            val coverage = if (total > 0) (100.0 * session.acceptedFrames / total).toInt() else 0
            Text("Tracking coverage: $coverage% · ${session.acceptedFrames} accepted / $total ${if (fromVideo) "sampled video frames" else "analysed walking frames"}")
            Text(if (fromVideo) "Coverage describes usable tracking, not angular accuracy. The recording's camera tilt can affect all angles." else "Coverage describes usable tracking, not angular accuracy. ${if (session.manualLevel) "The phone was levelled manually; camera tilt can affect all angles." else "Phone orientation was checked with sensors."}",
                style = MaterialTheme.typography.bodySmall)
            if (!session.completed) Text(if (fromVideo) "Limited coverage: a full two-view measurement needs enough usable front and back footage. Available angles are shown below; missing views are not reconstructed." else "These results may contain too little walking to represent the session. Repeat the guided test before comparing changes.", style = MaterialTheme.typography.bodySmall)
        }
        if (session.samples.isEmpty()) {
            GaitCard { Text("No usable angle samples", style = MaterialTheme.typography.titleMedium); Text(if (poseMode) "Check lighting, body landmark visibility, framing and the phone level, then repeat the session." else "Check lighting, marker visibility, framing and the phone level, then repeat the session.") }
        } else {
            Text("What the angles mean", style = MaterialTheme.typography.titleMedium)
            Text("Typical inclination is the average absolute angle, so opposite tilts do not cancel. Average direction shows the signed mean. A value close to zero is not a diagnosis of normal gait.", style = MaterialTheme.typography.bodySmall)
            Text("The knee value is the inclination of the line between the two knees, not knee-joint flexion or a diagnosis of leg alignment. It changes through the step cycle. This method has no validated clinical normal range; use comparable repeat measurements to discuss changes with a clinician.", style = MaterialTheme.typography.bodySmall)
            GaitView.entries.forEach { view ->
                Text(if (view == GaitView.FRONT) "Front view" else "Back view",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                val metrics = expectedGaitMetrics(view, session.measurementMethod)
                if (session.samples.none { it.view == view }) {
                    Text("No usable samples for this view.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else metrics.forEach { metric ->
                    val summary = GaitMath.summary(session, metric, view)
                    if (summary != null) {
                        val key = GaitMetricKey(view, metric)
                        val previousForLine = GaitInsights.previousComparableSession(availableHistory, session, key)
                        val earlier = previousForLine?.let { GaitMath.summary(it, metric, view) }
                        MetricCard(metric, view, summary,
                            session.samples.filter { it.view == view }.mapNotNull { it.angles[metric] }, earlier,
                            previousForLine?.createdAt, GaitInsights.personalReference(availableHistory, session, key), session.measurementMethod)
                    }
                }
            }
        }
        GaitCard {
            Text("Comparing measurements", style = MaterialTheme.typography.titleMedium)
            Text(if (comparable != null) "Each line is compared with its previous session with enough data and matching settings. Matching settings cannot confirm identical marker placement or walking conditions."
                else "A previous session with enough data for the same line and matching profile, protocol, acquisition mode and levelling method is needed for a numerical comparison.")
            Text("A smaller angle alone does not establish improved health. Use consistent conditions and discuss persistent changes with a qualified professional.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
    }
}

@Composable
private fun MetricCard(metric: GaitMetric, view: GaitView, summary: MetricSummary, values: List<Double>, previous: MetricSummary?,
                       previousAt: Long? = null, personal: GaitPersonalReference? = null,
                       measurementMethod: GaitMethod = GaitMethod.MARKERS) {
    GaitCard {
        Text(if (measurementMethod == GaitMethod.POSE && metric == GaitMetric.PELVIS) "Hip landmarks" else metric.label, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(angle(summary.meanAbsolute), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text("  typical inclination", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
        }
        val direction = gaitDirection(metric, summary.mean)
        Text(if (measurementMethod == GaitMethod.POSE) direction.replace("pelvic marker", "hip landmark").replace("kneecap marker", "knee landmark") else direction, style = MaterialTheme.typography.bodyMedium)
        Text("Signed mean ${signedAngle(summary.mean)} · range ${signedAngle(summary.min)} to ${signedAngle(summary.max)}\n${summary.count} samples",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AngleChart(values)
        Text("Earlier → later accepted samples · dashed line = 0°", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (previous != null) {
            HorizontalDivider()
            val change = summary.meanAbsolute - previous.meanAbsolute
            Text("Typical inclination changed by ${insightSignedAngle(change)} from the previous comparable session${previousAt?.let { " on ${gaitDate(it)}" }.orEmpty()}.", style = MaterialTheme.typography.bodySmall)
        }
        personal?.let { Text("Previous recorded range: ${angle(it.minTypical)} to ${angle(it.maxTypical)} across ${it.sessionCount} comparable sessions. This is not a clinical normal range.", style = MaterialTheme.typography.bodySmall) }
        if (metric == GaitMetric.KNEES) Text(if (measurementMethod == GaitMethod.POSE) "This is the line between camera-estimated knee landmarks, not knee flexion or a joint-alignment diagnosis." else "This is the line between the kneecap markers, not knee flexion or a joint-alignment diagnosis.", style = MaterialTheme.typography.bodySmall)
        if (metric == GaitMetric.SPINE) Text("This is the C7–coccyx marker line, not the shape or curvature of the spine.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AngleChart(values: List<Double>) {
    val line = MaterialTheme.colorScheme.primary
    val reference = MaterialTheme.colorScheme.outline
    val finite = values.filter { it.isFinite() }
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "Inclination over accepted samples, in degrees" }) {
        val mid = size.height / 2
        drawLine(reference.copy(alpha = .55f), Offset(0f, mid), Offset(size.width, mid),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        if (finite.isEmpty()) return@Canvas
        val bound = max(2.0, finite.maxOf { abs(it) } * 1.15)
        fun y(v: Double) = (mid - v / bound * (size.height * .45f)).toFloat()
        if (finite.size == 1) drawCircle(line, 3.dp.toPx(), Offset(size.width / 2, y(finite.first())))
        else {
            val path = Path()
            finite.forEachIndexed { i, v ->
                val x = size.width * i / (finite.size - 1)
                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Approximate guide only. Green rings mean visibility, never verified anatomy. */
@Composable
fun MarkerGuide(view: GaitView, modifier: Modifier = Modifier, found: Set<MarkerId> = emptySet()) {
    val outline = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val detail = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .48f)
    val reference = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
    val markerEdge = MaterialTheme.colorScheme.surface
    val front = view == GaitView.FRONT
    val positions = markerGuidePositions(view)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (front) "Front view" else "Back view", style = MaterialTheme.typography.titleSmall)
        Canvas(Modifier.fillMaxWidth().height(300.dp).semantics {
            contentDescription = if (front) "Front marker guide: two shoulders, two anterior pelvic landmarks and two kneecaps"
                                 else "Back marker guide: two shoulders, two posterior pelvic landmarks, C7 and coccyx"
        }) {
            val h = (size.height - 16.dp.toPx()).coerceAtMost(size.width / GaitSilhouetteAspectRatio)
            val w = h * GaitSilhouetteAspectRatio
            val bounds = Rect((size.width - w) / 2f, (size.height - h) / 2f,
                (size.width + w) / 2f, (size.height + h) / 2f)
            fun p(pos: Offset) = Offset(bounds.left + pos.x * bounds.width, bounds.top + pos.y * bounds.height)
            drawGaitSilhouette(bounds, view, fill, outline, detail)
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            fun referenceLine(first: MarkerId, second: MarkerId) {
                val start = positions[first] ?: return
                val end = positions[second] ?: return
                drawLine(reference, p(start), p(end), 1.dp.toPx(), pathEffect = dash)
            }
            referenceLine(MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER)
            referenceLine(MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS)
            if (front) referenceLine(MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE)
            else referenceLine(MarkerId.C7, MarkerId.COCCYX)
            positions.forEach { (id, pos) ->
                val centre = p(pos)
                if (id in found) drawCircle(StatusGreen, 9.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
                val edgeHalf = 5.5.dp.toPx()
                drawRoundRect(markerEdge, centre - Offset(edgeHalf, edgeHalf), Size(edgeHalf * 2, edgeHalf * 2), CornerRadius(2.dp.toPx()))
                val half = 4.5.dp.toPx()
                drawRoundRect(markerColour(id), centre - Offset(half, half), Size(half * 2, half * 2), CornerRadius(1.5.dp.toPx()))
            }
        }
        Text("Approximate silhouette. Stand naturally; do not try to match its proportions.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MarkerLegend(markerColour(MarkerId.LEFT_SHOULDER), "Shoulders: acromion tips")
        MarkerLegend(markerColour(MarkerId.LEFT_PELVIS), if (front) "Pelvis: anterior superior iliac spines (ASIS)" else "Pelvis: posterior superior iliac spines (PSIS)")
        if (front) MarkerLegend(markerColour(MarkerId.LEFT_KNEE), "Knees: centres of the kneecaps")
        else {
            MarkerLegend(markerColour(MarkerId.C7), "C7: one marker over the seventh cervical spinous process, at the base of the neck")
            MarkerLegend(markerColour(MarkerId.COCCYX), "Tailbone (coccyx): one marker over its lower end, below the pelvic pair near the centre")
        }
        Text("Dots show approximate landmarks. Dashed lines are drawn by the app, not strips to attach.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MarkerLegend(colour: Color, text: String) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(colour,MaterialTheme.shapes.extraSmall))
        Text(text,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String)->Unit, error: Boolean, hint: String) {
    OutlinedTextField(value=value,onValueChange={onChange(it.filter(Char::isDigit).take(3))},label={Text(label)},
        modifier=Modifier.fillMaxWidth(),singleLine=true,isError=error,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
        supportingText={if(error) Text(hint)})
}

@Composable
private fun MenuCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,Modifier.size(28.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ArrowForward,null)
        }
    }
}

@Composable
private fun GaitPage(title: String, onBack: ()->Unit, content: @Composable ColumnScope.()->Unit) {
    Scaffold(topBar={AppTopBar(title=title,onBack=onBack)}) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(18.dp),content=content)
    }
}

@Composable
private fun GaitCard(content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}

private fun gaitDate(at: Long): String = SimpleDateFormat("dd MMM yyyy · HH:mm",Locale.ENGLISH).format(Date(at))
private fun angle(value: Double): String = String.format(Locale.ENGLISH,"%.1f°",if(value==0.0) 0.0 else value)
private fun signedAngle(value: Double): String = String.format(Locale.ENGLISH,"%+.1f°",if(value==0.0) 0.0 else value)
internal fun gaitDirection(metric: GaitMetric, value: Double): String {
    if(abs(value)<.05) return "No average direction at the displayed precision"
    return if(metric==GaitMetric.SPINE) {
        if(value>0) "Average lean towards your right" else "Average lean towards your left"
    } else {
        val part=when(metric) {GaitMetric.SHOULDERS->"shoulder";GaitMetric.PELVIS->"pelvic marker";GaitMetric.KNEES->"kneecap marker";else->"marker"}
        if(value>0) "Your left $part was lower on average" else "Your right $part was lower on average"
    }
}

internal fun gaitSeconds(milliseconds: Long): String = String.format(Locale.US, "%.1f", milliseconds / 1000.0)

internal fun observedStepText(count: Int, partial: Boolean): String = when {
    partial && count == 0 -> "step count unavailable"
    partial -> "at least $count observed estimated steps (partial count)"
    else -> "$count observed estimated steps"
}
