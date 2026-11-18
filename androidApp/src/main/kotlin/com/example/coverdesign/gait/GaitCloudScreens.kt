package com.example.coverdesign.gait

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Patient access settings; cloud processing never receives a camera image or video here. */
@Composable
internal fun GaitCloudScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GaitCloud.get(context) }
    val sync by repository.state.collectAsStateWithLifecycle()
    key(sync.userId) { PatientSharingState(repository, sync, onBack) }
}

@Composable
private fun PatientSharingState(repository: GaitCloudRepository, sync: GaitSyncState, onBack: () -> Unit) {
    var link by remember { mutableStateOf<GaitPatientLink?>(null) }
    var loading by remember { mutableStateOf(sync.userId != null) }
    var readError by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmRevoke by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository, reload) {
        link = null; readError = null
        if (sync.userId == null) { loading = false; return@LaunchedEffect }
        loading = true
        repository.myShare().catch {
            link = null; loading = false
            readError = "Unable to confirm sharing access. Check your connection and retry."
        }.collect { link = it?.takeIf { item -> item.active }; loading = false; readError = null }
    }
    LaunchedEffect(loading, reload) {
        if (loading) {
            delay(12_000)
            if (loading) {
                loading = false
                readError = "Sharing access has not been confirmed. Reconnect and retry."
            }
        }
    }
    fun share() {
        if (busy || code.isBlank()) return
        busy = true; actionMessage = null
        scope.launch {
            try {
                val name = repository.shareWithDoctor(code.trim())
                code = ""; actionMessage = "Results shared with $name."
                repository.syncNow(); reload++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionMessage = "Could not share results. Check the doctor's code and your connection, then retry." }
            finally { busy = false }
        }
    }
    fun revoke() {
        confirmRevoke = false; busy = true; actionMessage = null
        scope.launch {
            try {
                repository.revokeDoctor(); link = null
                actionMessage = "Sharing stopped."; reload++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionMessage = "Could not stop sharing. Reconnect and retry." }
            finally { busy = false }
        }
    }
    GaitCloudContent(sync.message, sync.pending, sync.lastSyncedAt, sync.userId != null,
        link, loading, readError, code, busy, actionMessage,
        onCodeChange = { code = it.take(80) }, onShare = ::share,
        onRevoke = { confirmRevoke = true }, onSync = repository::syncNow,
        onRetry = { reload++; repository.syncNow() }, onBack = onBack)
    if (confirmRevoke) AlertDialog(onDismissRequest = { confirmRevoke = false },
        title = { Text("Stop sharing results?") },
        text = { Text("Your doctor will no longer be able to open your shared results through this application.") },
        confirmButton = { TextButton(onClick = ::revoke) { Text("Stop sharing") } },
        dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Cancel") } })
}

