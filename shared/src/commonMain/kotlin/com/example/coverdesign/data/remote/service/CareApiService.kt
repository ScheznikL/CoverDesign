package com.example.coverdesign.data.remote.service

import com.example.coverdesign.data.dto.CareRecommendationRequestDto
import com.example.coverdesign.data.dto.RosterEntryDto
import com.example.coverdesign.data.remote.client.ApiConstants
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody

// data/remote/service/CareApiService.kt
class CareApiService(private val client: HttpClient) {

    /** The calling doctor's patient roster, newest-reviewed first. Doctor-only on the server. */
    suspend fun getPatients(): List<RosterEntryDto> =
        client.get(ApiConstants.Endpoints.CARE_PATIENTS).body()

    /** Writes a recommendation for one of the caller's patients (`tag` = insole_based | photogrammetric). */
    suspend fun postRecommendation(patientUid: String, tag: String, text: String) {
        client.post("${ApiConstants.Endpoints.CARE_PATIENTS}/$patientUid/recommendations") {
            setBody(CareRecommendationRequestDto(tag = tag, text = text))
        }
    }
}
