package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.coverdesign.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Composable-only workflow tests. No Firebase calls, camera access or history persistence. */
@RunWith(AndroidJUnit4::class)
class GaitPagesTest {
    @get:Rule
    val compose = createComposeRule()

    private fun visual(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-test-visuals")!!
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun blankRequiredProfileFieldsPreventSavingAndExplainCorrections() {
        val saved = AtomicReference<GaitProfile?>(null)
        compose.setContent { AppTheme { GaitProfileEditor(null, onSave = saved::set, onBack = {}) } }

        compose.onNodeWithText("Save profile").performScrollTo().performClick()

        compose.runOnIdle { assertNull(saved.get()) }
        compose.onNodeWithText("Enter 80–250 cm").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enter 20–300 kg").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enter 5–110 years").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun validProfileSavesEnteredMeasurementsAndSelectedWalkingSettings() {
        val saved = AtomicReference<GaitProfile?>(null)
        compose.setContent { AppTheme { GaitProfileEditor(null, onSave = saved::set, onBack = {}) } }

        compose.onNode(hasSetTextAction() and hasText("Height (cm)"))
            .performScrollTo().performTextInput("176")
        compose.onNode(hasSetTextAction() and hasText("Weight (kg)"))
            .performScrollTo().performTextInput("74")
        compose.onNode(hasSetTextAction() and hasText("Age (years)"))
            .performScrollTo().performTextInput("32")
        closeSoftKeyboard()
        compose.onNodeWithText("Female").performScrollTo().performClick()
        compose.onNodeWithText("3 seconds").performScrollTo().performClick()
        compose.onNodeWithText("12 usable seconds").performScrollTo().performClick()
        compose.onNodeWithText("Save profile").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(GaitProfile(176, 74, "Female", 32, 4, 12, 3, 12), saved.get())
        }
    }

