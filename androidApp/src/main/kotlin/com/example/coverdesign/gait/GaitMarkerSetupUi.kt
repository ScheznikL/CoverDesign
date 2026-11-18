package com.example.coverdesign.gait

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.StatusGreen

/** Launches only on-body photo calibration; there is no close-up paper sampling route. */
@Composable
internal fun GaitBodyColourCaptureControls(
    view: GaitView,
    capturedColours: Map<MarkerColor, Color>,
    paletteReady: Boolean,
    paletteProblem: String?,
    onCapture: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val required = markerSetupRequiredColours(view)
    val captured = required.count { it in capturedColours }
    val ready = paletteReady && captured == required.size && paletteProblem.isNullOrBlank()
    val viewName = if (view == GaitView.FRONT) "Front view" else "Back view"
    Column(modifier.fillMaxWidth().testTag("gait-body-colour-controls"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$viewName: $captured/${required.size} required colours captured.",
            style = MaterialTheme.typography.titleSmall)
        Text("Colours are selected only from a photo with the markers attached to your body.",
            style = MaterialTheme.typography.bodySmall)
        Text("Stand at your walking distance for the countdown. After the capture sound, return to the phone and select every marker centre on the photo.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ready) Text("Ready for ${if (view == GaitView.FRONT) "front" else "back"} marker checks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        else if (!paletteProblem.isNullOrBlank()) Text(paletteProblem,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Button(onClick = onCapture, modifier = Modifier.fillMaxWidth().testTag("gait-capture-body-colours")) {
            Text("Calibrate on your body")
        }
        OutlinedButton(onClick = onReset,
            modifier = Modifier.fillMaxWidth().testTag("gait-reset-body-colours")) {
            Text("Reset all colours")
        }
    }
}

/** Display detections from the current camera frame, independently of the body/framing checks. */
@Composable
internal fun GaitMarkerDetectionCards(
    view: GaitView,
    found: Set<MarkerId>,
    modifier: Modifier = Modifier,
    capturedColours: Map<MarkerColor, Color> = emptyMap()
) {
    val groups = markerSetupRequiredColours(view)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Markers seen by the camera", style = MaterialTheme.typography.titleMedium)
        groups.forEach { group ->
            val ids = group.markerIds
            val count = ids.count { it in found }
            val complete = count == ids.size
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f))
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColourSwatch(capturedColours[group], Modifier.size(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(group.groupName, style = MaterialTheme.typography.titleMedium)
                        Text(if (group in capturedColours) "Your marker colour" else "Colour not captured",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$count/${ids.size} visible", style = MaterialTheme.typography.titleLarge)
                        if (complete) Icon(Icons.Outlined.CheckCircle,
                            contentDescription = "${group.groupName} markers visible",
                            tint = StatusGreen, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        Text("Green checks show visible markers. They do not confirm anatomical placement or full-body framing.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColourSwatch(colour: Color?, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(modifier.background(colour ?: MaterialTheme.colorScheme.surface, shape)
        .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f), shape))
}

private val MarkerColor.groupName: String get() = when (this) {
    MarkerColor.SHOULDERS -> "Shoulders"
    MarkerColor.PELVIS -> "Pelvis"
    MarkerColor.KNEES -> "Knees"
    MarkerColor.C7 -> "C7"
    MarkerColor.COCCYX -> "Coccyx"
}

internal val MarkerColor.markerIds: List<MarkerId> get() = when (this) {
    MarkerColor.SHOULDERS -> listOf(MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER)
    MarkerColor.PELVIS -> listOf(MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS)
    MarkerColor.KNEES -> listOf(MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE)
    MarkerColor.C7 -> listOf(MarkerId.C7)
    MarkerColor.COCCYX -> listOf(MarkerId.COCCYX)
}

private fun markerSetupRequiredColours(view: GaitView): Set<MarkerColor> =
    if (view == GaitView.FRONT) linkedSetOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.KNEES)
    else linkedSetOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.C7, MarkerColor.COCCYX)

@Preview
@Composable
private fun GaitBodyColourCapturePreview() {
    AppTheme {
        Surface {
            GaitBodyColourCaptureControls(GaitView.FRONT, emptyMap(), false, null, {}, {},
                Modifier.padding(16.dp))
        }
    }
}
