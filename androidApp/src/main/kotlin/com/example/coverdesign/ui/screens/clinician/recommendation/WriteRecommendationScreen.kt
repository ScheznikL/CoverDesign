package com.example.coverdesign.ui.screens.clinician.recommendation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.ui.viewmodel.WriteRecommendationViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.NeutralBackground
import com.example.coverdesign.ui.theme.NeutralLight
import com.example.coverdesign.ui.theme.NeutralText
import com.example.coverdesign.ui.theme.NeutralTextSecondary
import com.example.coverdesign.ui.theme.NeutralTextTertiary
import com.example.coverdesign.ui.theme.NeutralWhite
import com.example.coverdesign.ui.theme.PatientActiveTeal
import com.example.coverdesign.ui.theme.PatientActiveTealLight
import com.example.coverdesign.ui.theme.PatientPendingAlert
import com.example.coverdesign.ui.theme.PatientPendingAlertLight

enum class RecommendationType {
    Insole, Photogrammetric;

    val label: String get() = when (this) {
        Insole         -> "Insole-based"
        Photogrammetric -> "Photogrammetric"
    }
}

data class PatientRecommendationData(
    val patientName: String,
    val treatmentType: String,
    // Optional: gait metrics aren't part of care_links. Absent → the summary section is hidden
    // rather than showing fabricated/placeholder numbers.
    val lastUpdated: String = "",
    val kyphosisCoeff: Float? = null,
    val asymmetryCoeff: Float? = null,
    val pressureNote: String? = null,
    val gaitPattern: String? = null
) {
    val initials: String get() = patientName.split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
}

/** VM-backed entry: loads the patient from the care roster and posts to the server on send. */
@Composable
fun WriteRecommendationScreen(
    patientId: String,
    onBack: () -> Unit,
    viewModel: WriteRecommendationViewModel = koinViewModel { parametersOf(patientId) }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.sent) { if (state.sent) onBack() }
    WriteRecommendationScreen(
        patientData = PatientRecommendationData(
            patientName = state.patientName.ifBlank { "Patient" },
            treatmentType = state.treatmentType
        ),
        onBack = onBack,
        onSend = { text, type ->
            val tag = if (type == RecommendationType.Insole) "insole_based" else "photogrammetric"
            viewModel.submit(text, tag)
        }
    )
}

@Composable
fun WriteRecommendationScreen(
    patientData: PatientRecommendationData,
    onBack: () -> Unit = {},
    onSend: (text: String, type: RecommendationType) -> Unit = { _, _ -> }
) {
    var recommendationType by remember { mutableStateOf(RecommendationType.Insole) }
    var recommendationText by remember { mutableStateOf("") }

    WriteRecommendationContent(
        patientData = patientData,
        recommendationType = recommendationType,
        recommendationText = recommendationText,
        onTypeChange = { recommendationType = it },
        onTextChange = { recommendationText = it },
        onBack = onBack,
        onSend = { onSend(recommendationText, recommendationType) },
        onSuggestionClick = { chip ->
            recommendationText = if (recommendationText.isEmpty()) chip
            else "$recommendationText $chip"
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WriteRecommendationContent(
    patientData: PatientRecommendationData,
    recommendationType: RecommendationType,
    recommendationText: String,
    onTypeChange: (RecommendationType) -> Unit,
    onTextChange: (String) -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onSuggestionClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeutralBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        NavigationHeader(onBack = onBack, onSend = onSend)
        PatientInfoCard(patientData = patientData)
        DataSummarySection(patientData = patientData)
        RecommendationTypeSection(selected = recommendationType, onSelect = onTypeChange)
        WriteTextAreaSection(text = recommendationText, onTextChange = onTextChange)
        SuggestionsSection(onSuggestionClick = onSuggestionClick)
        SubmitButton(onClick = onSend)
        Spacer(Modifier.height(12.dp))
    }
}

// ── Navigation Header ──────────────────────────────────────────────────────────

@Composable
private fun NavigationHeader(onBack: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(NeutralWhite)
                    .border(1.dp, NeutralLight, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NeutralText,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = "Write Recommendation",
                style = MaterialTheme.typography.titleLarge,
                color = NeutralText
            )
        }
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraLarge)
                .background(PatientActiveTealLight)
                .clickable(onClick = onSend)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Send",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = PatientActiveTeal
            )
        }
    }
}

// ── Patient Info Card ─────────────────────────────────────────────────────────

@Composable
private fun PatientInfoCard(patientData: PatientRecommendationData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = NeutralWhite),
        border = BorderStroke(1.dp, NeutralLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(PatientActiveTeal, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = patientData.initials,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeutralWhite
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = patientData.patientName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeutralText
                    )
                    if (patientData.lastUpdated.isNotBlank()) {
                        Text(
                            text = patientData.lastUpdated,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralTextTertiary
                        )
                    }
                }
                Text(
                    text = patientData.treatmentType,
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralTextSecondary
                )
            }
        }
    }
}

// ── Data Summary Section ──────────────────────────────────────────────────────

