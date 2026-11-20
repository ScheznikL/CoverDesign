package com.example.coverdesign.domain.model

data class GaitSession(
    val id: String,
    val patientUid: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val report: GaitReport,
    val pressureData: InsoleData?,  // null when patient has no smart insoles
    val notes: String = ""
)

/**
 * Measured gait report mirroring the on-device gait module's numeric output
 * (`GaitCloudReport`). Values are mean-absolute inclination angles in degrees per body line
 * and camera view — not 0–100 health scores.
 */
data class GaitReport(
    val typicalInclinations: Map<GaitMetricKey, Float>,  // degrees, 0..90
    val sampleCounts: Map<GaitMetricKey, Int>,
    val qualifiedMetrics: Set<GaitMetricKey>,            // metrics with enough data to trend
    val measurementMethod: GaitMeasurementMethod,
    val acquisitionSource: GaitAcquisitionSource,
    val protocolVersion: Int,
    val acceptedFrames: Int,
    val rejectedFrames: Int,
    val frontValidDurationMs: Long,
    val backValidDurationMs: Long,
    val comparisonGroupId: String,
)

/** A body line measured from a single camera view. Wire names match the gait Firestore keys. */
enum class GaitMetricKey(val wireName: String, val label: String) {
    FRONT_SHOULDERS("FRONT_SHOULDERS", "Shoulders (front)"),
    FRONT_PELVIS("FRONT_PELVIS", "Pelvis (front)"),
    FRONT_KNEES("FRONT_KNEES", "Knees (front)"),
    BACK_SHOULDERS("BACK_SHOULDERS", "Shoulders (back)"),
    BACK_PELVIS("BACK_PELVIS", "Pelvis (back)"),
    BACK_SPINE("BACK_SPINE", "C7–coccyx line");

    companion object {
        fun fromWire(raw: String): GaitMetricKey? = entries.firstOrNull { it.wireName == raw }
    }
}

enum class GaitMeasurementMethod {
    MARKERS, POSE;

    companion object {
        fun fromRaw(raw: String?): GaitMeasurementMethod =
            if (raw?.trim()?.uppercase() == "POSE") POSE else MARKERS
    }
}

enum class GaitAcquisitionSource {
    LIVE, VIDEO;

    companion object {
        fun fromRaw(raw: String?): GaitAcquisitionSource =
            if (raw?.trim()?.uppercase() == "VIDEO") VIDEO else LIVE
    }
}
