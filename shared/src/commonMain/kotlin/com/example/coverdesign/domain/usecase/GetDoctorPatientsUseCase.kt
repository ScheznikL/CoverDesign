package com.example.coverdesign.domain.usecase

import com.example.coverdesign.domain.model.CarePatient
import com.example.coverdesign.domain.repo.CareRepository
import com.example.coverdesign.domain.util.Resource

class GetDoctorPatientsUseCase(private val repo: CareRepository) {
    suspend operator fun invoke(): Resource<List<CarePatient>> = repo.getDoctorPatients()
}
