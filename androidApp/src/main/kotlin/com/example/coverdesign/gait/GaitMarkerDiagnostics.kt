package com.example.coverdesign.gait

/** Distinguish seeing a colour from confirming its location on the body. */
internal fun missingMarkerMessage(frame: GaitFrame, id: MarkerId): String {
    val diagnostics = frame.markerDiagnostics
        ?: return "Check ${id.label.lowercase()}. The marker is not clearly visible."
    val group = when (id) {
        MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER -> MarkerColor.SHOULDERS
        MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS -> MarkerColor.PELVIS
        MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE -> MarkerColor.KNEES
        MarkerId.C7 -> MarkerColor.C7
        MarkerId.COCCYX -> MarkerColor.COCCYX
    }
    val candidates = diagnostics.colourCandidates[group.name] ?: 0
    val pixels = diagnostics.colourPixels[group.name] ?: candidates
    return when {
        pixels == 0 -> "Check ${id.label.lowercase()}. No captured colour detected for this group. Expose the marker or calibrate its colour."
        candidates == 0 -> "Check ${id.label.lowercase()}. Pixels matching the captured colour are visible, but no clear marker shape."
        else -> "Check ${id.label.lowercase()}. The captured colour is visible, but the marker cannot be confirmed near the expected area. Check visibility and similar-coloured objects; exact anatomical placement is not verified."
    }
}

internal fun markerPreparationMessage(frame: GaitFrame, view: GaitView, strictProblem: String?): String? {
    val diagnostic = frame.markerDiagnostics ?: return strictProblem
    if (!diagnostic.poseAvailable) return "Your body is not tracked clearly. Keep your whole body in view; coloured regions alone are not confirmed markers."
    if (frame.observedView == null) return if (view == GaitView.FRONT)
        "Front view is not confirmed. Check the knee markers and recapture their colour if needed."
        else "Back view is not confirmed. Check the C7 and coccyx markers and their captured colours."
    if (frame.observedView != view) return strictProblem
    val missing = requiredMarkers(view).firstOrNull { it !in frame.markers }
    // Do not hide the missing shoulder behind a separate shoe/head framing warning.
    if (missing != null) return missingMarkerMessage(frame, missing)
    return strictProblem
}

internal fun chooseMarkerDiagnosticFrame(previous: GaitFrame?, current: GaitFrame): GaitFrame {
    if (previous == null || current.timestampMs - previous.timestampMs > 30_000) return current
    fun score(frame: GaitFrame) = frame.markers.size + (if (frame.bodyVisible) 10 else 0) + (if (frame.markerDiagnostics?.poseAvailable == true) 2 else 0)
    return if (current.timestampMs >= previous.timestampMs && score(current) >= score(previous)) current else previous
}

internal fun markerDiagnosticReport(frame: GaitFrame?, calibration: MarkerColorCalibration, ageMs: Long? = null, calibrationSource: String? = null): String = buildString {
    appendLine("CoverDesign 1.0.9 marker detection")
    appendLine("Lighting tolerance: ${calibration.lightingTolerance}")
    if (frame == null) { appendLine("No camera frame available"); return@buildString }
    appendLine("Most informative recent frame; age: ${ageMs?.let { (it.coerceAtLeast(0) / 1000).toString() + "s" } ?: "unknown"}")
    appendLine("Frame: ${frame.width}x${frame.height}; colour working grid: ${frame.markerDiagnostics?.workingWidth ?: "unknown"}x${frame.markerDiagnostics?.workingHeight ?: "unknown"}")
    calibrationSource?.let { appendLine("Latest calibration action: $it") }
    frame.markerDiagnostics?.let { appendLine("Processing ms: RGB=${it.conversionMs ?: "unknown"}, colours=${it.colourMs ?: "unknown"}, pose=${it.poseMs ?: "unknown"}") }
    appendLine("View: ${frame.observedView ?: "unconfirmed"}; whole body in frame: ${frame.bodyVisible}; marker region: ${frame.observedView?.let { frame.measurementVisible(it) } ?: false}")
    appendLine("Pose anchors available: ${frame.markerDiagnostics?.poseAvailable ?: "unknown"}")
    appendLine("Confirmed locations: ${frame.markers.keys.joinToString { it.name }}")
    GaitView.entries.forEach { appendLine("$it palette ready: ${calibration.readyFor(it)}; ${calibration.problemFor(it) ?: "OK"}") }
    MarkerColor.entries.forEach { colour ->
        appendLine("$colour: colour pixels=${frame.markerDiagnostics?.colourPixels?.get(colour.name) ?: "unknown"}; shapes=${frame.markerDiagnostics?.colourCandidates?.get(colour.name) ?: "unknown"}")
        GaitView.entries.forEach { view ->
            calibration.targetsFor(view)[colour]?.let { target ->
                appendLine("  $view calibrated hue=${target.hueDegrees}, tolerance=${target.hueToleranceDegrees}, saturation>=${target.minSaturation}, brightness>=${target.minValue}")
            }
        }
    }
    appendLine("Counts are diagnostic colour regions, not anatomical/medical confirmation. No images included.")
}
