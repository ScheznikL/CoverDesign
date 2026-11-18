package com.example.coverdesign.ui.screens.patient.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.coverdesign.gait.GaitView
import com.example.coverdesign.gait.gaitMethodLabel
import com.example.coverdesign.ui.theme.AppTheme
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

@Composable
internal fun GaitHomeSummary(
    state: GaitHomeUiState,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val latest = state.latest
        if (state.loading) {
            Text("Loading saved gait measurements…", style = MaterialTheme.typography.bodyMedium)
        } else if (latest == null && state.error != null) {
            GaitHomeCard {
                Text("Gait measurements unavailable", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onHistory) { Text("Open gait history") }
            }
        } else if (latest == null) {
            GaitHomeCard {
                Text("No gait measurements yet", style = MaterialTheme.typography.titleMedium)
                Text("Save a gait analysis to see your measured angles and daily graphs here.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start gait analysis") }
            }
        } else {
            GaitHomeCard {
                Text("Latest saved analysis", style = MaterialTheme.typography.titleMedium)
                Text(homeDateTime(latest.createdAt), style = MaterialTheme.typography.bodyMedium)
                Text(gaitMethodLabel(latest.method), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("home-gait-method"))
                Text("${if (latest.acquisitionSource == "VIDEO") "Recorded video" else "Live analysis"} · ${if (latest.completed) "Completed session" else "Partial session"}",
                    style = MaterialTheme.typography.bodySmall)
                val measured = latest.lines.count { it.typicalAngle != null }
                val qualified = latest.lines.count { it.qualifiesForTrend }
                Text("$measured of ${latest.lines.size} body lines measured · $qualified with enough data for daily comparison",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("home-gait-coverage"))
                Text("Typical inclination is the average absolute angle in degrees. Graphs use only recordings with matching method and settings.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Open gait history") }
            }
            GaitView.entries.forEach { view ->
                Text(if (view == GaitView.FRONT) "Front view" else "Back view", style = MaterialTheme.typography.titleSmall)
                latest.lines.filter { it.key.view == view }.forEach { line -> GaitHomeAngleCard(line) }
            }
            Text("Graphs show up to seven measured days. Gaps are not filled with invented values. A recorded angle is not a health score.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GaitHomeAngleCard(line: GaitHomeLine) {
    val prefix = "home-gait-${line.key.view.name}-${line.key.metric.name}"
    GaitHomeCard(Modifier.testTag(prefix)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(line.key.metric.label, style = MaterialTheme.typography.titleSmall)
            Text(line.typicalAngle?.let(::homeAngle) ?: "Not measured",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("$prefix-angle"))
        }
        if (line.typicalAngle != null && !line.qualifiesForTrend) Text(
            "Limited data in the latest session — not included in its daily graph.", style = MaterialTheme.typography.bodySmall)
        if (line.days.isEmpty()) Text("No comparable daily values yet", style = MaterialTheme.typography.bodySmall)
        else GaitHomeChart(line, Modifier.testTag("$prefix-chart"))
    }
}

@Composable
private fun GaitHomeChart(line: GaitHomeLine, modifier: Modifier = Modifier) {
    val colour = MaterialTheme.colorScheme.primary
    val axisColour = MaterialTheme.colorScheme.outline
    val days = line.days
    val first = days.first().date.toEpochDay()
    val last = days.last().date.toEpochDay()
    val maximum = max(1.0, days.maxOf { it.angle } * 1.15)
    Row(Modifier.fillMaxWidth()) {
        Text("0–${homeAngle(maximum)}", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text("Daily typical inclination", style = MaterialTheme.typography.labelSmall)
    }
    Canvas(modifier.fillMaxWidth().height(84.dp).semantics {
        contentDescription = "${if (line.key.view == GaitView.FRONT) "Front" else "Back"} ${line.key.metric.label} daily typical inclination. " +
            days.joinToString("; ") { "${homeDate(it.date)}: ${homeAngle(it.angle)} from ${it.sessionCount} sessions" }
    }) {
        val inset = 6.dp.toPx()
        val width = (size.width - 2 * inset).coerceAtLeast(1f)
        val height = (size.height - 2 * inset).coerceAtLeast(1f)
        fun point(day: GaitHomeDay): Offset = Offset(
            if (last == first) size.width / 2 else inset + ((day.date.toEpochDay() - first).toDouble() / (last - first) * width).toFloat(),
            inset + height - (day.angle / maximum * height).toFloat())
        drawLine(axisColour.copy(alpha = .5f), Offset(inset, inset + height), Offset(inset + width, inset + height), 1.dp.toPx())
        if (days.size > 1) {
            val path = Path()
            days.forEachIndexed { index, day ->
                val point = point(day)
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, colour, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        days.forEach { drawCircle(colour, 3.dp.toPx(), point(it)) }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(homeDate(days.first().date), style = MaterialTheme.typography.labelSmall)
        if (days.size > 1) {
            Spacer(Modifier.weight(1f))
            Text(homeDate(days.last().date), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GaitHomeCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun homeAngle(value: Double): String = String.format(Locale.US, "%.1f°", value)
private fun homeDate(value: java.time.LocalDate): String = value.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH))
private fun homeDateTime(at: Long): String = Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.ENGLISH))

@Preview(showBackground = true)
@Composable
private fun EmptyGaitHomePreview() {
    AppTheme { GaitHomeSummary(GaitHomeUiState(), onStart = {}, onHistory = {}, modifier = Modifier.padding(16.dp)) }
}
