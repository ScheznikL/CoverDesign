package com.example.coverdesign.gait

/** Only observed points from the requested measurement method determine the usable window. */
internal fun GaitFrame.measurementVisible(view: GaitView, method: GaitMethod = measurementMethod): Boolean {
    if (measurementMethod != method || width <= 0 || height <= 0 || observedView != view) return false
    return requiredMarkers(view, method).all { id ->
        markers[id]?.let { point ->
            point.x.isFinite() && point.y.isFinite() &&
                point.x in .025.. .975 && point.y in .025.. .975
        } == true
    }
}

/** Warn while all required centres are still visible, before a marker reaches the frame edge. */
internal fun GaitFrame.nearMeasurementEdge(view: GaitView, method: GaitMethod = measurementMethod): Boolean {
    if (!measurementVisible(view, method)) return false
    return requiredMarkers(view, method).any { id ->
        val point = markers.getValue(id)
        point.x <= .06 || point.y <= .06 || point.x >= .94 || point.y >= .94
    }
}
