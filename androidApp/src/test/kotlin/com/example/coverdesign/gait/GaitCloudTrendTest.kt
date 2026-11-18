package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class GaitCloudTrendTest {
    private fun report(id: String, at: Long, group: String = "group-a", patient: String = "patient-a",
        qualified: Boolean = true, angle: Double = 2.0, method: GaitMethod = GaitMethod.POSE) =
        GaitCloudReport(patient, id, at, method, "LIVE", 9, true, true, group,
            mapOf("FRONT_SHOULDERS" to angle), mapOf("FRONT_SHOULDERS" to 20),
            if (qualified) setOf("FRONT_SHOULDERS") else emptySet(), 20, 5, 6000, 6000)

    @Test fun chartIncludesOnlyTheSelectedPatientAndQualifiedMatchingGroupInTimeOrder() {
        val reports = listOf(report("later", 3000), report("first", 1000),
            report("other-mode", 2000, group = "group-b"), report("other-person", 1500, patient = "patient-b"),
            report("short", 2500, qualified = false))
        val series = gaitCloudComparableSeries(reports, "patient-a", "group-a", "FRONT_SHOULDERS")
        assertEquals(listOf("first", "later"), series.map { it.sessionId })
        assertEquals(listOf(1000L, 3000L), series.map { it.createdAt })
    }

    @Test fun chartRejectsNonFiniteMissingAndTooShortMeasurementsInsteadOfDrawingZeroes() {
        val good = report("good", 1000, angle = 0.0)
        val reports = listOf(good, report("nan", 2000, angle = Double.NaN),
            report("negative", 3000, angle = -1.0), report("missing", 4000).copy(typicalInclinations = emptyMap()),
            report("single", 5000).copy(sampleCounts = mapOf("FRONT_SHOULDERS" to 1)))
        val series = gaitCloudComparableSeries(reports, "patient-a", "group-a", "FRONT_SHOULDERS")
        assertEquals(listOf("good"), series.map { it.sessionId })
        assertEquals(0.0, series.single().angle, 0.0)
    }

    @Test fun poseReportsCannotProduceASpinalTrendEvenIfAFieldWasMalformed() {
        val pose = report("pose", 1000).copy(typicalInclinations = mapOf("BACK_SPINE" to 2.0),
            sampleCounts = mapOf("BACK_SPINE" to 20), qualifiedMetrics = setOf("BACK_SPINE"))
        assertTrue(gaitCloudComparableSeries(listOf(pose), "patient-a", "group-a", "BACK_SPINE").isEmpty())
        val marker = pose.copy(sessionId = "marker", measurementMethod = GaitMethod.MARKERS)
        assertEquals(1, gaitCloudComparableSeries(listOf(marker), "patient-a", "group-a", "BACK_SPINE").size)
    }
}
