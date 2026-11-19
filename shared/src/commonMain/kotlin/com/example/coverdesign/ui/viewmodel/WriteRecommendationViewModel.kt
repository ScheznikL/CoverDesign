package com.example.coverdesign.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.coverdesign.domain.usecase.GetDoctorPatientsUseCase
import com.example.coverdesign.domain.usecase.SubmitRecommendationUseCase
import com.example.coverdesign.domain.util.Resource
import com.example.coverdesign.ui.viewmodel.base.BaseViewModel
import com.example.coverdesign.ui.viewmodel.base.UiEvent
import com.example.coverdesign.ui.viewmodel.base.UiState
import kotlinx.coroutines.launch

class WriteRecommendationViewModel(
    private val patientId: String,
    private val getDoctorPatients: GetDoctorPatientsUseCase,
    private val submitRecommendation: SubmitRecommendationUseCase,
) : BaseViewModel<WriteRecommendationUiState, WriteRecommendationUiEvent>() {

    override fun initialState() = WriteRecommendationUiState()

    init {
        loadPatient()
    }

    private fun loadPatient() {
        viewModelScope.launch {
            when (val result = getDoctorPatients()) {
                is Resource.Success -> result.data.firstOrNull { it.patientUid == patientId }?.let { p ->
                    setState { copy(patientName = p.name, treatmentType = p.treatmentType) }
                }
                else -> {}
            }
        }
    }

    /** [tag] is the server value: "insole_based" or "photogrammetric". */
    fun submit(text: String, tag: String) {
        if (text.isBlank() || uiState.value.sending) return
        viewModelScope.launch {
            setState { copy(sending = true, error = null) }
            when (val result = submitRecommendation(patientId, tag, text)) {
                is Resource.Success -> {
                    setState { copy(sending = false, sent = true) }
                    sendEvent(WriteRecommendationUiEvent.Sent)
                }
                is Resource.Error -> setState { copy(sending = false, error = result.message) }
                else -> {}
            }
        }
    }
}

data class WriteRecommendationUiState(
    val patientName: String = "",
    val treatmentType: String = "",
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
) : UiState

sealed class WriteRecommendationUiEvent : UiEvent {
    object Sent : WriteRecommendationUiEvent()
}