@Composable
internal fun GaitCloudContent(
    syncMessage: String, pending: Int, lastSyncedAt: Long?, signedIn: Boolean,
    link: GaitPatientLink?, loading: Boolean, readError: String?, code: String,
    busy: Boolean, actionMessage: String?, onCodeChange: (String) -> Unit,
    onShare: () -> Unit, onRevoke: () -> Unit, onSync: () -> Unit,
    onRetry: () -> Unit, onBack: () -> Unit
) {
    Scaffold(topBar = { AppTopBar("Sharing and sync", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your measured results", style = MaterialTheme.typography.headlineSmall)
            Text("Full numerical session reports, including angle charts and saved profile settings, sync while you are signed in and connected. Photos and videos stay on your phone.",
                style = MaterialTheme.typography.bodyMedium)
            CloudStatusCard(syncMessage, pending, lastSyncedAt, signedIn && !busy, onSync)
            if (!signedIn) Text("Sign in to sync results and share them with your doctor.")
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("gait-sharing-loading"))
            readError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry, enabled = !busy) { Text("Retry sharing status") }
            }
            if (signedIn && !loading && readError == null) {
                CloudCard {
                    Text("Doctor access", style = MaterialTheme.typography.titleMedium)
                    if (link != null && link.active) {
                        Text(link.doctorDisplayName.ifBlank { "Your connected doctor" },
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("gait-connected-doctor"))
                        Text("This doctor can open your uploaded session reports, angle graphs and comparable trends.")
                        OutlinedButton(onClick = onRevoke, enabled = !busy,
                            modifier = Modifier.testTag("gait-revoke-sharing")) { Text("Stop sharing") }
                    } else {
                        Text("No doctor has access to your results. Ask your doctor for their sharing code.")
                    }
                    OutlinedTextField(value = code, onValueChange = onCodeChange, enabled = !busy,
                        label = { Text("Doctor's sharing code") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("gait-doctor-code-input"))
                    Button(onClick = onShare, enabled = !busy && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("gait-share-results")) {
                        Text(if (link == null) "Share results" else "Change connected doctor")
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            actionMessage?.let { Text(it, modifier = Modifier.testTag("gait-sharing-message")) }
            Text("Shared session reports describe measured angles and capture settings. They are not an automatic diagnosis or a medical normality score.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** All patient lists and report streams originate in the repository's server-authorized flows. */
@Composable
internal fun GaitDoctorScreen(initialPatientUid: String? = null, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GaitCloud.get(context) }
    val sync by repository.state.collectAsStateWithLifecycle()
    key(sync.userId) { DoctorResultsState(repository, sync, initialPatientUid, onBack, context) }
}

@Composable
private fun DoctorResultsState(repository: GaitCloudRepository, sync: GaitSyncState,
    initialPatientUid: String?, onBack: (() -> Unit)?, context: Context) {
    var patients by remember { mutableStateOf<List<GaitPatientLink>>(emptyList()) }
    var reports by remember { mutableStateOf<List<GaitCloudReport>>(emptyList()) }
    var patientUid by remember { mutableStateOf(initialPatientUid) }
    var code by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var patientsLoading by remember { mutableStateOf(sync.userId != null) }
    var reportsLoading by remember { mutableStateOf(false) }
    var patientsError by remember { mutableStateOf<String?>(null) }
    var reportsError by remember { mutableStateOf<String?>(null) }
    var codeError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var openSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, reload) {
        code = null; copied = false; codeError = null
        if (sync.userId == null) return@LaunchedEffect
        try { code = repository.doctorCode() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { codeError = "Unable to load your sharing code. Check your connection and doctor account, then retry." }
    }
    LaunchedEffect(repository, reload) {
        patients = emptyList(); patientsError = null
        if (sync.userId == null) { patientsLoading = false; return@LaunchedEffect }
        patientsLoading = true
        repository.patients().catch {
            patients = emptyList(); reports = emptyList(); patientsLoading = false
            patientsError = "Unable to verify your patient list. Reconnect and retry."
        }.collect { links ->
            patients = links.filter { it.active && it.doctorUid == sync.userId }
            patientsLoading = false; patientsError = null
        }
    }
    LaunchedEffect(patientsLoading, reload) {
        if (patientsLoading) {
            delay(12_000)
            if (patientsLoading) {
                patientsLoading = false
                patientsError = "Your patient list has not been confirmed. Reconnect and retry."
            }
        }
    }
    val selected = patients.firstOrNull { it.patientUid == patientUid }
    LaunchedEffect(repository, selected?.patientUid, reload) {
        reports = emptyList(); reportsError = null; reportsLoading = selected != null
        if (selected == null) return@LaunchedEffect
        repository.reports(selected.patientUid).catch {
            reports = emptyList(); reportsLoading = false
            reportsError = "These results are unavailable. Access may have changed; reconnect and retry."
        }.collect { verified ->
            reports = verified.filter { it.patientUid == selected.patientUid }
            reportsLoading = false; reportsError = null
        }
    }
    val openReport = reports.firstOrNull { it.patientUid == selected?.patientUid && it.sessionId == openSessionId }
    LaunchedEffect(selected?.patientUid, reports, reportsLoading) {
        if (openSessionId != null && (selected == null || !reportsLoading && openReport == null)) openSessionId = null
    }
    BackHandler(enabled = openSessionId != null) { openSessionId = null }
    // Rendering is gated immediately; a revoked selection cannot leave its old report visible
    // while the LaunchedEffect above clears the remembered navigation state.
    if (selected != null && openReport != null && !patientsLoading && patientsError == null && reportsError == null) {
        key(sync.userId, selected.patientUid, openReport.sessionId, openReport.detailManifest) {
            DoctorSessionState(repository, selected, openReport,
                reports.filter { it.comparisonGroupId == openReport.comparisonGroupId },
                onBack = { openSessionId = null })
        }
        return
    }
    Scaffold(topBar = { AppTopBar("Patient results", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CloudCard {
                Text("Your sharing code", style = MaterialTheme.typography.titleMedium)
                Text("Give this code to a patient. Their results become available after they choose to share with you.")
                code?.let { sharingCode ->
                    Text(sharingCode, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("gait-doctor-sharing-code"))
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Doctor sharing code", sharingCode))
                        copied = true
                    }) { Text(if (copied) "Code copied" else "Copy code") }
                }
                codeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (code == null && codeError == null && sync.userId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (sync.userId == null) Text("Sign in with your doctor account to view shared results.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Patients", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { reload++ }) { Text("Refresh") }
            }
            if (patientsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            patientsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            GaitPatientListContent(patients, selected?.patientUid, !patientsLoading && patientsError == null,
                onSelect = { openSessionId = null; patientUid = it })
            if (selected != null) {
                Text(selected.patientDisplayName.ifBlank { "Patient" }, style = MaterialTheme.typography.headlineSmall)
                if (reportsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                reportsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                GaitDoctorReportsContent(selected.patientUid, reports,
                    showEmpty = !reportsLoading && reportsError == null,
                    onOpenSession = { openSessionId = it })
            }
        }
    }
}

@Composable
private fun DoctorSessionState(repository: GaitCloudRepository, patient: GaitPatientLink,
    report: GaitCloudReport, compatibleReports: List<GaitCloudReport>, onBack: () -> Unit) {
    var session by remember { mutableStateOf<GaitSession?>(null) }
    var history by remember { mutableStateOf<List<GaitSession>>(emptyList()) }
    var verifiedHistoryManifests by remember { mutableStateOf<Map<String, GaitSessionManifest>?>(null) }
    var sessionLoading by remember { mutableStateOf(true) }
    var historyLoading by remember { mutableStateOf(true) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val expectedHistoryManifests = compatibleReports.filter { it.detailManifest != null }
        .associate { it.sessionId to requireNotNull(it.detailManifest) } +
        (report.detailManifest?.let { mapOf(report.sessionId to it) } ?: emptyMap())
    LaunchedEffect(repository, patient.patientUid, report.sessionId, report.detailManifest, reload) {
        session = null; sessionLoading = true; sessionError = null
        repository.session(patient.patientUid, report.sessionId).catch {
            session = null; sessionLoading = false
            sessionError = "The session report is unavailable. Access may have changed; reconnect and retry."
        }.collect { verified ->
            session = verified?.takeIf { it.id == report.sessionId }
            // A null emission also clears a previously verified value during authorization
            // refresh. Only an actual legacy summary has no full report to wait for.
            sessionLoading = report.detailManifest != null && session == null
            sessionError = null
        }
    }
    LaunchedEffect(repository, patient.patientUid, report.comparisonGroupId, expectedHistoryManifests, reload) {
        history = emptyList(); verifiedHistoryManifests = null; historyLoading = true; historyError = null
        repository.sessionHistory(patient.patientUid, report.comparisonGroupId).catch {
            history = emptyList(); verifiedHistoryManifests = null; historyLoading = false
            historyError = "Earlier session reports could not be verified. Reconnect and retry."
        }.collect { verified ->
            history = verified
            // The repository first emits an empty list while the grant is unconfirmed.
            // Do not show a "no previous comparison" report until all known complete
            // compatible sessions (including this one) have arrived from the server.
            val loadedIds = verified.map { it.id }.toSet()
            historyLoading = !loadedIds.containsAll(expectedHistoryManifests.keys)
            verifiedHistoryManifests = if (historyLoading) null else expectedHistoryManifests
            historyError = null
        }
    }
    LaunchedEffect(sessionLoading, historyLoading, reload) {
        loadingError = null
        if (sessionLoading || historyLoading) {
            delay(15_000)
            if (sessionLoading || historyLoading) {
                loadingError = "The shared session has not been confirmed. Reconnect and retry."
            }
        }
    }
    val incompleteHistory = compatibleReports.any { it.sessionId != report.sessionId && it.detailManifest == null }
    GaitDoctorSessionContent(patient.patientDisplayName, session, history,
        sessionLoading || historyLoading || verifiedHistoryManifests != expectedHistoryManifests,
        sessionError ?: historyError ?: loadingError,
        accessConfirmed = true, onBack = onBack, onRetry = { reload++ },
        comparisonNotice = if (incompleteHistory) "Some earlier sessions have summary-only data. Their comparisons will appear after the patient syncs the updated app." else null)
}

@Composable
internal fun GaitPatientListContent(patients: List<GaitPatientLink>, selectedUid: String?,
    showEmpty: Boolean, onSelect: (String) -> Unit) {
    if (patients.isEmpty() && showEmpty) Text("No patients are sharing results with you yet.",
        modifier = Modifier.testTag("gait-no-shared-patients"))
    patients.forEach { patient ->
        OutlinedButton(onClick = { onSelect(patient.patientUid) }, modifier = Modifier.fillMaxWidth()
            .testTag("gait-patient-${patient.patientUid}"),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selectedUid == patient.patientUid)
                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
            Text(patient.patientDisplayName.ifBlank { "Patient" })
        }
    }
}

private val cloudMetricLabels = linkedMapOf(
    "FRONT_SHOULDERS" to "Front · Shoulders", "FRONT_PELVIS" to "Front · Pelvis / hips",
    "FRONT_KNEES" to "Front · Knees", "BACK_SHOULDERS" to "Back · Shoulders",
    "BACK_PELVIS" to "Back · Pelvis / hips", "BACK_SPINE" to "Back · C7–coccyx line")

internal data class GaitCloudTrendPoint(val sessionId: String, val createdAt: Long, val angle: Double)

/** A selected patient's qualified, matching series only; a different protocol never joins it. */
internal fun gaitCloudComparableSeries(reports: List<GaitCloudReport>, patientUid: String,
    comparisonGroupId: String, metric: String): List<GaitCloudTrendPoint> = reports.asSequence()
    .filter { it.patientUid == patientUid && it.comparisonGroupId == comparisonGroupId &&
        metric in cloudMetricLabels && metric in it.qualifiedMetrics &&
        (it.measurementMethod != GaitMethod.POSE || metric != "BACK_SPINE") &&
        (it.sampleCounts[metric] ?: 0) >= 3 && it.createdAt > 0 }
    .mapNotNull { report -> report.typicalInclinations[metric]?.takeIf { it.isFinite() && it in 0.0..90.0 }
        ?.let { GaitCloudTrendPoint(report.sessionId, report.createdAt, it) } }
    .sortedWith(compareBy<GaitCloudTrendPoint> { it.createdAt }.thenBy { it.sessionId })
    .distinctBy { it.sessionId }.toList()

@Composable
internal fun GaitDoctorReportsContent(patientUid: String, reports: List<GaitCloudReport>, showEmpty: Boolean = true,
    onOpenSession: ((String) -> Unit)? = null) {
    val ownReports = reports.filter { it.patientUid == patientUid }.sortedByDescending { it.createdAt }
    val groups = ownReports.groupBy { it.comparisonGroupId }
    var selectedGroupId by remember(patientUid) { mutableStateOf<String?>(null) }
    var selectedMetric by remember(patientUid) { mutableStateOf("FRONT_SHOULDERS") }
    var visibleSessionCount by remember(patientUid) { mutableIntStateOf(20) }
    val groupId = selectedGroupId?.takeIf { it in groups } ?: ownReports.firstOrNull()?.comparisonGroupId
    if (ownReports.isEmpty()) {
        if (showEmpty) Text("No uploaded measurements yet.", modifier = Modifier.testTag("gait-no-cloud-reports"))
        return
    }
    CloudCard {
        Text("Comparable trends", style = MaterialTheme.typography.titleMedium)
        Text("Choose matching recordings and a body line. Only results with enough usable data are included; an angle change alone is not a health score.",
            style = MaterialTheme.typography.bodySmall)
        groups.forEach { (id, members) ->
            val reference = members.first()
            FilterChip(selected = groupId == id, onClick = { selectedGroupId = id },
                label = { Text("${cloudMethod(reference)} · ${if (reference.acquisitionSource == "VIDEO") "video" else "live"} · ${members.size} sessions · ${cloudDate(members.last().createdAt)}") },
                modifier = Modifier.fillMaxWidth().testTag("gait-cloud-group-$id"))
        }
        val reference = groupId?.let { groups[it]?.firstOrNull() }
        reference?.let { Text(if (it.manualLevel) "Manual camera alignment" else "Sensor camera alignment",
            style = MaterialTheme.typography.bodySmall) }
        val supported = cloudMetricLabels.filterKeys { key -> reference?.measurementMethod != GaitMethod.POSE || key != "BACK_SPINE" }
        val metric = selectedMetric.takeIf { it in supported } ?: supported.keys.first()
        supported.forEach { (key, label) ->
            FilterChip(selected = metric == key, onClick = { selectedMetric = key }, label = { Text(label) },
                modifier = Modifier.fillMaxWidth().testTag("gait-cloud-metric-$key"))
        }
        val points = gaitCloudComparableSeries(ownReports, patientUid, requireNotNull(groupId), metric)
        GaitCloudTrendChart(points)
    }
    Text("Saved sessions", style = MaterialTheme.typography.titleLarge)
    ownReports.take(visibleSessionCount).forEach { report ->
        CloudCard {
            Text(cloudDate(report.createdAt), style = MaterialTheme.typography.titleMedium)
            Text("${cloudMethod(report)} · ${if (report.acquisitionSource == "VIDEO") "video" else "live"} · ${if (report.completed) "completed" else "partial result"}",
                style = MaterialTheme.typography.bodySmall)
            GaitView.entries.forEach { view ->
                val visible = cloudMetricLabels.filterKeys { key -> key.startsWith("${view.name}_") &&
                    (report.measurementMethod != GaitMethod.POSE || key != "BACK_SPINE") }
                Text(if (view == GaitView.FRONT) "Front" else "Back", style = MaterialTheme.typography.titleSmall)
                visible.forEach { (key, fullLabel) ->
                    val value = report.typicalInclinations[key]?.takeIf { it.isFinite() && it in 0.0..90.0 }
                    val label = if (key.endsWith("PELVIS") && report.measurementMethod == GaitMethod.POSE)
                        "Hip landmarks" else fullLabel.substringAfter(" · ").replace(" / hips", "")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label)
                        Text(value?.let(::cloudAngle) ?: "Not measured", modifier = Modifier.testTag("gait-cloud-value-${report.sessionId}-$key"))
                    }
                    if (value != null) Text("${report.sampleCounts[key] ?: 0} samples · ${if (key in report.qualifiedMetrics) "enough data for trends" else "limited coverage"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Usable time: front ${cloudSeconds(report.frontValidDurationMs)} s · back ${cloudSeconds(report.backValidDurationMs)} s",
                style = MaterialTheme.typography.bodySmall)
            Text("Frames: ${report.acceptedFrames} accepted · ${report.rejectedFrames} discarded",
                style = MaterialTheme.typography.bodySmall)
            onOpenSession?.let { open ->
                OutlinedButton(onClick = { open(report.sessionId) }, modifier = Modifier.fillMaxWidth()
                    .testTag("gait-open-session-${report.sessionId}")) { Text("Open session report") }
            }
        }
    }
    if (ownReports.size > visibleSessionCount) OutlinedButton(onClick = { visibleSessionCount += 20 },
        modifier = Modifier.fillMaxWidth()) { Text("Show more sessions") }
}

/** The doctor's detail view uses the patient's report renderer and its original angle samples. */
@Composable
internal fun GaitDoctorSessionContent(
    patientName: String,
    session: GaitSession?,
    history: List<GaitSession>,
    loading: Boolean,
    error: String?,
    accessConfirmed: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStatistics: (() -> Unit)? = null,
    comparisonNotice: String? = null
) {
    if (accessConfirmed && !loading && error == null && session != null) {
        GaitResults(session, GaitInsights.previousComparableSession(history, session), onBack,
            history = history, onStatistics = onStatistics, sharedPatientName = patientName,
            comparisonNotice = comparisonNotice)
        return
    }
    Scaffold(topBar = { AppTopBar("Session report", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                !accessConfirmed -> Text("This shared session is no longer available.",
                    modifier = Modifier.testTag("gait-session-access-unavailable"))
                error != null -> {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) { Text("Retry session report") }
                }
                loading -> {
                    Text("Loading the shared session report…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> {
                    Text("Full session report is not available yet.", style = MaterialTheme.typography.titleMedium)
                    Text("Ask the patient to open the updated app and sync this session. Summary-only results cannot reproduce its graphs.")
                    OutlinedButton(onClick = onRetry) { Text("Retry session report") }
                }
            }
        }
    }
}

@Composable
private fun GaitCloudTrendChart(points: List<GaitCloudTrendPoint>) {
    if (points.isEmpty()) { Text("Not enough usable data for a trend in this selection."); return }
    val colour = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val bound = points.maxOf { it.angle }.coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(140.dp).testTag("gait-cloud-trend")
        .semantics { contentDescription = "${points.size} comparable results, ${cloudAngle(points.first().angle)} to ${cloudAngle(points.last().angle)}" }) {
        val inset = 10.dp.toPx()
        val w = (size.width - 2 * inset).coerceAtLeast(1f)
        val h = (size.height - 2 * inset).coerceAtLeast(1f)
        val start = points.first().createdAt
        val end = points.last().createdAt
        fun point(value: GaitCloudTrendPoint) = Offset(
            if (end == start) size.width / 2 else inset + ((value.createdAt - start).toDouble() / (end - start) * w).toFloat(),
            inset + h - (value.angle / bound * h).toFloat())
        drawLine(grid, Offset(inset, inset + h), Offset(inset + w, inset + h), 1.dp.toPx())
        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { index, value -> point(value).let { if (index == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, colour, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        points.forEach { drawCircle(colour, 3.dp.toPx(), point(it)) }
    }
    Text("${points.size} comparable sessions · ${cloudDate(points.first().createdAt)} → ${cloudDate(points.last().createdAt)}",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CloudStatusCard(message: String, pending: Int, lastSyncedAt: Long?, enabled: Boolean, onSync: () -> Unit) {
    CloudCard {
        Text("Cloud sync", style = MaterialTheme.typography.titleMedium)
        Text(message, modifier = Modifier.testTag("gait-sync-status"))
        Text("$pending results waiting to sync", style = MaterialTheme.typography.bodySmall)
        lastSyncedAt?.let { Text("Last successful sync: ${cloudDate(it)}", style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = onSync, enabled = enabled, modifier = Modifier.testTag("gait-sync-now")) { Text("Sync now / retry") }
    }
}

@Composable
private fun CloudCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun cloudDate(time: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun cloudAngle(value: Double) = String.format(Locale.US, "%.1f°", value)
private fun cloudSeconds(value: Long) = String.format(Locale.US, "%.1f", value / 1000.0)
private fun cloudMethod(report: GaitCloudReport) = if (report.measurementMethod == GaitMethod.POSE) "Without markers" else "With markers"

@Preview(name = "Patient result sharing", showBackground = true)
@Composable
private fun GaitCloudContentPreview() {
    AppTheme {
        GaitCloudContent("Results are up to date.", 0, null, true, null, false, null,
            "", false, null, {}, {}, {}, {}, {}, {})
    }
}
