package com.example.coverdesign.gait

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Daily changes describe recorded body angles, with methods and acquisition groups kept separate. */
@Composable
internal fun GaitStatistics(sessions: List<GaitSession>, onOpen: (GaitSession) -> Unit, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val groups = remember(sessions) { GaitInsights.groups(sessions) }
    val defaultGroup = remember(groups) {
        groups.firstOrNull { group -> group.sessions.any { session ->
            GaitInsights.metricKeys(session).any { GaitInsights.qualifies(session, it) }
        } } ?: groups.firstOrNull()
    }
    var selectedGroupKey by remember { mutableStateOf<GaitComparisonKey?>(null) }
    var selectedMetric by remember { mutableStateOf<GaitMetricKey?>(null) }
    var choosingGroup by remember { mutableStateOf(false) }
    val group = groups.firstOrNull { it.key == selectedGroupKey } ?: defaultGroup
    val metricKeys = group?.let { GaitInsights.metricKeys(it.reference) } ?: GaitInsights.metricKeys
    val key = selectedMetric?.takeIf { it in metricKeys } ?: group?.let { selected ->
        metricKeys.firstOrNull { metric -> selected.sessions.any { GaitInsights.qualifies(it, metric) } }
    } ?: metricKeys.first()
    val trend = remember(sessions, group?.key, key, zone) {
        group?.let { GaitInsights.dailyTrend(sessions, it.reference, key, zone) }
    }
    val byId = remember(groups) { groups.flatMap { it.sessions }.associateBy { it.id } }

    Scaffold(topBar = { AppTopBar("Daily statistics", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your recorded angles over time", style = MaterialTheme.typography.headlineSmall)
            Text("Change in a recorded angle is not a health score.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Days use the saved analysis date in this phone's time zone. Imported recordings are grouped by analysis day.", style = MaterialTheme.typography.bodySmall)
            if (group == null) {
                StatisticsCard {
                    Text("No daily measurements yet", style = MaterialTheme.typography.titleMedium)
                    Text("Complete a live analysis or analyse a video to start your daily history. Numerical results are saved on this phone for your account.")
                }
            } else {
                StatisticsCard {
                    Text("Compare matching recordings", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { choosingGroup = true }, modifier = Modifier.fillMaxWidth().testTag("gait-statistics-group-selector")) {
                        Text(groupTitle(group.reference))
                    }
                    DropdownMenu(expanded = choosingGroup, onDismissRequest = { choosingGroup = false }) {
                        groups.forEach { candidate ->
                            DropdownMenuItem(text = {
                                Column {
                                    Text(groupTitle(candidate.reference))
                                    Text(profileDescription(candidate.reference), style = MaterialTheme.typography.bodySmall)
                                }
                            }, onClick = {
                                selectedGroupKey = candidate.key; selectedMetric = null; choosingGroup = false
                            }, modifier = Modifier.testTag("gait-statistics-group-${candidate.reference.id}"))
                        }
                    }
                    Text(profileDescription(group.reference), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("gait-statistics-group-details"))
                    Text("Coloured markers and pose landmarks are kept in separate groups, along with different recording modes, saved settings and levelling methods. Matching settings cannot confirm identical placement, visibility or lighting.", style = MaterialTheme.typography.bodySmall)
                }
                StatisticsCard {
                    Text("View and body line", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GaitView.entries.forEach { view ->
                            FilterChip(selected = key.view == view, onClick = {
                                selectedMetric = metricKeys.firstOrNull { it.view == view && it.metric == key.metric }
                                    ?: metricKeys.first { it.view == view }
                            }, label = { Text(if (view == GaitView.FRONT) "Front" else "Back") },
                                modifier = Modifier.testTag("gait-statistics-view-${view.name}"))
                        }
                    }
                    metricKeys.filter { it.view == key.view }.forEach { metric ->
                        FilterChip(selected = key == metric, onClick = { selectedMetric = metric },
                            label = { Text(metric.metric.label) }, modifier = Modifier.fillMaxWidth()
                                .testTag("gait-statistics-metric-${metric.view.name}-${metric.metric.name}"))
                    }
                    Text("Typical inclination is the average absolute tilt of this body line. Each qualifying session has equal weight in its day, regardless of recording length.", style = MaterialTheme.typography.bodySmall)
                    if (group.reference.measurementMethod == GaitMethod.POSE) Text(
                        "Pose landmarks do not provide a C7–coccyx measurement.", style = MaterialTheme.typography.bodySmall)
                }
                if (trend == null || trend.days.isEmpty()) {
                    StatisticsCard {
                        Text("Not enough usable data for this selection", style = MaterialTheme.typography.titleMedium)
                        Text("A daily point needs enough usable time or observed steps for this view and at least three angle samples. Shorter recordings and missing views remain available in session history.")
                        Text("${trend?.compatibleSessionCount ?: 0} matching sessions · ${trend?.excludedSessionCount ?: 0} without enough usable data for this view", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val latest = trend.days.last()
                    StatisticsCard {
                        Text("Latest measured day · ${statisticsDate(latest.date)}", style = MaterialTheme.typography.titleMedium)
                        Text(statisticsAngle(latest.typicalAngle), style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("gait-statistics-latest-angle"))
                        Text("Typical inclination · average absolute angle", style = MaterialTheme.typography.labelSmall)
                        if (latest.deltaFromPreviousDay == null) Text("Add another measured day to see a change.")
                        else Text("${statisticsDelta(latest.deltaFromPreviousDay)} from the previous measured day")
                        Text("${statisticsDelta(latest.deltaFromBaseline)} from the first measured day", style = MaterialTheme.typography.bodySmall)
                        Text("Signed mean ${statisticsDelta(latest.signedMean)}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("gait-statistics-latest-signed"))
                        Text(gaitDirection(key.metric, latest.signedMean), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("gait-statistics-latest-direction"))
                        Text("${trend.includedSessionCount} qualifying sessions across ${trend.days.size} measured ${if (trend.days.size == 1) "day" else "days"} · ${trend.excludedSessionCount} matching sessions excluded for limited data", style = MaterialTheme.typography.bodySmall)
                    }
                    StatisticsCard {
                        Text("${if (key.view == GaitView.FRONT) "Front" else "Back"} · ${key.metric.label}", style = MaterialTheme.typography.titleMedium)
                        Text("Daily mean absolute inclination", style = MaterialTheme.typography.bodySmall)
                        DailyAngleChart(trend.days)
                        Text("Points are measured days. Lines connect recorded values; no values are filled for unmeasured days.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("By day", style = MaterialTheme.typography.titleLarge)
                    trend.days.asReversed().forEach { day ->
                        DailyAngleCard(day, key, byId, onOpen)
                    }
                    Text("Day-to-day changes compare typical inclination (the absolute angle); the signed mean describes average direction. Positive changes mean a larger typical angle, negative changes a smaller one. A smaller angle alone does not establish improved health. Repeat measurements with the same measurement method and camera setup.", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun DailyAngleCard(day: GaitDailyPoint, key: GaitMetricKey, byId: Map<String, GaitSession>, onOpen: (GaitSession) -> Unit) {
    var expanded by remember(day.date) { mutableStateOf(false) }
    StatisticsCard(Modifier.testTag("gait-statistics-day-${day.date}")) {
        Text(statisticsDate(day.date), style = MaterialTheme.typography.titleMedium)
        Text(statisticsAngle(day.typicalAngle), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("gait-statistics-day-angle-${day.date}"))
        Text("Typical inclination · average absolute angle", style = MaterialTheme.typography.labelSmall)
        Text("${day.sessionCount} ${if (day.sessionCount == 1) "session" else "sessions"} · session values ${statisticsAngle(day.minSessionAngle)} to ${statisticsAngle(day.maxSessionAngle)}", style = MaterialTheme.typography.bodySmall)
        Text(day.deltaFromPreviousDay?.let { "${statisticsDelta(it)} from previous measured day" } ?: "First measured day",
            modifier = Modifier.testTag("gait-statistics-day-delta-${day.date}"))
        Text("${statisticsDelta(day.deltaFromBaseline)} from first measured day", style = MaterialTheme.typography.bodySmall)
        Text("Signed mean ${statisticsDelta(day.signedMean)}", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("gait-statistics-day-signed-${day.date}"))
        Text(gaitDirection(key.metric, day.signedMean), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("gait-statistics-day-direction-${day.date}"))
        val records = day.sessionIds.mapNotNull(byId::get)
        TextButton(onClick = {
            if (records.size == 1) onOpen(records.single()) else expanded = !expanded
        }, modifier = Modifier.testTag("gait-statistics-day-open-${day.date}")) {
            Text(if (records.size == 1) "Open session" else if (expanded) "Hide sessions" else "Show ${records.size} sessions")
        }
        if (expanded) records.sortedByDescending { it.createdAt }.forEach { session ->
            TextButton(onClick = { onOpen(session) }, modifier = Modifier.testTag("gait-statistics-session-${session.id}")) {
                Text("Open ${Instant.ofEpochMilli(session.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))} · ${gaitMethodLabel(session.measurementMethod)} · ${if (session.acquisitionSource == "VIDEO") "video" else "live"}")
            }
        }
    }
}

@Composable
private fun DailyAngleChart(days: List<GaitDailyPoint>) {
    val colour = MaterialTheme.colorScheme.primary
    val reference = MaterialTheme.colorScheme.outline
    val first = days.first().date.toEpochDay()
    val last = days.last().date.toEpochDay()
    val bound = max(1.0, days.maxOf { it.typicalAngle } * 1.15)
    Row(Modifier.fillMaxWidth()) {
        Text("Degrees", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text("0 to ${statisticsAngle(bound)}", style = MaterialTheme.typography.labelSmall)
    }
    Canvas(Modifier.fillMaxWidth().height(150.dp).testTag("gait-statistics-chart").semantics {
        contentDescription = "Daily typical inclination in degrees, plotted by calendar date. " +
            days.joinToString("; ") { "${statisticsDate(it.date)}: ${statisticsAngle(it.typicalAngle)}" }
    }) {
        val inset = 7.dp.toPx()
        val plotWidth = (size.width - 2 * inset).coerceAtLeast(1f)
        val plotHeight = (size.height - 2 * inset).coerceAtLeast(1f)
        fun point(day: GaitDailyPoint): Offset {
            val x = if (last == first) size.width / 2 else
                inset + ((day.date.toEpochDay() - first).toDouble() / (last - first) * plotWidth).toFloat()
            val y = inset + plotHeight - (day.typicalAngle / bound * plotHeight).toFloat()
            return Offset(x, y)
        }
        drawLine(reference.copy(alpha = .45f), Offset(inset, inset + plotHeight),
            Offset(inset + plotWidth, inset + plotHeight), 1.dp.toPx())
        if (days.size > 1) {
            val path = Path()
            days.forEachIndexed { index, day ->
                val p = point(day)
                if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, colour, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        days.forEach { drawCircle(colour, 3.5.dp.toPx(), point(it)) }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(statisticsDate(days.first().date), style = MaterialTheme.typography.labelSmall)
        if (days.size > 1) {
            Spacer(Modifier.weight(1f))
            Text(statisticsDate(days.last().date), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatisticsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

private fun groupTitle(reference: GaitSession) =
    "${gaitMethodLabel(reference.measurementMethod)} · ${if (reference.acquisitionSource == "VIDEO") "Recorded video" else "Live analysis"} · ${if (reference.manualLevel) "manual alignment" else "sensor alignment"} · protocol ${reference.protocolVersion}"

private fun profileDescription(session: GaitSession): String = with(session.profile) {
    "$heightCm cm · $weightKg kg · age $age · $sex\n" + if (session.protocolVersion >= 6)
        "$passDurationSeconds s passes · $targetDurationSecondsPerView s target per view"
    else "$passSteps steps per pass · $targetStepsPerView steps target per view"
}

private fun statisticsDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))
private fun statisticsAngle(value: Double) = String.format(Locale.US, "%.1f°", if (abs(value) < .05) 0.0 else value)
private fun statisticsDelta(value: Double) = if (abs(value) < .05) "0.0°" else String.format(Locale.US, "%+.1f°", value)

@Preview(name = "Empty daily statistics", showBackground = true)
@Composable
private fun GaitStatisticsPreview() {
    AppTheme { GaitStatistics(emptyList(), onOpen = {}, onBack = {}) }
}
