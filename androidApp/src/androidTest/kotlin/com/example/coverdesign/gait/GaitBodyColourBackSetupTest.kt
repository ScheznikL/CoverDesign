package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coverdesign.ui.theme.AppTheme
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Six synthetic paper patches only; no camera, person, external media or saved images. */
@RunWith(AndroidJUnit4::class)
class GaitBodyColourBackSetupTest {
    @get:Rule val compose = createComposeRule()
    private val colours = mapOf(
        MarkerColor.SHOULDERS to Color(0xFF20BFAE),
        MarkerColor.PELVIS to Color(0xFFE047BD),
        MarkerColor.C7 to Color(0xFFFF2436),
        MarkerColor.COCCYX to Color(0xFFF5C02A)
    )
    private val centres = mapOf(
        MarkerColor.SHOULDERS to listOf(MarkerPoint(.30, .25), MarkerPoint(.70, .25)),
        MarkerColor.PELVIS to listOf(MarkerPoint(.35, .48), MarkerPoint(.65, .48)),
        MarkerColor.C7 to listOf(MarkerPoint(.50, .15)),
        MarkerColor.COCCYX to listOf(MarkerPoint(.50, .68))
    )

    private fun photograph(): GaitColourSnapshot {
        val width = 240
        val height = 480
        val pixels = IntArray(width * height) { 0xFF777777.toInt() }
        centres.forEach { (group, papers) -> papers.forEach { point ->
            val cx = (point.x * width).roundToInt()
            val cy = (point.y * height).roundToInt()
            for (y in cy - 8..cy + 8) for (x in cx - 8..cx + 8) {
                pixels[y * width + x] = colours.getValue(group).toArgb()
            }
        } }
        return GaitColourSnapshot(pixels, width, height, 1, GaitView.BACK)
    }

    /** The expectation derives FIT coordinates independently from the editor's tap conversion. */
    private fun tap(snapshot: GaitColourSnapshot, point: MarkerPoint) {
        val viewport = compose.onNodeWithTag("body-colour-viewport")
        val bounds = viewport.fetchSemanticsNode().boundsInRoot
        val scale = min(bounds.width / snapshot.width, bounds.height / snapshot.height)
        val x = (bounds.width - snapshot.width * scale) / 2 + point.x.toFloat() * snapshot.width * scale
        val y = (bounds.height - snapshot.height * scale) / 2 + point.y.toFloat() * snapshot.height * scale
        viewport.performTouchInput { click(Offset(x, y)) }
    }

    @Test fun sixRearPaperCentresBuildFourGroupsWithoutChangingTheFrontPalette() {
        val snapshot = photograph()
        val existing = MarkerColorCalibration().withViewTargets(GaitView.FRONT, mapOf(
            MarkerColor.SHOULDERS to MarkerColorTarget(65.0, 14.0, .25, .15),
            MarkerColor.PELVIS to MarkerColorTarget(197.0, 14.0, .30, .15),
            MarkerColor.KNEES to MarkerColorTarget(340.0, 14.0, .30, .15)
        ))
        val originalFront = existing.targetsFor(GaitView.FRONT)
        assertTrue(existing.readyFor(GaitView.FRONT))
        assertFalse(existing.readyFor(GaitView.BACK))
        val applied = AtomicReference<Pair<MarkerColorCalibration, Map<MarkerColor, Color>>?>(null)
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) {
            GaitBodyColourSetup(snapshot, existing,
                { palette, swatches -> applied.set(palette to swatches) }, {}, {})
        } } }
        compose.onNodeWithTag("body-group-KNEES").assertDoesNotExist()
        compose.onNodeWithTag("body-selection-progress").performScrollTo()
            .assertTextEquals("Shoulders: 0/2 centres selected")
        compose.onNodeWithTag("body-apply").assertIsNotEnabled()

        centres.getValue(MarkerColor.SHOULDERS).forEach { point ->
            tap(snapshot, point)
            compose.onNodeWithTag("body-apply").assertIsNotEnabled()
        }
        compose.onNodeWithTag("body-selection-progress").performScrollTo()
            .assertTextEquals("Pelvis: 0/2 centres selected")
        centres.getValue(MarkerColor.PELVIS).forEach { point ->
            tap(snapshot, point)
            compose.onNodeWithTag("body-apply").assertIsNotEnabled()
        }
        compose.onNodeWithTag("body-selection-progress").performScrollTo()
            .assertTextEquals("C7: 0/1 centres selected")
        tap(snapshot, centres.getValue(MarkerColor.C7).single())
        compose.onNodeWithTag("body-selection-progress").performScrollTo()
            .assertTextEquals("Tailbone: 0/1 centres selected")
        compose.onNodeWithTag("body-apply").assertIsNotEnabled()
        compose.runOnIdle { assertNull(applied.get()) }

        tap(snapshot, centres.getValue(MarkerColor.COCCYX).single())
        compose.onNodeWithTag("body-selection-progress").performScrollTo()
            .assertTextEquals("Tailbone: 1/1 centres selected")
        colours.keys.forEach { group ->
            compose.onNodeWithTag("body-swatch-${group.name}", useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag("body-apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            val result = requireNotNull(applied.get())
            assertTrue(result.first.readyFor(GaitView.BACK))
            assertTrue(result.first.readyFor(GaitView.FRONT))
            assertEquals(originalFront, result.first.targetsFor(GaitView.FRONT))
            assertEquals(originalFront, existing.targetsFor(GaitView.FRONT))
            assertEquals(colours.keys, result.first.targetsFor(GaitView.BACK).keys)
            assertEquals(colours, result.second)
        }
    }

    @Test fun lightingChoiceSurvivesApplyingTheSelectedBodyPapers() {
        val snapshot = photograph()
        val applied = AtomicReference<MarkerColorCalibration?>(null)
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) {
            GaitBodyColourSetup(snapshot, MarkerColorCalibration(),
                { palette, _ -> applied.set(palette) }, {}, {})
        } } }
        centres.values.flatten().forEach { tap(snapshot, it) }
        compose.onNodeWithTag("gait-lighting-variable").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("gait-lighting-standard").performScrollTo().performClick().assertIsSelected()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-test-visuals"))
        directory.mkdirs()
        File(directory, "lighting-tolerance-body.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("body-apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(MarkerLightingTolerance.STANDARD, requireNotNull(applied.get()).lightingTolerance)
            assertTrue(requireNotNull(applied.get()).readyFor(GaitView.BACK))
        }
    }
}
