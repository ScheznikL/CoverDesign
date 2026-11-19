package com.example.coverdesign.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.coverdesign.domain.model.RehabSessionStatus
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.usecase.PollRehabSessionUseCase
import com.example.coverdesign.domain.usecase.SubmitRehabSessionUseCase
import com.example.coverdesign.domain.util.Resource
import com.example.coverdesign.ui.viewmodel.base.BaseViewModel
import com.example.coverdesign.ui.viewmodel.base.UiEvent
import com.example.coverdesign.ui.viewmodel.base.UiState
import kotlinx.coroutines.launch

/**
 * Drives the server side of the SCAN flow: once a recorded video has been uploaded to
 * Firebase Storage, [submitVideo] notifies the backend with the storage path, then polls
 * until the gait session is processed (or fails / times out).
 */
class SensorCameraViewModel(
    private val submitRehabSession: SubmitRehabSessionUseCase,
    private val pollRehabSession: PollRehabSessionUseCase,
    private val authRepo: FireAuthRepository
) : BaseViewModel<SensorCameraUiState, SensorCameraUiEvent>() {

    override fun initialState() = SensorCameraUiState()

    /**
     * @param videoPath Firebase Storage path, e.g. "sessions/<uid>/pose_<ts>.mp4".
     * @param measurementMethod "MARKERS" | "POSE"; the SCAN flow is MediaPipe pose.
     * @param acquisitionSource "LIVE" | "VIDEO".
     * @param views subset of "FRONT","BACK" captured, empty when the flow does not distinguish them.
     */
    fun submitVideo(
        videoPath: String,
        durationSeconds: Int,
        measurementMethod: String? = null,
        acquisitionSource: String? = null,
        views: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            setState { copy(status = SubmitStatus.Submitting, errorMessage = null) }

            val taskId = when (val submit = submitRehabSession(
                videoPath, durationSeconds, measurementMethod, acquisitionSource, views
            )) {
                is Resource.Success -> submit.data
                is Resource.Error -> {
                    setState { copy(status = SubmitStatus.Error, errorMessage = submit.message) }
                    return@launch
                }
                is Resource.Loading -> return@launch
            }

            setState { copy(status = SubmitStatus.Processing) }
            pollRehabSession(taskId).collect { result ->
                when (result) {
                    is Resource.Success -> when (result.data.status) {
                        RehabSessionStatus.READY -> {
                            setState { copy(status = SubmitStatus.Ready) }
                            sendEvent(SensorCameraUiEvent.SessionReady)
                        }
                        RehabSessionStatus.FAILED -> setState {
                            copy(status = SubmitStatus.Error, errorMessage = "Processing failed")
                        }
                        RehabSessionStatus.PROCESSING -> { /* keep waiting */ }
                    }
                    is Resource.Error -> setState {
                        copy(status = SubmitStatus.Error, errorMessage = result.message)
                    }
                    is Resource.Loading -> {}
                }
            }
        }
    }

    fun currentUserId(): String? = authRepo.getCurrentUserId()

    fun reset() = setState { SensorCameraUiState() }
}

enum class SubmitStatus { Idle, Submitting, Processing, Ready, Error }

data class SensorCameraUiState(
    val status: SubmitStatus = SubmitStatus.Idle,
    val errorMessage: String? = null
) : UiState

sealed class SensorCameraUiEvent : UiEvent {
    object SessionReady : SensorCameraUiEvent()
}
