package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class GaitVideoAnalysisTest {
    private fun frame(atMs: Long, view: GaitView = GaitView.FRONT, scale: Double = 1.0): GaitFrame {
        val left = if (view == GaitView.FRONT) .7 else .3
        val right = 1 - left
        val markers = linkedMapOf(
            MarkerId.LEFT_SHOULDER to MarkerPoint(left, .2),
            MarkerId.RIGHT_SHOULDER to MarkerPoint(right, .21),
            MarkerId.LEFT_PELVIS to MarkerPoint(left, .5),
            MarkerId.RIGHT_PELVIS to MarkerPoint(right, .51)
        )
        if (view == GaitView.FRONT) {
            markers[MarkerId.LEFT_KNEE] = MarkerPoint(.6, .73)
            markers[MarkerId.RIGHT_KNEE] = MarkerPoint(.4, .72)
        } else {
            markers[MarkerId.C7] = MarkerPoint(.51, .15)
            markers[MarkerId.COCCYX] = MarkerPoint(.5, .58)
        }
        return GaitFrame(atMs, 1000, 2000, markers.mapValues { (_, p) ->
            MarkerPoint(.5 + (p.x - .5) * scale, .45 + (p.y - .45) * scale)
        }, view, bodyVisible = false, stepSignal = null)
    }

    private fun send(run: GaitVideoAccumulator, from: Long, until: Long, view: GaitView = GaitView.FRONT,
                     step: Double = .01) {
        var index = 0
        for (at in from..until step 200L) {
            val scale = if (view == GaitView.FRONT) 1 + step * index else 1 - step * index
            run.receive(listOf(frame(at, view, scale)), at)
            index++
        }
    }

    @Test fun frontOnlyVideoProducesUsefulPartialAnglesWithoutInventingBackOrSteps() {
        val run = GaitVideoAccumulator(GaitProfile(), setOf(GaitView.FRONT))
        send(run, 0, 2000)
        val session = run.finish(3000)
        assertEquals("VIDEO", session.acquisitionSource)
        assertEquals(8, session.protocolVersion)
        assertEquals(3000L, session.sourceDurationMs)
        assertEquals(setOf(GaitView.FRONT), session.videoViews)
        assertFalse(session.completed)
        assertTrue(session.frontValidDurationMs > 0)
        assertEquals(0L, session.backValidDurationMs)
        assertEquals(0, session.frontSteps + session.backSteps)
        assertTrue(session.frontStepsPartial && session.backStepsPartial)
        assertTrue(session.manualLevel)
        assertTrue(session.samples.isNotEmpty())
        assertTrue(session.samples.all { it.view == GaitView.FRONT && it.angles.size == 3 && it.stepIndex == 0 && it.passIndex == 1 })
    }

    @Test fun backOnlyVideoAcceptsRecedingMarkersWithoutFeetOrHead() {
        val run = GaitVideoAccumulator(GaitProfile(), setOf(GaitView.BACK))
        send(run, 0, 2000, GaitView.BACK)
        val session = run.finish(3000, mirrored = true)
        assertTrue(session.sourceMirrored)
        assertEquals(setOf(GaitView.BACK), session.videoViews)
        assertTrue(session.backValidDurationMs > 0)
        assertEquals(0L, session.frontValidDurationMs)
        assertTrue(session.samples.all { GaitMetric.SPINE in it.angles })
        assertFalse(session.completed)
    }

    @Test fun standingAndWrongWayScaleCannotFillTheWalkingDuration() {
        for (direction in listOf(0.0, -.02)) {
            val run = GaitVideoAccumulator(GaitProfile(), setOf(GaitView.FRONT))
            send(run, 0, 5000, step = direction)
            val session = run.finish(6000)
            assertEquals(0L, session.frontValidDurationMs)
            assertTrue(session.samples.isEmpty())
            assertFalse(session.completed)
        }
    }

    @Test fun aLongStaticTailDoesNotBecomeWalkingAfterOneMotionCue() {
        val run = GaitVideoAccumulator(GaitProfile(), setOf(GaitView.FRONT))
        send(run, 0, 1000, step = .02)
        for (at in 1200L..10_000L step 200) run.receive(listOf(frame(at, scale = 1.10)), at)
        val session = run.finish(11_000)
        assertTrue(session.frontValidDurationMs in 1..1600)
        assertTrue(session.samples.maxOf { it.atMs } <= 1600)
        assertFalse(session.completed)
    }

    @Test fun badFramesAndLongGapsCannotBridgeMeasuredTimeOrPasses() {
        val run = GaitVideoAccumulator(GaitProfile(), setOf(GaitView.FRONT))
        send(run, 0, 1000)
        run.receive(emptyList(), 1200)
        send(run, 1400, 2400)
        send(run, 5000, 6000)
        val session = run.finish(7000)
        assertTrue(session.frontValidDurationMs in 1..3000)
        assertEquals(3, session.frontCompletedPasses)
        assertEquals(setOf(1, 2, 3), session.samples.map { it.passIndex }.toSet())
        assertTrue(session.rejectedFrames > 0)
    }

    @Test fun ambiguousViewsAndMarkersFromAnUnselectedViewAreDiscarded() {
        val both = GaitVideoAccumulator(GaitProfile(), GaitView.entries.toSet())
        for (at in 0L..2000L step 200) both.receive(listOf(frame(at), frame(at, GaitView.BACK)), at)
        assertTrue(both.finish(3000).samples.isEmpty())
        val front = GaitVideoAccumulator(GaitProfile(), setOf(GaitView.FRONT))
        send(front, 0, 2000, GaitView.BACK)
        assertTrue(front.finish(3000).samples.isEmpty())
    }

    @Test fun duplicateOrNonMonotonicSourceTimesCannotDuplicateSamples() {
        val run = GaitVideoAccumulator(GaitProfile(), setOf(GaitView.FRONT))
        send(run, 0, 1000)
        run.receive(listOf(frame(1000, scale = 1.1)), 1000)
        run.receive(listOf(frame(800, scale = 1.2)), 800)
        send(run, 1200, 2200)
        val samples = run.finish(3000).samples
        assertEquals(samples.map { it.atMs }.distinct().size, samples.size)
        assertTrue(samples.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
    }

    @Test fun missingOrInvalidMarkerPreventsTheAffectedAngles() {
        val run = GaitVideoAccumulator(GaitProfile(), setOf(GaitView.FRONT))
        for (at in 0L..2000L step 200) {
            val frame = frame(at, scale = 1 + at * .0001)
            run.receive(listOf(frame.copy(markers = frame.markers - MarkerId.LEFT_KNEE)), at)
        }
        val session = run.finish(3000)
        assertTrue(session.samples.isEmpty())
        assertEquals(0L, session.frontValidDurationMs)
    }

    @Test fun bothTargetsRequireActualCoverageOfBothViewsAndPreserveAllAcceptedData() {
        val run = GaitVideoAccumulator(GaitProfile(), GaitView.entries.toSet())
        // Repeated short passes avoid putting any paper outside the marker framing bounds.
        var at = 0L
        repeat(8) {
            val view = if (it % 2 == 0) GaitView.FRONT else GaitView.BACK
            send(run, at, at + 1800, view, .012)
            at += 2200
        }
        val session = run.finish(at)
        assertTrue(session.frontValidDurationMs >= 6000)
        assertTrue(session.backValidDurationMs >= 6000)
        assertTrue(session.completed)
        assertEquals(4, session.frontCompletedPasses)
        assertEquals(4, session.backCompletedPasses)
        assertEquals(session.samples.size, session.acceptedFrames)
    }

    @Test fun progressUsesSourceFrameCountAndStaysBounded() {
        assertEquals(0f, GaitVideoProgress(0, 100, 0, 20_000).fraction)
        assertEquals(.5f, GaitVideoProgress(50, 100, 10_000, 20_000).fraction)
        assertEquals(1f, GaitVideoProgress(101, 100, 20_000, 20_000).fraction)
        assertEquals(0f, GaitVideoProgress(1, 0, 0, 20_000).fraction)
    }
}
