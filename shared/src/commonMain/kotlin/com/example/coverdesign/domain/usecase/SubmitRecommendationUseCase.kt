package com.example.coverdesign.domain.usecase

import com.example.coverdesign.domain.repo.CareRepository
import com.example.coverdesign.domain.util.Resource

class SubmitRecommendationUseCase(private val repo: CareRepository) {
    suspend operator fun invoke(patientUid: String, tag: String, text: String): Resource<Unit> =
        repo.submitRecommendation(patientUid, tag, text)
}
