package com.example.coverdesign.domain.usecase

import com.example.coverdesign.domain.repo.RehabRepository
import com.example.coverdesign.domain.util.Resource

class SubmitRehabSessionUseCase(private val repo: RehabRepository) {
    suspend operator fun invoke(
        videoPath: String,
        durationSeconds: Int,
        measurementMethod: String? = null,
        acquisitionSource: String? = null,
        views: List<String> = emptyList()
    ): Resource<String> =
        repo.submitSession(videoPath, durationSeconds, measurementMethod, acquisitionSource, views)
}
