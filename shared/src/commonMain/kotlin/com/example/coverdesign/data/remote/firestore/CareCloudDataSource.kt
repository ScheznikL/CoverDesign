package com.example.coverdesign.data.remote.firestore

import com.example.coverdesign.domain.model.CarePatient
import com.example.coverdesign.domain.model.CareStatus
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.firestore
import kotlinx.serialization.Serializable

/**
 * Direct Firestore read of the doctor ↔ patient roster, used as a fallback when the
 * `GET /care/patients` server API is unreachable or returns nothing.
 *
 * Reads `care_links` where `doctor_uid == <caller>` (schema: backend README → Care). Those documents
 * carry no name/email — the server enriches those from Firebase Auth — so we resolve each patient's
 * display name/email from the `users/{patient_uid}` profile document (the same collection
 * [com.example.coverdesign.data.repoimpl.FireAuthRepositoryImpl] writes). Results are sorted
 * client-side (newest reviewed first) to avoid requiring the server's composite index.
 */
class CareCloudDataSource(
    private val firestore: FirebaseFirestore = Firebase.firestore,
) {

    suspend fun getPatientsForDoctor(doctorUid: String): List<CarePatient> {
        val snapshot = firestore.collection(CARE_LINKS)
            .where { FIELD_DOCTOR_UID equalTo doctorUid }
            .get()

        val links = snapshot.documents.mapNotNull { doc ->
            runCatching { doc.data<CareLinkDoc>() }.getOrNull()
        }.filter { it.doctorUid == doctorUid }

        return links
            .map { link ->
                val profile = resolveProfile(link.patientUid)
                CarePatient(
                    patientUid = link.patientUid,
                    name = profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: profile?.email?.takeIf { it.isNotBlank() }
                        ?: "Patient",
                    email = profile?.email?.takeIf { it.isNotBlank() },
                    treatmentType = link.treatmentType,
                    status = CareStatus.fromRaw(link.status),
                    assignedAt = link.assignedAt,
                    lastReviewedAt = link.lastReviewedAt,
                )
            }
            .sortedByDescending { it.lastReviewedAt ?: it.assignedAt }
    }

    private suspend fun resolveProfile(patientUid: String): UserNameDoc? = runCatching {
        val snapshot = firestore.collection(USERS).document(patientUid).get()
        if (snapshot.exists) snapshot.data<UserNameDoc>() else null
    }.getOrNull()

    @Serializable
    private data class CareLinkDoc(
        val doctor_uid: String = "",
        val patient_uid: String = "",
        val treatment_type: String = "",
        val status: String = "pending",
        val assigned_at: Long = 0L,
        val last_reviewed_at: Long? = null,
    ) {
        val doctorUid get() = doctor_uid
        val patientUid get() = patient_uid
        val treatmentType get() = treatment_type
        val assignedAt get() = assigned_at
        val lastReviewedAt get() = last_reviewed_at
    }

    // Only the profile fields we need; GitLive ignores the rest (uid, phone, role, createdAt).
    @Serializable
    private data class UserNameDoc(
        val displayName: String = "",
        val email: String = "",
    )

    private companion object {
        const val CARE_LINKS = "care_links"
        const val USERS = "users"
        const val FIELD_DOCTOR_UID = "doctor_uid"
    }
}
