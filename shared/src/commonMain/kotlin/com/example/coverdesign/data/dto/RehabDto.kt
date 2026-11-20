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
    @SerialName("gait_report")      val gaitReport:      GaitReportDto,
    @SerialName("pressure_data")    val pressureData:    InsoleDataDto? = null,
    @SerialName("notes")            val notes:           String = ""
)

// ── Gait report ───────────────────────────────────────────────────────────────
// Mirrors the on-device gait module's numeric output (GaitCloudReport) minus its Firestore
// transport fields. Metric keys ("FRONT_SHOULDERS"…) match the gait Firestore doc and rules.

@Serializable
data class GaitReportDto(
    @SerialName("typical_inclinations")    val typicalInclinations:  Map<String, Double>, // deg 0..90
    @SerialName("sample_counts")           val sampleCounts:         Map<String, Int>,
    @SerialName("qualified_metrics")       val qualifiedMetrics:     List<String>,
    @SerialName("measurement_method")      val measurementMethod:    String,  // "MARKERS" | "POSE"
    @SerialName("acquisition_source")      val acquisitionSource:    String,  // "LIVE" | "VIDEO"
    @SerialName("protocol_version")        val protocolVersion:      Int,
    @SerialName("accepted_frames")         val acceptedFrames:       Int,
    @SerialName("rejected_frames")         val rejectedFrames:       Int,
    @SerialName("front_valid_duration_ms") val frontValidDurationMs: Long,
    @SerialName("back_valid_duration_ms")  val backValidDurationMs:  Long,
    @SerialName("comparison_group_id")     val comparisonGroupId:    String
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