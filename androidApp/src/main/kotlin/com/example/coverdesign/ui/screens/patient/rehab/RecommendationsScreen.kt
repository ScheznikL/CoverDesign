package com.example.coverdesign.ui.screens.patient.rehab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.domain.model.DoctorRecommendation
import com.example.coverdesign.domain.model.FootPressureMap
import com.example.coverdesign.domain.model.GaitAnalysisResult
import com.example.coverdesign.domain.model.RecommendationTag
import com.example.coverdesign.ui.components.EventHandler
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.NeutralBackground
import com.example.coverdesign.ui.theme.NeutralText
import com.example.coverdesign.ui.theme.NeutralTextSecondary
import com.example.coverdesign.ui.theme.NeutralTextTertiary
import com.example.coverdesign.ui.theme.PatientActiveTeal
import com.example.coverdesign.ui.theme.PatientActiveTealBorder
import com.example.coverdesign.ui.theme.PatientActiveTealLight
import com.example.coverdesign.ui.theme.PatientPendingAlert
import com.example.coverdesign.ui.theme.PatientPendingAmber
import com.example.coverdesign.ui.theme.PatientPendingAmberLight
import com.example.coverdesign.ui.theme.PatientStableViolet
import com.example.coverdesign.ui.theme.PatientStableVioletBorder
import com.example.coverdesign.ui.theme.PatientStableVioletLight
import com.example.coverdesign.ui.viewmodel.RecommendationsAction
import com.example.coverdesign.ui.viewmodel.RecommendationsUiEvent
import com.example.coverdesign.ui.viewmodel.RecommendationsUiState
import com.example.coverdesign.ui.viewmodel.RecommendationsViewModel
import com.example.coverdesign.ui.viewmodel.RehabMockData
import org.koin.compose.viewmodel.koinViewModel
import java.util.concurrent.TimeUnit

@Composable
fun RecommendationsScreen(
    viewModel: RecommendationsViewModel = koinViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    EventHandler(viewModel.events) { event ->
        when (event) {
            RecommendationsUiEvent.NavigateBack -> onBack()
        }
    }

    RecommendationsContent(state = state, onAction = viewModel::onAction)
}

@Composable
internal fun RecommendationsContent(
    state: RecommendationsUiState,
    onAction: (RecommendationsAction) -> Unit
) {
    Scaffold(
        topBar = {
            RecommendationsTopBar(onBack = { onAction(RecommendationsAction.Back) })
        },
        containerColor = NeutralBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            PatientInfoRow(
                patientName = state.patientName,
                hasUnread = state.recommendations.isNotEmpty()
            )

            val analysis = state.latestSession?.gaitAnalysis
            val prevAnalysis = state.previousSession?.gaitAnalysis
            if (analysis != null) {
                CoefficientsSection(current = analysis, previous = prevAnalysis)
            }

            state.latestSession?.pressureData?.let { insoleData ->
                FootLoadSection(left = insoleData.leftFoot, right = insoleData.rightFoot)
            }

            if (state.recommendations.isNotEmpty()) {
                RecommendationsSection(recommendations = state.recommendations)
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecommendationsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = NeutralBackground)
    )
}

// ── Patient info row ──────────────────────────────────────────────────────────

@Composable
private fun PatientInfoRow(patientName: String, hasUnread: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PatientAvatar(name = patientName)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "My Rehab Progress",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeutralTextSecondary
                )
                Text(
                    text = patientName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NeutralText
                )
            }
        }
        NotificationBell(hasUnread = hasUnread)
    }
}

@Composable
private fun PatientAvatar(name: String) {
    val initials = name.split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(PatientActiveTealLight),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = PatientActiveTeal
        )
    }
}

@Composable
private fun NotificationBell(hasUnread: Boolean) {
    Box(contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = NeutralText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (hasUnread) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(PatientPendingAlert)
            )
        }
    }
}

// ── Coefficients section ──────────────────────────────────────────────────────