    @Test
    fun instructionsSwitchMarkerViewsAndContinueAfterScrolling() {
        val continues = AtomicInteger(0)
        compose.setContent { AppTheme { GaitInstructions(onContinue = { continues.incrementAndGet() }, onBack = {}) } }

        compose.onNodeWithText("Front").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Pelvis: anterior superior iliac spines (ASIS)")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Pelvis: posterior superior iliac spines (PSIS)")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Back marker guide: two shoulders, two posterior pelvic landmarks, C7 and coccyx"
        ).assertExists()
        compose.onNodeWithText("Knees: centres of the kneecaps").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back marker guide: two shoulders, two posterior pelvic landmarks, C7 and coccyx").performScrollTo()
        visual("back-marker-guide")
        compose.runOnIdle { assertEquals(0, continues.get()) }
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, continues.get()) }
    }

    @Test
    fun emptyHistoryExplainsHowResultsAppearWithoutInventingMeasurements() {
        val opened = AtomicInteger(0)
        compose.setContent { AppTheme { GaitHistory(emptyList(), onOpen = { opened.incrementAndGet() }, onBack = {}) } }

        compose.onNodeWithText("No measurements yet").assertIsDisplayed()
        compose.onNodeWithText("Complete your first guided walk to see its numerical results here. Live video is never stored.")
            .assertIsDisplayed()
        compose.onAllNodesWithContentDescription("View measurement").assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, opened.get()) }
        visual("empty-history")
    }

    @Test
    fun partialResultsExplainDirectionTrackingQualityAndLimitsWithoutDiagnosticCategories() {
        // Test-only in-memory samples; this test never constructs a GaitStore.
        val session = GaitSession(
            id = "ui-test-only",
            createdAt = 1_700_000_000_000,
            profile = GaitProfile(),
            samples = listOf(
                GaitSample(100, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 4.0, GaitMetric.PELVIS to -2.0, GaitMetric.KNEES to 1.0), 1),
                GaitSample(200, GaitView.BACK, mapOf(GaitMetric.SHOULDERS to -1.0, GaitMetric.PELVIS to 1.0, GaitMetric.SPINE to -3.0), 1)
            ),
            frontSteps = 3,
            backSteps = 2,
            acceptedFrames = 2,
            rejectedFrames = 1,
            manualLevel = true,
            completed = false,
            protocolVersion = 5
        )
        compose.setContent { AppTheme { GaitResults(session, previous = null, onBack = {}) } }

        compose.onNodeWithText("Partial measurement").assertIsDisplayed()
        compose.onNodeWithText("Session stopped before completion").assertIsDisplayed()
        compose.onNodeWithText("Coverage describes usable tracking, not angular accuracy.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your left shoulder was lower on average")
            .performScrollTo().assertIsDisplayed()
        visual("results-shoulders")
        compose.onNodeWithText("Your right pelvic marker was lower on average")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Average lean towards your left")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This is the C7–coccyx marker line, not the shape or curvature of the spine.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A smaller angle alone does not establish improved health.", substring = true)
            .performScrollTo().assertIsDisplayed()
        listOf("Normal", "Abnormal", "Mild", "Severe").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }
    @Test fun timedResultsShowUsableSecondsAndDoNotInventUnseenSteps() {
        val session = GaitSession("timed-ui", 1_700_000_000_000, GaitProfile(),
            listOf(GaitSample(100, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 2.0), 0, 1)),
            0, 0, 1, 0, false, false, 6,
            frontValidDurationMs = 1500, backValidDurationMs = 2300,
            frontCompletedPasses = 1, backCompletedPasses = 2,
            frontStepsPartial = true, backStepsPartial = true)
        compose.setContent { AppTheme { GaitResults(session, previous = null, onBack = {}) } }
        compose.onNodeWithText("Front: 1.5 / 6 usable seconds", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back: 2.3 / 6 usable seconds", substring = true).assertExists()
        compose.onNodeWithText("Front: step count unavailable", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 / 12 estimated steps", substring = true).assertDoesNotExist()
        visual("timed-results-unavailable-steps")
    }

    @Test fun importedPartialResultShowsItsSourceAndAvailableAnglesWithoutSensorOrStepClaims() {
        val session = GaitSession("video-ui", 1_700_000_000_000, GaitProfile(),
            listOf(GaitSample(2_000, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 2.0), 0, 1)),
            0, 0, 1, 0, true, false, protocolVersion = 8,
            frontValidDurationMs = 1_500, frontStepsPartial = true, backStepsPartial = true,
            acquisitionSource = "VIDEO", sourceDurationMs = 40_000, sourceMirrored = true,
            videoViews = setOf(GaitView.FRONT))
        compose.setContent { AppTheme { GaitResults(session, null, {}) } }
        compose.onNodeWithText("Recorded video analysed").assertIsDisplayed()
        compose.onNodeWithText("Recording: 40.0 seconds · front selected").assertExists()
        compose.onNodeWithText("Mirrored recording corrected before measurement.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Front: 1.5 usable seconds", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Step counts are not estimated from imported video.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Phone orientation was checked with sensors.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Your left shoulder was lower on average").performScrollTo().assertIsDisplayed()
        visual("imported-video-results")
        compose.onNodeWithText("Back view").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No usable samples for this view.").assertExists()
    }

    @Test fun menuOffersLiveAndRecordedPathsIndependently() {
        val live = AtomicInteger(0)
        val video = AtomicInteger(0)
        compose.setContent { AppTheme {
            GaitMenu(GaitProfile(), onStart = { live.incrementAndGet() }, onHistory = {},
                onProfile = {}, onInstructions = {}, onBack = {}, onVideo = { video.incrementAndGet() })
        } }
        compose.onNodeWithText("Live with markers").performScrollTo().performClick()
        compose.onNodeWithText("Analyse a video").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, live.get()); assertEquals(1, video.get()) }
        visual("gait-two-modes-menu")
    }

}
