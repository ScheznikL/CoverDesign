package com.example.coverdesign.gait

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.example.coverdesign.ui.theme.AppTheme

@Composable
internal fun GaitLightingControls(
    selected: MarkerLightingTolerance,
    onSelect: (MarkerLightingTolerance) -> Unit
) {
    Column {
        Text("Lighting tolerance", style = MaterialTheme.typography.titleSmall)
        FilterChip(selected = selected == MarkerLightingTolerance.VARIABLE,
            onClick = { onSelect(MarkerLightingTolerance.VARIABLE) },
            label = { Text("Changing light (recommended)") },
            modifier = Modifier.fillMaxWidth().testTag("gait-lighting-variable"))
        FilterChip(selected = selected == MarkerLightingTolerance.STANDARD,
            onClick = { onSelect(MarkerLightingTolerance.STANDARD) }, label = { Text("Standard") },
            modifier = Modifier.fillMaxWidth().testTag("gait-lighting-standard"))
        Text(if (selected == MarkerLightingTolerance.VARIABLE)
            "Allows moderate changes in brightness, saturation and shade as you walk. Glare and very deep shadow can still hide a marker."
        else "Uses narrower colour limits. Try Changing light if a marker disappears as you move through light and shadow.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(name = "Lighting tolerance", showBackground = true)
@Composable
private fun GaitLightingControlsPreview() {
    AppTheme { GaitLightingControls(MarkerLightingTolerance.VARIABLE, onSelect = {}) }
}
