package com.example.coverdesign.gait

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.StatusGreen

/** Markerless guidance never exposes palettes, photo capture or unsupported anatomical points. */
@Composable
internal fun GaitPoseCaptureGuidance(
    view: GaitView,
    found: Set<MarkerId>,
    initialSetup: Boolean,
    bodyVisible: Boolean,
    showPointChecks: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().testTag("gait-pose-guidance"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Live without markers", style = MaterialTheme.typography.titleMedium)
        Text("The camera estimates shoulder, hip and knee landmarks. No colour setup or attached papers are needed.",
            style = MaterialTheme.typography.bodySmall)
        Text("Five inclination lines: shoulders, hips and knees from the front; shoulders and hips from the back.",
            style = MaterialTheme.typography.bodySmall)
        if (initialSetup && !bodyVisible) Text("For setup, fit your whole body and feet inside the frame. Stand naturally with your arms relaxed and clear of your hips.",
            style = MaterialTheme.typography.bodySmall)
        else Text(if (view == GaitView.FRONT) "Keep both shoulders, hips and knees visible while facing the camera."
            else "Keep both shoulders and hips visible while facing away from the camera.",
            style = MaterialTheme.typography.bodySmall)
        Text("Turn only at the spoken instruction. If your direction is unclear, stop and face directly towards or away from the camera until tracking is ready.",
            style = MaterialTheme.typography.bodySmall)
        if (showPointChecks) {
            val required = requiredMarkers(view, GaitMethod.POSE)
            Text("${required.count { it in found }}/${required.size} body landmarks tracked",
                style = MaterialTheme.typography.titleSmall)
            val pairs = listOf("Shoulders" to listOf(MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER),
                "Hip landmarks" to listOf(MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS)) +
                if (view == GaitView.FRONT) listOf("Knees" to listOf(MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE)) else emptyList()
            pairs.forEach { (label, ids) ->
                val count = ids.count { it in found }
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text("$count/${ids.size} tracked", style = MaterialTheme.typography.labelLarge,
                            color = if (count == ids.size) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("These are camera-estimated body landmarks. Keep the same camera setup and measurement method when comparing sessions.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview
@Composable
private fun GaitPoseCaptureGuidancePreview() {
    AppTheme {
        Surface {
            GaitPoseCaptureGuidance(GaitView.BACK,
                setOf(MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER, MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS),
                initialSetup = true, bodyVisible = true, showPointChecks = true, Modifier.padding(16.dp))
        }
    }
}
