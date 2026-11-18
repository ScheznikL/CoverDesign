package com.example.coverdesign.gait

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.theme.AppTheme

/** Separate measured regions keep native camera drawing and long guidance apart. */
@Composable
internal fun GaitCaptureLayout(
    title: String,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable BoxScope.() -> Unit,
    guidance: @Composable ColumnScope.() -> Unit,
    footer: (@Composable () -> Unit)? = null
) {
    val guidanceScroll = rememberScrollState()
    LaunchedEffect(title) { guidanceScroll.scrollTo(0) }
    Column(modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("gait-stage"))
        Box(Modifier.fillMaxWidth().weight(1.15f).clipToBounds().testTag("gait-preview"), content = preview)
        Surface(Modifier.fillMaxWidth().weight(1f), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().testTag("gait-guidance").verticalScroll(guidanceScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = guidance)
        }
        HorizontalDivider()
        if (footer != null) footer()
        else TextButton(onClick = onPause, modifier = Modifier.fillMaxWidth().testTag("gait-pause")) { Text("Pause / exit") }
    }
}

@Composable
internal fun GaitPauseDialog(onContinue: () -> Unit, onSaveAndExit: () -> Unit, onDiscardAndExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("Analysis paused") },
        text = { Text("Continue this session, save the measurements collected so far and return to the menu, or exit without saving.") },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue analysis") }
                OutlinedButton(onClick = onSaveAndExit, modifier = Modifier.fillMaxWidth()) { Text("Save and exit") }
                TextButton(onClick = onDiscardAndExit, modifier = Modifier.fillMaxWidth()) { Text("Exit without saving") }
            }
        }
    )
}

@Preview(name = "Guided gait capture", showBackground = true)
@Composable
private fun GaitCaptureLayoutPreview() {
    AppTheme {
        GaitCaptureLayout(title = "Position your phone", onPause = {},
            preview = { GaitCameraOverlay(null, GaitView.FRONT, mirrored = true) },
            guidance = {
                Text("Keep the phone upright and stable.")
                Text("Attach the markers, then choose their colours from a photograph of your body.")
            })
    }
}
