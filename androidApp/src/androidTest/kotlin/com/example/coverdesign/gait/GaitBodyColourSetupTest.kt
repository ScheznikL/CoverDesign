package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coverdesign.ui.theme.AppTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only flat paper pixels. No person, pose, camera, external image or saved file is involved. */
@RunWith(AndroidJUnit4::class)
class GaitBodyColourSetupTest {
    @get:Rule val compose = createComposeRule()
    private val colours = mapOf(
        MarkerColor.SHOULDERS to Color(0xFF20BFAE),
        MarkerColor.PELVIS to Color(0xFFE047BD),
        MarkerColor.KNEES to Color(0xFF3E95DC)
    )
    private val centres = mapOf(
        MarkerColor.SHOULDERS to listOf(MarkerPoint(.30, .20), MarkerPoint(.70, .20)),
        MarkerColor.PELVIS to listOf(MarkerPoint(.35, .48), MarkerPoint(.65, .48)),
        MarkerColor.KNEES to listOf(MarkerPoint(.35, .75), MarkerPoint(.65, .75))
    )

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-test-visuals")!!
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun photograph(): GaitColourSnapshot {
        val width = 240
        val height = 480
        val pixels = IntArray(width * height) { 0xFF777777.toInt() }
        centres.forEach { (group, papers) -> papers.forEach { point ->
            val cx = (point.x * width).roundToInt()
            val cy = (point.y * height).roundToInt()
            for (y in cy - 8..cy + 8) for (x in cx - 8..cx + 8) pixels[y * width + x] = colours.getValue(group).toArgb()
        } }
        return GaitColourSnapshot(pixels, width, height, 1, GaitView.FRONT)
    }

    /** Independently map original centres into the specified centred FIT + zoom viewport. */
    private fun tap(snapshot: GaitColourSnapshot, point: MarkerPoint, zoom: Float = 1f) {
        val viewport = compose.onNodeWithTag("body-colour-viewport")
        val bounds = viewport.fetchSemanticsNode().boundsInRoot
        val scale = min(bounds.width / snapshot.width, bounds.height / snapshot.height) * zoom
        val x = (bounds.width - snapshot.width * scale) / 2 + point.x.toFloat() * snapshot.width * scale
        val y = (bounds.height - snapshot.height * scale) / 2 + point.y.toFloat() * snapshot.height * scale
        viewport.performTouchInput { click(Offset(x, y)) }
    }

