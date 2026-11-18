package com.example.coverdesign.ui.screens.patient.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.theme.AppTheme

@Composable
fun ModelEntryScreen(onNewOrder: () -> Unit, onBack: () -> Unit) {
    ModelEntryContent(onNewOrder = onNewOrder, onBack = onBack)
}

@Composable
internal fun ModelEntryContent(onNewOrder: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { AppTopBar("3D preview", onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Preview your custom cover", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Create an order and choose your measurements and design. You can inspect the generated 3D model in the preview step before confirming the order.",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onNewOrder, modifier = Modifier.fillMaxWidth()) {
                Text("Create a new order")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ModelEntryPreview() {
    AppTheme { ModelEntryContent(onNewOrder = {}, onBack = {}) }
}
