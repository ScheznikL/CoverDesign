package com.example.coverdesign.gait

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.coverdesign.ui.theme.AppTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Photo-calibration controls only; fixtures never enter a real account's saved history. */
@RunWith(AndroidJUnit4::class)
class GaitMarkerSetupUiTest {
    @get:Rule val compose = createComposeRule()
    private val frontSamples = mapOf(
        MarkerColor.SHOULDERS to Color(0xFFCFD239),
        MarkerColor.PELVIS to Color(0xFF59CED6),
        MarkerColor.KNEES to Color(0xFFED1F5F)
    )
    private val backSamples = frontSamples - MarkerColor.KNEES + mapOf(
        MarkerColor.C7 to Color(0xFFAB55D5),
        MarkerColor.COCCYX to Color(0xFFF08026)
    )

    @Test fun photoCaptureIsTheOnlyColourSetupActionAndResetClearsThePalette() {
        val captures = AtomicInteger()
        val resets = AtomicInteger()
        var saved by mutableStateOf<Map<MarkerColor, Color>>(emptyMap())
        compose.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        GaitBodyColourCaptureControls(GaitView.FRONT, saved,
                            paletteReady = saved.keys.containsAll(frontSamples.keys), paletteProblem = null,
                            onCapture = { captures.incrementAndGet(); saved = frontSamples },
                            onReset = { resets.incrementAndGet(); saved = emptyMap() })
                    }
                }
            }
        }
        compose.onNodeWithText("Front view: 0/3 required colours captured.").assertIsDisplayed()
        compose.onNodeWithText("Colours are selected only from a photo with the markers attached to your body.").assertIsDisplayed()
        compose.onNodeWithText("Paper sample setup").assertDoesNotExist()
        compose.onNodeWithText("Use this colour").assertDoesNotExist()
        compose.onNodeWithText("Camera colour sample").assertDoesNotExist()
        compose.onNodeWithTag("gait-capture-body-colours").performScrollTo().performClick()
        compose.onNodeWithText("Front view: 3/3 required colours captured.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ready for front marker checks.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, captures.get()) }
        compose.onNodeWithTag("gait-reset-body-colours").performScrollTo().performClick()
        compose.onNodeWithText("Front view: 0/3 required colours captured.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ready for front marker checks.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, resets.get()) }
    }

    @Test fun backViewRemainsIncompleteUntilItsOwnPhotoIncludesC7AndCoccyx() {
        var view by mutableStateOf(GaitView.FRONT)
        var saved by mutableStateOf<Map<MarkerColor, Color>>(frontSamples)
        var ready by mutableStateOf(true)
        var problem by mutableStateOf<String?>(null)
        compose.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        GaitBodyColourCaptureControls(view, saved, ready, problem, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Ready for front marker checks.").assertIsDisplayed()
        compose.runOnIdle { view = GaitView.BACK; saved = emptyMap(); ready = false }
        compose.onNodeWithText("Back view: 0/4 required colours captured.").assertIsDisplayed()
        compose.onNodeWithText("Ready for back marker checks.").assertDoesNotExist()
        compose.runOnIdle { saved = backSamples - MarkerColor.COCCYX }
        compose.onNodeWithText("Back view: 3/4 required colours captured.").assertIsDisplayed()
        compose.onNodeWithText("Ready for back marker checks.").assertDoesNotExist()
        compose.runOnIdle { saved = backSamples; ready = true }
        compose.onNodeWithText("Back view: 4/4 required colours captured.").assertIsDisplayed()
        compose.onNodeWithText("Ready for back marker checks.").assertIsDisplayed()
        compose.runOnIdle { problem = "C7 and coccyx look too similar. Choose another photo or different marker paper." }
        compose.onNodeWithText("Ready for back marker checks.").assertDoesNotExist()
        compose.onNodeWithText("C7 and coccyx look too similar. Choose another photo or different marker paper.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun detectionCardsShowActualMarkerCountsIndependentlyOfBodyFraming() {
        val found = setOf(MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER, MarkerId.LEFT_PELVIS)
        compose.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        GaitMarkerDetectionCards(GaitView.FRONT, found, capturedColours = frontSamples)
                    }
                }
            }
        }
        compose.onNodeWithText("Shoulders").assertIsDisplayed()
        compose.onNodeWithText("2/2 visible").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shoulders markers visible").assertIsDisplayed()
        compose.onNodeWithText("1/2 visible").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0/2 visible").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Pelvis markers visible").assertDoesNotExist()
        compose.onNodeWithContentDescription("Knees markers visible").assertDoesNotExist()
        compose.onAllNodesWithText("Your marker colour").assertCountEquals(3)
    }

    @Test fun photoCaptureAndResetRemainReachableOnCompactScreenWithLargeText() {
        val captures = AtomicInteger()
        val resets = AtomicInteger()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppTheme {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        GaitCaptureLayout(title = "Check front markers", onPause = {},
                            preview = { GaitCameraOverlay(null, GaitView.FRONT, true) },
                            guidance = {
                                GaitBodyColourCaptureControls(GaitView.FRONT, emptyMap(), false, null,
                                    { captures.incrementAndGet() }, { resets.incrementAndGet() })
                            })
                    }
                }
            }
        }
        compose.onNodeWithTag("gait-capture-body-colours").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("gait-reset-body-colours").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Paper sample setup").assertDoesNotExist()
        compose.onNodeWithText("Use this colour").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, captures.get()); assertEquals(1, resets.get()) }
    }
}
