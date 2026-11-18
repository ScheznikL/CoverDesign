package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class MarkerDiagnosticsTest {
    private fun frame(pixels: Int, shapes: Int) = GaitFrame(10_000, 480, 640,
        emptyMap(), GaitView.FRONT, false, null,
        MarkerDiagnostics(mapOf("SHOULDERS" to shapes), true, mapOf("SHOULDERS" to pixels)))

    @Test fun distinguishColourRejectionShapeRejectionAndUnconfirmedLocation() {
        val id = MarkerId.LEFT_SHOULDER
        assertTrue(missingMarkerMessage(frame(0, 0), id).contains("No captured colour detected"))
        assertTrue(missingMarkerMessage(frame(150, 0), id).contains("no clear marker shape"))
        assertTrue(missingMarkerMessage(frame(150, 2), id).contains("marker cannot be confirmed near the expected area"))
    }

    @Test fun unconfirmedFrontViewExplainsMissingKneesRatherThanInventingShoulderLocations() {
        val observation = frame(300, 2).copy(observedView = null)
        assertTrue(markerPreparationMessage(observation, GaitView.FRONT, "Face camera")!!.contains("knee markers"))
        assertTrue(observation.markers.isEmpty())
    }

    @Test fun reportRetainsUsefulStandingFrameWhenUserWalksBackToPhoneThenExpiresIt() {
        val standing = frame(150, 2).copy(bodyVisible = true)
        val nearPhone = frame(500, 1).copy(timestampMs = 15_000)
        assertSame(standing, chooseMarkerDiagnosticFrame(standing, nearPhone))
        val muchLater = nearPhone.copy(timestampMs = 45_000)
        assertSame(muchLater, chooseMarkerDiagnosticFrame(standing, muchLater))
        val report = markerDiagnosticReport(standing, MarkerColorCalibration(), 5000)
        assertTrue(report.contains("age: 5s"))
        assertTrue(report.contains("SHOULDERS: colour pixels=150; shapes=2"))
        assertFalse(report.contains("weight"))
    }
}
