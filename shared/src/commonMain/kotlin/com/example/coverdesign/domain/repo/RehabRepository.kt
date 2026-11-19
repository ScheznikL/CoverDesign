package com.example.coverdesign.domain.repo

import com.example.coverdesign.domain.model.GaitProfileInput
import com.example.coverdesign.domain.model.RehabData
import com.example.coverdesign.domain.model.RehabTask
import com.example.coverdesign.domain.util.Resource

// shared/commonMain/domain/repo/RehabRepository.kt
interface RehabRepository {

    /** Fetch the current user's gait sessions + doctor recommendations. */
    suspend fun getRehabData(): Resource<RehabData>

    /** Upsert the current user's gait profile (biometrics + capture settings). */
    suspend fun upsertGaitProfile(profile: GaitProfileInput): Resource<Unit>

    /** Notify the backend of an uploaded session video; returns the processing task id. */
    suspend fun submitSession(
        videoPath: String,
        durationSeconds: Int,
        measurementMethod: String? = null,
        acquisitionSource: String? = null,
        views: List<String> = emptyList()
    ): Resource<String>

    /** Poll processing status for a submitted session. */
    suspend fun getSessionStatus(taskId: String): Resource<RehabTask>
}
