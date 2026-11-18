package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

/** Pure report-contract checks: no Firebase instance, account, network or real patient data. */
class GaitCloudModelsTest {
    private val owner = "patient-contract-test"
    private fun session(method: GaitMethod = GaitMethod.MARKERS): GaitSession {
        val values = listOf(-1.0, 1.0, -2.0, 2.0)
        val samples = GaitView.entries.flatMap { view -> values.mapIndexed { index, angle ->
            GaitSample(index * 200L, view, expectedGaitMetrics(view, method).associateWith { angle }, 0, view.ordinal + 1)
        } }
        return GaitSession("session-contract-test", 1_000_000, GaitProfile(181, 77, "Male", 35), samples,
            0, 0, samples.size, 2, true, true, protocolVersion = if (method == GaitMethod.POSE) 9 else 7,
            frontValidDurationMs = 6_000, backValidDurationMs = 6_000, measurementMethod = method)
    }
    private fun report(method: GaitMethod = GaitMethod.MARKERS) = GaitCloudReport.fromSession(owner, session(method))
    private fun assertInvalid(data: Map<String, Any?>, patient: String = owner, id: String = session().id) {
        assertTrue("Malformed or mismatched report must be rejected: $data",
            runCatching { GaitCloudReport.fromMap(data, patient, id) }.isFailure)
    }

    @Test fun payloadContainsOnlyCompactNumericalResultsAndNoRawOrDemographicFields() {
        val payload = report().toMap()
        assertEquals(setOf("schemaVersion", "patientUid", "sessionId", "createdAt", "measurementMethod",
            "acquisitionSource", "protocolVersion", "completed", "manualLevel", "comparisonGroupId",
            "typicalInclinations", "sampleCounts", "qualifiedMetrics", "acceptedFrames", "rejectedFrames",
            "frontValidDurationMs", "backValidDurationMs"), payload.keys)
        fun keys(value: Any?): Set<String> = when (value) {
            is Map<*, *> -> value.keys.filterIsInstance<String>().toSet() + value.values.flatMap { keys(it) }
            is Iterable<*> -> value.flatMap { keys(it) }.toSet()
            else -> emptySet()
        }
        assertTrue(keys(payload).intersect(setOf("samples", "markers", "coordinates", "video", "uri", "photo",
            "profile", "heightCm", "weightKg", "age", "sex", "palette", "calibration")).isEmpty())
        assertEquals(1.5, report().typicalInclinations.getValue("FRONT_SHOULDERS"), .00001)
        assertEquals(4, report().sampleCounts.getValue("FRONT_SHOULDERS"))
        assertEquals(64, report().comparisonGroupId.length)
    }

    @Test fun markersExportSixLinesAndPoseExportsFiveWithoutSynthesizingSpine() {
        val marker = report()
        assertEquals(6, marker.typicalInclinations.size)
        assertTrue("BACK_SPINE" in marker.typicalInclinations)
        val pose = report(GaitMethod.POSE)
        assertEquals(5, pose.typicalInclinations.size)
        assertFalse("BACK_SPINE" in pose.typicalInclinations)
        assertEquals(pose.typicalInclinations.keys, pose.sampleCounts.keys)
        assertEquals(pose.typicalInclinations.keys, pose.qualifiedMetrics)
    }

    @Test fun partialResultsKeepTheirActualAnglesWithoutClaimingQualifiedDailyCoverage() {
        val partial = session(GaitMethod.POSE).copy(completed = false, frontValidDurationMs = 500,
            backValidDurationMs = 0, samples = session(GaitMethod.POSE).samples.filter { it.view == GaitView.FRONT }.take(2))
        val report = GaitCloudReport.fromSession(owner, partial)
        assertEquals(setOf("FRONT_SHOULDERS", "FRONT_PELVIS", "FRONT_KNEES"), report.typicalInclinations.keys)
        assertTrue(report.qualifiedMetrics.isEmpty())
        assertFalse(report.completed)
        assertFalse("BACK_SHOULDERS" in report.typicalInclinations)
    }

    @Test fun firestoreLongNumericRepresentationRoundTripsWithoutLosingValues() {
        val original = report(GaitMethod.POSE)
        val payload = original.toMap().toMutableMap()
        payload["schemaVersion"] = 1L
        payload["protocolVersion"] = original.protocolVersion.toLong()
        payload["acceptedFrames"] = original.acceptedFrames.toLong()
        payload["rejectedFrames"] = original.rejectedFrames.toLong()
        payload["sampleCounts"] = original.sampleCounts.mapValues { it.value.toLong() }
        assertEquals(original, GaitCloudReport.fromMap(payload, owner, original.sessionId))
    }

