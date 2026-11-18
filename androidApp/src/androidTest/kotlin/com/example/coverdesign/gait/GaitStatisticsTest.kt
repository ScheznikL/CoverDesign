package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coverdesign.ui.theme.AppTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic numeric history only. No account, camera, imported file or persisted measurement. */
@RunWith(AndroidJUnit4::class)
class GaitStatisticsTest {
    @get:Rule val compose = createComposeRule()

    private fun session(id: String, day: Int, angle: Double, samples: Int = 3,
                        hour: Int = 10, source: String = "VIDEO", duration: Long = 6_000): GaitSession {
        val created = LocalDate.of(2026, 11, day).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return GaitSession(id, created, GaitProfile(),
            List(samples) { index -> GaitSample(index * 200L, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to angle), 0, 1) },
            0, 0, samples, 0, true, false, protocolVersion = if (source == "VIDEO") 8 else 7,
            frontValidDurationMs = duration, frontStepsPartial = true, backStepsPartial = true,
            acquisitionSource = source, sourceDurationMs = if (source == "VIDEO") 40_000 else 0,
            videoViews = if (source == "VIDEO") setOf(GaitView.FRONT) else emptySet())
    }

    private fun visual(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-test-visuals"))
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun compatibleDailyMeansDeltasMissingDaysAndOpeningSessionsAreCorrect() {
        val a = session("video-a", 7, 2.0, samples = 3, hour = 10)
        val b = session("video-b", 7, 4.0, samples = 30, hour = 12)
        val latest = session("video-latest", 9, 1.0)
        val otherMode = session("live-latest", 10, 50.0, source = "LIVE")
        val opened = AtomicReference<GaitSession?>(null)
        compose.setContent { AppTheme { GaitStatistics(listOf(a, b, latest, otherMode), opened::set, {}) } }

        // The newest live series is separate. Switching to the recorded series must not pool it.
        compose.onNodeWithTag("gait-statistics-latest-angle").performScrollTo().assertTextEquals("50.0°")
        compose.onNodeWithTag("gait-statistics-group-selector").performScrollTo().performClick()
        compose.onNodeWithTag("gait-statistics-group-video-latest").performClick()
        compose.onNodeWithTag("gait-statistics-latest-angle").performScrollTo().assertTextEquals("1.0°")
        compose.onNodeWithTag("gait-statistics-day-angle-2026-11-07").performScrollTo().assertTextEquals("3.0°")
        compose.onNodeWithTag("gait-statistics-day-delta-2026-11-09").performScrollTo()
            .assertTextEquals("-2.0° from previous measured day")
        compose.onNodeWithTag("gait-statistics-day-2026-11-08").assertDoesNotExist()
        val chart = compose.onNodeWithTag("gait-statistics-chart").performScrollTo().assertIsDisplayed()
        val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description.contains("07 Nov 2026: 3.0°"))
        assertTrue(description.contains("09 Nov 2026: 1.0°"))
        assertFalse(description.contains("08 Nov 2026"))
        visual("daily-statistics-chart")
        compose.onNodeWithTag("gait-statistics-day-open-2026-11-07").performScrollTo().performClick()
        compose.onNodeWithTag("gait-statistics-session-video-b").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(b, opened.get()) }
    }

    @Test fun oneMeasuredDayExplainsThatNoDayToDayChangeExistsYet() {
        compose.setContent { AppTheme { GaitStatistics(listOf(session("one-day", 7, 1.0)), {}, {}) } }
        compose.onNodeWithText("Add another measured day to see a change.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gait-statistics-day-delta-2026-11-07").performScrollTo().assertTextEquals("First measured day")
        compose.onNodeWithTag("gait-statistics-view-BACK").performScrollTo().performClick()
        compose.onNodeWithText("Not enough usable data for this selection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gait-statistics-chart").assertDoesNotExist()
    }

    @Test fun shortOrSparseSessionsAreExplainedWithoutCreatingADailyValue() {
        compose.setContent { AppTheme { GaitStatistics(listOf(
            session("sparse", 7, 1.0, samples = 2), session("short", 9, 3.0, duration = 1_000)
        ), {}, {}) } }
        compose.onNodeWithText("Not enough usable data for this selection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gait-statistics-chart").assertDoesNotExist()
        compose.onNodeWithTag("gait-statistics-day-2026-11-07").assertDoesNotExist()
        compose.onNodeWithTag("gait-statistics-day-2026-11-09").assertDoesNotExist()
        compose.onNodeWithText("2 matching sessions · 2 without enough usable data for this view").assertExists()
    }

    @Test fun emptyStatisticsOffersAReachableWayBack() {
        val backs = AtomicInteger(0)
        compose.setContent { AppTheme { GaitStatistics(emptyList(), {}, { backs.incrementAndGet() }) } }
        compose.onNodeWithText("No daily measurements yet").assertIsDisplayed()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backs.get()) }
    }

    @Test fun compactLargeFontKeepsFiltersChartAndSessionActionReachable() {
        val record = session("large-font", 9, 2.0)
        val opened = AtomicReference<GaitSession?>(null)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppTheme { Box(Modifier.requiredSize(360.dp, 640.dp)) {
                    GaitStatistics(listOf(record), opened::set, {})
                } }
            }
        }
        compose.onNodeWithTag("gait-statistics-metric-FRONT-SHOULDERS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gait-statistics-chart").performScrollTo().assertIsDisplayed()
        visual("daily-statistics-large-font")
        compose.onNodeWithTag("gait-statistics-day-open-2026-11-09").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(record, opened.get()) }
    }

    @Test fun equalTypicalAnglesDoNotHideAReversalOfTheAverageDirection() {
        compose.setContent { AppTheme { GaitStatistics(listOf(
            session("left-lower", 7, 2.0), session("right-lower", 9, -2.0)
        ), {}, {}) } }
        compose.onNodeWithTag("gait-statistics-latest-angle").performScrollTo().assertTextEquals("2.0°")
        compose.onNodeWithTag("gait-statistics-latest-signed").assertTextEquals("Signed mean -2.0°")
        compose.onNodeWithTag("gait-statistics-latest-direction").performScrollTo()
            .assertTextEquals("Your right shoulder was lower on average")
        compose.onNodeWithTag("gait-statistics-day-angle-2026-11-07").performScrollTo().assertTextEquals("2.0°")
        compose.onNodeWithTag("gait-statistics-day-signed-2026-11-07").assertTextEquals("Signed mean +2.0°")
        compose.onNodeWithTag("gait-statistics-day-direction-2026-11-07").performScrollTo()
            .assertTextEquals("Your left shoulder was lower on average")
        compose.onNodeWithTag("gait-statistics-day-delta-2026-11-09").performScrollTo()
            .assertTextEquals("0.0° from previous measured day")
        compose.onNodeWithTag("gait-statistics-day-signed-2026-11-09").performScrollTo()
            .assertTextEquals("Signed mean -2.0°")
    }

    @Test fun poseAndMarkerMethodsHaveSeparateSelectableSeriesAndPoseHasNoSpineFilter() {
        val markers = session("markers", 9, 50.0, source = "LIVE").copy(protocolVersion = 9)
        val pose = session("pose", 10, 1.0, source = "LIVE").copy(protocolVersion = 9,
            measurementMethod = GaitMethod.POSE)
        compose.setContent { AppTheme { GaitStatistics(listOf(markers, pose), {}, {}) } }
        compose.onNodeWithTag("gait-statistics-latest-angle").performScrollTo().assertTextEquals("1.0°")
        compose.onNodeWithTag("gait-statistics-view-BACK").performScrollTo().performClick()
        compose.onNodeWithTag("gait-statistics-metric-BACK-PELVIS").assertExists()
        compose.onNodeWithTag("gait-statistics-metric-BACK-SPINE").assertDoesNotExist()
        compose.onNodeWithTag("gait-statistics-group-selector").performScrollTo().performClick()
        compose.onNodeWithTag("gait-statistics-group-markers").performClick()
        compose.onNodeWithTag("gait-statistics-latest-angle").performScrollTo().assertTextEquals("50.0°")
        compose.onNodeWithTag("gait-statistics-view-BACK").performScrollTo().performClick()
        compose.onNodeWithTag("gait-statistics-metric-BACK-SPINE").assertExists()
    }
}
