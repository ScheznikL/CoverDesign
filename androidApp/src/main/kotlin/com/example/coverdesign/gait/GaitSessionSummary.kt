package com.example.coverdesign.gait

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/** Overall reading of supported body lines, without population cutoffs or a health score. */
@Composable
internal fun GaitSessionSummary(session: GaitSession, sessions: List<GaitSession>) {
    val overview = remember(session) { GaitInsights.overview(session) }
    var expanded by remember(session.id) { mutableStateOf(false) }
    val changes = remember(session, sessions) {
        overview.metrics.filter { it.qualifiesForTrend }.mapNotNull { measured ->
            GaitInsights.previousComparableSession(sessions, session, measured.key)?.let { previous ->
                val earlier = GaitMath.summary(previous, measured.key.metric, measured.key.view) ?: return@let null
                measured.key to (measured.summary.meanAbsolute - earlier.meanAbsolute)
            }
        }
    }
    val reference = remember(session, sessions) {
        overview.metrics.filter { it.qualifiesForTrend }.sortedByDescending { it.summary.meanAbsolute }
            .firstNotNullOfOrNull { measured ->
                GaitInsights.personalReference(sessions, session, measured.key)?.let { measured to it }
            }
    }
    Card(Modifier.fillMaxWidth().testTag("gait-overall-result")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Overall result", style = MaterialTheme.typography.titleLarge)
            Text(gaitMethodLabel(session.measurementMethod), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("gait-measurement-method"))
            Text(when {
                overview.metrics.isEmpty() -> "No usable angle data"
                overview.fullCoverage -> "Coverage available for both views"
                overview.qualifiedViews == setOf(GaitView.FRONT) -> "Front-view coverage available"
                overview.qualifiedViews == setOf(GaitView.BACK) -> "Back-view coverage available"
                overview.qualifiedMetricCount > 0 -> if (session.measurementMethod == GaitMethod.POSE)
                    "Some body lines have enough coverage for comparison" else "Some marker lines have enough coverage for comparison"
                else -> "Limited data — repeat the measurement before comparing changes"
            }, style = MaterialTheme.typography.titleMedium)
            val lineType = if (session.measurementMethod == GaitMethod.POSE) "pose" else "marker"
            Text("${overview.availableMetricCount} of ${overview.expectedMetricCount} $lineType lines measured · ${overview.qualifiedMetricCount} eligible for trends",
                style = MaterialTheme.typography.bodySmall)
            if (session.measurementMethod == GaitMethod.POSE) Text(
                "This method does not measure C7 or the coccyx.", style = MaterialTheme.typography.bodySmall)
            overview.largest?.let { largest ->
                Text("Largest recorded typical inclination: ${insightLabel(largest.key)} ${insightAngle(largest.summary.meanAbsolute)}.")
                if (overview.availableMetricCount > 1) Text(
                    "Available typical inclinations range from ${insightAngle(requireNotNull(overview.minimumTypical))} to ${insightAngle(requireNotNull(overview.maximumTypical))}.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("A reading of 1° alone does not establish abnormal gait. This app has no validated clinical normal range.",
                style = MaterialTheme.typography.bodySmall)
            changes.maxByOrNull { abs(it.second) }?.let { (key, delta) ->
                Text("Largest numeric change from earlier comparable sessions: ${insightLabel(key)} ${insightSignedAngle(delta)}.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (reference != null) {
                val (measured, prior) = reference
                Text("Your previous recorded range", style = MaterialTheme.typography.titleSmall)
                Text("${insightLabel(measured.key)}: now ${insightAngle(measured.summary.meanAbsolute)}; previously ${insightAngle(prior.minTypical)}–${insightAngle(prior.maxTypical)} across ${prior.sessionCount} comparable saved sessions.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Observed values only, not a medical normal range.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("gait-overall-explanation")) {
                Text(if (expanded) "Hide explanation" else "How to interpret these numbers")
            }
            if (expanded) {
                val factors = if (session.measurementMethod == GaitMethod.POSE)
                    "Natural movement, camera alignment and landmark detection affect the result."
                else "Natural movement, camera alignment and marker placement affect the result."
                Text("$factors Measurement error for this app has not been established. A smaller or larger recorded angle does not by itself establish improved or worsened health.",
                    style = MaterialTheme.typography.bodySmall)
                if (reference == null) Text(if (session.measurementMethod == GaitMethod.POSE)
                    "A personal recorded range is shown after at least three earlier comparable sessions with enough data for the same body line."
                    else "A personal recorded range is shown after at least three earlier comparable sessions with enough data for the same marker line.",
                    style = MaterialTheme.typography.bodySmall)
                else Text("This is an observed personal range, not a medical normal range or an estimate of measurement error.",
                    style = MaterialTheme.typography.bodySmall)
                val repetition = if (session.measurementMethod == GaitMethod.POSE)
                    "Repeat different walks with the same camera setup and settings."
                else "Repeat different walks with the same marker locations, camera and settings."
                Text("$repetition Analysing the same video again does not add independent evidence.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun insightLabel(key: GaitMetricKey): String =
    "${if (key.view == GaitView.FRONT) "Front" else "Back"} · ${key.metric.label.lowercase(Locale.US)}"

internal fun insightAngle(value: Double) = String.format(Locale.US, "%.1f°", value)
internal fun insightSignedAngle(value: Double): String {
    // Display precision is not a clinical significance threshold; avoid a misleading signed zero.
    return if (abs(value) < .05) "0.0°" else String.format(Locale.US, "%+.1f°", value)
}
