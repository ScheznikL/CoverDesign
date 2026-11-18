package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coverdesign.ui.theme.AppTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Visuals contain only the illustrative guide: no camera, account, session or personal data. */
@RunWith(AndroidJUnit4::class)
class GaitMarkerGuideVisualTest {
    @get:Rule val compose = createComposeRule()

    private fun guide(view: GaitView): Pair<Bitmap, Int> {
        val fill = AtomicInteger()
        compose.setContent {
            AppTheme {
                fill.set(MaterialTheme.colorScheme.surfaceVariant.toArgb())
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), contentAlignment = Alignment.TopCenter) {
                        MarkerGuide(view, Modifier.width(320.dp))
                    }
                }
            }
        }
        val front = view == GaitView.FRONT
        compose.onNodeWithText(if (front) "Front view" else "Back view").assertIsDisplayed()
        compose.onNodeWithText("Approximate silhouette. Stand naturally; do not try to match its proportions.")
            .assertExists()
        val description = if (front) "Front marker guide: two shoulders, two anterior pelvic landmarks and two kneecaps"
                          else "Back marker guide: two shoulders, two posterior pelvic landmarks, C7 and coccyx"
        val canvas = compose.onNodeWithContentDescription(description).captureToImage().asAndroidBitmap()
        val prefix = if (front) "front-human-guide" else "back-human-guide"
        save(canvas, "$prefix-figure.png")
        save(compose.onRoot().captureToImage().asAndroidBitmap(), "$prefix.png")
        return canvas to fill.get()
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-test-visuals")!!
        directory.mkdirs()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Maps the same bounds as the production 300 dp canvas, independent of device density. */
    private fun pixel(bitmap: Bitmap, position: Offset): Int {
        val density = bitmap.height / 300f
        val height = (bitmap.height - 16f * density).coerceAtMost(bitmap.width / GaitSilhouetteAspectRatio)
        val width = height * GaitSilhouetteAspectRatio
        val x = (bitmap.width - width) / 2 + position.x * width
        val y = (bitmap.height - height) / 2 + position.y * height
        return bitmap.getPixel(x.roundToInt(), y.roundToInt())
    }

    private fun verifyFigureAndMarkers(bitmap: Bitmap, fill: Int, view: GaitView) {
        assertEquals("The head must be fully present", fill, pixel(bitmap, Offset(.5f, .04f)))
        // The exposed neck below the head, above the C7 marker's white border.
        assertEquals("The neck must connect head and torso", fill, pixel(bitmap, Offset(.5f, .166f)))
        assertEquals("The left foot must fit in the guide", fill, pixel(bitmap, Offset(.4f, .965f)))
        assertEquals("The right foot must fit in the guide", fill, pixel(bitmap, Offset(.6f, .965f)))
        markerGuidePositions(view).forEach { (id, position) ->
            assertEquals("$id must retain its marker colour", markerColour(id).toArgb(), pixel(bitmap, position))
        }
        assertNotEquals("The two pelvic markers must remain visibly separate",
            markerColour(MarkerId.LEFT_PELVIS).toArgb(), pixel(bitmap, Offset(.5f, .480f)))
    }

    @Test fun frontGuideShowsFullHumanFigureAndSixSeparateMarkers() {
        val (bitmap, fill) = guide(GaitView.FRONT)
        verifyFigureAndMarkers(bitmap, fill, GaitView.FRONT)
        compose.onNodeWithText("Pelvis: anterior superior iliac spines (ASIS)").assertExists()
        compose.onNodeWithText("Knees: centres of the kneecaps").assertExists()
        compose.onNodeWithText("Tailbone (coccyx):", substring = true).assertDoesNotExist()
    }

    @Test fun backGuideSeparatesPosteriorPelvisC7AndCoccyxWithoutKneeMarkers() {
        val (bitmap, fill) = guide(GaitView.BACK)
        verifyFigureAndMarkers(bitmap, fill, GaitView.BACK)
        compose.onNodeWithText("Pelvis: posterior superior iliac spines (PSIS)").assertExists()
        compose.onNodeWithText("C7: one marker", substring = true).assertExists()
        compose.onNodeWithText("Tailbone (coccyx): one marker", substring = true).assertExists()
        compose.onNodeWithText("Knees: centres of the kneecaps").assertDoesNotExist()
    }
}
