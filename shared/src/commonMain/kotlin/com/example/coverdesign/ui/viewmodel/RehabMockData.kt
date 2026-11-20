package com.example.coverdesign.ui.viewmodel

import com.example.coverdesign.domain.model.CopPoint
import com.example.coverdesign.domain.model.DoctorRecommendation
import com.example.coverdesign.domain.model.FootPressureMap
import com.example.coverdesign.domain.model.GaitAcquisitionSource
import com.example.coverdesign.domain.model.GaitMeasurementMethod
import com.example.coverdesign.domain.model.GaitMetricKey
import com.example.coverdesign.domain.model.GaitReport
import com.example.coverdesign.domain.model.GaitSession
import com.example.coverdesign.domain.model.InsoleData
import com.example.coverdesign.domain.model.RecommendationTag
import com.example.coverdesign.domain.util.currentEpochMillis
import kotlin.math.PI
import kotlin.math.sin

object RehabMockData {

    private val DAY_MS = 86_400_000L

    // A plausible, fixed 64-hex comparison group so all mock sessions trend together.
    private const val MOCK_COMPARISON_GROUP =
        "0000000000000000000000000000000000000000000000000000000000000000"

    fun generateSessions(): List<GaitSession> {
        val now = currentEpochMillis()
        // Inclination angles (degrees) DECREASE toward the newest session = improving posture.
        return listOf(
            buildSession("s1", now,               baseAngle = 2.5f, durationSec = 420),
            buildSession("s2", now - 7 * DAY_MS,  baseAngle = 3.8f, durationSec = 390),
            buildSession("s3", now - 14 * DAY_MS, baseAngle = 5.2f, durationSec = 360),
            buildSession("s4", now - 21 * DAY_MS, baseAngle = 6.6f, durationSec = 330),
            buildSession("s5", now - 28 * DAY_MS, baseAngle = 8.0f, durationSec = 300),
        )
    }

    // COP lissajous: x = A·sin(t), y = B·sin(2t + π/4) — no Random needed
    fun generateCopTrajectory(n: Int = 120): List<CopPoint> {
        val twoPI = 2.0 * PI
        return (0 until n).map { i ->
            val t = i.toDouble() * twoPI / n
            CopPoint(
                timeMs = i * 100L,
                x = (30.0 * sin(t)).toFloat(),
                y = (20.0 * sin(2.0 * t + PI / 4.0)).toFloat()
            )
        }
    }

    fun generateRecommendations(): List<DoctorRecommendation> {
        val now = currentEpochMillis()
        return listOf(
            DoctorRecommendation(
                id = "r1",
                doctorName = "Dr. Petrenko",
                timestampMs = now - 2 * DAY_MS,
                tag = RecommendationTag.INSOLE_BASED,
                text = "Adjust left insole angle by 2 degrees. Continue current exercises and increase walking duration to 30 min daily."
            ),
            DoctorRecommendation(
                id = "r2",
                doctorName = "Dr. Petrenko",
                timestampMs = now - 7 * DAY_MS,
                tag = RecommendationTag.PHOTOGRAMMETRIC,
                text = "Posture correction exercises showing good results. Maintain current routine and focus on shoulder alignment."
            )
        )
    }

    private fun buildSession(
        id: String,
        timestamp: Long,
        baseAngle: Float,
        durationSec: Int
    ): GaitSession {
        // Per-metric mean-absolute inclination derived from the session's base angle.
        val inclinations = mapOf(
            GaitMetricKey.FRONT_SHOULDERS to baseAngle,
            GaitMetricKey.FRONT_PELVIS to baseAngle * 0.8f,
            GaitMetricKey.FRONT_KNEES to baseAngle * 1.1f,
            GaitMetricKey.BACK_SHOULDERS to baseAngle * 0.9f,
            GaitMetricKey.BACK_PELVIS to baseAngle * 0.7f,
            GaitMetricKey.BACK_SPINE to baseAngle * 1.2f,
        )
        return GaitSession(
            id = id,
            patientUid = "mock_patient",
            timestamp = timestamp,
            durationSeconds = durationSec,
            report = GaitReport(
                typicalInclinations = inclinations,
                sampleCounts = inclinations.keys.associateWith { 80 },
                qualifiedMetrics = inclinations.keys,
                measurementMethod = GaitMeasurementMethod.MARKERS,
                acquisitionSource = GaitAcquisitionSource.VIDEO,
                protocolVersion = 7,
                acceptedFrames = 500,
                rejectedFrames = 40,
                frontValidDurationMs = durationSec * 500L,
                backValidDurationMs = durationSec * 500L,
                comparisonGroupId = MOCK_COMPARISON_GROUP
            ),
            pressureData = InsoleData(
                leftFoot = FootPressureMap(heel = 0.35f, midfoot = 0.15f, forefoot = 0.35f, toes = 0.15f),
                rightFoot = FootPressureMap(heel = 0.33f, midfoot = 0.17f, forefoot = 0.34f, toes = 0.16f),
                copTrajectory = generateCopTrajectory(),
                stepCount = (durationSec / 0.6f).toInt(),
                cadence = 100f
            )
        )
    }
}
