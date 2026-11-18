package com.example.coverdesign.ui.screens.patient.home

import com.example.coverdesign.gait.GaitInsights
import com.example.coverdesign.gait.GaitMethod
import com.example.coverdesign.gait.GaitMetric
import com.example.coverdesign.gait.GaitMetricKey
import com.example.coverdesign.gait.GaitSession
import java.time.LocalDate
import java.time.ZoneId

internal data class GaitHomeDay(val date: LocalDate, val angle: Double, val sessionCount: Int)

internal data class GaitHomeLine(
    val key: GaitMetricKey,
    val typicalAngle: Double?,
    val qualifiesForTrend: Boolean,
    val days: List<GaitHomeDay>
)

internal data class GaitHomeLatest(
    val sessionId: String,
    val createdAt: Long,
    val method: GaitMethod,
    val acquisitionSource: String,
    val completed: Boolean,
    val lines: List<GaitHomeLine>
)

internal data class GaitHomeUiState(
    val latest: GaitHomeLatest? = null,
    val savedSessionCount: Int = 0,
    val error: String? = null,
    val loading: Boolean = false
)

/** Home shows real angles only; upstream demonstration rehab scores never enter this model. */
internal fun buildGaitHomeState(
    sessions: List<GaitSession>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    error: String? = null
): GaitHomeUiState {
    val saved = sessions.sortedWith(compareByDescending<GaitSession> { it.createdAt }.thenBy { it.id }).distinctBy { it.id }
    val latest = saved.firstOrNull() ?: return GaitHomeUiState(error = error)
    val overview = GaitInsights.overview(latest)
    // C7/coccyx remains in detailed marker results; Home compares the five common body lines.
    val keys = GaitInsights.metricKeys(latest).filter { it.metric != GaitMetric.SPINE }
    val lines = keys.map { key ->
        val measured = overview.metrics.firstOrNull { it.key == key }
        val days = GaitInsights.dailyTrend(saved, latest, key, zoneId).days.takeLast(7)
            .map { GaitHomeDay(it.date, it.typicalAngle, it.sessionCount) }
        GaitHomeLine(key, measured?.summary?.meanAbsolute, measured?.qualifiesForTrend == true, days)
    }
    return GaitHomeUiState(GaitHomeLatest(latest.id, latest.createdAt, latest.measurementMethod,
        latest.acquisitionSource, latest.completed, lines), saved.size, error)
}