    @Test fun mismatchedDocumentOwnerOrSessionIdentityIsRejected() {
        val payload = report().toMap()
        assertInvalid(payload, patient = "different-patient")
        assertInvalid(payload, id = "different-session")
        assertInvalid(payload + ("patientUid" to "different-patient"))
        assertInvalid(payload + ("sessionId" to "different-session"))
    }

    @Test fun malformedMethodSourceSchemaAndGroupAreRejected() {
        val payload = report().toMap()
        listOf("measurementMethod" to "UNKNOWN", "acquisitionSource" to "UNKNOWN", "schemaVersion" to 2,
            "protocolVersion" to 0, "protocolVersion" to 101, "comparisonGroupId" to "not-a-fingerprint",
            "createdAt" to 0L, "createdAt" to -1L).forEach { (key, value) -> assertInvalid(payload + (key to value)) }
        assertInvalid(payload - "typicalInclinations")
        assertInvalid(payload - "qualifiedMetrics")
    }

    @Test fun nonfiniteOutOfRangeUnknownOrUnsupportedAnglesCannotBeDecoded() {
        val payload = report().toMap()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 90.1).forEach { angle ->
            assertInvalid(payload + ("typicalInclinations" to (report().typicalInclinations + ("FRONT_SHOULDERS" to angle))))
        }
        assertInvalid(payload + ("typicalInclinations" to (report().typicalInclinations + ("FRONT_SPINE" to 1.0))))
        val pose = report(GaitMethod.POSE).toMap()
        assertInvalid(pose + mapOf("typicalInclinations" to (report(GaitMethod.POSE).typicalInclinations + ("BACK_SPINE" to 1.0)),
            "sampleCounts" to (report(GaitMethod.POSE).sampleCounts + ("BACK_SPINE" to 4))))
    }

    @Test fun metricCountsAndQualificationMustReferToExistingMeasuredValues() {
        val report = report()
        val payload = report.toMap()
        assertInvalid(payload + ("sampleCounts" to (report.sampleCounts - "FRONT_SHOULDERS")))
        assertInvalid(payload + ("sampleCounts" to (report.sampleCounts + ("FRONT_SHOULDERS" to 0))))
        assertInvalid(payload + ("sampleCounts" to (report.sampleCounts + ("FRONT_SHOULDERS" to 30_001))))
        assertInvalid(payload + ("qualifiedMetrics" to listOf("UNKNOWN_METRIC")))
        assertInvalid(payload + ("acceptedFrames" to -1))
        assertInvalid(payload + ("rejectedFrames" to -1))
        assertInvalid(payload + ("frontValidDurationMs" to -1L))
    }

    @Test fun integerFieldsCannotSilentlyTruncateFractionsOrNonfiniteValues() {
        val payload = report().toMap()
        listOf("schemaVersion", "protocolVersion", "createdAt", "acceptedFrames", "rejectedFrames", "frontValidDurationMs", "backValidDurationMs")
            .forEach { field ->
                assertInvalid(payload + (field to 1.5))
                assertInvalid(payload + (field to Double.NaN))
                assertInvalid(payload + (field to Double.POSITIVE_INFINITY))
            }
        assertInvalid(payload + ("sampleCounts" to (report().sampleCounts + ("FRONT_SHOULDERS" to 1.5))))
    }

    @Test fun comparisonFingerprintChangesForMethodSettingsSourceAndProtocolButNotMeasuredValues() {
        val base = session()
        val original = GaitCloudReport.fromSession(owner, base).comparisonGroupId
        val alternatives = listOf(
            base.copy(measurementMethod = GaitMethod.POSE),
            base.copy(acquisitionSource = "VIDEO"), base.copy(protocolVersion = 8), base.copy(manualLevel = false),
            base.copy(profile = base.profile.copy(heightCm = 190)), base.copy(profile = base.profile.copy(weightKg = 90)),
            base.copy(profile = base.profile.copy(age = 40)), base.copy(profile = base.profile.copy(sex = "Other")),
            base.copy(profile = base.profile.copy(passDurationSeconds = 3)),
            base.copy(profile = base.profile.copy(targetDurationSecondsPerView = 9)))
        alternatives.forEach { assertNotEquals(original, GaitCloudReport.fromSession(owner, it).comparisonGroupId) }
        val changedValues = base.copy(id = "new-session", createdAt = base.createdAt + 1,
            samples = base.samples.map { it.copy(angles = it.angles.mapValues { (_, value) -> value / 2 }) })
        assertEquals(original, GaitCloudReport.fromSession(owner, changedValues).comparisonGroupId)
        assertEquals(original, GaitCloudReport.fromSession(owner, base.copy(sourceMirrored = true)).comparisonGroupId)
    }
}
