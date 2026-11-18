package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coverdesign.ui.theme.AppTheme
import java.io.File
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic marker positions deliberately differ from the illustrative silhouette. */
@RunWith(AndroidJUnit4::class)
class GaitCameraOverlayTest {
    @get:Rule val compose = createComposeRule()

    private fun render(view: GaitView, mirrored: Boolean): Pair<Bitmap, GaitFrame> {
        val points = if (view == GaitView.FRONT) mapOf(
            MarkerId.LEFT_SHOULDER to MarkerPoint(.78, .24), MarkerId.RIGHT_SHOULDER to MarkerPoint(.25, .28),
            MarkerId.LEFT_PELVIS to MarkerPoint(.67, .52), MarkerId.RIGHT_PELVIS to MarkerPoint(.33, .49),
            MarkerId.LEFT_KNEE to MarkerPoint(.61, .76), MarkerId.RIGHT_KNEE to MarkerPoint(.38, .74)
        ) else mapOf(
            MarkerId.LEFT_SHOULDER to MarkerPoint(.25, .26), MarkerId.RIGHT_SHOULDER to MarkerPoint(.78, .23),
            MarkerId.LEFT_PELVIS to MarkerPoint(.34, .49), MarkerId.RIGHT_PELVIS to MarkerPoint(.66, .52),
            MarkerId.C7 to MarkerPoint(.46, .18), MarkerId.COCCYX to MarkerPoint(.53, .59)
        )
        // A 1:2 frame in a 3:4 preview exercises the FIT letterbox, as well as selfie mirroring.
        val frame = GaitFrame(10_000, 400, 800, points, view, true, .1)
        compose.setContent {
            AppTheme {
                Box(Modifier.requiredSize(300.dp, 400.dp).background(Color(0xFF293E48))) {
                    GaitCameraOverlay(frame, view, mirrored)
                }
            }
        }
        val bitmap = compose.onNodeWithContentDescription(
            if (view == GaitView.FRONT) "Front positioning silhouette" else "Back positioning silhouette"
        ).captureToImage().asAndroidBitmap()
        val name = if (view == GaitView.FRONT) "front-camera-silhouette.png" else "back-camera-silhouette.png"
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("gait-test-visuals")!!
        dir.mkdirs()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap to frame
    }

    private fun verifyActualMarkers(bitmap: Bitmap, frame: GaitFrame, mirrored: Boolean) {
        val scale = min(bitmap.width.toFloat() / frame.width, bitmap.height.toFloat() / frame.height)
        val left = (bitmap.width - frame.width * scale) / 2
        val top = (bitmap.height - frame.height * scale) / 2
        frame.markers.forEach { (id, p) ->
            val x = left + (if (mirrored) 1 - p.x else p.x) * frame.width * scale
            val y = top + p.y * frame.height * scale
            assertEquals("$id must be drawn at its actual detected coordinate", 0xFF4BDBA1.toInt(), bitmap.getPixel(x.toInt(), y.toInt()))
        }
    }

    @Test fun selfieGuideUsesMirroredDetectedCoordinatesWithFitMargins() {
        val (bitmap, frame) = render(GaitView.FRONT, true)
        verifyActualMarkers(bitmap, frame, true)
    }

    @Test fun rearViewUsesUnmirroredDetectedCoordinatesWithFitMargins() {
        val (bitmap, frame) = render(GaitView.BACK, false)
        verifyActualMarkers(bitmap, frame, false)
    }
}
