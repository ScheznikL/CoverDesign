package com.example.coverdesign.ui.viewmodel

import com.example.coverdesign.domain.model.CarePatient
import com.example.coverdesign.domain.model.CareStatus
import com.example.coverdesign.domain.util.currentEpochMillis

/** Maps a domain [CarePatient] roster entry to the UI [PatientSummary] the clinician screens render. */
internal fun CarePatient.toSummary(now: Long = currentEpochMillis()): PatientSummary = PatientSummary(
    id = patientUid,
    name = name,
    lastVisitDate = relativeDay(lastReviewedAt ?: assignedAt, now),
    treatmentType = treatmentType,
    status = status.toUiStatus(),
    // care_links carries no gait metrics; those fields stay null so the metric rows are hidden.
    asymmetryCoeff = null,
    kyphosisCoeff = null,
    latestUpdate = null,
)

private fun CareStatus.toUiStatus(): PatientStatus = when (this) {
    CareStatus.ACTIVE -> PatientStatus.Active
    CareStatus.STABLE -> PatientStatus.Stable
    CareStatus.PENDING -> PatientStatus.Pending
}

/** Coarse "N days/weeks ago" label for an epoch-ms timestamp, without a date library. */
private fun relativeDay(timestampMs: Long, now: Long): String {
    if (timestampMs <= 0L) return "—"
    val days = ((now - timestampMs) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "1 week ago"
        days < 30 -> "${days / 7} weeks ago"
        days < 60 -> "1 month ago"
        days < 365 -> "${days / 30} months ago"
        else -> "${days / 365} year${if (days / 365 == 1) "" else "s"} ago"
    }
}
