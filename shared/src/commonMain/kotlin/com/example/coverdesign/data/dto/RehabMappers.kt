package com.example.coverdesign.data.dto

import com.example.coverdesign.domain.model.BodyPosition
import com.example.coverdesign.domain.model.CopPoint
import com.example.coverdesign.domain.model.DoctorRecommendation
import com.example.coverdesign.domain.model.FootPressureMap
import com.example.coverdesign.domain.model.GaitAnalysisResult
import com.example.coverdesign.domain.model.GaitProfileInput
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
    gaitAnalysis    = gaitAnalysis.toDomain(),
    pressureData    = pressureData?.toDomain(),
    notes           = notes
)

fun GaitAnalysisResultDto.toDomain() = GaitAnalysisResult(
    postureScore   = postureScore,
    balanceScore   = balanceScore,
    symmetryScore  = symmetryScore,
    scoliosisScore = scoliosisScore,
    positionSeries = positionSeries.map { it.toDomain() },
    abnormalities  = abnormalities
)

fun BodyPositionDto.toDomain() = BodyPosition(
    timeMs     = timeMs,
    deviationX = deviationX,
    deviationY = deviationY
)

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
    gaitAnalysis    = gaitAnalysis.toDto(),
    pressureData    = pressureData?.toDto(),
    notes           = notes
)

fun GaitAnalysisResult.toDto() = GaitAnalysisResultDto(
    postureScore   = postureScore,
    balanceScore   = balanceScore,
    symmetryScore  = symmetryScore,
    scoliosisScore = scoliosisScore,
    positionSeries = positionSeries.map { it.toDto() },
    abnormalities  = abnormalities
)

fun BodyPosition.toDto() = BodyPositionDto(
    timeMs     = timeMs,
    deviationX = deviationX,
    deviationY = deviationY
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