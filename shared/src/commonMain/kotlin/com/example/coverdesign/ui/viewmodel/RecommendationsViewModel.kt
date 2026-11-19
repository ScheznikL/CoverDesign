package com.example.coverdesign.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.coverdesign.domain.model.DoctorRecommendation
import com.example.coverdesign.domain.model.GaitSession
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.usecase.GetRehabDataUseCase
import com.example.coverdesign.domain.util.Resource
import com.example.coverdesign.ui.viewmodel.base.BaseViewModel
import com.example.coverdesign.ui.viewmodel.base.UiEvent
import com.example.coverdesign.ui.viewmodel.base.UiState
import kotlinx.coroutines.launch

class RecommendationsViewModel(
    private val getRehabData: GetRehabDataUseCase,
    private val authRepo: FireAuthRepository
) : BaseViewModel<RecommendationsUiState, RecommendationsUiEvent>() {

    override fun initialState() = RecommendationsUiState()

    init {
        loadRehabData()
        viewModelScope.launch {
            val uid = authRepo.getCurrentUserId() ?: return@launch
            when (val result = authRepo.getUserProfile(uid)) {
                is Resource.Success -> {
                    val name = result.data.displayName.ifBlank { "Patient" }
                    setState { copy(patientName = name) }
                }
                else -> {}
            }
        }
    }

    private fun loadRehabData() {
        viewModelScope.launch {
            setState { copy(isLoading = true) }
            when (val result = getRehabData()) {
                is Resource.Success -> setState {
                    copy(
                        latestSession = result.data.sessions.getOrNull(0),
                        previousSession = result.data.sessions.getOrNull(1),
                        recommendations = result.data.recommendations,
                        isLoading = false
                    )
                }
                is Resource.Error -> setState {
                    copy(
                        latestSession = null,
                        previousSession = null,
                        recommendations = emptyList(),
                        isLoading = false
                    )
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun onAction(action: RecommendationsAction) {
        when (action) {
            RecommendationsAction.Back -> sendEvent(RecommendationsUiEvent.NavigateBack)
        }
    }
}

data class RecommendationsUiState(
    val latestSession: GaitSession? = null,
    val previousSession: GaitSession? = null,
    val patientName: String = "Patient",
    val recommendations: List<DoctorRecommendation> = emptyList(),
    val isLoading: Boolean = false
) : UiState

sealed class RecommendationsUiEvent : UiEvent {
    object NavigateBack : RecommendationsUiEvent()
}

sealed class RecommendationsAction {
    object Back : RecommendationsAction()
}
