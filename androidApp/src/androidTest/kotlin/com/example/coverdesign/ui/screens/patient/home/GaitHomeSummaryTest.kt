package com.example.coverdesign.ui.screens.patient.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.coverdesign.gait.GaitMethod
import com.example.coverdesign.gait.GaitMetric
import com.example.coverdesign.gait.GaitProfile
import com.example.coverdesign.gait.GaitSample
import com.example.coverdesign.gait.GaitSession
import com.example.coverdesign.gait.GaitView
import com.example.coverdesign.ui.theme.AppTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GaitHomeSummaryTest {
    @get:Rule val compose = createComposeRule()

    private fun recording(id: String, day: Int, angle: Double = 1.2,
                          method: GaitMethod = GaitMethod.POSE): GaitSession {
        val at = LocalDate.of(2026, 11, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val front = List(3) { GaitSample(it * 200L, GaitView.FRONT,
            mapOf(GaitMetric.SHOULDERS to angle, GaitMetric.PELVIS to -.8, GaitMetric.KNEES to 2.1), 0, 1) }
        val back = List(3) { GaitSample(1_000 + it * 200L, GaitView.BACK,
            mapOf(GaitMetric.SHOULDERS to 1.4, GaitMetric.PELVIS to .6), 0, 2) }
        return GaitSession(id, at, GaitProfile(), front + back, 0, 0, 6, 0, true, true,
            protocolVersion = 9, frontValidDurationMs = 6_000, backValidDurationMs = 6_000,
            measurementMethod = method)
    }

    private fun show(state: GaitHomeUiState, onStart: () -> Unit = {}, onHistory: () -> Unit = {}) {
        compose.setContent { AppTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                GaitHomeSummary(state, onStart = onStart, onHistory = onHistory)
            }
        } }
    }

    @Test fun emptyStateHasNoInventedScoresAndStartsAnalysis() {
        var opened = false
        show(GaitHomeUiState(), onStart = { opened = true })
        compose.onNodeWithText("No gait measurements yet").assertIsDisplayed()
        compose.onNodeWithTag("home-gait-FRONT-SHOULDERS-angle").assertDoesNotExist()
        compose.onNodeWithText("Posture").assertDoesNotExist()
        compose.onNodeWithText("Start gait analysis").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun latestMeasurementDisplaysActualFiveAnglesAndReachableHistory() {
        var opened = false
        show(buildGaitHomeState(listOf(recording("real", 18))), onHistory = { opened = true })
        compose.onNodeWithTag("home-gait-method").performScrollTo().assertTextEquals("Pose landmarks")
        compose.onNodeWithTag("home-gait-FRONT-SHOULDERS-angle").performScrollTo().assertTextEquals("1.2°")
        compose.onNodeWithTag("home-gait-FRONT-PELVIS-angle").performScrollTo().assertTextEquals("0.8°")
        compose.onNodeWithTag("home-gait-FRONT-KNEES-angle").performScrollTo().assertTextEquals("2.1°")
        compose.onNodeWithTag("home-gait-BACK-SHOULDERS-angle").performScrollTo().assertTextEquals("1.4°")
        compose.onNodeWithTag("home-gait-BACK-PELVIS-angle").performScrollTo().assertTextEquals("0.6°")
        compose.onNodeWithTag("home-gait-BACK-SPINE-angle").assertDoesNotExist()
        compose.onNodeWithText("Open gait history").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun dailyGraphContainsMatchingRecordedDaysAndNeverOtherMethodOrGapValues() {
        val state = buildGaitHomeState(listOf(recording("old-pose", 15, 3.0),
            recording("other-method", 16, 80.0, GaitMethod.MARKERS), recording("latest-pose", 18, 1.0)))
        show(state)
        val chart = compose.onNodeWithTag("home-gait-FRONT-SHOULDERS-chart").performScrollTo().assertIsDisplayed()
        val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description.contains("15 Nov: 3.0°"))
        assertTrue(description.contains("18 Nov: 1.0°"))
        assertFalse(description.contains("16 Nov"))
        assertFalse(description.contains("17 Nov"))
        assertFalse(description.contains("80.0°"))
    }

    @Test fun partialMeasurementShowsMissingInsteadOfZeroAndExcludesShortValuesFromDailyGraph() {
        val original = recording("partial", 18)
        val partial = original.copy(completed = false, frontValidDurationMs = 500, backValidDurationMs = 0,
            samples = original.samples.filter { it.view == GaitView.FRONT }.map { it.copy(angles = it.angles - GaitMetric.PELVIS) })
        show(buildGaitHomeState(listOf(partial)))
        compose.onNodeWithText("Live analysis · Partial session").assertExists()
        compose.onNodeWithTag("home-gait-coverage").assertTextEquals("2 of 5 body lines measured · 0 with enough data for daily comparison")
        compose.onNodeWithTag("home-gait-FRONT-SHOULDERS-angle").performScrollTo().assertTextEquals("1.2°")
        compose.onNodeWithTag("home-gait-FRONT-PELVIS-angle").performScrollTo().assertTextEquals("Not measured")
        compose.onNodeWithTag("home-gait-BACK-SHOULDERS-angle").performScrollTo().assertTextEquals("Not measured")
        compose.onNodeWithTag("home-gait-FRONT-SHOULDERS-chart").assertDoesNotExist()
    }
}
