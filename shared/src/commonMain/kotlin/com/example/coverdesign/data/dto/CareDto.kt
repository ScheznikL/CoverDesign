package com.example.coverdesign.data.dto

import com.example.coverdesign.domain.model.CarePatient
import com.example.coverdesign.domain.model.CareStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `RosterEntry` from `GET /care/patients` (see backend README → Care). */
@Serializable
data class RosterEntryDto(
    @SerialName("patient_uid") val patientUid: String,
    @SerialName("patient_email") val patientEmail: String? = null,
    @SerialName("patient_name") val patientName: String? = null,
    @SerialName("treatment_type") val treatmentType: String = "",
    @SerialName("status") val status: String = "pending",
    @SerialName("assigned_at") val assignedAt: Long = 0L,
    @SerialName("last_reviewed_at") val lastReviewedAt: Long? = null,
)

/** Body for `POST /care/patients/{patient_uid}/recommendations`. */
@Serializable
data class CareRecommendationRequestDto(
    @SerialName("tag") val tag: String,
    @SerialName("text") val text: String,
)

fun RosterEntryDto.toDomain(): CarePatient = CarePatient(
    patientUid = patientUid,
    name = patientName?.takeIf { it.isNotBlank() }
        ?: patientEmail?.takeIf { it.isNotBlank() }
        ?: "Patient",
    email = patientEmail?.takeIf { it.isNotBlank() },
    treatmentType = treatmentType,
    status = CareStatus.fromRaw(status),
    assignedAt = assignedAt,
    lastReviewedAt = lastReviewedAt,
)
