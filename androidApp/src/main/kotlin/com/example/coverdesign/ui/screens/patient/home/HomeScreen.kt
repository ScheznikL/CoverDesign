package com.example.coverdesign.ui.screens.patient.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.R
import com.example.coverdesign.domain.model.BodyPosition
import com.example.coverdesign.domain.model.CopPoint
import com.example.coverdesign.domain.model.GaitSession
import com.example.coverdesign.domain.model.OrderCategory
import com.example.coverdesign.domain.model.OrderListItem
import com.example.coverdesign.domain.model.OrderStatus
import com.example.coverdesign.ui.components.EventHandler
import com.example.coverdesign.ui.components.OrderStatusChip
import com.example.coverdesign.ui.components.OrdersSyncErrorBanner
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.ChartEndRed
import com.example.coverdesign.ui.theme.ChartStartGreen
import com.example.coverdesign.ui.theme.NeutralMid
import com.example.coverdesign.ui.theme.PatientActiveTeal
import com.example.coverdesign.ui.theme.ScrimDark
import com.example.coverdesign.ui.theme.StatusBlue
import com.example.coverdesign.ui.theme.StatusBlueBg
import com.example.coverdesign.ui.theme.StatusGreen
import com.example.coverdesign.ui.theme.StatusGreenBg
import com.example.coverdesign.ui.theme.StatusPurple
import com.example.coverdesign.ui.theme.StatusPurpleBg
import com.example.coverdesign.ui.viewmodel.HomeAction
import com.example.coverdesign.ui.viewmodel.HomeUiEvent
import com.example.coverdesign.ui.viewmodel.HomeUiState
import com.example.coverdesign.ui.viewmodel.HomeViewModel
import com.example.coverdesign.ui.viewmodel.OrderSyncStatus
import com.example.coverdesign.ui.viewmodel.RehabMockData
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun HomeScreen(
    viewModel: HomeViewModel = koinViewModel(),
    onNewOrder: () -> Unit,
    on3DVisualize: () -> Unit,
    onOrderClick: (String) -> Unit,
    onSeeAllOrders: () -> Unit,
    onOpenRehabHistory: () -> Unit,
    onOpenRecommendations: () -> Unit = {},
    onOrderInsoles: () -> Unit = {},
    onStartRehabScan: () -> Unit = {},
    onGaitAnalysis: () -> Unit = {},
    onRecordVideo: () -> Unit = onStartRehabScan,
    onGaitHistory: () -> Unit = onGaitAnalysis
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gaitState = rememberGaitHomeState()

    EventHandler(viewModel.events) { event ->
        when (event) {
            is HomeUiEvent.NavigateToNewOrder    -> onNewOrder()
            is HomeUiEvent.NavigateTo3D          -> on3DVisualize()
            is HomeUiEvent.NavigateToAllOrders   -> onSeeAllOrders()
            is HomeUiEvent.NavigateToOrder       -> onOrderClick(event.orderId)
            is HomeUiEvent.NavigateToRehabHistory    -> onOpenRehabHistory()
            is HomeUiEvent.NavigateToRecommendations -> onOpenRecommendations()
            is HomeUiEvent.NavigateToInsoleOrder     -> onOrderInsoles()
            is HomeUiEvent.NavigateToSensorCamera    -> onStartRehabScan()
        }
    }

    HomeContent(
        state = state, onAction = viewModel::onAction,
        onGaitAnalysis = onGaitAnalysis, onRecordVideo = onRecordVideo,
        gaitState = gaitState, onGaitHistory = onGaitHistory
    )
}

