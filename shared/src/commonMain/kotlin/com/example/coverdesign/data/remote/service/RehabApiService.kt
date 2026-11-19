package com.example.coverdesign.data.remote.service

import com.example.coverdesign.data.dto.GaitProfileDto
import com.example.coverdesign.data.dto.RehabResponseDto
import com.example.coverdesign.data.dto.RehabTaskDto
import com.example.coverdesign.data.dto.SubmitRehabSessionDto
import com.example.coverdesign.data.remote.client.ApiConstants
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody

// data/remote/service/RehabApiService.kt
class RehabApiService(private val client: HttpClient) {

    suspend fun getRehabData(): RehabResponseDto =
        client.get(ApiConstants.Endpoints.REHAB).body()

    suspend fun putProfile(body: GaitProfileDto): GaitProfileDto =
        client.put(ApiConstants.Endpoints.REHAB_PROFILE) {
            setBody(body)
        }.body()

    suspend fun submitSession(body: SubmitRehabSessionDto): RehabTaskDto =
        client.post(ApiConstants.Endpoints.SUBMIT_REHAB_SESSION) {
            setBody(body)
        }.body()

    suspend fun getSessionStatus(taskId: String): RehabTaskDto =
        client.get(
            ApiConstants.Endpoints.REHAB_SESSION_STATUS.replace("{taskId}", taskId)
        ).body()
}