@Composable
private fun CoefficientsSection(
    current: GaitAnalysisResult,
    previous: GaitAnalysisResult?
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Progress Coefficients",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = NeutralText
        )

        val symmetry = current.symmetryScore / 100f
        val prevSymmetry = previous?.let { it.symmetryScore / 100f }
        val gaitNormal = current.abnormalities.isEmpty()
        val prevGaitNormal = previous?.let { it.abnormalities.isEmpty() }
        val kyphosis = (100f - current.postureScore) * 0.2f
        val prevKyphosis = previous?.let { (100f - it.postureScore) * 0.2f }
        val asymmetry = (100f - current.symmetryScore) / 100f
        val prevAsymmetry = previous?.let { (100f - it.symmetryScore) / 100f }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "Symmetry Coeff.",
                value = "%.2f".format(symmetry),
                statusLabel = if (symmetry >= 0.8f) "Good" else "Watch",
                statusGood = symmetry >= 0.8f,
                trendIcon = {
                    val up = prevSymmetry == null || symmetry >= prevSymmetry
                    Icon(
                        imageVector = if (up) Icons.AutoMirrored.Outlined.TrendingUp
                                      else Icons.Outlined.ArrowDownward,
                        contentDescription = null,
                        tint = PatientActiveTeal,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "Gait Classification",
                value = if (gaitNormal) "Normal" else "Irregular",
                statusLabel = null,
                statusGood = gaitNormal,
                trendIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (gaitNormal) PatientActiveTeal else PatientPendingAmber)
                    )
                },
                subLabel = prevGaitNormal?.let {
                    "Prev: ${if (it) "Normal" else "Irregular"}"
                },
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "Kyphosis Coeff.",
                value = "%.1f°".format(kyphosis),
                statusLabel = if (prevKyphosis != null && kyphosis < prevKyphosis) "Improving"
                              else if (prevKyphosis != null && kyphosis > prevKyphosis) "Watch"
                              else "Stable",
                statusGood = prevKyphosis == null || kyphosis <= prevKyphosis,
                trendIcon = {
                    val improving = prevKyphosis == null || kyphosis <= prevKyphosis
                    Icon(
                        imageVector = if (improving) Icons.Outlined.ArrowDownward
                                      else Icons.AutoMirrored.Outlined.TrendingUp,
                        contentDescription = null,
                        tint = if (improving) PatientActiveTeal else PatientPendingAmber,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "Asymmetry Coeff.",
                value = "%.2f".format(asymmetry),
                statusLabel = if (prevAsymmetry == null || kotlin.math.abs(asymmetry - (prevAsymmetry ?: asymmetry)) < 0.05f) "Stable"
                              else if (asymmetry < (prevAsymmetry ?: asymmetry)) "Improving"
                              else "Watch",
                statusGood = prevAsymmetry == null || asymmetry <= prevAsymmetry,
                trendIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Remove,
                        contentDescription = null,
                        tint = PatientPendingAmber,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        val scoliosis = (100f - current.scoliosisScore) / 100f
        val prevScoliosis = previous?.let { (100f - it.scoliosisScore) / 100f }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "Balance Score",
                value = "%.0f%%".format(current.balanceScore),
                statusLabel = if (current.balanceScore >= 75f) "Good" else "Watch",
                statusGood = current.balanceScore >= 75f,
                trendIcon = {
                    val up = previous == null || current.balanceScore >= previous.balanceScore
                    Icon(
                        imageVector = if (up) Icons.AutoMirrored.Outlined.TrendingUp
                                      else Icons.Outlined.ArrowDownward,
                        contentDescription = null,
                        tint = if (up) PatientActiveTeal else PatientPendingAmber,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "Scoliosis Coeff.",
                value = "%.2f".format(scoliosis),
                statusLabel = when {
                    prevScoliosis == null -> "Stable"
                    scoliosis < prevScoliosis -> "Improving"
                    scoliosis > prevScoliosis -> "Watch"
                    else -> "Stable"
                },
                statusGood = prevScoliosis == null || scoliosis <= prevScoliosis,
                trendIcon = {
                    val improving = prevScoliosis == null || scoliosis <= prevScoliosis
                    Icon(
                        imageVector = if (improving) Icons.Outlined.ArrowDownward
                                      else Icons.AutoMirrored.Outlined.TrendingUp,
                        contentDescription = null,
                        tint = if (improving) PatientActiveTeal else PatientPendingAmber,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    statusLabel: String?,
    statusGood: Boolean,
    trendIcon: @Composable () -> Unit,
    subLabel: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                trendIcon()
            }

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = NeutralText
            )

            if (statusLabel != null) {
                MetricStatusBadge(label = statusLabel, good = statusGood)
            }

            if (subLabel != null) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralTextTertiary
                )
            }
        }
    }
}

