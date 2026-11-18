package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class GaitFrameBuilderTest {
    private val width = 480
    private val height = 640
    private val pose = PoseLandmarks(mapOf(
        BodyLandmark.LEFT_SHOULDER to observed(.65, .30),
        BodyLandmark.RIGHT_SHOULDER to observed(.35, .28),
        BodyLandmark.LEFT_HIP to observed(.58, .54),
        BodyLandmark.RIGHT_HIP to observed(.42, .52),
        BodyLandmark.LEFT_KNEE to observed(.59, .74),
        BodyLandmark.RIGHT_KNEE to observed(.41, .73),
        BodyLandmark.LEFT_ANKLE to observed(.61, .94),
        BodyLandmark.RIGHT_ANKLE to observed(.39, .89),
        BodyLandmark.LEFT_HEEL to observed(.61, .95),
        BodyLandmark.RIGHT_HEEL to observed(.39, .90),
        BodyLandmark.LEFT_FOOT_INDEX to observed(.62, .96),
        BodyLandmark.RIGHT_FOOT_INDEX to observed(.38, .91)
    )) // Deliberately contains no face landmarks, as in a rear-camera view.

    private fun observed(x: Double, y: Double, confidence: Float = .95f) =
        ObservedBodyPoint(MarkerPoint(x, y), confidence, confidence)

    private fun coloredBlobs(front: Boolean, back: Boolean): List<MarkerBlob> {
        val pixels = IntArray(width * height) { 0xff222222.toInt() }
        fun patch(x: Int, y: Int, color: Long) {
            for (py in y - 5..y + 5) for (px in x - 5..x + 5) pixels[py * width + px] = color.toInt()
        }
        // Actual colored pixels are displaced from pose anchors. The returned angle points
        // must remain these measured centroids, rather than the network's estimated joints.
        patch(164, 177, 0xff14b8a6); patch(316, 196, 0xff14b8a6)
        patch(197, 336, 0xff2563eb); patch(283, 351, 0xff2563eb)
        if (front) { patch(192, 465, 0xffd946ef); patch(289, 480, 0xffd946ef) }
        if (back) { patch(239, 149, 0xfffacc15); patch(237, 359, 0xfffb923c) }
        return MarkerBlobDetector().detect(pixels, width, height)
    }

    private fun frame(p: PoseLandmarks = pose, front: Boolean = true, back: Boolean = false) =
        GaitFrameBuilder.build(p, coloredBlobs(front, back), width, height, 1234)

    @Test fun frontUsesMeasuredPixelCentersAndUnmirroredAnatomicalSides() {
        val result = frame()
        assertEquals(GaitView.FRONT, result.observedView)
        assertEquals(6, result.markers.size)
        assertTrue(result.bodyVisible)
        assertEquals(316.5 / width, result.markers.getValue(MarkerId.LEFT_SHOULDER).x, 1e-6)
        assertEquals(177.5 / height, result.markers.getValue(MarkerId.RIGHT_SHOULDER).y, 1e-6)
        assertNotEquals(pose.points.getValue(BodyLandmark.LEFT_SHOULDER).point,
            result.markers.getValue(MarkerId.LEFT_SHOULDER))
        assertEquals(1234L, result.timestampMs)
    }

    @Test fun rearWorksWithoutFacialPointsAndDoesNotDependOnNeuralSideLabels() {
        val result = frame(front = false, back = true)
        assertEquals(GaitView.BACK, result.observedView)
        assertTrue(result.bodyVisible)
        assertEquals(6, result.markers.size)
        assertEquals(164.5 / width, result.markers.getValue(MarkerId.LEFT_SHOULDER).x, 1e-6)
        assertEquals(239.5 / width, result.markers.getValue(MarkerId.C7).x, 1e-6)
        assertFalse(result.markers.containsKey(MarkerId.LEFT_KNEE))
        assertNotNull(result.stepSignal)
    }

    @Test fun conflictingFrontAndRearColorsDoNotConfirmAView() {
        val result = frame(front = true, back = true)
        assertNull(result.observedView)
        assertTrue(result.markers.isEmpty())
        assertNull(result.stepSignal)
    }

    @Test fun emptyOrLowConfidencePoseDoesNotInventAnyMarkerLocations() {
        val noPose = frame(PoseLandmarks(emptyMap()), front = false, back = true)
        assertTrue(noPose.markers.isEmpty())
        assertFalse(noPose.bodyVisible)
        val weakHip = pose.copy(points = pose.points +
            (BodyLandmark.LEFT_HIP to observed(.58, .54, .3f)))
        assertTrue(frame(weakHip).markers.isEmpty())
        val noPresence = pose.copy(points = pose.points +
            (BodyLandmark.LEFT_HIP to ObservedBodyPoint(MarkerPoint(.58, .54), .99f, .1f)))
        assertTrue(frame(noPresence).markers.isEmpty())
    }

    @Test fun ankleInsideButClippedOrUncertainToeIsNotWholeBodyVisible() {
        val clipped = pose.copy(points = pose.points +
            (BodyLandmark.LEFT_FOOT_INDEX to observed(.62, 1.01)))
        assertFalse(frame(clipped).bodyVisible)
        assertNull(frame(clipped).stepSignal)
        val weak = pose.copy(points = pose.points +
            (BodyLandmark.RIGHT_HEEL to observed(.39, .90, .2f)))
        assertFalse(frame(weak, front = false, back = true).bodyVisible)
        assertNull(frame(weak).stepSignal)
    }

    @Test fun clippedHeadCannotPassWholeBodyFramingButVisibleFeetKeepTheirOwnPhase() {
        val clipped = pose.copy(points = pose.points + (BodyLandmark.LEFT_EAR to observed(-.02, .12)))
        val result = frame(clipped)
        assertFalse(result.bodyVisible)
        assertTrue(result.measurementVisible(GaitView.FRONT))
        assertEquals(frame().stepSignal!!, result.stepSignal!!, 1e-12)
    }

    @Test fun swappingAnatomicalAnkleLabelsCannotReverseTheStepSignal() {
        val swapped = pose.copy(points = pose.points + mapOf(
            BodyLandmark.LEFT_ANKLE to pose.points.getValue(BodyLandmark.RIGHT_ANKLE),
            BodyLandmark.RIGHT_ANKLE to pose.points.getValue(BodyLandmark.LEFT_ANKLE)
        ))
        assertEquals(frame().stepSignal!!, frame(swapped).stepSignal!!, 1e-12)
        assertEquals(frame(front = false, back = true).stepSignal!!,
            frame(swapped, front = false, back = true).stepSignal!!, 1e-12)
    }

    @Test fun overlappingFeetDoNotProduceAWalkingPhase() {
        val overlap = pose.copy(points = pose.points + mapOf(
            BodyLandmark.LEFT_ANKLE to observed(.505, .94),
            BodyLandmark.RIGHT_ANKLE to observed(.495, .89)
        ))
        val result = frame(overlap)
        assertTrue(result.bodyVisible)
        assertNull(result.stepSignal)
    }
    @Test fun headAndFeetCroppingDoesNotDiscardSixMeasuredFrontMarkerCentres() {
        val cropped = pose.copy(points = pose.points + mapOf(
            BodyLandmark.LEFT_EAR to observed(-.02, .12),
            BodyLandmark.LEFT_ANKLE to observed(.61, 1.10),
            BodyLandmark.RIGHT_ANKLE to observed(.39, 1.05),
            BodyLandmark.LEFT_HEEL to observed(.61, 1.12),
            BodyLandmark.RIGHT_HEEL to observed(.39, 1.07),
            BodyLandmark.LEFT_FOOT_INDEX to observed(.62, 1.15),
            BodyLandmark.RIGHT_FOOT_INDEX to observed(.38, 1.10)
        ))
        val result = frame(cropped)
        assertFalse(result.bodyVisible)
        assertNull(result.stepSignal)
        assertTrue(result.measurementVisible(GaitView.FRONT))
        assertFalse(result.measurementVisible(GaitView.BACK))
        assertEquals(frame().markers, result.markers)
        assertEquals(3, GaitMath.angles(result, GaitView.FRONT).size)
    }

    @Test fun rearMeasurementEndsAtCoccyxWithoutInventingMissingKneesOrAnkles() {
        val lowerBody = setOf(BodyLandmark.LEFT_KNEE, BodyLandmark.RIGHT_KNEE,
            BodyLandmark.LEFT_ANKLE, BodyLandmark.RIGHT_ANKLE,
            BodyLandmark.LEFT_HEEL, BodyLandmark.RIGHT_HEEL,
            BodyLandmark.LEFT_FOOT_INDEX, BodyLandmark.RIGHT_FOOT_INDEX)
        val cropped = pose.copy(points = pose.points - lowerBody)
        val result = frame(cropped, front = false, back = true)
        assertFalse(result.bodyVisible)
        assertNull(result.stepSignal)
        assertTrue(result.measurementVisible(GaitView.BACK))
        assertEquals(requiredMarkers(GaitView.BACK).toSet(), result.markers.keys)
        assertEquals(frame(front = false, back = true).markers, result.markers)
        assertEquals(3, GaitMath.angles(result, GaitView.BACK).size)
    }

    @Test fun missingLowerMarkerOrWrongViewCannotPassMeasurementFraming() {
        val front = frame()
        assertFalse(front.copy(markers = front.markers - MarkerId.RIGHT_KNEE)
            .measurementVisible(GaitView.FRONT))
        val back = frame(front = false, back = true)
        assertFalse(back.copy(markers = back.markers - MarkerId.COCCYX)
            .measurementVisible(GaitView.BACK))
        assertFalse(front.copy(observedView = null).measurementVisible(GaitView.FRONT))
        assertFalse(front.measurementVisible(GaitView.BACK))
    }

    @Test fun nonfiniteOrEdgeClippedCentresDoNotBecomeAValidMeasurementWindow() {
        val base = frame()
        for (bad in listOf(MarkerPoint(Double.NaN, .7), MarkerPoint(.5, Double.POSITIVE_INFINITY),
            MarkerPoint(.0249, .7), MarkerPoint(.9751, .7), MarkerPoint(.5, .0249), MarkerPoint(.5, .9751))) {
            val invalid = base.copy(markers = base.markers + (MarkerId.LEFT_KNEE to bad))
            assertFalse(invalid.measurementVisible(GaitView.FRONT))
            assertFalse(invalid.nearMeasurementEdge(GaitView.FRONT))
        }
        assertFalse(base.copy(width = 0).measurementVisible(GaitView.FRONT))
        assertFalse(base.copy(height = 0).measurementVisible(GaitView.FRONT))
    }

    @Test fun nearEdgeWarningUsesRequiredMeasuredCentresOnlyWhileTheWindowRemainsVisible() {
        val base = frame()
        assertTrue(base.measurementVisible(GaitView.FRONT))
        assertFalse(base.nearMeasurementEdge(GaitView.FRONT))
        for (edge in listOf(MarkerPoint(.06, .7), MarkerPoint(.94, .7),
            MarkerPoint(.5, .06), MarkerPoint(.5, .94), MarkerPoint(.5, .975))) {
            val near = base.copy(markers = base.markers + (MarkerId.LEFT_KNEE to edge))
            assertTrue(near.measurementVisible(GaitView.FRONT))
            assertTrue(near.nearMeasurementEdge(GaitView.FRONT))
        }
        val back = frame(front = false, back = true)
        val nearCoccyx = back.copy(markers = back.markers + (MarkerId.COCCYX to MarkerPoint(.5, .94)))
        assertTrue(nearCoccyx.measurementVisible(GaitView.BACK))
        assertTrue(nearCoccyx.nearMeasurementEdge(GaitView.BACK))
    }

    @Test fun absentAnklesCannotBeReplacedByTorsoMarkersForStepCounting() {
        val missingAnkles = pose.copy(points = pose.points -
            setOf(BodyLandmark.LEFT_ANKLE, BodyLandmark.RIGHT_ANKLE))
        val result = frame(missingAnkles)
        assertTrue(result.measurementVisible(GaitView.FRONT))
        assertFalse(result.bodyVisible)
        assertNull(result.stepSignal)
    }

    private fun rearWith(replacements: Map<MarkerColor, List<MarkerPoint>>): GaitFrame {
        val blobs = coloredBlobs(front = false, back = true).filter { it.color !in replacements } +
            replacements.flatMap { (colour, points) -> points.map { MarkerBlob(colour, it, .001) } }
        return GaitFrameBuilder.build(pose, blobs, width, height, 1234)
    }

    @Test fun rearHeightOffsetsUseMeasuredCentresWithinExpandedBoundedRegions() {
        // All four replacements are just outside the previous circular height limits.
        val papers = mapOf(
            MarkerColor.C7 to listOf(MarkerPoint(.50, .14)),
            MarkerColor.PELVIS to listOf(MarkerPoint(.42, .39), MarkerPoint(.58, .41)),
            MarkerColor.COCCYX to listOf(MarkerPoint(.50, .68))
        )
        val result = rearWith(papers)
        assertEquals(GaitView.BACK, result.observedView)
        assertEquals(requiredMarkers(GaitView.BACK).toSet(), result.markers.keys)
        assertEquals(papers.getValue(MarkerColor.C7).single(), result.markers[MarkerId.C7])
        assertEquals(papers.getValue(MarkerColor.COCCYX).single(), result.markers[MarkerId.COCCYX])
        assertEquals(papers.getValue(MarkerColor.PELVIS)[0], result.markers[MarkerId.LEFT_PELVIS])
        assertEquals(papers.getValue(MarkerColor.PELVIS)[1], result.markers[MarkerId.RIGHT_PELVIS])
        assertTrue(result.measurementVisible(GaitView.BACK))
        assertEquals(3, GaitMath.angles(result, GaitView.BACK).size)
    }

    @Test fun expandedRearSearchDoesNotChangeAConfirmedFrontPattern() {
        val nearNeckPatch = MarkerBlob(MarkerColor.C7, MarkerPoint(.50, .14), .001)
        val result = GaitFrameBuilder.build(pose, coloredBlobs(front = true, back = false) + nearNeckPatch,
            width, height, 1234)
        assertEquals(GaitView.FRONT, result.observedView)
        assertEquals(frame().markers, result.markers)
    }

    @Test fun aSingleNewlyAdmittedRearPatchCannotConfirmTheRearView() {
        for ((colour, point) in listOf(MarkerColor.C7 to MarkerPoint(.50, .14),
            MarkerColor.COCCYX to MarkerPoint(.50, .68))) {
            val others = coloredBlobs(front = false, back = false)
            val result = GaitFrameBuilder.build(pose, others + MarkerBlob(colour, point, .001), width, height, 1234)
            assertNull(result.observedView)
            assertTrue(result.markers.isEmpty())
        }
    }

    @Test fun duplicateCoccyxPapersInExpandedRegionRemainAmbiguous() {
        val result = rearWith(mapOf(MarkerColor.COCCYX to listOf(MarkerPoint(.50, .68), MarkerPoint(.50, .66))))
        assertEquals(GaitView.BACK, result.observedView) // The genuine C7 still establishes the view.
        assertFalse(result.markers.containsKey(MarkerId.COCCYX))
        assertFalse(result.measurementVisible(GaitView.BACK))
        assertFalse(GaitMath.angles(result, GaitView.BACK).containsKey(GaitMetric.SPINE))
    }

    @Test fun rearToleranceStillRejectsDistantOrAnatomicallyUnorderedPapers() {
        for (orange in listOf(MarkerPoint(.92, .68), MarkerPoint(.50, .85), MarkerPoint(.50, .49))) {
            val result = rearWith(mapOf(MarkerColor.COCCYX to listOf(orange)))
            assertEquals(GaitView.BACK, result.observedView)
            assertFalse(result.markers.containsKey(MarkerId.COCCYX))
        }
        val upperBackBlue = rearWith(mapOf(MarkerColor.PELVIS to listOf(MarkerPoint(.42, .30), MarkerPoint(.58, .31))))
        assertFalse(upperBackBlue.markers.containsKey(MarkerId.LEFT_PELVIS))
        assertFalse(upperBackBlue.markers.containsKey(MarkerId.RIGHT_PELVIS))
        assertFalse(upperBackBlue.measurementVisible(GaitView.BACK))
    }

    @Test fun duplicateRearPelvisAndC7RegionsDoNotChooseTheNearestPaper() {
        val duplicateBlue = rearWith(mapOf(MarkerColor.PELVIS to listOf(
            MarkerPoint(.42, .39), MarkerPoint(.43, .40), MarkerPoint(.58, .41))))
        assertFalse(duplicateBlue.markers.containsKey(MarkerId.LEFT_PELVIS))
        assertEquals(MarkerPoint(.58, .41), duplicateBlue.markers[MarkerId.RIGHT_PELVIS])
        val duplicateC7 = rearWith(mapOf(MarkerColor.C7 to listOf(MarkerPoint(.50, .14), MarkerPoint(.50, .15))))
        assertFalse(duplicateC7.markers.containsKey(MarkerId.C7))
        assertFalse(duplicateC7.measurementVisible(GaitView.BACK))
    }

    @Test fun rejectedAbovePelvisOrangePatchCannotBootstrapANewlyAdmittedNeckPatch() {
        val result = rearWith(mapOf(MarkerColor.C7 to listOf(MarkerPoint(.50, .14)),
            MarkerColor.COCCYX to listOf(MarkerPoint(.50, .49))))
        assertNull(result.observedView)
        assertTrue(result.markers.isEmpty())
    }

    @Test fun tinyKneeFragmentCannotProduceAKneeAngleButStrongKneeKeepsFrontView() {
        val blobs = coloredBlobs(front = true, back = false)
        val knees = blobs.filter { it.color == MarkerColor.KNEES }.sortedBy { it.point.x }
        val weak = knees[0]
        for (ratio in listOf(.03, .20)) {
            val resized = blobs.map { if (it === weak) it.copy(areaFraction = knees[1].areaFraction * ratio) else it }
            val result = GaitFrameBuilder.build(pose, resized, width, height, 1234)
            assertEquals(GaitView.FRONT, result.observedView)
            assertEquals(knees[1].point, result.markers[MarkerId.LEFT_KNEE])
            if (ratio < .10) {
                assertFalse(result.markers.containsKey(MarkerId.RIGHT_KNEE))
                assertFalse(result.measurementVisible(GaitView.FRONT))
                assertFalse(GaitMath.angles(result, GaitView.FRONT).containsKey(GaitMetric.KNEES))
            } else {
                assertEquals(weak.point, result.markers[MarkerId.RIGHT_KNEE])
                assertTrue(result.measurementVisible(GaitView.FRONT))
                assertTrue(GaitMath.angles(result, GaitView.FRONT).containsKey(GaitMetric.KNEES))
            }
        }
        val single = GaitFrameBuilder.build(pose, blobs - weak, width, height, 1234)
        assertEquals(GaitView.FRONT, single.observedView)
        assertEquals(knees[1].point, single.markers[MarkerId.LEFT_KNEE])
        assertFalse(single.markers.containsKey(MarkerId.RIGHT_KNEE))
    }

    @Test fun kneeFragmentGuardDoesNotChangeShoulderOrPelvisAreaAcceptance() {
        for (front in listOf(true, false)) {
            val blobs = coloredBlobs(front = front, back = !front)
            val weak = listOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS).map { colour ->
                blobs.filter { it.color == colour }.minBy { it.point.x }
            }
            val changedAreas = blobs.map { blob -> if (blob in weak) blob.copy(areaFraction = blob.areaFraction * .03) else blob }
            val result = GaitFrameBuilder.build(pose, changedAreas, width, height, 1234)
            assertEquals(frame(front = front, back = !front).markers, result.markers)
        }
    }

}
