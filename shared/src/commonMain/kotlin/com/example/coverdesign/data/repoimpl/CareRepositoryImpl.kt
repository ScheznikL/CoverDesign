package com.example.coverdesign.data.repoimpl

import com.example.coverdesign.data.dto.toDomain
import com.example.coverdesign.data.remote.firestore.CareCloudDataSource
import com.example.coverdesign.data.remote.service.CareApiService
import com.example.coverdesign.domain.model.CarePatient
import com.example.coverdesign.domain.repo.CareRepository
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CareRepositoryImpl(
    private val api: CareApiService,
    private val cloud: CareCloudDataSource,
    private val authRepo: FireAuthRepository,
) : CareRepository {

    override suspend fun getDoctorPatients(): Resource<List<CarePatient>> =
        withContext(Dispatchers.Default) {
            // Primary path: the server roster API.
            val fromApi = runCatching { api.getPatients().map { it.toDomain() } }.getOrNull()
            if (!fromApi.isNullOrEmpty()) return@withContext Resource.Success(fromApi)

            // Fallback: read care_links directly from Firestore (API errored or returned nothing).
            val uid = authRepo.getCurrentUserId()
                ?: return@withContext Resource.Error("Not signed in.")
            try {
                Resource.Success(cloud.getPatientsForDoctor(uid))
            } catch (e: Exception) {
                Resource.Error("Failed to load patients: ${e.message}", e)
            }
        }

    override suspend fun submitRecommendation(
        patientUid: String, tag: String, text: String
    ): Resource<Unit> = withContext(Dispatchers.Default) {
        try {
            api.postRecommendation(patientUid, tag, text)
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error("Failed to send recommendation: ${e.message}", e)
        }
    }
}