@Composable
private fun MetricStatusBadge(label: String, good: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (good) PatientActiveTealLight else PatientPendingAmberLight
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = if (good) PatientActiveTeal else PatientPendingAmber,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ── Recommendations section ───────────────────────────────────────────────────

@Composable
private fun RecommendationsSection(recommendations: List<DoctorRecommendation>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Doctor Recommendations",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = NeutralText
        )
        recommendations.forEach { rec ->
            RecommendationCard(rec)
        }
    }
}

@Composable
private fun RecommendationCard(rec: DoctorRecommendation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DoctorAvatar(name = rec.doctorName)
                    Column {
                        Text(
                            text = rec.doctorName,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = NeutralText
                        )
                        Text(
                            text = relativeTime(rec.timestampMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralTextTertiary
                        )
                    }
                }
                RecommendationTagBadge(tag = rec.tag)
            }

            Text(
                text = rec.text,
                style = MaterialTheme.typography.bodySmall,
                color = NeutralTextSecondary,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
            )
        }
    }
}

@Composable
private fun DoctorAvatar(name: String) {
    val initials = name.split(" ")
        .filter { it.isNotBlank() && it != "Dr." }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "DR" }

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun RecommendationTagBadge(tag: RecommendationTag) {
    val (bg, border, text, label) = when (tag) {
        RecommendationTag.INSOLE_BASED -> TagStyle(
            bg = PatientActiveTealLight,
            border = PatientActiveTealBorder,
            text = PatientActiveTeal,
            label = "Insole-based"
        )
        RecommendationTag.PHOTOGRAMMETRIC -> TagStyle(
            bg = PatientStableVioletLight,
            border = PatientStableVioletBorder,
            text = PatientStableViolet,
            label = "Photogrammetric"
        )
    }
    Surface(
        shape = CircleShape,
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Foot Load Distribution",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = NeutralText
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Left",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralTextSecondary,
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "Right",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralTextSecondary,
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.Center
                    )
                }
                zones.forEach { (zoneName, lv, rv) -> FootZoneRow(zoneName, lv, rv) }
            }
        }
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
            color = NeutralText,
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
            color = NeutralTextSecondary,
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
            color = NeutralText,
            modifier = Modifier.width(28.dp)
        )
    }
}

private data class TagStyle(
    val bg: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
    val text: androidx.compose.ui.graphics.Color,
    val label: String
)

private fun relativeTime(timestampMs: Long): String {
    val diff = System.currentTimeMillis() - timestampMs
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        days == 0L -> "Today"
        days == 1L -> "1 day ago"
        days < 7L  -> "$days days ago"
        days < 14L -> "1 week ago"
        else       -> "${days / 7} weeks ago"
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun RecommendationsScreenPreview() {
    AppTheme {
        val sessions = RehabMockData.generateSessions()
        RecommendationsContent(
            state = RecommendationsUiState(
                latestSession = sessions.getOrNull(0),
                previousSession = sessions.getOrNull(1),
                patientName = "Maria L.",
                recommendations = RehabMockData.generateRecommendations()
            ),
            onAction = {}
        )
    }
}
