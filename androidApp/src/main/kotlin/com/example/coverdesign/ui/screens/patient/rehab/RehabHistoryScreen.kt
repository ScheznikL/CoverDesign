package com.example.coverdesign.ui.screens.patient.rehab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.domain.model.CopPoint
import com.example.coverdesign.domain.model.FootPressureMap
import com.example.coverdesign.domain.model.GaitMetricKey
import com.example.coverdesign.domain.model.GaitReport
import com.example.coverdesign.domain.model.GaitSession
import com.example.coverdesign.ui.components.EventHandler
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.AccentAmber
import com.example.coverdesign.ui.theme.AccentCoral
import com.example.coverdesign.ui.theme.ChartEndRed
import com.example.coverdesign.ui.theme.ChartStartGreen
import com.example.coverdesign.ui.theme.NeutralMid
import com.example.coverdesign.ui.theme.NeutralWhite
import com.example.coverdesign.ui.theme.StatusBlue
import com.example.coverdesign.ui.theme.StatusGreen
import com.example.coverdesign.ui.theme.StatusPurple
import com.example.coverdesign.ui.viewmodel.RehabAction
import com.example.coverdesign.ui.viewmodel.RehabMockData
import com.example.coverdesign.ui.viewmodel.RehabUiEvent
import com.example.coverdesign.ui.viewmodel.RehabUiState
import com.example.coverdesign.ui.viewmodel.RehabViewModel
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun RehabHistoryScreen(
    viewModel: RehabViewModel = koinViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    EventHandler(viewModel.events) { event ->
        when (event) {
            is RehabUiEvent.NavigateBack -> onBack()
        }
    }

    RehabHistoryContent(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RehabHistoryContent(
    state: RehabUiState,
    onAction: (RehabAction) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rehabilitation History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                horizontal = 16.dp, vertical = 12.dp
            )
        ) {
            // ── Score trend chart ─────────────────────────────────────────
            item {
                ScoreTrendCard(sessions = state.sessions)
            }

            // ── Sessions list ─────────────────────────────────────────────
            item {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (state.sessions.isEmpty()) {
                item {
                    Text(
                        "No sessions recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                items(state.sessions) { session ->
                    SessionCard(
                        session = session,
                        hasInsoles = state.hasInsoles,
                        expanded = state.expandedSessionId == session.id,
                        onToggle = { onAction(RehabAction.ToggleSession(session.id)) }
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ── Score trend card ──────────────────────────────────────────────────────────
@Composable
private fun ScoreTrendCard(sessions: List<GaitSession>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Inclination Trend", style = MaterialTheme.typography.titleSmall)
            Text(
                "Mean body-line tilt per session · lower is better",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // Legend
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                LegendDot(StatusBlue, "Shoulders")
                LegendDot(StatusGreen, "Pelvis")
                LegendDot(StatusPurple, "Knees")
            }

            ScoreTrendChart(
                sessions = sessions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(shape = RoundedCornerShape(50), color = color, modifier = Modifier.size(8.dp)) {}
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScoreTrendChart(sessions: List<GaitSession>, modifier: Modifier = Modifier) {
    val postureColor = StatusBlue
    val balanceColor = StatusGreen
    val symmetryColor = StatusPurple
    val gridColor = NeutralMid.copy(alpha = 0.2f)
    val axisColor = NeutralMid.copy(alpha = 0.5f)
    NeutralMid.copy(alpha = 0.7f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padLeft = 32.dp.toPx()
        val padBottom = 28.dp.toPx()
        val padTop = 8.dp.toPx()
        val chartW = w - padLeft
        val chartH = h - padBottom - padTop

        // Reversed: oldest session on left, most recent on right
        val orderedSessions = sessions.reversed()
        val n = orderedSessions.size.coerceAtLeast(1)

        // Fixed degree scale so sessions stay visually comparable.
        val maxDeg = 15f

        // Grid lines at 5°, 10°, 15°
        listOf(5f, 10f, 15f).forEach { deg ->
            val y = padTop + chartH * (1f - deg / maxDeg)
            drawLine(gridColor, Offset(padLeft, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }

        // X axis
        drawLine(
            axisColor,
            Offset(padLeft, padTop + chartH),
            Offset(w, padTop + chartH),
            strokeWidth = 1.dp.toPx()
        )

        if (orderedSessions.isEmpty()) return@Canvas

        fun xPos(i: Int) =
            padLeft + if (n == 1) chartW / 2f else i.toFloat() / (n - 1).toFloat() * chartW

        fun yPos(deg: Float) = padTop + chartH * (1f - (deg / maxDeg).coerceIn(0f, 1f))

        // Draw a line per front body-line metric (degrees).
        data class Metric(val color: Color, val key: GaitMetricKey)

        val metrics = listOf(
            Metric(postureColor, GaitMetricKey.FRONT_SHOULDERS),
            Metric(balanceColor, GaitMetricKey.FRONT_PELVIS),
            Metric(symmetryColor, GaitMetricKey.FRONT_KNEES)
        )

        metrics.forEach { metric ->
            // Sessions that did not measure this metric are skipped (no invented points).
            val points = orderedSessions.mapIndexedNotNull { i, session ->
                session.report.typicalInclinations[metric.key]?.let { i to it }
            }
            val path = Path()
            points.forEachIndexed { idx, (i, deg) ->
                val x = xPos(i)
                val y = yPos(deg)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                metric.color,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Dots
            points.forEach { (i, deg) ->
                drawCircle(metric.color, radius = 4.dp.toPx(), center = Offset(xPos(i), yPos(deg)))
                drawCircle(NeutralWhite, radius = 2.dp.toPx(), center = Offset(xPos(i), yPos(deg)))
            }
        }
    }
}

// ── Session card ──────────────────────────────────────────────────────────────
@Composable
private fun SessionCard(
    session: GaitSession,
    hasInsoles: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateStr = SimpleDateFormat(
        "MMM d, yyyy",
        LocalLocale.current.platformLocale
    ).format(Date(session.timestamp))

    Card(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(dateStr, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${session.durationSeconds / 60} min session · ${session.pressureData?.stepCount ?: 0} steps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // Front body-line inclinations (degrees)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AngleBadge("Shoulders", GaitMetricKey.FRONT_SHOULDERS, StatusBlue, session.report)
                AngleBadge("Pelvis", GaitMetricKey.FRONT_PELVIS, StatusGreen, session.report)
                AngleBadge("Knees", GaitMetricKey.FRONT_KNEES, StatusPurple, session.report)
            }
            Spacer(Modifier.height(4.dp))
            // Back body-line inclinations (degrees)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AngleBadge("Shoulders", GaitMetricKey.BACK_SHOULDERS, AccentAmber, session.report)
                AngleBadge("Pelvis", GaitMetricKey.BACK_PELVIS, AccentCoral, session.report)
                AngleBadge("Spine", GaitMetricKey.BACK_SPINE, StatusPurple, session.report)
            }

            // Expanded detail
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // COP stabilogram
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
                            if (hasInsoles && session.pressureData != null) {
                                StabilogramChart(
                                    copPoints = session.pressureData!!.copTrajectory,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                )
                            } else {
                                NoInsolesPlaceholder(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                )
                            }
                        }

                        // Body-line inclination
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Body-Line Inclination",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            InclinationBars(
                                report = session.report,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                            )
                        }
                    }

                    // Foot load distribution
                    if (hasInsoles && session.pressureData != null) {
                        Spacer(Modifier.height(10.dp))
                        FootLoadSection(
                            left = session.pressureData!!.leftFoot,
                            right = session.pressureData!!.rightFoot
                        )
                    }
                }
            }
        }
    }
}

/** Mean-absolute inclination for one body line, in degrees. Dimmed when it lacks enough data to trend. */
@Composable
private fun AngleBadge(label: String, key: GaitMetricKey, color: Color, report: GaitReport) {
    val deg = report.typicalInclinations[key] ?: return
    val alpha = if (key in report.qualifiedMetrics) 1f else 0.5f
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f * alpha)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "%.1f°".format(deg),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = color.copy(alpha = alpha)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f * alpha)
            )
        }
    }
}

