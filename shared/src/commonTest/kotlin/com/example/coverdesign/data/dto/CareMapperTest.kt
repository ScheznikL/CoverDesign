package com.example.coverdesign.data.dto

import com.example.coverdesign.domain.model.AppUser
import com.example.coverdesign.domain.model.CareStatus
import com.example.coverdesign.ui.viewmodel.PatientStatus
import com.example.coverdesign.ui.viewmodel.toSummary
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CareMapperTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun rosterEntry_deserializesSnakeCaseFromServer() {
        val raw = """
            {
              "patient_uid": "uid-1",
              "patient_email": "alice@example.com",
              "patient_name": "Alice R.",
              "treatment_type": "Scoliosis Alignment",
              "status": "active",
              "assigned_at": 1700000000000,
              "last_reviewed_at": 1700500000000
            }
        """.trimIndent()
        val dto = json.decodeFromString(RosterEntryDto.serializer(), raw)
        val domain = dto.toDomain()
        assertEquals("uid-1", domain.patientUid)
        assertEquals("Alice R.", domain.name)
        assertEquals("alice@example.com", domain.email)
        assertEquals("Scoliosis Alignment", domain.treatmentType)
        assertEquals(CareStatus.ACTIVE, domain.status)
        assertEquals(1700500000000, domain.lastReviewedAt)
    }

    @Test
    fun rosterEntry_toleratesMissingOptionalFieldsAndFallsBackName() {
        // No name → use email; nulls allowed for email/last_reviewed_at.
        val emailOnly = json.decodeFromString(
            RosterEntryDto.serializer(),
            """{"patient_uid":"u","patient_email":"bob@x.com","treatment_type":"Rehab","status":"stable","assigned_at":1}"""
        ).toDomain()
        assertEquals("bob@x.com", emailOnly.name)
        assertEquals(CareStatus.STABLE, emailOnly.status)
        assertNull(emailOnly.lastReviewedAt)

        // No name and no email → generic placeholder.
        val bare = json.decodeFromString(
            RosterEntryDto.serializer(),
            """{"patient_uid":"u","treatment_type":"","status":"pending","assigned_at":0}"""
        ).toDomain()
        assertEquals("Patient", bare.name)
        assertNull(bare.email)
    }

    @Test
    fun careStatus_fromRaw_isCaseInsensitiveAndDefaultsToPending() {
        assertEquals(CareStatus.ACTIVE, CareStatus.fromRaw("active"))
        assertEquals(CareStatus.ACTIVE, CareStatus.fromRaw("ACTIVE"))
        assertEquals(CareStatus.STABLE, CareStatus.fromRaw("Stable"))
        assertEquals(CareStatus.PENDING, CareStatus.fromRaw("pending"))
        assertEquals(CareStatus.PENDING, CareStatus.fromRaw("something-unknown"))
        assertEquals(CareStatus.PENDING, CareStatus.fromRaw(null))
    }

    @Test
    fun toSummary_mapsStatusLeavesMetricsNullAndFormatsRelativeDay() {
        val now = 10_000L * 86_400_000L // day 10000 in epoch-days
        val dto = RosterEntryDto(
            patientUid = "uid-2",
            patientName = "Carl T.",
            treatmentType = "Gait Instability",
            status = "active",
            assignedAt = now - 30L * 86_400_000L,       // 30 days ago
            lastReviewedAt = now - 3L * 86_400_000L,     // 3 days ago (preferred)
        )
        val summary = dto.toDomain().toSummary(now = now)
        assertEquals("uid-2", summary.id)
        assertEquals("Carl T.", summary.name)
        assertEquals(PatientStatus.Active, summary.status)
        assertEquals("3 days ago", summary.lastVisitDate)
        assertNull(summary.asymmetryCoeff)
        assertNull(summary.kyphosisCoeff)
    }

    @Test
    fun toSummary_fallsBackToAssignedWhenNeverReviewed_andHandlesMissingTimestamp() {
        val now = 10_000L * 86_400_000L
        val neverReviewed = RosterEntryDto(
            patientUid = "u", patientName = "D", treatmentType = "x", status = "pending",
            assignedAt = now - 1L * 86_400_000L, lastReviewedAt = null,
        ).toDomain().toSummary(now = now)
        assertEquals("Yesterday", neverReviewed.lastVisitDate)

        val noTimes = RosterEntryDto(
            patientUid = "u", patientName = "D", treatmentType = "x", status = "pending",
            assignedAt = 0L, lastReviewedAt = null,
        ).toDomain().toSummary(now = now)
        assertEquals("—", noTimes.lastVisitDate)
    }

    @Test
    fun recommendationRequest_serializesTagAndText() {
        val text = json.encodeToString(
            CareRecommendationRequestDto.serializer(),
            CareRecommendationRequestDto(tag = "insole_based", text = "Adjust left insole angle.")
        )
        assertTrue("\"tag\"" in text, text)
        assertTrue("\"insole_based\"" in text, text)
        assertTrue("\"text\"" in text, text)
    }

    @Test
    fun appUser_defaultsToSharingGaitWithCareTeam() {
        // Opt-out model: consent is on unless the patient turns it off.
        assertTrue(AppUser().shareGaitWithCareTeam)
    }
}
