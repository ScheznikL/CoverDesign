package com.example.coverdesign.gait

/**
 * Requests a fresh VIDEO tracker after a view change or a sustained missing/incorrect pose.
 * The requested view controls re-detection only; it never labels an observed frame.
 */
internal class GaitPoseTrackingRecovery {
    private var previousMethod: GaitMethod? = null
    private var expectedView: GaitView? = null
    private var pendingReset = false
    private var lastResetAt: Long? = null
    private var missingSince: Long? = null

    fun beforeFrame(method: GaitMethod, requestedView: GaitView?, now: Long): Boolean {
        if (method != GaitMethod.POSE) {
            previousMethod = method; expectedView = null; pendingReset = false
            lastResetAt = null; missingSince = null
            return false
        }
        if (previousMethod != method || expectedView != requestedView) {
            pendingReset = true
            missingSince = null
        }
        previousMethod = method
        expectedView = requestedView
        val overdue = missingSince?.let { now >= it && now - it >= 2500L } == true
        val resetAllowed = lastResetAt?.let { now >= it && now - it >= 1500L } ?: true
        if (resetAllowed && (pendingReset || overdue)) {
            pendingReset = false; lastResetAt = now; missingSince = null
            return true
        }
        return false
    }

    fun observe(frame: GaitFrame, now: Long) {
        if (previousMethod != GaitMethod.POSE || frame.measurementMethod != GaitMethod.POSE) return
        val view = expectedView ?: frame.observedView
        val usable = view != null && frame.observedView == view && frame.measurementVisible(view, GaitMethod.POSE)
        if (usable) missingSince = null
        else if (missingSince == null) missingSince = now
    }
}