// ── Full chart composables ────────────────────────────────────────────────────

@Composable
private fun StabilogramChart(copPoints: List<CopPoint>, modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val axisColor = NeutralMid.copy(alpha = 0.35f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        drawRect(color = surfaceVariant, size = size)

        val axisStroke = 1.dp.toPx()
        drawLine(axisColor, Offset(0f, cy), Offset(w, cy), strokeWidth = axisStroke)
        drawLine(axisColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = axisStroke)

        if (copPoints.size < 2) return@Canvas

        val scaleX = (w * 0.85f) / 70f
        val scaleY = (h * 0.85f) / 50f

        val path = Path()
        copPoints.forEachIndexed { index, pt ->
            val px = cx + pt.x * scaleX
            val py = cy - pt.y * scaleY
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = primaryColor.copy(alpha = 0.85f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val first = copPoints.first()
        val last = copPoints.last()
        drawCircle(
            ChartStartGreen,
            radius = 4.dp.toPx(),
            center = Offset(cx + first.x * scaleX, cy - first.y * scaleY)
        )
        drawCircle(
            ChartEndRed,
            radius = 4.dp.toPx(),
            center = Offset(cx + last.x * scaleX, cy - last.y * scaleY)
        )
    }
}

/** Horizontal bars of the session's mean-absolute inclination per measured body line (degrees). */
@Composable
private fun InclinationBars(report: GaitReport, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxDeg = 15f

    // Fixed order so bars stay stable across sessions; only measured metrics are shown.
    val measured = GaitMetricKey.entries.mapNotNull { key ->
        report.typicalInclinations[key]?.let { key to it }
    }

    if (measured.isEmpty()) {
        Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = trackColor) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No measured body lines",
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor
                )
            }
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        measured.forEach { (key, deg) ->
            val qualified = key in report.qualifiedMetrics
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(key.label, style = MaterialTheme.typography.labelSmall, color = labelColor)
                    Text(
                        "%.1f°".format(deg),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (qualified) barColor else labelColor
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(trackColor)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((deg / maxDeg).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(barColor.copy(alpha = if (qualified) 0.8f else 0.4f))
                    )
                }
            }
        }
    }
}

