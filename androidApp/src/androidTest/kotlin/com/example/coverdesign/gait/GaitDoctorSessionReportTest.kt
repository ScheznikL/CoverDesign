package com.example.coverdesign.gait

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.coverdesign.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Full numerical fixtures only; no Firebase account or private session files are accessed. */
class GaitDoctorSessionReportTest {
    @get:Rule val compose = createComposeRule()

    private fun recording(method: GaitMethod = GaitMethod.POSE, at: Long = 4_000,
        id: String = "doctor-session", values: List<Double> = listOf(-3.0, 1.0, 2.0)): GaitSession {
        val samples = GaitView.entries.flatMap { view -> values.mapIndexed { index, angle ->
            GaitSample(view.ordinal * 1_000L + index * 200L, view,
                expectedGaitMetrics(view, method).associateWith { angle }, index, view.ordinal + 1)
        } }
        return GaitSession(id, at, GaitProfile(), samples, 2, 3, samples.size, 2, true, true,
            protocolVersion = if (method == GaitMethod.POSE) 9 else 7,
            frontValidDurationMs = 6_000, backValidDurationMs = 6_000,
            frontCompletedPasses = 2, backCompletedPasses = 3,
            frontStepsPartial = true, measurementMethod = method)
    }

    @Test fun poseDetailUsesAllFiveOriginalChartsSignedStatisticsAndPriorComparisons() {
        val current = recording()
        val history = (1..3).map { recording(at = it * 1_000L, id = "earlier-$it", values = listOf(1.0, 1.0, 1.0)) } + current
        var closed = false
        compose.setContent { AppTheme {
            GaitDoctorSessionContent("Shared patient", current, history, false, null, true,
                onBack = { closed = true }, onRetry = {})
        } }
        compose.onNodeWithText("Patient: Shared patient").assertExists()
        compose.onNodeWithText("Shared session report · read only").assertExists()
        compose.onNodeWithText("5 of 5 pose lines measured · 5 eligible for trends").assertExists()
        compose.onAllNodesWithContentDescription("Inclination over accepted samples, in degrees").assertCountEquals(5)
        compose.onAllNodesWithText("Signed mean +0.0° · range -3.0° to +2.0°\n3 samples").assertCountEquals(5)
        compose.onAllNodesWithText("Typical inclination changed by +1.0°", substring = true).assertCountEquals(5)
        compose.onNodeWithText("Tracking coverage: 75% · 6 accepted / 8 analysed walking frames").assertExists()
        compose.onNodeWithText(GaitMetric.SPINE.label).assertDoesNotExist()
        compose.onNodeWithText("Session saved").assertDoesNotExist()
        compose.onNodeWithText("Delete session").assertDoesNotExist()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }

    @Test fun markerVideoDetailIncludesTheSixthSpinalChartAndRecordingMetadata() {
        val current = recording(GaitMethod.MARKERS).copy(acquisitionSource = "VIDEO", protocolVersion = 8,
            sourceDurationMs = 45_000, sourceMirrored = true, videoViews = GaitView.entries.toSet())
        compose.setContent { AppTheme {
            GaitDoctorSessionContent("Shared patient", current, listOf(current), false, null, true, {}, {})
        } }
        compose.onAllNodesWithContentDescription("Inclination over accepted samples, in degrees").assertCountEquals(6)
        compose.onNodeWithText(GaitMetric.SPINE.label).assertExists()
        compose.onNodeWithText("Recording: 45.0 seconds · front, back selected").assertExists()
        compose.onNodeWithText("Mirrored recording corrected before measurement.").assertExists()
        compose.onNodeWithText("Step counts are not estimated from imported video.", substring = true).assertExists()
        compose.onNodeWithText("Delete session").assertDoesNotExist()
    }

    @Test fun oldSummaryOnlyResultExplainsResyncInsteadOfInventingGraphs() {
        var retried = false
        compose.setContent { AppTheme {
            GaitDoctorSessionContent("Shared patient", null, emptyList(), false, null, true,
                onBack = {}, onRetry = { retried = true })
        } }
        compose.onNodeWithText("Full session report is not available yet.").assertExists()
        compose.onNodeWithText("Ask the patient to open the updated app and sync this session.", substring = true).assertExists()
        compose.onAllNodesWithContentDescription("Inclination over accepted samples, in degrees").assertCountEquals(0)
        compose.onNodeWithText("Retry session report").performClick()
        compose.runOnIdle { assertEquals(true, retried) }
    }

    @Test fun revocationHidesThePreviouslyRenderedFullReportImmediately() {
        val access = mutableStateOf(true)
        val current = recording()
        compose.setContent { AppTheme {
            GaitDoctorSessionContent("Private patient", current, listOf(current), false, null, access.value, {}, {})
        } }
        compose.onAllNodesWithContentDescription("Inclination over accepted samples, in degrees").assertCountEquals(5)
        compose.runOnIdle { access.value = false }
        compose.onNodeWithTag("gait-session-access-unavailable").assertExists()
        compose.onNodeWithText("Patient: Private patient").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Inclination over accepted samples, in degrees").assertCountEquals(0)
        compose.onNodeWithText("Retry session report").assertDoesNotExist()
    }

    @Test fun failedVerificationDoesNotDisplayAnEarlierLoadedSession() {
        val current = recording()
        compose.setContent { AppTheme {
            GaitDoctorSessionContent("Private patient", current, listOf(current), false,
                "Session verification failed.", true, {}, {})
        } }
        compose.onNodeWithText("Session verification failed.").assertExists()
        compose.onNodeWithText("Patient: Private patient").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Inclination over accepted samples, in degrees").assertCountEquals(0)
        compose.onNodeWithText("Retry session report").assertExists()
    }

    @Test fun incompleteHistoryWaitsBeforeDisplayingTheFullComparisonReport() {
        val loading = mutableStateOf(true)
        val current = recording()
        val earlier = recording(at = 1_000, id = "earlier", values = listOf(1.0, 1.0, 1.0))
        compose.setContent { AppTheme {
            GaitDoctorSessionContent("Shared patient", current,
                if (loading.value) emptyList() else listOf(earlier, current),
                loading.value, null, true, {}, {})
        } }
        compose.onNodeWithText("Loading the shared session report…").assertExists()
        compose.onAllNodesWithContentDescription("Inclination over accepted samples, in degrees").assertCountEquals(0)
        compose.runOnIdle { loading.value = false }
        compose.onAllNodesWithContentDescription("Inclination over accepted samples, in degrees").assertCountEquals(5)
        compose.onAllNodesWithText("Typical inclination changed by +1.0°", substring = true).assertCountEquals(5)
    }

    @Test fun sessionListOffersOpeningTheChosenSessionId() {
        val current = recording()
        val report = GaitCloudReport.fromSession("patient-a", current)
        var chosen: String? = null
        compose.setContent { AppTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                GaitDoctorReportsContent("patient-a", listOf(report), onOpenSession = { chosen = it })
            }
        } }
        compose.onNodeWithTag("gait-open-session-${current.id}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(current.id, chosen) }
    }
}
