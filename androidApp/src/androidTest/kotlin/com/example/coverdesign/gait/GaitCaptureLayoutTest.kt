package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
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
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.theme.AppTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production capture layout with a synthetic preview; never opens a camera. */
@RunWith(AndroidJUnit4::class)
class GaitCaptureLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val title = "2 · Check front markers"

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun captureViewport(fontScale: Float, pause: () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppTheme {
                    Box(Modifier.requiredSize(360.dp, 640.dp), contentAlignment = Alignment.TopStart) {
                        Scaffold(topBar = { AppTopBar("Gait analysis", onBack = {}, actions = {
                            TextButton(onClick = {}) { Text("Sound on") }
                        }) }) { padding ->
                        GaitCaptureLayout(
                            title = title,
                            onPause = pause,
                            modifier = Modifier.fillMaxSize().padding(padding),
                            preview = { Box(Modifier.fillMaxSize().background(Color(0xFF182B39))) {
                                GaitCameraOverlay(null, GaitView.FRONT, mirrored = true)
                            } },
                            guidance = {
                                Text("Fit your whole body and feet inside the camera view")
                                Text("Place the camera lens about 85 cm above the floor (around half your height of 170 cm).")
                                repeat(8) { index ->
                                    Text("Guidance ${index + 1}: keep your phone upright and your markers clearly visible while walking.")
                                }
                                Text("Guidance end")
                            }
                        )
                        }
                    }
                }
            }
        }
    }

    private fun verifySeparateRegionsAndScrolling() {
        val titleBounds = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val preview = bounds("gait-preview")
        val guidance = bounds("gait-guidance")
        val pause = bounds("gait-pause")
        assertTrue("The title must be above the preview", titleBounds.bottom <= preview.top + 1f)
        assertTrue("Camera and guidance must not overlap", preview.bottom <= guidance.top + 1f)
        assertTrue("Guidance must not cover the exit control", guidance.bottom <= pause.top + 1f)
        assertTrue("The preview must retain visible height", preview.height > 0)
        assertTrue("Guidance must retain visible height", guidance.height > 0)
        compose.onNodeWithTag("gait-pause").assertIsDisplayed()
        compose.onNodeWithText("Guidance end").performScrollTo().assertIsDisplayed()
        assertEquals("Scrolling guidance must not move the preview", preview, bounds("gait-preview"))
        assertEquals("Pause must remain available while scrolling", pause, bounds("gait-pause"))
        compose.onNodeWithTag("gait-pause").assertIsDisplayed()
    }

    @Test fun compactViewportKeepsCameraAndScrollableTextSeparate() {
        val pauses = AtomicInteger(0)
        captureViewport(1f) { pauses.incrementAndGet() }
        verifySeparateRegionsAndScrolling()
        compose.onNodeWithTag("gait-pause").performClick()
        compose.runOnIdle { assertEquals(1, pauses.get()) }
    }

    @Test fun compactViewportWithLargeFontKeepsCameraAndExitAccessible() {
        captureViewport(1.5f) {}
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-test-visuals")!!
        dir.mkdirs()
        File(dir, "capture-compact-large-font.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        verifySeparateRegionsAndScrolling()
    }

    @Test fun pausedSessionOffersThreeDistinctActions() {
        val continued = AtomicInteger(0)
        val saved = AtomicInteger(0)
        val discarded = AtomicInteger(0)
        var shown by mutableStateOf(true)
        compose.setContent {
            AppTheme {
                if (shown) GaitPauseDialog(
                    onContinue = { continued.incrementAndGet(); shown = false },
                    onSaveAndExit = { saved.incrementAndGet(); shown = false },
                    onDiscardAndExit = { discarded.incrementAndGet(); shown = false }
                )
            }
        }
        compose.onNodeWithText("Analysis paused").assertIsDisplayed()
        compose.onNodeWithText("Continue analysis").assertIsDisplayed()
        compose.onNodeWithText("Save and exit").assertIsDisplayed()
        compose.onNodeWithText("Exit without saving").assertIsDisplayed()
        compose.onNodeWithText("Continue analysis").performClick()
        compose.runOnIdle {
            assertEquals(1, continued.get()); assertEquals(0, saved.get()); assertEquals(0, discarded.get())
            shown = true
        }
        compose.onNodeWithText("Save and exit").performClick()
        compose.runOnIdle {
            assertEquals(1, continued.get()); assertEquals(1, saved.get()); assertEquals(0, discarded.get())
            shown = true
        }
        compose.onNodeWithText("Exit without saving").performClick()
        compose.runOnIdle {
            assertEquals(1, continued.get()); assertEquals(1, saved.get()); assertEquals(1, discarded.get())
        }
    }
}
