package com.example.coverdesign.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Top-level envelope ────────────────────────────────────────────────────────

@Serializable
data class RehabResponseDto(
    @SerialName("sessions")        val sessions:        List<GaitSessionDto>,
    @SerialName("recommendations") val recommendations: List<DoctorRecommendationDto>
)

// ── Session ───────────────────────────────────────────────────────────────────

@Serializable
data class GaitSessionDto(
    @SerialName("id")               val id:              String,
    @SerialName("patient_uid")      val patientUid:      String,
    @SerialName("timestamp_ms")     val timestampMs:     Long,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("gait_analysis")    val gaitAnalysis:    GaitAnalysisResultDto,
    @SerialName("pressure_data")    val pressureData:    InsoleDataDto? = null,
    @SerialName("notes")            val notes:           String = ""
)

// ── Gait analysis ─────────────────────────────────────────────────────────────

@Serializable
data class GaitAnalysisResultDto(
    @SerialName("posture_score")   val postureScore:   Float,   // 0–100
    @SerialName("balance_score")   val balanceScore:   Float,   // 0–100
    @SerialName("symmetry_score")  val symmetryScore:  Float,   // 0–100
    @SerialName("scoliosis_score") val scoliosisScore: Float,   // 0–100; 100 = straight
    @SerialName("position_series") val positionSeries: List<BodyPositionDto>,
    @SerialName("abnormalities")   val abnormalities:  List<String>
)

@Serializable
data class BodyPositionDto(
    @SerialName("time_ms")     val timeMs:     Long,
    @SerialName("deviation_x") val deviationX: Float,  // lateral cm
    @SerialName("deviation_y") val deviationY: Float   // anterior-posterior cm
)

// ── Insole / pressure data ────────────────────────────────────────────────────

@Serializable
data class InsoleDataDto(
    @SerialName("left_foot")       val leftFoot:      FootPressureMapDto,
    @SerialName("right_foot")      val rightFoot:     FootPressureMapDto,
    @SerialName("cop_trajectory")  val copTrajectory: List<CopPointDto>,
    @SerialName("step_count")      val stepCount:     Int,
    @SerialName("cadence")         val cadence:       Float   // steps/min
)

@Serializable
data class FootPressureMapDto(
    @SerialName("heel")     val heel:     Float,  // fraction 0–1
    @SerialName("midfoot")  val midfoot:  Float,
    @SerialName("forefoot") val forefoot: Float,
    @SerialName("toes")     val toes:     Float
)

@Serializable
data class CopPointDto(
    @SerialName("time_ms") val timeMs: Long,
    @SerialName("x")       val x:      Float,  // medial-lateral mm (+ = lateral)
    @SerialName("y")       val y:      Float   // anterior-posterior mm (+ = forward)
)

// ── Doctor recommendation ─────────────────────────────────────────────────────

@Serializable
data class DoctorRecommendationDto(
    @SerialName("id")           val id:          String,
    @SerialName("doctor_name")  val doctorName:  String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    @SerialName("tag")          val tag:         String,  // "insole_based" | "photogrammetric"
    @SerialName("text")         val text:        String
)

// ── Session submission / processing (video → gait chart) ───────────────────────

@Serializable
data class SubmitRehabSessionDto(
    @SerialName("video_path")         val videoPath:          String,  // Firebase Storage path, e.g. "sessions/<uid>/pose_<ts>.mp4"
    @SerialName("duration_seconds")   val durationSeconds:    Int = 0,
    @SerialName("measurement_method") val measurementMethod:  String? = null,  // "MARKERS" | "POSE"
    @SerialName("acquisition_source") val acquisitionSource:  String? = null,  // "LIVE" | "VIDEO"
    @SerialName("views")              val views:              List<String> = emptyList()  // subset of "FRONT","BACK"
)

// ── User gait profile (biometrics + capture settings) ──────────────────────────
// Sent to its own user-scoped location; the server reads it when analysing a video.

@Serializable
data class GaitProfileDto(
    @SerialName("height_cm")                        val heightCm:                     Int,
    @SerialName("weight_kg")                        val weightKg:                     Int,
    @SerialName("sex")                              val sex:                          String,  // "Female" | "Male" | "Other" | "Prefer not to say"
    @SerialName("age")                              val age:                          Int,
    @SerialName("pass_steps")                       val passSteps:                    Int,
    @SerialName("target_steps_per_view")            val targetStepsPerView:           Int,
    @SerialName("pass_duration_seconds")            val passDurationSeconds:          Int,
    @SerialName("target_duration_seconds_per_view") val targetDurationSecondsPerView: Int,
    @SerialName("updated_at")                       val updatedAt:                    Long
)

@Serializable
data class RehabTaskDto(
    @SerialName("task_id") val taskId:  String,
    @SerialName("status")  val status:  String,          // "processing" | "ready" | "failed"
    @SerialName("session") val session: GaitSessionDto? = null
)