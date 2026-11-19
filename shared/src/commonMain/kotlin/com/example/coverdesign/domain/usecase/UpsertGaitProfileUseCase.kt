package com.example.coverdesign.domain.usecase

import com.example.coverdesign.domain.model.GaitProfileInput
import com.example.coverdesign.domain.repo.RehabRepository
import com.example.coverdesign.domain.util.Resource

class UpsertGaitProfileUseCase(private val repo: RehabRepository) {
    suspend operator fun invoke(profile: GaitProfileInput): Resource<Unit> =
        repo.upsertGaitProfile(profile)
}
