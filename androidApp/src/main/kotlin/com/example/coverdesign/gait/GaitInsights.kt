package com.example.coverdesign.gait

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A body line and camera view are one measurement; front/back shoulders are never pooled. */
internal data class GaitMetricKey(val view: GaitView, val metric: GaitMetric)

internal data class GaitMetricInsight(
    val key: GaitMetricKey,
    val summary: MetricSummary,
    val qualifiesForTrend: Boolean
)

internal data class GaitSessionOverview(
    val metrics: List<GaitMetricInsight>,
    val qualifiedViews: Set<GaitView>,
    val availableViews: Set<GaitView>,
    val fullCoverage: Boolean,
    val minimumTypical: Double?,
    val maximumTypical: Double?,
    val largest: GaitMetricInsight?,
    val sampleCount: Int,
    val trackingCoverage: Double?,
    val expectedMetricCount: Int
) {
    val availableMetricCount: Int get() = metrics.size
    val qualifiedMetricCount: Int get() = metrics.count { it.qualifiesForTrend }
}

/** Only matching acquisition settings form a series. Mirror correction restores canonical sides. */
internal data class GaitComparisonKey(
    val acquisitionSource: String,
    val protocolVersion: Int,
    val manualLevel: Boolean,
    val heightCm: Int,
    val weightKg: Int,
    val sex: String,
    val age: Int,
    val passSetting: Int,
    val targetSetting: Int,
    val measurementMethod: GaitMethod = GaitMethod.MARKERS
)

internal data class GaitTrendGroup(
    val key: GaitComparisonKey,
    val reference: GaitSession,
    val sessions: List<GaitSession>
)

internal data class GaitDailyPoint(
    val date: LocalDate,
    val typicalAngle: Double,
    val signedMean: Double,
    val minSessionAngle: Double,
    val maxSessionAngle: Double,
    val sessionCount: Int,
    val sessionIds: List<String>,
    val deltaFromPreviousDay: Double?,
    val deltaFromBaseline: Double
)

internal enum class GaitTrendExclusion { NO_FINITE_SAMPLES, TOO_FEW_SAMPLES, VIEW_TARGET_NOT_MET }

internal data class GaitDailyTrend(
    val key: GaitMetricKey,
    val days: List<GaitDailyPoint>,
    val compatibleSessionCount: Int,
    val includedSessionCount: Int,
    val excludedSessionCount: Int,
    val excludedReasons: Map<GaitTrendExclusion, Int>
)

/** A person's previously observed values, not population norms or an accuracy/confidence interval. */
internal data class GaitPersonalReference(
    val key: GaitMetricKey,
    val sessionCount: Int,
    val minTypical: Double,
    val maxTypical: Double,
    val meanTypical: Double,
    val sessionIds: List<String>
)

/** Pure presentation statistics. No clinical cutoffs, health score or inferred missing angles. */
internal object GaitInsights {
    val metricKeys = listOf(
        GaitMetricKey(GaitView.FRONT, GaitMetric.SHOULDERS),
        GaitMetricKey(GaitView.FRONT, GaitMetric.PELVIS),
        GaitMetricKey(GaitView.FRONT, GaitMetric.KNEES),
        GaitMetricKey(GaitView.BACK, GaitMetric.SHOULDERS),
        GaitMetricKey(GaitView.BACK, GaitMetric.PELVIS),
        GaitMetricKey(GaitView.BACK, GaitMetric.SPINE)
    )

    /** A pose recording has no measured C7/coccyx line, even if malformed input contains one. */
    fun metricKeys(session: GaitSession): List<GaitMetricKey> = metricKeys.filter {
        it.metric in expectedGaitMetrics(it.view, session.measurementMethod)
    }

    private const val MIN_METRIC_SAMPLES = 3

    fun overview(session: GaitSession): GaitSessionOverview {
        val expectedKeys = metricKeys(session)
        val metrics = expectedKeys.mapNotNull { key ->
            summary(session, key)?.let { GaitMetricInsight(key, it, qualifies(session, key)) }
        }
        val qualifiedViews = GaitView.entries.filter { view ->
            expectedKeys.filter { it.view == view }.all { key -> metrics.any { it.key == key && it.qualifiesForTrend } }
        }.toSet()
        val accepted = session.acceptedFrames.coerceAtLeast(0).toLong()
        val total = accepted + session.rejectedFrames.coerceAtLeast(0).toLong()
        return GaitSessionOverview(
            metrics = metrics,
            qualifiedViews = qualifiedViews,
            availableViews = metrics.map { it.key.view }.toSet(),
            fullCoverage = qualifiedViews.size == GaitView.entries.size,
            minimumTypical = metrics.minOfOrNull { it.summary.meanAbsolute },
            maximumTypical = metrics.maxOfOrNull { it.summary.meanAbsolute },
            largest = metrics.maxByOrNull { it.summary.meanAbsolute },
            sampleCount = session.samples.count { sample ->
                expectedKeys.any { key -> key.view == sample.view && sample.angles[key.metric]?.isFinite() == true }
            },
            trackingCoverage = if (total > 0) accepted.toDouble() / total else null,
            expectedMetricCount = expectedKeys.size
        )
    }

    fun qualifies(session: GaitSession, key: GaitMetricKey): Boolean = exclusion(session, key) == null

    fun compatible(first: GaitSession, second: GaitSession): Boolean = comparisonKey(first) == comparisonKey(second)