@Composable
private fun NoInsolesPlaceholder(modifier: Modifier = Modifier) {
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
            Text(
                "No smart insoles connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun FootLoadSection(left: FootPressureMap, right: FootPressureMap) {
    val zones = listOf(
        Triple("Heel", left.heel, right.heel),
        Triple("Midfoot", left.midfoot, right.midfoot),
        Triple("Forefoot", left.forefoot, right.forefoot),
        Triple("Toes", left.toes, right.toes),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Foot Load Distribution", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Left",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.5f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "Right",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.5f),
                textAlign = TextAlign.Center
            )
        }
        zones.forEach { (zoneName, lv, rv) -> FootZoneRow(zoneName, lv, rv) }
    }
}

@Composable
private fun FootZoneRow(label: String, leftVal: Float, rightVal: Float) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val barColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "${(leftVal * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.End
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(leftVal)
                    .fillMaxHeight()
                    .background(barColor.copy(alpha = 0.7f))
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(rightVal)
                    .fillMaxHeight()
                    .background(barColor.copy(alpha = 0.7f))
            )
        }
        Text(
            "${(rightVal * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.width(28.dp)
        )
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────
@Preview(showBackground = true)
@Composable
private fun RehabHistoryPreview() {
    AppTheme {
        RehabHistoryContent(
            state = RehabUiState(
                sessions = RehabMockData.generateSessions(),
                hasInsoles = true
            ),
            onAction = {},
            onBack = {}
        )
    }
}
