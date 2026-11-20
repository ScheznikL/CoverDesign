package com.example.coverdesign.gait

import com.example.coverdesign.domain.model.GaitAcquisitionSource
import com.example.coverdesign.domain.model.GaitMeasurementMethod
import com.example.coverdesign.domain.model.GaitMetricKey
import com.example.coverdesign.domain.model.GaitReport
import com.example.coverdesign.domain.model.GaitSession as RehabSession

/**
 * Bridges a locally-computed gait session (Path A) into the shared REST domain model so it can
 * be mirrored to the rehab server via `POST /rehab/sessions_data`. Reuses [GaitCloudReport]'s
 * exact numeric summaries; the cloud metric keys ("FRONT_SHOULDERS"…) already match the wire
 * names used by [GaitMetricKey].
 */
internal fun GaitSession.toRehabDomain(patientUid: String): RehabSession {
    val cloud = GaitCloudReport.fromSession(patientUid, this)
    val durationSeconds = if (acquisitionSource == "VIDEO") {
        (sourceDurationMs / 1000L).toInt()
    } else {
        ((cloud.frontValidDurationMs + cloud.backValidDurationMs) / 1000L).toInt()
    }
    return RehabSession(
        id = id,
        patientUid = patientUid,
        timestamp = createdAt,
        durationSeconds = durationSeconds,
        report = GaitReport(
            typicalInclinations = cloud.typicalInclinations.toMetricMap { it.toFloat() },
            sampleCounts = cloud.sampleCounts.toMetricMap { it },
            qualifiedMetrics = cloud.qualifiedMetrics.mapNotNull { GaitMetricKey.fromWire(it) }.toSet(),
            measurementMethod = when (cloud.measurementMethod) {
                GaitMethod.POSE -> GaitMeasurementMethod.POSE
                GaitMethod.MARKERS -> GaitMeasurementMethod.MARKERS
            },
            acquisitionSource = GaitAcquisitionSource.fromRaw(cloud.acquisitionSource),
            protocolVersion = cloud.protocolVersion,
            acceptedFrames = cloud.acceptedFrames,
            rejectedFrames = cloud.rejectedFrames,
            frontValidDurationMs = cloud.frontValidDurationMs,
            backValidDurationMs = cloud.backValidDurationMs,
            comparisonGroupId = cloud.comparisonGroupId,
        ),
        pressureData = null,  // the gait module carries no smart-insole data
        notes = "",
    )
}

private inline fun <V, R> Map<String, V>.toMetricMap(transform: (V) -> R): Map<GaitMetricKey, R> =
    buildMap {
        this@toMetricMap.forEach { (raw, value) ->
            GaitMetricKey.fromWire(raw)?.let { put(it, transform(value)) }
        }
    }
