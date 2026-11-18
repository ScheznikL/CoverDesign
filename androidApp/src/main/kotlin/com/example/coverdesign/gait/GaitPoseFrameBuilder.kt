package com.example.coverdesign.gait

import kotlin.math.abs
import kotlin.math.max

/**
 * Projected MediaPipe joints, without coloured paper or inferred spinal surface landmarks.
 * Front/back is a conservative anatomical-label ordering heuristic. It cannot independently
 * establish physical orientation when the model itself swaps anatomical labels.
 */
internal object GaitPoseFrameBuilder {
    fun build(pose: PoseLandmarks, width: Int, height: Int, time: Long): GaitFrame {
        fun empty() = GaitFrame(time, width, height, emptyMap(), null, false, null,
            measurementMethod = GaitMethod.POSE)
        if (width <= 0 || height <= 0) return empty()
        fun landmark(type: BodyLandmark, confidence: Float = .65f): MarkerPoint? = pose.points[type]?.takeIf {
            it.visibility.isFinite() && it.presence.isFinite() &&
                it.visibility >= confidence && it.presence >= confidence &&
                it.point.x.isFinite() && it.point.y.isFinite() &&
                it.point.x in 0.0..1.0 && it.point.y in 0.0..1.0
        }?.point

        val leftShoulder = landmark(BodyLandmark.LEFT_SHOULDER) ?: return empty()
        val rightShoulder = landmark(BodyLandmark.RIGHT_SHOULDER) ?: return empty()
        val leftHip = landmark(BodyLandmark.LEFT_HIP) ?: return empty()
        val rightHip = landmark(BodyLandmark.RIGHT_HIP) ?: return empty()
        val shoulderMid = MarkerPoint((leftShoulder.x + rightShoulder.x) / 2,
            (leftShoulder.y + rightShoulder.y) / 2)
        val hipMid = MarkerPoint((leftHip.x + rightHip.x) / 2, (leftHip.y + rightHip.y) / 2)
        val torsoHeight = hipMid.y - shoulderMid.y
        if (torsoHeight < .08) return empty()
        val torsoPixels = torsoHeight * height
        val shoulderDx = leftShoulder.x - rightShoulder.x
        val hipDx = leftHip.x - rightHip.x
        // Geometry thresholds only reject ambiguous projections; they are not anatomical norms.
        if (abs(shoulderDx) * width < max(width * .025, torsoPixels * .25) ||
            abs(hipDx) * width < max(width * .02, torsoPixels * .16)) return empty()
        val view = when {
            shoulderDx > 0 && hipDx > 0 -> GaitView.FRONT
            shoulderDx < 0 && hipDx < 0 -> GaitView.BACK
            else -> return empty()
        }
        val points = linkedMapOf(
            MarkerId.LEFT_SHOULDER to leftShoulder,
            MarkerId.RIGHT_SHOULDER to rightShoulder,
            MarkerId.LEFT_PELVIS to leftHip,
            MarkerId.RIGHT_PELVIS to rightHip
        )
        val leftKnee = landmark(BodyLandmark.LEFT_KNEE)
        val rightKnee = landmark(BodyLandmark.RIGHT_KNEE)
        if (view == GaitView.FRONT) {
            leftKnee?.takeIf { it.y > hipMid.y }?.let { points[MarkerId.LEFT_KNEE] = it }
            rightKnee?.takeIf { it.y > hipMid.y }?.let { points[MarkerId.RIGHT_KNEE] = it }
        }

        // Setup framing also reserves room for the crown, shoe and toes. A cropped foot never
        // blocks angle capture after setup, but it makes the optional step estimate unavailable.
        val headY = shoulderMid.y - max(abs(shoulderDx) * width * .85, torsoPixels * .65) / height
        val headHalfWidth = max(abs(shoulderDx) * .32, torsoPixels / width * .22)
        val knownHeadInside = listOf(BodyLandmark.NOSE, BodyLandmark.LEFT_EAR, BodyLandmark.RIGHT_EAR)
            .mapNotNull { pose.points[it] }
            .filter { it.visibility >= .65f && it.presence >= .65f }
            .all { it.point.x.isFinite() && it.point.y.isFinite() &&
                it.point.x in .025.. .975 && it.point.y in .025.. .975 }
        val headInFrame = headY > .025 && shoulderMid.x - headHalfWidth > .025 &&
            shoulderMid.x + headHalfWidth < .975 && knownHeadInside
        val ankles = listOfNotNull(landmark(BodyLandmark.LEFT_ANKLE), landmark(BodyLandmark.RIGHT_ANKLE))
            .sortedBy { it.x }
        val footTypes = listOf(BodyLandmark.LEFT_HEEL, BodyLandmark.RIGHT_HEEL,
            BodyLandmark.LEFT_FOOT_INDEX, BodyLandmark.RIGHT_FOOT_INDEX)
        val feet = footTypes.mapNotNull { landmark(it) }
        val feetInFrame = ankles.size == 2 && feet.size == footTypes.size && (ankles + feet).all {
            it.x in .025.. .975 && it.y in .025.. .97
        }
        val kneesTracked = leftKnee?.let { it.y > hipMid.y } == true &&
            rightKnee?.let { it.y > hipMid.y } == true
        val legTrackingReady = feetInFrame && kneesTracked && ankles.all { it.y > hipMid.y }
        // Rear heels and knees are often partially occluded. Framing is only an in-frame
        // check, so it may use lower-confidence visible lower-body points than angle/step
        // measurements. Both ankles and one actual shoe point per side must remain inside.
        val framingConfidence = .40f
        fun inLowerFrame(point: MarkerPoint) = point.x in .025.. .975 && point.y in .025.. .97 && point.y > hipMid.y
        val framingAnkles = listOfNotNull(landmark(BodyLandmark.LEFT_ANKLE, framingConfidence),
            landmark(BodyLandmark.RIGHT_ANKLE, framingConfidence))
        val shoeSides = listOf(
            listOf(BodyLandmark.LEFT_HEEL, BodyLandmark.LEFT_FOOT_INDEX),
            listOf(BodyLandmark.RIGHT_HEEL, BodyLandmark.RIGHT_FOOT_INDEX)
        )
        val knownLowerOutside = (footTypes + listOf(BodyLandmark.LEFT_ANKLE, BodyLandmark.RIGHT_ANKLE))
            .mapNotNull { pose.points[it] }
            .filter { it.visibility >= framingConfidence && it.presence >= framingConfidence }
            .any { !it.point.x.isFinite() || !it.point.y.isFinite() || !inLowerFrame(it.point) }
        val lowerBodyInFrame = !knownLowerOutside && framingAnkles.size == 2 && framingAnkles.all(::inLowerFrame) &&
            shoeSides.all { side ->
                val visible = side.mapNotNull { landmark(it, framingConfidence) }
                visible.isNotEmpty() && visible.all(::inLowerFrame)
            }
        val bodyPixels = torsoPixels * 2.8
        val separatedFeet = ankles.size == 2 && (ankles[1].x - ankles[0].x) * width >= bodyPixels * .025
        val stepSignal = if (legTrackingReady && separatedFeet) {
            val legLength = (ankles.map { it.y }.average() - hipMid.y).coerceAtLeast(.12)
            ((ankles[0].y - ankles[1].y) / legLength).coerceIn(-.7, .7)
        } else null
        return GaitFrame(time, width, height, points, view, lowerBodyInFrame && headInFrame,
            stepSignal, measurementMethod = GaitMethod.POSE)
    }
}
