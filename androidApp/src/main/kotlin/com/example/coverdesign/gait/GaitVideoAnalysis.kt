package com.example.coverdesign.gait

import java.util.UUID
import kotlin.math.abs

internal data class GaitVideoProgress(
    val processedFrames: Int,
    val totalFrames: Int,
    val positionMs: Long,
    val durationMs: Long
) {
    val fraction: Float get() = if (totalFrames <= 0) 0f else
        (processedFrames.toFloat() / totalFrames).coerceIn(0f, 1f)
}

/** Source-clock accumulation; processing speed never becomes measured walking time. */
internal class GaitVideoAccumulator(private val profile: GaitProfile, views: Set<GaitView>) {
    private val selectedViews = views.toSet()
    private data class Pending(val frame: GaitFrame, val deltaMs: Long)
    private data class Scale(val atMs: Long, val shoulder: Double, val pelvis: Double, val torso: Double)
    private val samples = mutableListOf<GaitSample>()
    private val durations = GaitView.entries.associateWith { 0L }.toMutableMap()
    private val passes = GaitView.entries.associateWith { 0 }.toMutableMap()
    private val pending = ArrayDeque<Pending>()
    private var previousView: GaitView? = null
    private var lastObservedAt: Long? = null
    private var lastFrameAt: Long? = null
    private var anchor: Scale? = null
    private var lastMovementAt: Long? = null
    private var passOpen = false
    private var passIndex = 0
    var rejectedFrames = 0
        private set

    init { require(selectedViews.isNotEmpty()) { "Choose at least one view to analyse." } }

    private fun discardPending() { rejectedFrames += pending.size; pending.clear() }
    private fun breakContinuity() {
        discardPending(); previousView = null; lastFrameAt = null; anchor = null
        lastMovementAt = null; passOpen = false
    }

    /** Exactly one selected view may be eligible. Ambiguous front/back patterns are discarded. */
    fun receive(candidates: List<GaitFrame>, atMs: Long) {
        if (atMs < 0 || lastObservedAt?.let { atMs <= it } == true) {
            rejectedFrames++; breakContinuity(); return
        }
        lastObservedAt = atMs
        val eligible = candidates.filter { frame ->
            frame.observedView in selectedViews && frame.timestampMs == atMs &&
                frame.observedView?.let { frame.measurementVisible(it) && GaitMath.angles(frame, it).size == 3 } == true
        }
        if (eligible.size != 1) {
            rejectedFrames++; breakContinuity(); return
        }
        val frame = eligible.single()
        val view = requireNotNull(frame.observedView)
        val previous = lastFrameAt
        if (previous != null && atMs <= previous) {
            rejectedFrames++; breakContinuity(); return
        }
        if (previousView != view || previous != null && atMs - previous > 400) breakContinuity()
        val delta = lastFrameAt?.let { atMs - it } ?: 0L
        previousView = view
        lastFrameAt = atMs
        val current = scale(frame, atMs)
        if (current == null) { rejectedFrames++; breakContinuity(); return }
        val baseline = anchor
        if (baseline == null || atMs - baseline.atMs > 1000) {
            anchor = current
        } else if (atMs - baseline.atMs >= 100) {
            val direction = if (view == GaitView.FRONT) 1 else -1
            val changes = listOf(current.shoulder / baseline.shoulder - 1,
                current.pelvis / baseline.pelvis - 1, current.torso / baseline.torso - 1)
            if (changes.all { it * direction >= .015 }) {
                lastMovementAt = atMs; anchor = current
            } else if (changes.all { it * direction <= -.015 }) {
                lastMovementAt = null; anchor = current; passOpen = false
                discardPending(); rejectedFrames++; return
            }
        }
        pending.addLast(Pending(frame, delta))
        // Only a bounded prefix immediately adjoining confirmed visible approach/recession can
        // be included. Standing, turns and camera gaps cannot gradually fill the time target.
        while (pending.isNotEmpty() && atMs - (pending.first().frame.timestampMs - pending.first().deltaMs) > 600) {
            pending.removeFirst(); rejectedFrames++
        }
        if (lastMovementAt?.let { atMs - it in 0..600 } == true) commit(view)
        else if (lastMovementAt != null) passOpen = false
    }

    private fun commit(view: GaitView) {
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            // A single isolated picture has an angle, but is not a walking interval.
            if (next.deltaMs <= 0) { rejectedFrames++; continue }
            if (!passOpen) { passOpen = true; passIndex++; passes[view] = passes.getValue(view) + 1 }
            durations[view] = durations.getValue(view) + next.deltaMs
            samples += GaitSample(next.frame.timestampMs, view,
                GaitMath.angles(next.frame, view), stepIndex = 0, passIndex = passIndex)
        }
    }

    private fun scale(frame: GaitFrame, atMs: Long): Scale? {
        val ls = frame.markers[MarkerId.LEFT_SHOULDER] ?: return null
        val rs = frame.markers[MarkerId.RIGHT_SHOULDER] ?: return null
        val lp = frame.markers[MarkerId.LEFT_PELVIS] ?: return null
        val rp = frame.markers[MarkerId.RIGHT_PELVIS] ?: return null
        val shoulder = abs(ls.x - rs.x)
        val pelvis = abs(lp.x - rp.x)
        val torso = (lp.y + rp.y - ls.y - rs.y) / 2
        return Scale(atMs, shoulder, pelvis, torso).takeIf { shoulder > .02 && pelvis > .02 && torso > .04 }
    }

    fun finish(sourceDurationMs: Long, mirrored: Boolean = false): GaitSession {
        discardPending()
        val front = durations.getValue(GaitView.FRONT)
        val back = durations.getValue(GaitView.BACK)
        val target = profile.targetDurationSecondsPerView * 1000L
        return GaitSession(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(),
            profile = profile, samples = samples.toList(), frontSteps = 0, backSteps = 0,
            acceptedFrames = samples.size, rejectedFrames = rejectedFrames, manualLevel = true,
            completed = selectedViews.size == 2 && front >= target && back >= target,
            protocolVersion = 8, frontValidDurationMs = front, backValidDurationMs = back,
            frontCompletedPasses = passes.getValue(GaitView.FRONT), backCompletedPasses = passes.getValue(GaitView.BACK),
            frontStepsPartial = true, backStepsPartial = true,
            acquisitionSource = "VIDEO", sourceDurationMs = sourceDurationMs, sourceMirrored = mirrored,
            videoViews = selectedViews)
    }
}
