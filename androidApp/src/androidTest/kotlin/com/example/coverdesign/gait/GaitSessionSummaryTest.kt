package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coverdesign.ui.theme.AppTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic session summaries only. No camera, private files or account history. */
@RunWith(AndroidJUnit4::class)
class GaitSessionSummaryTest {
    @get:Rule val compose = createComposeRule()

    private fun front(id: String, at: Long, angle: Double) = GaitSession(id, at, GaitProfile(),
        (1..3).map { GaitSample(it * 200L, GaitView.FRONT, mapOf(
            GaitMetric.SHOULDERS to angle, GaitMetric.PELVIS to angle, GaitMetric.KNEES to angle), 0, 1) },
        0, 0, 3, 0, true, false, 8, frontValidDurationMs = 6_000,
        acquisitionSource = "VIDEO", sourceDurationMs = 20_000, videoViews = setOf(GaitView.FRONT))

    private fun show(session: GaitSession, history: List<GaitSession> = emptyList()) {
        compose.setContent { AppTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                GaitSessionSummary(session, history)
            }
        } }
    }

    @Test fun smallReadingHasAnOverallResultWithoutAnInventedClinicalVerdict() {
        show(front("small", 4_000, 1.0))
        compose.onNodeWithText("Overall result").assertIsDisplayed()
        compose.onNodeWithText("Front-view coverage available").assertIsDisplayed()
        compose.onNodeWithText("3 of 6 marker lines measured · 3 eligible for trends").assertExists()
        compose.onNodeWithText("A reading of 1° alone does not establish abnormal gait.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Normal gait").assertDoesNotExist()
        compose.onNodeWithText("Abnormal gait").assertDoesNotExist()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-test-visuals"))
        dir.mkdirs()
        File(dir, "overall-session-summary.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun personalRangeNeedsThreeEarlierComparableSavedSessions() {
        val current = front("now", 4_000, 1.7)
        show(current, listOf(front("first", 1_000, 1.0), front("second", 2_000, 1.5), front("third", 3_000, 2.0)))
        compose.onNodeWithText("previously 1.0°–2.0° across 3 comparable saved sessions.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gait-overall-explanation").performScrollTo().performClick()
        compose.onNodeWithText("This is an observed personal range, not a medical normal range or an estimate of measurement error.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun shortSessionKeepsItsAnglesButDoesNotClaimComparableTrendCoverage() {
        show(front("short", 4_000, 1.0).copy(frontValidDurationMs = 500))
        compose.onNodeWithText("Limited data — repeat the measurement before comparing changes").assertIsDisplayed()
        compose.onNodeWithText("3 of 6 marker lines measured · 0 eligible for trends").assertExists()
        compose.onNodeWithTag("gait-overall-explanation").performScrollTo().performClick()
        compose.onNodeWithText("A personal recorded range is shown after at least three earlier comparable sessions with enough data for the same marker line.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun poseSummaryUsesFiveLinesAndExplainsUnavailableSpineWithoutLosingCoverage() {
        val front = front("pose", 4_000, 1.0).copy(measurementMethod = GaitMethod.POSE,
            protocolVersion = 9, acquisitionSource = "LIVE")
        val rear = (1..3).map { GaitSample(1_000 + it * 200L, GaitView.BACK,
            mapOf(GaitMetric.SHOULDERS to 1.2, GaitMetric.PELVIS to .8), 0, 2) }
        show(front.copy(samples = front.samples + rear, acceptedFrames = 6, backValidDurationMs = 6_000))
        compose.onNodeWithTag("gait-measurement-method").assertExists()
        compose.onNodeWithText("Pose landmarks").assertIsDisplayed()
        compose.onNodeWithText("5 of 5 pose lines measured · 5 eligible for trends").assertExists()
        compose.onNodeWithText("Coverage available for both views").assertIsDisplayed()
        compose.onNodeWithText("This method does not measure C7 or the coccyx.").assertExists()
        compose.onNodeWithText("5 of 6 marker lines measured", substring = true).assertDoesNotExist()
    }

    @Test fun poseSummaryDoesNotCompareEarlierMarkerRecordingsWithMatchingProtocol() {
        val current = front("pose", 4_000, 1.0).copy(measurementMethod = GaitMethod.POSE,
            protocolVersion = 9, acquisitionSource = "LIVE")
        val markers = (1..3).map { index -> front("marker-$index", index * 1_000L, 80.0)
            .copy(protocolVersion = 9, acquisitionSource = "LIVE") }
        show(current, markers)
        compose.onNodeWithText("Your previous recorded range").assertDoesNotExist()
        compose.onNodeWithText("Largest numeric change", substring = true).assertDoesNotExist()
        compose.onNodeWithText("3 of 5 pose lines measured · 3 eligible for trends").assertExists()
    }
}