    @Test fun selectsNewRealPaperCoresRejectsSamePointAndInvertsZoomBeforeApply() {
        val snapshot = photograph()
        // Existing colours are deliberately assigned differently. None count as new selections.
        val existing = MarkerColorCalibration(mapOf(
            MarkerColor.SHOULDERS to MarkerColorTarget(310.0, 14.0, .4, .2),
            MarkerColor.PELVIS to MarkerColorTarget(175.0, 14.0, .4, .2),
            MarkerColor.KNEES to MarkerColorTarget(210.0, 14.0, .4, .2)
        ))
        assertTrue(existing.readyFor(GaitView.FRONT))
        val applied = AtomicReference<Pair<MarkerColorCalibration, Map<MarkerColor, Color>>?>(null)
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) {
            GaitBodyColourSetup(snapshot, existing, { palette, swatches -> applied.set(palette to swatches) }, {}, {})
        } } }
        compose.onNodeWithTag("body-retake").assertIsDisplayed()
        compose.onNodeWithTag("body-cancel").assertIsDisplayed()
        compose.onNodeWithTag("body-apply").assertIsNotEnabled()
        val shoulders = centres.getValue(MarkerColor.SHOULDERS)
        tap(snapshot, shoulders[0])
        tap(snapshot, shoulders[0])
        compose.onNodeWithTag("body-sample-message").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose the other marker on the opposite side of the body.").assertExists()
        compose.onNodeWithTag("body-apply").assertIsNotEnabled()
        compose.onNodeWithTag("body-undo").performScrollTo().performClick()
        tap(snapshot, shoulders[1])
        compose.onNodeWithTag("body-selection-progress").performScrollTo().assertTextEquals("Pelvis: 0/2 centres selected")
        compose.onNodeWithTag("body-apply").assertIsNotEnabled()

        compose.onNodeWithTag("body-zoom-2").performClick().assertIsSelected()
        centres.getValue(MarkerColor.PELVIS).forEach { tap(snapshot, it, 2f) }
        compose.onNodeWithTag("body-selection-progress").performScrollTo().assertTextEquals("Knees: 0/2 centres selected")
        compose.onNodeWithTag("body-apply").assertIsNotEnabled()
        compose.onNodeWithTag("body-zoom-1").performClick()
        centres.getValue(MarkerColor.KNEES).forEach { tap(snapshot, it) }
        colours.keys.forEach { group ->
            compose.onNodeWithTag("body-swatch-${group.name}", useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag("body-selection-progress").performScrollTo()
        screenshot("body-colour-editor-filled")
        compose.onNodeWithTag("body-apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            val result = requireNotNull(applied.get())
            assertTrue(result.first.readyFor(GaitView.FRONT))
            assertEquals(colours, result.second)
        }
    }

    @Test fun draggingDoesNotSelectPaperAndRetakeCancelStayAvailableAtHighZoom() {
        val snapshot = photograph()
        val retakes = AtomicInteger()
        val cancels = AtomicInteger()
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) {
            GaitBodyColourSetup(snapshot, MarkerColorCalibration(), { _, _ -> }, { retakes.incrementAndGet() }, { cancels.incrementAndGet() })
        } } }
        compose.onNodeWithTag("body-zoom-8").performClick().assertIsSelected()
        compose.onNodeWithTag("body-colour-viewport").performTouchInput {
            swipe(Offset(center.x, center.y), Offset(center.x, center.y + height * .15f), durationMillis = 300)
        }
        compose.onNodeWithTag("body-selection-progress").performScrollTo().assertTextEquals("Shoulders: 0/2 centres selected")
        compose.onNodeWithTag("body-apply").assertIsNotEnabled()
        compose.onNodeWithTag("body-retake").assertIsDisplayed().performClick()
        compose.onNodeWithTag("body-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retakes.get()); assertEquals(1, cancels.get()) }
    }

    @Test fun compactLargeFontKeepsSelectionViewportZoomAndActionsVisibleWhileGuidanceScrolls() {
        val snapshot = photograph()
        val densityValue = AtomicReference(1f)
        compose.setContent {
            val density = LocalDensity.current
            densityValue.set(density.density)
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppTheme { Box(Modifier.requiredSize(360.dp, 528.dp)) {
                    Surface(Modifier.fillMaxSize()) {
                        GaitBodyColourSetup(snapshot, MarkerColorCalibration(), { _, _ -> }, {}, {})
                    }
                } }
            }
        }
        val viewport = compose.onNodeWithTag("body-colour-viewport").fetchSemanticsNode().boundsInRoot
        val px = densityValue.get()
        assertTrue("Selection image must retain at least 144 dp of height at large font", viewport.height >= 144f * px)
        assertTrue("Selection viewport must retain the compact screen width", viewport.width >= 320f * px)
        listOf(1, 2, 4, 8).forEach { compose.onNodeWithTag("body-zoom-$it").assertIsDisplayed() }
        listOf("body-apply", "body-retake", "body-cancel").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("body-reset-group").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("body-selection-progress").assertIsNotDisplayed()
        assertEquals("Scrolling guidance must not move the photograph", viewport,
            compose.onNodeWithTag("body-colour-viewport").fetchSemanticsNode().boundsInRoot)
        listOf(1, 2, 4, 8).forEach { compose.onNodeWithTag("body-zoom-$it").assertIsDisplayed() }
        listOf("body-apply", "body-retake", "body-cancel").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        screenshot("body-colour-editor-compact-large-font")

        // Verify the retained viewport can still choose a real paper core at this font size.
        compose.onNodeWithTag("body-group-PELVIS").performScrollTo().performClick()
        compose.onNodeWithTag("body-zoom-2").performClick()
        tap(snapshot, centres.getValue(MarkerColor.PELVIS).first(), 2f)
        compose.onNodeWithTag("body-selection-progress").performScrollTo().assertTextEquals("Pelvis: 1/2 centres selected")
        compose.onNodeWithTag("body-apply").assertIsNotEnabled()
        compose.onNodeWithTag("body-retake").assertIsDisplayed()
        compose.onNodeWithTag("body-cancel").assertIsDisplayed()
    }
}
