package com.example.coverdesign.data.dto

import com.example.coverdesign.domain.model.CopPoint
import com.example.coverdesign.domain.model.DoctorRecommendation
import com.example.coverdesign.domain.model.FootPressureMap
import com.example.coverdesign.domain.model.GaitAcquisitionSource
import com.example.coverdesign.domain.model.GaitMeasurementMethod
import com.example.coverdesign.domain.model.GaitMetricKey
import com.example.coverdesign.domain.model.GaitProfileInput
import com.example.coverdesign.domain.model.GaitReport
import com.example.coverdesign.domain.model.GaitSession
import com.example.coverdesign.domain.model.InsoleData
import com.example.coverdesign.domain.model.RecommendationTag
import com.example.coverdesign.domain.model.RehabData
import com.example.coverdesign.domain.model.RehabSessionStatus
import com.example.coverdesign.domain.model.RehabTask

// ═══════════════════════════════════════════════════════════════════
// DTO → Domain  (incoming from backend)
// ═══════════════════════════════════════════════════════════════════

fun RehabResponseDto.toDomain(): RehabData = RehabData(
    sessions        = sessions.map { it.toDomain() },
    recommendations = recommendations.map { it.toDomain() }
)

fun RehabTaskDto.toDomain() = RehabTask(
    taskId  = taskId,
    status  = when (status.lowercase()) {
        "ready"  -> RehabSessionStatus.READY
        "failed" -> RehabSessionStatus.FAILED
        else     -> RehabSessionStatus.PROCESSING
    },
    session = session?.toDomain()
)

fun GaitSessionDto.toDomain() = GaitSession(
    id              = id,
    patientUid      = patientUid,
    timestamp       = timestampMs,
    durationSeconds = durationSeconds,
    report          = gaitReport.toDomain(),
    pressureData    = pressureData?.toDomain(),
    notes           = notes
)

fun GaitReportDto.toDomain() = GaitReport(
    typicalInclinations = typicalInclinations.mapKeysToMetric { it.toFloat() },
    sampleCounts        = sampleCounts.mapKeysToMetric { it },
    qualifiedMetrics    = qualifiedMetrics.mapNotNull { GaitMetricKey.fromWire(it) }.toSet(),
    measurementMethod   = GaitMeasurementMethod.fromRaw(measurementMethod),
    acquisitionSource   = GaitAcquisitionSource.fromRaw(acquisitionSource),
    protocolVersion     = protocolVersion,
    acceptedFrames      = acceptedFrames,
    rejectedFrames      = rejectedFrames,
    frontValidDurationMs = frontValidDurationMs,
    backValidDurationMs  = backValidDurationMs,
    comparisonGroupId    = comparisonGroupId
)

/** Maps wire metric keys to [GaitMetricKey], dropping unknown keys defensively. */
private inline fun <V, R> Map<String, V>.mapKeysToMetric(transform: (V) -> R): Map<GaitMetricKey, R> =
    buildMap {
        this@mapKeysToMetric.forEach { (raw, value) ->
            GaitMetricKey.fromWire(raw)?.let { put(it, transform(value)) }
        }
    }

fun InsoleDataDto.toDomain() = InsoleData(
    leftFoot      = leftFoot.toDomain(),
    rightFoot     = rightFoot.toDomain(),
    copTrajectory = copTrajectory.map { it.toDomain() },
    stepCount     = stepCount,
    cadence       = cadence
)

fun FootPressureMapDto.toDomain() = FootPressureMap(
    heel     = heel,
    midfoot  = midfoot,
    forefoot = forefoot,
    toes     = toes
)

fun CopPointDto.toDomain() = CopPoint(
    timeMs = timeMs,
    x      = x,
    y      = y
)

fun DoctorRecommendationDto.toDomain() = DoctorRecommendation(
    id          = id,
    doctorName  = doctorName,
    timestampMs = timestampMs,
    tag         = when (tag.lowercase()) {
        "photogrammetric" -> RecommendationTag.PHOTOGRAMMETRIC
        else              -> RecommendationTag.INSOLE_BASED
    },
    text = text
)

// ═══════════════════════════════════════════════════════════════════
// Domain → DTO  (outgoing to backend, e.g. for syncing local sessions)
// ═══════════════════════════════════════════════════════════════════

fun GaitSession.toDto() = GaitSessionDto(
    id              = id,
    patientUid      = patientUid,
    timestampMs     = timestamp,
    durationSeconds = durationSeconds,
    gaitReport      = report.toDto(),
    pressureData    = pressureData?.toDto(),
    notes           = notes
)

fun GaitReport.toDto() = GaitReportDto(
    typicalInclinations  = typicalInclinations.entries.associate { it.key.wireName to it.value.toDouble() },
    sampleCounts         = sampleCounts.entries.associate { it.key.wireName to it.value },
    qualifiedMetrics     = qualifiedMetrics.map { it.wireName },
    measurementMethod    = measurementMethod.name,
    acquisitionSource    = acquisitionSource.name,
    protocolVersion      = protocolVersion,
    acceptedFrames       = acceptedFrames,
    rejectedFrames       = rejectedFrames,
    frontValidDurationMs = frontValidDurationMs,
    backValidDurationMs  = backValidDurationMs,
    comparisonGroupId    = comparisonGroupId
)

fun InsoleData.toDto() = InsoleDataDto(
    leftFoot      = leftFoot.toDto(),
    rightFoot     = rightFoot.toDto(),
    copTrajectory = copTrajectory.map { it.toDto() },
    stepCount     = stepCount,
    cadence       = cadence
)

fun FootPressureMap.toDto() = FootPressureMapDto(
    heel     = heel,
    midfoot  = midfoot,
    forefoot = forefoot,
    toes     = toes
)

fun CopPoint.toDto() = CopPointDto(
    timeMs = timeMs,
    x      = x,
    y      = y
)

fun DoctorRecommendation.toDto() = DoctorRecommendationDto(
    id          = id,
    doctorName  = doctorName,
    timestampMs = timestampMs,
    tag         = when (tag) {
        RecommendationTag.PHOTOGRAMMETRIC -> "photogrammetric"
        RecommendationTag.INSOLE_BASED    -> "insole_based"
    },
    text = text
)

fun GaitProfileInput.toDto() = GaitProfileDto(
    heightCm                     = heightCm,
    weightKg                     = weightKg,
    sex                          = sex,
    age                          = age,
    passSteps                    = passSteps,
    targetStepsPerView           = targetStepsPerView,
    passDurationSeconds          = passDurationSeconds,
    targetDurationSecondsPerView = targetDurationSecondsPerView,
    updatedAt                    = updatedAt
)

fun GaitProfileDto.toDomain() = GaitProfileInput(
    heightCm                     = heightCm,
    weightKg                     = weightKg,
    sex                          = sex,
    age                          = age,
    passSteps                    = passSteps,
    targetStepsPerView           = targetStepsPerView,
    passDurationSeconds          = passDurationSeconds,
    targetDurationSecondsPerView = targetDurationSecondsPerView,
    updatedAt                    = updatedAt
)