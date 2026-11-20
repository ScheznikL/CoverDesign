package com.example.coverdesign.data.demo

import com.example.coverdesign.domain.model.CopPoint
import com.example.coverdesign.domain.model.FootPressureMap
import com.example.coverdesign.domain.model.InsoleData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object DemoInsoleData {

    private const val POINT_COUNT_PER_FOOT = 60

    fun generateCopTrajectory(
        pointCount: Int = POINT_COUNT_PER_FOOT,
        phase: Float = 0f,
        lateralBias: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ): List<CopPoint> {

        require(pointCount >= 2)

        return (0 until pointCount).map { index ->

            val t = index.toFloat() / (pointCount - 1)
            val angle = t * 2f * PI.toFloat() + phase

            val x = (
                    16f * sin(angle) +
                            4f * sin(3f * angle)
                    ) * scaleX + lateralBias

            val y = (
                    11f * cos(angle) +
                            3f * sin(2f * angle)
                    ) * scaleY

            CopPoint(
                timeMs = index * 100L,
                x = x,
                y = y
            )
        }
    }

    fun generateCombinedCopTrajectory(): List<CopPoint> {

        val leftFootPoints = generateCopTrajectory(
            phase = 0f,
            lateralBias = -8f,
            scaleX = 1f,
            scaleY = 1f
        )

        val rightFootPoints = generateCopTrajectory(
            phase = 0.8f,
            lateralBias = 8f,
            scaleX = 0.85f,
            scaleY = 1.1f
        )

        return leftFootPoints + rightFootPoints
    }

    fun create(): InsoleData {

        return InsoleData(
            leftFoot = FootPressureMap(
                heel = 0.72f,
                midfoot = 0.48f,
                forefoot = 0.82f,
                toes = 0.38f
            ),

            rightFoot = FootPressureMap(
                heel = 0.68f,
                midfoot = 0.55f,
                forefoot = 0.76f,
                toes = 0.42f
            ),

            copTrajectory = generateCombinedCopTrajectory(),

            stepCount = 12,
            cadence = 90f
        )
    }
}