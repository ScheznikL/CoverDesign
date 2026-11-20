package com.example.coverdesign.data.repoimpl

import com.example.coverdesign.data.dto.SubmitRehabSessionDto
import com.example.coverdesign.data.dto.toDomain
import com.example.coverdesign.data.dto.toDto
import com.example.coverdesign.data.remote.service.RehabApiService
import com.example.coverdesign.domain.model.GaitProfileInput
import com.example.coverdesign.domain.model.GaitSession
import com.example.coverdesign.domain.model.RehabData
import com.example.coverdesign.domain.model.RehabTask
import com.example.coverdesign.domain.repo.RehabRepository
import com.example.coverdesign.domain.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RehabRepositoryImpl(
    private val api: RehabApiService
) : RehabRepository {

    override suspend fun getRehabData(): Resource<RehabData> =
        withContext(Dispatchers.Default) {
            try {
                Resource.Success(api.getRehabData().toDomain())
            } catch (e: Exception) {
                Resource.Error("Failed to load rehab data: ${e.message}", e)
            }
        }

    override suspend fun upsertGaitProfile(profile: GaitProfileInput): Resource<Unit> =
        withContext(Dispatchers.Default) {
            try {
                api.putProfile(profile.toDto())
                Resource.Success(Unit)
            } catch (e: Exception) {
                Resource.Error("Failed to save gait profile: ${e.message}", e)
            }
        }

    override suspend fun submitSession(
        videoPath: String,
        durationSeconds: Int,
        measurementMethod: String?,
        acquisitionSource: String?,
        views: List<String>
    ): Resource<String> = withContext(Dispatchers.Default) {
        try {
            val task = api.submitSession(
                SubmitRehabSessionDto(
                    videoPath = videoPath,
                    durationSeconds = durationSeconds,
                    measurementMethod = measurementMethod,
                    acquisitionSource = acquisitionSource,
                    views = views
                )
            )
            Resource.Success(task.taskId)
        } catch (e: Exception) {
            Resource.Error("Failed to submit session: ${e.message}", e)
        }
    }

    override suspend fun getSessionStatus(taskId: String): Resource<RehabTask> =
        withContext(Dispatchers.Default) {
            try {
                Resource.Success(api.getSessionStatus(taskId).toDomain())
            } catch (e: Exception) {
                Resource.Error("Failed to get session status: ${e.message}", e)
            }
        }

    override suspend fun submitSessionData(session: GaitSession): Resource<Unit> =
        withContext(Dispatchers.Default) {
            try {
                api.postSessionData(session.toDto())
                Resource.Success(Unit)
            } catch (e: Exception) {
                Resource.Error("Failed to upload session data: ${e.message}", e)
            }
        }
}