    fun comparisonKey(session: GaitSession): GaitComparisonKey {
        val profile = session.profile
        val timed = session.protocolVersion >= 6
        return GaitComparisonKey(session.acquisitionSource, session.protocolVersion, session.manualLevel,
            profile.heightCm, profile.weightKg, profile.sex, profile.age,
            if (timed) profile.passDurationSeconds else profile.passSteps,
            if (timed) profile.targetDurationSecondsPerView else profile.targetStepsPerView,
            session.measurementMethod)
    }

    fun groups(sessions: List<GaitSession>): List<GaitTrendGroup> = uniqueSessions(sessions)
        .groupBy(::comparisonKey)
        .map { (key, members) -> GaitTrendGroup(key, members.first(), members) }

    /** Analysis/session creation day in the supplied zone, not an imported file's recording date. */
    fun dailyTrend(
        sessions: List<GaitSession>,
        reference: GaitSession,
        key: GaitMetricKey,
        zoneId: ZoneId
    ): GaitDailyTrend {
        require(key in metricKeys(reference)) { "Choose a metric/view pair supported by this measurement method." }
        val matching = uniqueSessions(sessions).filter { compatible(it, reference) }
        val included = matching.filter { qualifies(it, key) }
        val reasons = matching.mapNotNull { exclusion(it, key) }.groupingBy { it }.eachCount()
        val grouped = included.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zoneId).toLocalDate() }.toSortedMap()
        var previous: Double? = null
        var baseline: Double? = null
        val points = grouped.map { (date, members) ->
            val ordered = members.sortedWith(compareBy<GaitSession> { it.createdAt }.thenBy { it.id })
            val summaries = ordered.map { requireNotNull(summary(it, key)) }
            // Each recording/session gets one vote. A long video cannot outweigh short sessions.
            val typical = stableAverage(summaries.map { it.meanAbsolute })
            val first = baseline ?: typical.also { baseline = it }
            GaitDailyPoint(date, typical, stableAverage(summaries.map { it.mean }),
                summaries.minOf { it.meanAbsolute }, summaries.maxOf { it.meanAbsolute },
                ordered.size, ordered.map { it.id }, previous?.let { typical - it }, typical - first)
                .also { previous = typical }
        }
        return GaitDailyTrend(key, points, matching.size, included.size,
            matching.size - included.size, reasons)
    }

    /** Latest earlier comparable recording sharing enough data for this metric (or any metric). */
    fun previousComparableSession(
        sessions: List<GaitSession>,
        current: GaitSession,
        key: GaitMetricKey? = null
    ): GaitSession? {
        val availableKeys = metricKeys(current)
        val keys = if (key == null) availableKeys.filter { qualifies(current, it) }
            else listOf(key).filter { it in availableKeys && qualifies(current, it) }
        if (keys.isEmpty()) return null
        return uniqueSessions(sessions).firstOrNull { previous ->
            previous.id != current.id && previous.createdAt < current.createdAt && compatible(previous, current) &&
                keys.any { qualifies(previous, it) }
        }
    }

    /** A small repeat-recording minimum avoids calling a single earlier measurement a range. */
    fun personalReference(
        sessions: List<GaitSession>,
        current: GaitSession,
        key: GaitMetricKey,
        minimumSessions: Int = 3
    ): GaitPersonalReference? {
        require(minimumSessions >= 2) { "An observed range needs at least two prior sessions." }
        if (key !in metricKeys(current) || !qualifies(current, key)) return null
        val previous = uniqueSessions(sessions).filter {
            it.id != current.id && it.createdAt < current.createdAt && compatible(it, current) && qualifies(it, key)
        }
        if (previous.size < minimumSessions) return null
        val values = previous.map { requireNotNull(summary(it, key)).meanAbsolute }
        return GaitPersonalReference(key, previous.size, values.min(), values.max(), stableAverage(values), previous.map { it.id })
    }

    private fun summary(session: GaitSession, key: GaitMetricKey): MetricSummary? =
        if (key in metricKeys(session)) GaitMath.summary(session, key.metric, key.view)?.takeIf {
            it.mean.isFinite() && it.meanAbsolute.isFinite() && it.min.isFinite() && it.max.isFinite()
        } else null

    private fun stableAverage(values: List<Double>): Double = values.sumOf { it / values.size }

    private fun exclusion(session: GaitSession, key: GaitMetricKey): GaitTrendExclusion? {
        val values = summary(session, key) ?: return GaitTrendExclusion.NO_FINITE_SAMPLES
        if (values.count < MIN_METRIC_SAMPLES) return GaitTrendExclusion.TOO_FEW_SAMPLES
        val enoughView = if (session.protocolVersion >= 6) {
            val duration = if (key.view == GaitView.FRONT) session.frontValidDurationMs else session.backValidDurationMs
            val target = session.profile.targetDurationSecondsPerView
            target > 0 && duration >= target.toLong() * 1000L
        } else {
            val steps = if (key.view == GaitView.FRONT) session.frontSteps else session.backSteps
            val target = session.profile.targetStepsPerView
            session.protocolVersion >= 1 && target > 0 && steps >= target
        }
        return if (enoughView) null else GaitTrendExclusion.VIEW_TARGET_NOT_MET
    }

    private fun uniqueSessions(sessions: List<GaitSession>): List<GaitSession> = sessions
        .sortedWith(compareByDescending<GaitSession> { it.createdAt }.thenBy { it.id })
        .distinctBy { it.id }
}

internal fun gaitMethodLabel(method: GaitMethod): String = when (method) {
    GaitMethod.MARKERS -> "Coloured markers"
    GaitMethod.POSE -> "Pose landmarks"
}
