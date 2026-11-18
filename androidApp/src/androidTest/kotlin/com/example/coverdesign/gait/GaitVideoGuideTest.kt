package com.example.coverdesign.gait

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.coverdesign.ui.theme.AppTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Recording guidance is reachable before requesting any file, camera or storage permission. */
@RunWith(AndroidJUnit4::class)
class GaitVideoGuideTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordingInstructionsAndBothActionsAreReachableBeforeChoosingMedia() {
        val chosen = AtomicInteger(0)
        val backs = AtomicInteger(0)
        compose.setContent { AppTheme {
            GaitVideoRecordingGuide(onChooseVideo = { chosen.incrementAndGet() },
                onBack = { backs.incrementAndGet() })
        } }
        compose.onNodeWithText("Record a walking video").assertIsDisplayed()
        compose.onNodeWithText("Nothing is uploaded. Only numerical results are saved.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("video-choose").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Back to menu").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, chosen.get())
            assertEquals(1, backs.get())
        }
    }
}
