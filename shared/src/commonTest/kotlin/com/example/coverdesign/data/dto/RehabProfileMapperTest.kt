package com.example.coverdesign.data.dto

import com.example.coverdesign.domain.model.GaitProfileInput
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RehabProfileMapperTest {

    private val json = Json { encodeDefaults = true }

    private val sample = GaitProfileInput(
        heightCm = 181,
        weightKg = 77,
        sex = "Male",
        age = 35,
        passSteps = 4,
        targetStepsPerView = 12,
        passDurationSeconds = 2,
        targetDurationSecondsPerView = 6,
        updatedAt = 1_731_801_600_000
    )

    @Test
    fun profile_roundTripsThroughDto() {
        assertEquals(sample, sample.toDto().toDomain())
    }

    @Test
    fun profileDto_serializesSnakeCaseKeys() {
        val text = json.encodeToString(GaitProfileDto.serializer(), sample.toDto())
        listOf(
            "\"height_cm\"", "\"weight_kg\"", "\"sex\"", "\"age\"",
            "\"pass_steps\"", "\"target_steps_per_view\"",
            "\"pass_duration_seconds\"", "\"target_duration_seconds_per_view\"",
            "\"updated_at\""
        ).forEach { key -> assertTrue(key in text, "Missing $key in $text") }
    }

    @Test
    fun submitDto_carriesSessionDescriptors() {
        val dto = SubmitRehabSessionDto(
            videoPath = "sessions/uid/pose_1.mp4",
            durationSeconds = 420,
            measurementMethod = "POSE",
            acquisitionSource = "VIDEO",
            views = listOf("FRONT", "BACK")
        )
        val text = json.encodeToString(SubmitRehabSessionDto.serializer(), dto)
        assertEquals(dto, json.decodeFromString(SubmitRehabSessionDto.serializer(), text))
        listOf("\"measurement_method\"", "\"acquisition_source\"", "\"views\"")
            .forEach { key -> assertTrue(key in text, "Missing $key in $text") }
    }

    @Test
    fun submitDto_backwardCompatibleWithoutDescriptors() {
        val legacy = """{"video_path":"sessions/uid/pose_1.mp4","duration_seconds":420}"""
        val dto = json.decodeFromString(SubmitRehabSessionDto.serializer(), legacy)
        assertEquals("sessions/uid/pose_1.mp4", dto.videoPath)
        assertEquals(420, dto.durationSeconds)
        assertEquals(null, dto.measurementMethod)
        assertEquals(emptyList(), dto.views)
    }
}