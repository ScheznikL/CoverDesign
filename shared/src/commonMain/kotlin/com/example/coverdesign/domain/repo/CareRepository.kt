package com.example.coverdesign.domain.repo

import com.example.coverdesign.domain.model.CarePatient
import com.example.coverdesign.domain.util.Resource

interface CareRepository {

    /**
     * The signed-in doctor's patient roster. Fetched from the server `GET /care/patients`;
     * on error or an empty result it falls back to a direct Firestore `care_links` read.
     */
    suspend fun getDoctorPatients(): Resource<List<CarePatient>>

    /** Posts a recommendation for one of the caller's patients. `tag` is insole_based | photogrammetric. */
    suspend fun submitRecommendation(patientUid: String, tag: String, text: String): Resource<Unit>
}