@Composable
internal fun HomeContent(
    state: HomeUiState, onAction: (HomeAction) -> Unit,
    onGaitAnalysis: () -> Unit = {},
    onRecordVideo: () -> Unit = { onAction(HomeAction.StartRehabScan) },
    gaitState: GaitHomeUiState = GaitHomeUiState(),
    onGaitHistory: () -> Unit = onGaitAnalysis
) {
    // Bounds of the top-bar info icon in root coordinates — drives the spotlight cutout.
    var infoIconBounds by remember { mutableStateOf<Rect?>(null) }
    var showTour by remember { mutableStateOf(false) }

    val openTour = {
        showTour = true
        onAction(HomeAction.DismissWelcome)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            HomeTopBar(
                onInfoClick = openTour,
                onInfoPositioned = { infoIconBounds = it }
            )
        }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            // ── New order CTA ─────────────────────────────────────────────
            NewOrderBanner(
                onClick = { onAction(HomeAction.NewOrder) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // ── Tools section ─────────────────────────────────────────────
            HomeSectionHeader(title = "Tools")
            ToolCard(
                icon = Icons.Outlined.DirectionsWalk,
                label = "Gait analysis",
                tint = StatusBlue,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                onClick = onGaitAnalysis
            )
            Spacer(Modifier.height(8.dp))
            ToolCard(
                icon = Icons.Outlined.AssignmentTurnedIn,
                label = "View Recommendations",
                tint = PatientActiveTeal,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                onClick = { onAction(HomeAction.OpenRecommendations) })
            Spacer(Modifier.height(8.dp))
            ToolCard(
                icon = Icons.Outlined.Videocam,
                label = "Record video",
                tint = StatusPurple,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                onClick = onRecordVideo
            )

            Spacer(Modifier.height(24.dp))

            // Local gait angles are separate from the author's rehabilitation service data.
            HomeSectionHeader(
                title = "My gait measurements",
                action = "View History",
                onAction = onGaitHistory,
                actionTag = "home-gait-view-history"
            )
            GaitHomeSummary(
                state = gaitState,
                onStart = onGaitAnalysis,
                onHistory = onGaitHistory,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(24.dp))

            // ── My Rehabilitation ─────────────────────────────────────────
            HomeSectionHeader(
                title = "My Rehabilitation",
                action = "View History",
                onAction = { onAction(HomeAction.OpenRehabHistory) },
                actionTag = "home-server-rehab-history"
            )
            if (state.isLoadingRehab) {
                Text(
                    "Loading rehabilitation data…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .testTag("home-server-rehab-loading")
                )
            } else {
                RehabSummaryCard(
                    latestSession = state.rehabSessions.firstOrNull(),
                    onOrderInsoles = { onAction(HomeAction.OrderInsoles) },
                    onStartScan = { onAction(HomeAction.StartRehabScan) },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("home-server-rehab-summary")
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Recent orders ─────────────────────────────────────────────
            HomeSectionHeader(
                title = "Recent Orders",
                action = "See all",
                onAction = { onAction(HomeAction.SeeAllOrders) })

            if (state.ordersSyncStatus == OrderSyncStatus.FAILED) {
                OrdersSyncErrorBanner(
                    onRetry  = { onAction(HomeAction.RetryOrderSync) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (state.recentOrders.isEmpty()) {
                EmptyOrdersCard(
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                state.recentOrders.forEach { item ->
                    RecentOrderCard(
                        item    = item,
                        onClick = { onAction(HomeAction.OpenOrder(item.orderId)) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Contact section ───────────────────────────────────────────
            HomeSectionHeader(title = "Contact Us")
            ContactCard(
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }

        // ── First-run welcome coach-mark ──────────────────────────────────
        val bounds = infoIconBounds
        if (state.isNewUser && bounds != null) {
            WelcomeSpotlight(
                targetBounds = bounds,
                onStartTour = openTour,
                onDismiss = { onAction(HomeAction.DismissWelcome) }
            )
        }

        if (showTour) {
            QuickTourDialog(onDismiss = { showTour = false })
        }
    }
}

// ── Rehabilitation summary card ───────────────────────────────────────────────
@Composable
private fun RehabSummaryCard(
    latestSession: GaitSession?,
    onOrderInsoles: () -> Unit,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Score chips — only when a session exists ──────────────────
            if (latestSession != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScoreChip(
                        "Posture", latestSession.gaitAnalysis.postureScore, Modifier.weight(1f)
                    )
                    ScoreChip(
                        "Balance", latestSession.gaitAnalysis.balanceScore, Modifier.weight(1f)
                    )
                    ScoreChip(
                        "Symmetry", latestSession.gaitAnalysis.symmetryScore, Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
            }

            // ── Mini charts — each panel reflects its own server data ─────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left: stabilogram (COP trajectory) from insole pressure data
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "COP Trajectory",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    val pressureData = latestSession?.pressureData
                    if (pressureData != null) {
                        StabilogramMiniChart(
                            copPoints = pressureData.copTrajectory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    } else {
                        NoInsolesMiniCard(
                            onOrderInsoles = onOrderInsoles,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    }
                }

                // Right: body deviation from the scanner (pose) data
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Body Deviation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    if (latestSession != null) {
                        DeviationMiniChart(
                            series = latestSession.gaitAnalysis.positionSeries,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    } else {
                        MakeAScanMiniCard(
                            onStartScan = onStartScan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreChip(label: String, score: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                "${score.toInt()}%",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// COP trajectory on a 2-D plane — lissajous ellipse in primary color
@Composable
private fun StabilogramMiniChart(copPoints: List<CopPoint>, modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val axisColor = NeutralMid.copy(alpha = 0.35f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        drawRect(color = surfaceVariant, size = size)

        // axis lines
        val axisStroke = 1.dp.toPx()
        drawLine(axisColor, Offset(0f, cy), Offset(w, cy), strokeWidth = axisStroke)
        drawLine(axisColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = axisStroke)

        if (copPoints.size < 2) return@Canvas

        // scale: ±35mm → width, ±25mm → height
        val scaleX = w / 70f
        val scaleY = h / 50f

        val path = Path()
        copPoints.forEachIndexed { index, pt ->
            val px = cx + pt.x * scaleX
            val py = cy - pt.y * scaleY   // flip Y so +y = up
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = primaryColor.copy(alpha = 0.8f),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // start dot (green) and end dot (red)
        val first = copPoints.first()
        val last = copPoints.last()
        drawCircle(
            ChartStartGreen,
            radius = 3.dp.toPx(),
            center = Offset(cx + first.x * scaleX, cy - first.y * scaleY)
        )
        drawCircle(
            ChartEndRed,
            radius = 3.dp.toPx(),
            center = Offset(cx + last.x * scaleX, cy - last.y * scaleY)
        )
    }
}

// Lateral (red) and AP (blue) body deviation over time
@Composable
private fun DeviationMiniChart(series: List<BodyPosition>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        drawRect(color = surfaceColor, size = size)

        // zero line
        drawLine(
            color = ChartStartGreen,
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
        )

        if (series.size < 2) return@Canvas

        val maxDev = 12f  // cm range
        val scaleY = (h / 2f) / maxDev
        val scaleX = w / (series.size - 1).toFloat().coerceAtLeast(1f)

        // lateral deviation (X, red)
        val pathX = Path()
        series.forEachIndexed { i, pt ->
            val px = i * scaleX
            val py = midY - pt.deviationX * scaleY
            if (i == 0) pathX.moveTo(px, py) else pathX.lineTo(px, py)
        }
        drawPath(
            pathX,
            errorColor.copy(alpha = 0.85f),
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // AP deviation (Y, blue/primary)
        val pathY = Path()
        series.forEachIndexed { i, pt ->
            val px = i * scaleX
            val py = midY - pt.deviationY * scaleY
            if (i == 0) pathY.moveTo(px, py) else pathY.lineTo(px, py)
        }
        drawPath(
            pathY,
            primary.copy(alpha = 0.85f),
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun NoInsolesMiniCard(onOrderInsoles: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.BluetoothDisabled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "No insoles connected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            TextButton(
                onClick = onOrderInsoles,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Order Insoles", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// Placeholder shown in the right panel when no scanner session has been recorded yet.
@Composable
private fun MakeAScanMiniCard(onStartScan: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "No scan recorded",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            TextButton(
                onClick = onStartScan,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Make a scan", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    onInfoClick: () -> Unit,
    onInfoPositioned: (Rect) -> Unit = {}
) {
    TopAppBar(
        title = {
        Column {
            Text(
                text = stringResource(R.string.title_custom),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.slogan),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }, actions = {
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier.onGloballyPositioned { onInfoPositioned(it.boundsInRoot()) }
        ) {
            Icon(Icons.Outlined.Info, contentDescription = "Info")
        }
    }, colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background
    )
    )
}

// ── New order banner ──────────────────────────────────────────────────────────
@Composable
private fun NewOrderBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Create new order",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = "Start designing your cover",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
            }

            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

// ── Section header ────────────────────────────────────────────────────────────
@Composable
private fun HomeSectionHeader(
    title: String, action: String? = null, onAction: (() -> Unit)? = null,
    actionTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = actionTag?.let { Modifier.testTag(it) } ?: Modifier,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── Tool card ─────────────────────────────────────────────────────────────────
@Composable
private fun ToolCard(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(28.dp)
                )
            }
            Text(
                text = label, style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ── Recent order card ─────────────────────────────────────────────────────────
@Composable
private fun RecentOrderCard(
    item: OrderListItem, onClick: () -> Unit, modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.category.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    formatDate(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OrderStatusChip(status = item.status)
        }
    }
}

private val OrderCategory.label: String get() = when (this) {
    OrderCategory.PROSTHETIC_COVER -> "Prosthetic Cover"
    OrderCategory.INSOLE         -> "Insole"
    else                         -> name
}

// ── Empty orders ──────────────────────────────────────────────────────────────
@Composable
private fun EmptyOrdersCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Outlined.ShoppingBag,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "No orders yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Create your first custom prosthetic cover",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Contact card ──────────────────────────────────────────────────────────────
@Composable
private fun ContactCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.consultation_question) + stringResource(R.string.our_team_is_always_ready_to_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            ContactRow(
                icon = Icons.Outlined.Phone,
                tint = StatusGreen,
                label = stringResource(R.string.phone),
                value = stringResource(R.string.contact_number),
                bgColor = StatusGreenBg
            )
            ContactRow(
                icon = Icons.Outlined.Email,
                tint = StatusBlue,
                label = "Email",
                value = stringResource(R.string.info_email),
                bgColor = StatusBlueBg
            )
            ContactRow(
                icon = Icons.Outlined.Chat,
                tint = StatusPurple,
                label = stringResource(R.string.online_chat),
                value = stringResource(R.string.available_24_7),
                bgColor = StatusPurpleBg
            )
        }
    }
}

@Composable
private fun ContactRow(
    icon: ImageVector, tint: Color, label: String, value: String, bgColor: Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = bgColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Welcome spotlight coach-mark ────────────────────────────────────────────────
// Dims the whole screen except a circular cutout around the top-bar info icon and
// shows a tooltip bubble inviting the new user to tap it for a quick tour.
@Composable
private fun WelcomeSpotlight(
    targetBounds: Rect,
    onStartTour: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val scrim = ScrimDark.copy(alpha = 0.82f)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val spotlightRadius = with(density) {
        maxOf(targetBounds.width, targetBounds.height) / 2f + 10.dp.toPx()
    }
    val caretWidth = 16.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim with a transparent circular hole punched around the icon.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            drawRect(color = scrim)
            drawCircle(
                color = scrim,
                radius = spotlightRadius,
                center = targetBounds.center,
                blendMode = BlendMode.Clear
            )
        }

        // Tap anywhere on the scrim to dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        )

        // Transparent hit target over the icon → starts the tour.
        Box(
            modifier = Modifier
                .offset(
                    x = with(density) { targetBounds.left.toDp() },
                    y = with(density) { targetBounds.top.toDp() }
                )
                .size(
                    width = with(density) { targetBounds.width.toDp() },
                    height = with(density) { targetBounds.height.toDp() }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onStartTour() }
        )

        // Upward caret pointing at the icon.
        Canvas(
            modifier = Modifier
                .offset(
                    x = with(density) { targetBounds.center.x.toDp() } - caretWidth / 2,
                    y = with(density) { targetBounds.bottom.toDp() } + 6.dp
                )
                .size(width = caretWidth, height = 8.dp)
        ) {
            val caret = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(caret, color = surfaceColor)
        }

        // Tooltip bubble below the icon.
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = with(density) { targetBounds.bottom.toDp() } + 13.dp,
                    end = 12.dp,
                    start = 24.dp
                )
                .widthIn(max = 300.dp),
            shape = MaterialTheme.shapes.large,
            color = surfaceColor,
            shadowElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Welcome aboard!",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tap the info icon here any time to take a quick tour of the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Skip", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onStartTour) {
                        Text("Start tour", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ── Quick tour ──────────────────────────────────────────────────────────────────
private data class TourStep(
    val icon: ImageVector,
    val title: String,
    val description: String
)

@Composable
private fun QuickTourDialog(onDismiss: () -> Unit) {
    val steps = remember {
        listOf(
            TourStep(
                Icons.Default.Add,
                "Create an order",
                "Tap \"Create new order\" to design a custom prosthetic cover or insole from scratch."
            ),
            TourStep(
                Icons.Outlined.AssignmentTurnedIn,
                "Your recommendations",
                "Open \"View Recommendations\" to see suggestions tailored to you."
            ),
            TourStep(
                Icons.Outlined.ShoppingBag,
                "Track your orders",
                "\"Recent Orders\" shows your latest orders — tap \"See all\" for the full history."
            ),
            TourStep(
                Icons.Outlined.Info,
                "Need help?",
                "Reach our team any time from the \"Contact Us\" section at the bottom of the screen."
            )
        )
    }
    var index by remember { mutableStateOf(0) }
    val step = steps[index]
    val isLast = index == steps.lastIndex

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = step.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(12.dp)
                            .size(28.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(step.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    step.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        steps.indices.forEach { i ->
                            val selected = i == index
                            Surface(
                                modifier = Modifier.size(if (selected) 8.dp else 6.dp),
                                shape = CircleShape,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            ) {}
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isLast) {
                            TextButton(onClick = onDismiss) {
                                Text("Skip", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Button(onClick = { if (isLast) onDismiss() else index++ }) {
                            Text(
                                if (isLast) "Done" else "Next",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Date formatter ────────────────────────────────────────────────────────────
fun formatDate(epochMillis: Long): String {
    val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AppTheme {
        HomeContent(
            state = HomeUiState(
                recentOrders = listOf(
                    OrderListItem(
                        orderId = "1",
                        category = OrderCategory.PROSTHETIC_COVER,
                        createdAt = System.currentTimeMillis(),
                        totalPrice = 250.0,
                        status = OrderStatus.SUBMITTED
                    ),
                    OrderListItem(
                        orderId = "2",
                        category = OrderCategory.INSOLE,
                        createdAt = System.currentTimeMillis() - 86400000,
                        totalPrice = 85.0,
                        status = OrderStatus.DELIVERED
                    )
                ),
                rehabSessions = emptyList(),
                hasInsoles = true
            ),
            onAction = {}
        )
    }
}

@Preview(showBackground = true, name = "New user welcome")
@Composable
private fun HomeScreenNewUserPreview() {
    AppTheme {
        HomeContent(
            state = HomeUiState(
                recentOrders = emptyList(),
                rehabSessions = emptyList(),
                hasInsoles = false,
                isNewUser = true
            ),
            onAction = {}
        )
    }
}
