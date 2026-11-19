package com.example.coverdesign.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.usecase.GetDoctorPatientsUseCase
import com.example.coverdesign.domain.util.Resource
import com.example.coverdesign.ui.viewmodel.base.BaseViewModel
import com.example.coverdesign.ui.viewmodel.base.UiEvent
import com.example.coverdesign.ui.viewmodel.base.UiState
import kotlinx.coroutines.launch

data class PatientSummary(
    val id: String,
    val name: String,
    val lastVisitDate: String,
    val treatmentType: String,
    val status: PatientStatus,
    val asymmetryCoeff: Float? = null,
    val kyphosisCoeff: Float? = null,
    val latestUpdate: String? = null,
)

enum class PatientStatus { Active, Stable, Pending }

data class ClinicianHomeUiState(
    val doctorName: String = "",
    val notificationCount: Int = 0,
    val notifications: List<String> = emptyList(),
    val recentPatients: List<PatientSummary> = emptyList(),
    val allPatients: List<PatientSummary> = emptyList(),
    val isLoading: Boolean = false,
) : UiState

sealed class ClinicianHomeUiEvent : UiEvent

class ClinicianHomeViewModel(
    private val authRepo: FireAuthRepository,
    private val getDoctorPatients: GetDoctorPatientsUseCase,
) : BaseViewModel<ClinicianHomeUiState, ClinicianHomeUiEvent>() {

    override fun initialState() = ClinicianHomeUiState()

    init {
        loadDoctorInfo()
        loadPatients()
    }

    private fun loadDoctorInfo() {
        viewModelScope.launch {
            val uid = authRepo.getCurrentUserId() ?: return@launch
            when (val result = authRepo.getUserProfile(uid)) {
                is Resource.Success -> setState { copy(doctorName = result.data.displayName) }
                else -> {}
            }
        }
    }

    private fun loadPatients() {
        viewModelScope.launch {
            setState { copy(isLoading = true) }
            when (val result = getDoctorPatients()) {
                is Resource.Success -> {
                    val summaries = result.data.map { it.toSummary() }
                    setState {
                        copy(
                            allPatients = summaries,
                            recentPatients = summaries.take(RECENT_PATIENT_COUNT),
                            isLoading = false,
                        )
                    }
                }

                else -> setState { copy(isLoading = false) }
            }
        }
    }

    private companion object {
        const val RECENT_PATIENT_COUNT = 5
    }
}
