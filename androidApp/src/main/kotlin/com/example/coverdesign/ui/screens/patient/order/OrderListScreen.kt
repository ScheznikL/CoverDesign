package com.example.coverdesign.ui.screens.patient.order

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.domain.model.OrderCategory
import com.example.coverdesign.domain.model.OrderListItem
import com.example.coverdesign.domain.model.OrderStatus
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.components.OrderStatusChip
import com.example.coverdesign.ui.components.OrdersSyncErrorBanner
import com.example.coverdesign.ui.screens.patient.home.formatDate
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.viewmodel.OrderListFilter
import com.example.coverdesign.ui.viewmodel.OrderListUiState
import com.example.coverdesign.ui.viewmodel.OrderListViewModel
import com.example.coverdesign.ui.viewmodel.OrderSyncStatus
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

@Composable
fun OrdersListScreen(
    viewModel    : OrderListViewModel = koinViewModel(),
    onOrderClick : (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OrdersListContent(
        state        = state,
        onOrderClick = onOrderClick,
        onFilterSelect = viewModel::setFilter,
        onRetry      = viewModel::refresh
    )
}

@Composable
internal fun OrdersListContent(
    state          : OrderListUiState,
    onOrderClick   : (String) -> Unit,
    onFilterSelect : (OrderListFilter) -> Unit,
    onRetry        : () -> Unit = {}
) {
    Scaffold(
        topBar = { AppTopBar(title = "My Orders") }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterRow(selected = state.filter, onSelect = onFilterSelect)

            if (state.syncStatus == OrderSyncStatus.FAILED) {
                OrdersSyncErrorBanner(
                    onRetry  = onRetry,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (state.visibleItems.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No orders yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier            = Modifier.fillMaxSize(),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.visibleItems, key = { it.orderId }) { item ->
                        OrderListCard(item = item, onClick = { onOrderClick(item.orderId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected : OrderListFilter,
    onSelect : (OrderListFilter) -> Unit
) {
    LazyRow(
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(OrderListFilter.entries) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick  = { onSelect(filter) },
                label    = { Text(filter.label) }
            )
        }
    }
}

private val OrderListFilter.label: String get() = when (this) {
    OrderListFilter.ALL            -> "All"
    OrderListFilter.COSMETIC_COVER -> "Prosthetic Cover"
    OrderListFilter.INSOLE         -> "Insole"
}

@Composable
private fun OrderListCard(item: OrderListItem, onClick: () -> Unit) {
    Card(
        onClick  = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier          = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.category.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatDate(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$${String.format(Locale.US, "%.2f", item.totalPrice)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                OrderStatusChip(status = item.status)
            }
        }
    }
}

private val OrderCategory.label: String get() = when (this) {
    OrderCategory.PROSTHETIC_COVER -> "Prosthetic Cover"
    OrderCategory.INSOLE         -> "Insole"
    else                         -> name
}

@Preview(showBackground = true)
@Composable
private fun OrdersListScreenPreview() {
    val now = System.currentTimeMillis()
    AppTheme {
        OrdersListContent(
            state = OrderListUiState(
                allItems = listOf(
                    OrderListItem("1", OrderCategory.PROSTHETIC_COVER, now,               320.0, OrderStatus.DRAFT),
                    OrderListItem("2", OrderCategory.INSOLE,         now - 86_400_000,  95.0,  OrderStatus.IN_PRODUCTION),
                    OrderListItem("3", OrderCategory.PROSTHETIC_COVER, now - 172_800_000, 415.0, OrderStatus.SHIPPED),
                    OrderListItem("4", OrderCategory.INSOLE,         now - 259_200_000, 80.0,  OrderStatus.CANCELLED),
                    OrderListItem("5", OrderCategory.PROSTHETIC_COVER, now - 345_600_000, 310.0, OrderStatus.CONFIRMED),
                )
            ),
            onOrderClick   = {},
            onFilterSelect = {}
        )
    }
}