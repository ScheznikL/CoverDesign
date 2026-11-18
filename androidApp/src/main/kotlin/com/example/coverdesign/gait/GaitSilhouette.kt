package com.example.coverdesign.gait

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.theme.GaitMarkerC7
import com.example.coverdesign.ui.theme.GaitMarkerCoccyx
import com.example.coverdesign.ui.theme.GaitMarkerKnee
import com.example.coverdesign.ui.theme.GaitMarkerPelvis
import com.example.coverdesign.ui.theme.GaitMarkerShoulder

/** Natural drawing bounds; guides are illustrative and must never be used to validate anatomy. */
internal const val GaitSilhouetteAspectRatio = .55f

/**
 * Draws a neutral, full-length figure inside [bounds]. The head, neck, hands and feet are included.
 * Use [GaitSilhouetteAspectRatio] for undistorted proportions. Marker positions use these same bounds.
 * [alpha] multiplies each supplied colour's alpha, so this can also be a translucent camera guide.
 */
internal fun DrawScope.drawGaitSilhouette(
    bounds: Rect,
    view: GaitView,
    fill: Color,
    outline: Color,
    detail: Color,
    alpha: Float = 1f
) {
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val opacity = alpha.coerceIn(0f, 1f)
    fun Color.faded() = copy(alpha = this.alpha * opacity)
    fun point(x: Float, y: Float) = Offset(bounds.left + x * bounds.width, bounds.top + y * bounds.height)
    val stroke = (bounds.height * .004f).coerceIn(.8.dp.toPx(), 1.7.dp.toPx())
    val edge = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val fine = Stroke(stroke * .8f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // One continuous body contour: the arm-to-torso gaps and the space between legs stay open.
    val body = gaitPath(bounds) {
        move(.455f, .139f)
        curve(.456f, .162f, .455f, .177f, .435f, .186f)
        curve(.405f, .198f, .356f, .194f, .309f, .206f)
        curve(.272f, .213f, .249f, .228f, .239f, .258f)
        curve(.222f, .298f, .219f, .332f, .203f, .371f)
        curve(.187f, .414f, .173f, .454f, .157f, .486f)
        curve(.148f, .502f, .137f, .518f, .137f, .535f)
        curve(.136f, .546f, .146f, .553f, .157f, .548f)
        curve(.174f, .549f, .184f, .541f, .190f, .530f)
        curve(.198f, .518f, .200f, .505f, .207f, .493f)
        curve(.226f, .460f, .244f, .418f, .260f, .382f)
        curve(.276f, .350f, .292f, .313f, .310f, .281f)
        curve(.318f, .309f, .330f, .337f, .346f, .365f)
        curve(.361f, .393f, .360f, .420f, .349f, .447f)
        curve(.333f, .474f, .311f, .499f, .309f, .531f)
        curve(.306f, .575f, .320f, .625f, .331f, .666f)
        curve(.340f, .694f, .344f, .711f, .341f, .735f)
        curve(.332f, .763f, .328f, .792f, .338f, .825f)
        curve(.348f, .857f, .365f, .894f, .368f, .922f)
        curve(.371f, .940f, .354f, .948f, .333f, .954f)
        curve(.316f, .959f, .309f, .968f, .317f, .975f)
        curve(.338f, .982f, .371f, .978f, .395f, .978f)
        curve(.418f, .978f, .445f, .982f, .452f, .971f)
        curve(.458f, .957f, .450f, .941f, .451f, .924f)
        curve(.451f, .889f, .465f, .851f, .467f, .819f)
        curve(.470f, .786f, .459f, .756f, .461f, .731f)
        curve(.464f, .694f, .477f, .648f, .484f, .611f)
        curve(.489f, .585f, .493f, .567f, .500f, .565f)
        curve(.507f, .567f, .511f, .585f, .516f, .611f)
        curve(.523f, .648f, .536f, .694f, .539f, .731f)
        curve(.541f, .756f, .530f, .786f, .533f, .819f)
        curve(.535f, .851f, .549f, .889f, .549f, .924f)
        curve(.550f, .941f, .542f, .957f, .548f, .971f)
        curve(.555f, .982f, .582f, .978f, .605f, .978f)
        curve(.629f, .978f, .662f, .982f, .683f, .975f)
        curve(.691f, .968f, .684f, .959f, .667f, .954f)
        curve(.646f, .948f, .629f, .940f, .632f, .922f)
        curve(.635f, .894f, .652f, .857f, .662f, .825f)
        curve(.672f, .792f, .668f, .763f, .659f, .735f)
        curve(.656f, .711f, .660f, .694f, .669f, .666f)
        curve(.680f, .625f, .694f, .575f, .691f, .531f)
        curve(.689f, .499f, .667f, .474f, .651f, .447f)
        curve(.640f, .420f, .639f, .393f, .654f, .365f)
        curve(.670f, .337f, .682f, .309f, .690f, .281f)
        curve(.708f, .313f, .724f, .350f, .740f, .382f)
        curve(.756f, .418f, .774f, .460f, .793f, .493f)
        curve(.800f, .505f, .802f, .518f, .810f, .530f)
        curve(.816f, .541f, .826f, .549f, .843f, .548f)
        curve(.854f, .553f, .864f, .546f, .863f, .535f)
        curve(.863f, .518f, .852f, .502f, .843f, .486f)
        curve(.827f, .454f, .813f, .414f, .797f, .371f)
        curve(.781f, .332f, .778f, .298f, .761f, .258f)
        curve(.751f, .228f, .728f, .213f, .691f, .206f)
        curve(.644f, .194f, .595f, .198f, .565f, .186f)
        curve(.545f, .177f, .544f, .162f, .545f, .139f)
        close()
    }
    // The head overlaps the top of the neck, avoiding a floating-head icon or a gap.
    val head = gaitPath(bounds) {
        move(.500f, .012f)
        curve(.560f, .012f, .597f, .033f, .600f, .071f)
        curve(.602f, .086f, .599f, .099f, .591f, .112f)
        curve(.582f, .136f, .548f, .156f, .500f, .159f)
        curve(.452f, .156f, .418f, .136f, .409f, .112f)
        curve(.401f, .099f, .398f, .086f, .400f, .071f)
        curve(.403f, .033f, .440f, .012f, .500f, .012f)
        close()
    }
    // A single exterior contour also stays seamless when the camera guide is translucent.
    val silhouette = Path.combine(PathOperation.Union, body, head)
    drawPath(silhouette, fill.faded())
    drawPath(silhouette, outline.faded(), style = edge)

    val features = gaitPath(bounds) {
        if (view == GaitView.FRONT) {
            // A restrained face and collarbone cues identify the front without changing the body.
            move(.498f, .077f)
            curve(.493f, .088f, .489f, .096f, .506f, .098f)
            move(.476f, .119f)
            curve(.491f, .123f, .509f, .123f, .524f, .119f)
            move(.446f, .212f)
            curve(.415f, .223f, .377f, .219f, .347f, .225f)
            move(.554f, .212f)
            curve(.585f, .223f, .623f, .219f, .653f, .225f)
            move(.365f, .719f)
            curve(.384f, .708f, .421f, .708f, .440f, .719f)
            move(.560f, .719f)
            curve(.579f, .708f, .616f, .708f, .635f, .719f)
        } else {
            // Two short scapular cues distinguish the back; there is no solid spinal strip.
            move(.445f, .071f)
            curve(.467f, .062f, .533f, .062f, .555f, .071f)
            move(.397f, .247f)
            curve(.368f, .266f, .367f, .298f, .403f, .325f)
            curve(.419f, .302f, .430f, .282f, .425f, .263f)
            move(.603f, .247f)
            curve(.632f, .266f, .633f, .298f, .597f, .325f)
            curve(.581f, .302f, .570f, .282f, .575f, .263f)
            move(.369f, .731f)
            curve(.389f, .738f, .420f, .738f, .439f, .731f)
            move(.561f, .731f)
            curve(.580f, .738f, .611f, .738f, .631f, .731f)
        }
        move(.377f, .930f)
        curve(.396f, .934f, .423f, .934f, .442f, .930f)
        move(.558f, .930f)
        curve(.577f, .934f, .604f, .934f, .623f, .930f)
    }
    drawPath(features, detail.faded(), style = fine)
    if (view == GaitView.FRONT) {
        val eyeRadius = (bounds.height * .0024f).coerceAtLeast(.55.dp.toPx())
        drawCircle(detail.faded(), eyeRadius, point(.458f, .076f))
        drawCircle(detail.faded(), eyeRadius, point(.542f, .076f))
    }
}

/** Anatomical left/right in an unmirrored view, normalised to the silhouette drawing bounds. */
internal fun markerGuidePositions(view: GaitView): Map<MarkerId, Offset> {
    val front = view == GaitView.FRONT
    return linkedMapOf(
        MarkerId.LEFT_SHOULDER to Offset(if (front) .715f else .285f, .218f),
        MarkerId.RIGHT_SHOULDER to Offset(if (front) .285f else .715f, .218f),
        MarkerId.LEFT_PELVIS to Offset(if (front) .640f else .420f, .480f),
        MarkerId.RIGHT_PELVIS to Offset(if (front) .360f else .580f, .480f)
    ).apply {
        if (front) {
            put(MarkerId.LEFT_KNEE, Offset(.598f, .725f))
            put(MarkerId.RIGHT_KNEE, Offset(.402f, .725f))
        } else {
            put(MarkerId.C7, Offset(.500f, .192f))
            put(MarkerId.COCCYX, Offset(.500f, .553f))
        }
    }
}

internal fun markerColour(id: MarkerId): Color = when (id) {
    MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER -> GaitMarkerShoulder
    MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS -> GaitMarkerPelvis
    MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE -> GaitMarkerKnee
    MarkerId.C7 -> GaitMarkerC7
    MarkerId.COCCYX -> GaitMarkerCoccyx
}

private fun gaitPath(bounds: Rect, content: GaitPath.() -> Unit): Path = GaitPath(bounds).apply(content).path

private class GaitPath(private val bounds: Rect) {
    val path = Path()
    private fun px(value: Float) = bounds.left + value * bounds.width
    private fun py(value: Float) = bounds.top + value * bounds.height
    fun move(x: Float, y: Float) = path.moveTo(px(x), py(y))
    fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        path.cubicTo(px(x1), py(y1), px(x2), py(y2), px(x3), py(y3))
    fun close() = path.close()
}
