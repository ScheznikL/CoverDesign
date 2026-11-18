package com.example.coverdesign.ui.screens.patient.home

import com.example.coverdesign.gait.GaitMethod
import com.example.coverdesign.gait.GaitMetric
import com.example.coverdesign.gait.GaitProfile
import com.example.coverdesign.gait.GaitSample
import com.example.coverdesign.gait.GaitSession
import com.example.coverdesign.gait.GaitView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class GaitHomeModelsTest {
    private val utc = ZoneId.of("UTC")
    private fun at(day: Int) = Instant.parse("2026-11-${day.toString().padStart(2, '0')}T12:00:00Z").toEpochMilli()
    private fun recording(id: String, day: Int, angle: Double, method: GaitMethod = GaitMethod.POSE): GaitSession {
        val front = List(3) { GaitSample(it * 200L, GaitView.FRONT,
            mapOf(GaitMetric.SHOULDERS to angle, GaitMetric.PELVIS to -angle, GaitMetric.KNEES to angle * 2), 0, 1) }
        val back = List(3) { GaitSample(1_000 + it * 200L, GaitView.BACK,
            mapOf(GaitMetric.SHOULDERS to angle, GaitMetric.PELVIS to angle), 0, 2) }
        return GaitSession(id, at(day), GaitProfile(), front + back, 0, 0, 6, 0, true, true,
            protocolVersion = 9, frontValidDurationMs = 6_000, backValidDurationMs = 6_000,
            measurementMethod = method)
    }

    @Test fun emptyHistoryHasNoInventedMeasurementsOrGraphs() {
        val state = buildGaitHomeState(emptyList(), utc)
        assertNull(state.latest)
        assertEquals(0, state.savedSessionCount)
    }

    @Test fun latestRealAnglesUseAbsoluteDegreesAndFiveExplicitViewMetricPairs() {
        val state = buildGaitHomeState(listOf(recording("earlier", 7, 8.0), recording("latest", 9, 1.2)), utc)
        val latest = requireNotNull(state.latest)
        assertEquals("latest", latest.sessionId)
        assertEquals(5, latest.lines.size)
        assertFalse(latest.lines.any { it.key.metric == GaitMetric.SPINE })
        assertEquals(1.2, latest.lines.first { it.key.view == GaitView.FRONT && it.key.metric == GaitMetric.PELVIS }.typicalAngle!!, .00001)
        assertEquals(2.4, latest.lines.first { it.key.metric == GaitMetric.KNEES }.typicalAngle!!, .00001)
        assertTrue(latest.lines.all { it.qualifiesForTrend })
    }

    @Test fun dailyChartsExcludeOtherMethodsAndSettingsInsteadOfPoolingTheirValues() {
        val earlier = recording("earlier", 7, 3.0)
        val latest = recording("latest", 9, 1.0)
        val markers = recording("markers", 8, 80.0, GaitMethod.MARKERS)
        val otherSettings = recording("other-profile", 8, 70.0).copy(profile = GaitProfile(heightCm = 190))
        val lines = requireNotNull(buildGaitHomeState(listOf(earlier, latest, markers, otherSettings), utc).latest).lines
        val shoulders = lines.first { it.key.view == GaitView.FRONT && it.key.metric == GaitMetric.SHOULDERS }
        assertEquals(listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 9)), shoulders.days.map { it.date })
        assertEquals(listOf(3.0, 1.0), shoulders.days.map { it.angle })
    }

    @Test fun partialLatestKeepsMeasuredValueButCannotAddInsufficientDataToGraph() {
        val earlier = recording("earlier", 7, 3.0)
        val partial = recording("partial", 9, 1.0).copy(completed = false, frontValidDurationMs = 500,
            samples = recording("partial", 9, 1.0).samples.filter { it.view == GaitView.FRONT }
                .map { it.copy(angles = it.angles - GaitMetric.PELVIS) }, backValidDurationMs = 0)
        val latest = requireNotNull(buildGaitHomeState(listOf(earlier, partial), utc).latest)
        assertFalse(latest.completed)
        val shoulders = latest.lines.first { it.key.view == GaitView.FRONT && it.key.metric == GaitMetric.SHOULDERS }
        assertEquals(1.0, shoulders.typicalAngle!!, .00001)
        assertFalse(shoulders.qualifiesForTrend)
        assertEquals(listOf(LocalDate.of(2026, 11, 7)), shoulders.days.map { it.date })
        assertNull(latest.lines.first { it.key.view == GaitView.FRONT && it.key.metric == GaitMetric.PELVIS }.typicalAngle)
        assertTrue(latest.lines.filter { it.key.view == GaitView.BACK }.all { it.typicalAngle == null })
    }
}
