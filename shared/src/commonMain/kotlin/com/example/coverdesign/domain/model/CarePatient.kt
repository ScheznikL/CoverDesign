package com.example.coverdesign.domain.model

/** One patient on a doctor's care roster (backend `care_links` / `GET /care/patients`). */
data class CarePatient(
    val patientUid: String,
    val name: String,
    val email: String?,
    val treatmentType: String,
    val status: CareStatus,
    val assignedAt: Long,
    val lastReviewedAt: Long?,
)

enum class CareStatus {
    ACTIVE, STABLE, PENDING;

    companion object {
        /** Maps the backend's lowercase status string; unknown values fall back to [PENDING]. */
        fun fromRaw(raw: String?): CareStatus = when (raw?.trim()?.lowercase()) {
            "active" -> ACTIVE
            "stable" -> STABLE
            else -> PENDING
        }
    }
}
