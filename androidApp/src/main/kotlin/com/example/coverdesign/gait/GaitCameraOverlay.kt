package com.example.coverdesign.gait

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.GaitMarkerOutline
import com.example.coverdesign.ui.theme.GaitOverlayFallback
import com.example.coverdesign.ui.theme.GaitOverlayWhite
import com.example.coverdesign.ui.theme.GaitPhotoBackground
import com.example.coverdesign.ui.theme.GaitReferenceGrid
import com.example.coverdesign.ui.theme.GaitSilhouetteDetail
import com.example.coverdesign.ui.theme.GaitSilhouetteFill
import com.example.coverdesign.ui.theme.GaitTrackingGreen
import kotlin.math.min

/** The silhouette guides framing only. Measured dots/lines use points from the selected method. */
@Composable
internal fun GaitCameraOverlay(frame: GaitFrame?, view: GaitView, mirrored: Boolean, modifier: Modifier = Modifier, capturedColours: Map<MarkerColor, Color> = emptyMap(), measurementMethod: GaitMethod = GaitMethod.MARKERS) {
    Canvas(modifier.fillMaxSize().semantics {
        contentDescription = if (measurementMethod == GaitMethod.POSE) {
            if (view == GaitView.FRONT) "Front body-landmark positioning silhouette" else "Back body-landmark positioning silhouette"
        } else if (view == GaitView.FRONT) "Front positioning silhouette" else "Back positioning silhouette"
    }) {
        val width = frame?.width ?: 480
        val height = frame?.height ?: 640
        val scale = min(size.width / width, size.height / height)
        val ox = (size.width - width * scale) / 2
        val oy = (size.height - height * scale) / 2
        val image = Rect(ox, oy, ox + width * scale, oy + height * scale)
        fun actualPoint(p: MarkerPoint) = Offset(
            image.left + (if (mirrored) 1.0 - p.x else p.x).toFloat() * image.width,
            image.top + p.y.toFloat() * image.height
        )
        val guideHeight = min(image.height * .94f, image.width * .90f / GaitSilhouetteAspectRatio)
        val guideWidth = guideHeight * GaitSilhouetteAspectRatio
        val guide = Rect(image.center.x - guideWidth / 2, image.center.y - guideHeight / 2,
            image.center.x + guideWidth / 2, image.center.y + guideHeight / 2)
        val visibleBody = frame?.bodyVisible == true || frame?.measurementVisible(view, measurementMethod) == true
        val guideAlpha = if (visibleBody) .15f else .72f
        drawGaitSilhouette(guide, view, GaitSilhouetteFill, GaitOverlayWhite, GaitSilhouetteDetail, guideAlpha)
        // Reference axes are independent of the person's shoulders and pelvis.
        val grid = GaitReferenceGrid
        drawLine(grid, Offset(image.center.x, image.top), Offset(image.center.x, image.bottom), 1.dp.toPx())
        drawLine(grid, Offset(image.left, image.center.y), Offset(image.right, image.center.y), 1.dp.toPx())
        val required = requiredMarkers(view, measurementMethod)
        markerGuidePositions(view).filterKeys { it in required }.forEach { (id, p) ->
            val x = if (mirrored) 1f - p.x else p.x
            val c = Offset(guide.left + x * guide.width, guide.top + p.y * guide.height)
            val colour = if (measurementMethod == GaitMethod.POSE) GaitOverlayFallback else capturedColours.entries.firstOrNull { id in it.key.markerIds }?.value ?: GaitOverlayFallback
            drawCircle(colour.copy(alpha = guideAlpha), 4.5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        }
        if (frame == null || frame.observedView != view || frame.measurementMethod != measurementMethod) return@Canvas
        val green = GaitTrackingGreen
        val pairs = listOf(MarkerId.LEFT_SHOULDER to MarkerId.RIGHT_SHOULDER,
            MarkerId.LEFT_PELVIS to MarkerId.RIGHT_PELVIS) +
            if (view == GaitView.FRONT) listOf(MarkerId.LEFT_KNEE to MarkerId.RIGHT_KNEE)
            else if (measurementMethod == GaitMethod.MARKERS) listOf(MarkerId.C7 to MarkerId.COCCYX) else emptyList()
        pairs.forEach { (a, b) ->
            val pa = frame.markers[a]; val pb = frame.markers[b]
            if (pa != null && pb != null) drawLine(green, actualPoint(pa), actualPoint(pb), 1.25.dp.toPx())
        }
        frame.markers.filterKeys { it in required }.forEach { (_, p) ->
            drawCircle(GaitMarkerOutline, 5.5.dp.toPx(), actualPoint(p))
            drawCircle(green, 4.dp.toPx(), actualPoint(p))
        }
    }
}

@Preview(name = "Front marker positioning", widthDp = 300, heightDp = 400)
@Composable
private fun GaitCameraOverlayPreview() {
    AppTheme {
        Box(Modifier.fillMaxSize().background(GaitPhotoBackground)) {
            GaitCameraOverlay(frame = null, view = GaitView.FRONT, mirrored = true)
        }
    }
}
