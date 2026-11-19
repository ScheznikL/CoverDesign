package com.example.coverdesign.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.usecase.GetDoctorPatientsUseCase
import com.example.coverdesign.domain.util.Resource
import com.example.coverdesign.ui.viewmodel.base.BaseViewModel
import com.example.coverdesign.ui.viewmodel.base.UiEvent
import com.example.coverdesign.ui.viewmodel.base.UiState
import kotlinx.coroutines.launch

enum class PatientFilter { All, Active, Stable, Pending }

data class ClinicianPatientsUiState(
    val doctorName: String = "",
    val patients: List<PatientSummary> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: PatientFilter = PatientFilter.All,
    val isLoading: Boolean = false,
) : UiState {
    /** Patients narrowed by the active status filter and search query. */
    val filteredPatients: List<PatientSummary>
        get() = patients.filter { patient ->
            val matchesFilter = when (selectedFilter) {
                PatientFilter.All -> true
                PatientFilter.Active -> patient.status == PatientStatus.Active
                PatientFilter.Stable -> patient.status == PatientStatus.Stable
                PatientFilter.Pending -> patient.status == PatientStatus.Pending
            }
            val query = searchQuery.trim()
            val matchesQuery = query.isEmpty() ||
                patient.name.contains(query, ignoreCase = true) ||
                patient.treatmentType.contains(query, ignoreCase = true)
            matchesFilter && matchesQuery
        }

    fun count(filter: PatientFilter): Int = when (filter) {
        PatientFilter.All -> patients.size
        PatientFilter.Active -> patients.count { it.status == PatientStatus.Active }
        PatientFilter.Stable -> patients.count { it.status == PatientStatus.Stable }
        PatientFilter.Pending -> patients.count { it.status == PatientStatus.Pending }
    }
}

sealed class ClinicianPatientsUiEvent : UiEvent

class ClinicianPatientsViewModel(
    private val authRepo: FireAuthRepository,
    private val getDoctorPatients: GetDoctorPatientsUseCase,
) : BaseViewModel<ClinicianPatientsUiState, ClinicianPatientsUiEvent>() {

    override fun initialState() = ClinicianPatientsUiState()

    init {
        loadDoctorInfo()
        loadPatients()
    }

    fun onSearchChange(query: String) = setState { copy(searchQuery = query) }

    fun onFilterChange(filter: PatientFilter) = setState { copy(selectedFilter = filter) }

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
                is Resource.Success ->
                    setState { copy(patients = result.data.map { it.toSummary() }, isLoading = false) }

                else -> setState { copy(isLoading = false) }
            }
        }
    }
}
