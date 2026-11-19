package com.example.coverdesign.ui.components

// androidApp/ui/components/CommonComponents.kt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.coverdesign.domain.model.OrderStatus
import com.example.coverdesign.ui.theme.StatusBlue
import com.example.coverdesign.ui.theme.StatusGreen
import com.example.coverdesign.ui.theme.StatusPurple
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier              = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center
    ) {
        Text(
            text  = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        onRetry?.let {
            Spacer(Modifier.height(16.dp))
            Button(onClick = it) { Text("Retry") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title   : String,
    onBack  : (() -> Unit)? = null,
    actions : @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title           = { Text(title) },
        navigationIcon  = {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = actions
    )
}

// Collects one-time events from a SharedFlow safely inside a Composable
@Composable
fun <E> EventHandler(
    events  : SharedFlow<E>,
    onEvent : (E) -> Unit
) {
    LaunchedEffect(events) {
        events.collect { onEvent(it) }
    }
}

// ── Orders sync error banner ────────────────────────────────────────────────────
// Non-blocking banner shown over the cached order lists when the Firestore reload fails.
@Composable
fun OrdersSyncErrorBanner(
    onRetry  : () -> Unit,
    modifier : Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = MaterialTheme.shapes.medium,
        color    = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector        = Icons.Default.Warning,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text     = "Couldn't load latest orders",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

// ── Order status chip ─────────────────────────────────────────────────────────
@Composable
fun OrderStatusChip(status: OrderStatus) {
    val (label, color) = when (status) {
        OrderStatus.DRAFT        -> "Draft"       to MaterialTheme.colorScheme.onSurfaceVariant
        OrderStatus.SUBMITTED    -> "Submitted"   to StatusBlue
        OrderStatus.CONFIRMED    -> "Confirmed"   to StatusGreen
        OrderStatus.IN_PRODUCTION -> "Processing" to StatusBlue
        OrderStatus.SHIPPED      -> "Shipped"     to StatusPurple
        OrderStatus.DELIVERED    -> "Delivered"   to StatusGreen
        OrderStatus.CANCELLED    -> "Cancelled"   to MaterialTheme.colorScheme.error
    }
    Text(
        text  = label,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}