@Composable
private fun DataSummarySection(patientData: PatientRecommendationData) {
    val hasAsymmetry = patientData.kyphosisCoeff != null || patientData.asymmetryCoeff != null
    val hasInsole = !patientData.pressureNote.isNullOrBlank() || !patientData.gaitPattern.isNullOrBlank()
    // No fabricated data: only shown when the patient actually has measured values.
    if (!hasAsymmetry && !hasInsole) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "RECEIVED DATA SUMMARY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NeutralTextSecondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasAsymmetry) {
                AsymmetryCard(
                    kyphosisCoeff = patientData.kyphosisCoeff,
                    asymmetryCoeff = patientData.asymmetryCoeff,
                    modifier = Modifier.weight(1f)
                )
            }
            if (hasInsole) {
                InsoleCard(
                    pressureNote = patientData.pressureNote,
                    gaitPattern = patientData.gaitPattern,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AsymmetryCard(
    kyphosisCoeff: Float?,
    asymmetryCoeff: Float?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = NeutralWhite),
        border = BorderStroke(1.dp, NeutralLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Asymmetry Data",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeutralText
                )
                Icon(
                    imageVector = Icons.Filled.TrendingUp,
                    contentDescription = null,
                    tint = PatientPendingAlert,
                    modifier = Modifier.size(14.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                kyphosisCoeff?.let { MetricRow(label = "Kyphosis Coeff.", value = "%.1f°".format(it)) }
                asymmetryCoeff?.let { MetricRow(label = "Asymmetry Coeff.", value = "%.2f".format(it)) }
            }
            StatusBadge(
                label = "Needs attention",
                dotColor = PatientPendingAlert,
                backgroundColor = PatientPendingAlertLight,
                textColor = PatientPendingAlert
            )
        }
    }
}

@Composable
private fun InsoleCard(
    pressureNote: String?,
    gaitPattern: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = NeutralWhite),
        border = BorderStroke(1.dp, NeutralLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Insole Data",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeutralText
                )
                Icon(
                    imageVector = Icons.Filled.DirectionsWalk,
                    contentDescription = null,
                    tint = PatientActiveTeal,
                    modifier = Modifier.size(14.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pressureNote?.takeIf { it.isNotBlank() }?.let { InsoleMetricItem(label = "Pressure Distribution", value = it) }
                gaitPattern?.takeIf { it.isNotBlank() }?.let { InsoleMetricItem(label = "Gait Pattern", value = it) }
            }
            StatusBadge(
                label = "New data",
                dotColor = PatientActiveTeal,
                backgroundColor = PatientActiveTealLight,
                textColor = PatientActiveTeal
            )
        }
    }
}

// ── Recommendation Type Section ───────────────────────────────────────────────

@Composable
private fun RecommendationTypeSection(
    selected: RecommendationType,
    onSelect: (RecommendationType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "RECOMMENDATION TYPE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NeutralTextSecondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RecommendationType.entries.forEach { type ->
                val isSelected = selected == type
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .then(
                            if (isSelected) Modifier.background(PatientActiveTeal)
                            else Modifier
                                .background(NeutralWhite)
                                .border(1.dp, NeutralLight, MaterialTheme.shapes.extraLarge)
                        )
                        .clickable { onSelect(type) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = NeutralWhite,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(14.dp)
                        )
                    }
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) NeutralWhite else NeutralTextSecondary
                    )
                }
            }
        }
    }
}

// ── Write Text Area Section ───────────────────────────────────────────────────

@Composable
private fun WriteTextAreaSection(text: String, onTextChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "YOUR RECOMMENDATION",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NeutralTextSecondary
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            placeholder = {
                Text(
                    text = "Write your recommendation based on the patient data above...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeutralTextTertiary
                )
            },
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PatientActiveTeal,
                unfocusedBorderColor = NeutralLight,
                focusedContainerColor = NeutralWhite,
                unfocusedContainerColor = NeutralWhite,
                cursorColor = PatientActiveTeal
            )
        )
    }
}

// ── Suggestions Section ───────────────────────────────────────────────────────

private val quickSuggestions = listOf(
    "Adjust insole angle",
    "Increase exercises",
    "Schedule follow-up",
    "Refer to specialist"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionsSection(onSuggestionClick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Quick Suggestions",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = NeutralTextSecondary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickSuggestions.forEach { suggestion ->
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.large)
                        .background(NeutralWhite)
                        .border(1.dp, NeutralLight, MaterialTheme.shapes.large)
                        .clickable { onSuggestionClick(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = NeutralTextSecondary
                    )
                }
            }
        }
    }
}

// ── Submit Button ─────────────────────────────────────────────────────────────

@Composable
private fun SubmitButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = PatientActiveTeal,
            contentColor = NeutralWhite
        )
    ) {
        Text(
            text = "Submit Recommendation",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Shared sub-composables ────────────────────────────────────────────────────

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NeutralTextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = NeutralText
        )
    }
}

@Composable
private fun InsoleMetricItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NeutralTextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = NeutralTextSecondary
        )
    }
}

@Composable
private fun StatusBadge(
    label: String,
    dotColor: Color,
    backgroundColor: Color,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun WriteRecommendationPreview() {
    AppTheme {
        WriteRecommendationScreen(
            patientData = PatientRecommendationData(
                patientName = "Maria L.",
                treatmentType = "Post-operative Rehab",
                lastUpdated = "Updated 2h ago",
                kyphosisCoeff = 12.4f,
                asymmetryCoeff = 0.82f,
                pressureNote = "Left foot imbalance",
                gaitPattern = "Irregular"
            )
        )
    }
}