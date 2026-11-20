package com.example.coverdesign.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.coverdesign.data.remote.client.ApiClient
import com.example.coverdesign.domain.model.GaitSession
import com.example.coverdesign.domain.model.OrderListItem
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.usecase.GetOrderListUseCase
import com.example.coverdesign.domain.usecase.GetRehabDataUseCase
import com.example.coverdesign.domain.usecase.LogoutUseCase
import com.example.coverdesign.domain.usecase.SyncOrdersUseCase
import com.example.coverdesign.domain.util.Resource
import com.example.coverdesign.domain.util.currentEpochMillis
import com.example.coverdesign.ui.viewmodel.HomeViewModel.Companion.NEW_USER_WINDOW_MS
import com.example.coverdesign.ui.viewmodel.base.BaseViewModel
import com.example.coverdesign.ui.viewmodel.base.UiEvent
import com.example.coverdesign.ui.viewmodel.base.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class HomeViewModel(
    private val getOrderList : GetOrderListUseCase,
    private val getRehabData : GetRehabDataUseCase,
    private val authRepo     : FireAuthRepository,
    private val logout       : LogoutUseCase,
    private val syncOrders   : SyncOrdersUseCase,
    private val apiClient: ApiClient,
) : BaseViewModel<HomeUiState, HomeUiEvent>() {

    fun checkBackend(scope: CoroutineScope) {
        scope.launch {
            apiClient.testConnection()
        }
    }

    override fun initialState() = HomeUiState()

    // Internal signals for the "new user" welcome coach-mark. Kept out of UiState so the
    // UI layer never has to combine them — it only ever reads the derived [isNewUser] flag.
    private var accountCreatedAt: Long? = null
    private var welcomeDismissed = false

    init {
        checkBackend(viewModelScope) //TODO temp
        loadRecentOrders()
        refreshOrders()
        loadUserState()
        loadRehabData()
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.NewOrder          -> sendEvent(HomeUiEvent.NavigateToNewOrder)
            is HomeAction.Open3D            -> sendEvent(HomeUiEvent.NavigateTo3D)
            is HomeAction.OpenOrder         -> sendEvent(HomeUiEvent.NavigateToOrder(action.orderId))
            is HomeAction.SeeAllOrders      -> sendEvent(HomeUiEvent.NavigateToAllOrders)
            is HomeAction.OpenRehabHistory      -> sendEvent(HomeUiEvent.NavigateToRehabHistory)
            is HomeAction.OpenRecommendations   -> sendEvent(HomeUiEvent.NavigateToRecommendations)
            is HomeAction.OrderInsoles          -> sendEvent(HomeUiEvent.NavigateToInsoleOrder)
            is HomeAction.StartRehabScan        -> sendEvent(HomeUiEvent.NavigateToSensorCamera)
            is HomeAction.DismissWelcome        -> dismissWelcome()
            is HomeAction.Logout            -> handleLogout()
            is HomeAction.RetryOrderSync        -> refreshOrders()
        }
    }

    /** Reloads orders from Firestore on open; failure surfaces via [HomeUiState.ordersSyncStatus]. */
    private fun refreshOrders() {
        viewModelScope.launch {
            setState { copy(ordersSyncStatus = OrderSyncStatus.SYNCING) }
            val result = syncOrders()
            setState {
                copy(
                    ordersSyncStatus = if (result is Resource.Error) OrderSyncStatus.FAILED
                    else OrderSyncStatus.IDLE
                )
            }
        }
    }

    private fun loadRecentOrders() {
        viewModelScope.launch {
            getOrderList().collect { items ->
                val recent = items.take(3)
                setState { copy(recentOrders = recent, isNewUser = computeIsNewUser(recent.isEmpty())) }
            }
        }
    }

    private fun loadRehabData() {
        viewModelScope.launch {
            setState { copy(isLoadingRehab = true) }
            when (val result = getRehabData()) {
                is Resource.Success -> setState {
                    copy(rehabSessions = result.data.sessions, isLoadingRehab = false)
                }
                // On error, fall back to an empty card (shows the "Make a record" CTA).
                is Resource.Error -> setState {
                    copy(rehabSessions = emptyList(), isLoadingRehab = false)
                }
                is Resource.Loading -> {}
            }
        }
    }

    private fun loadUserState() {
        viewModelScope.launch {
            val uid = authRepo.getCurrentUserId() ?: return@launch
            when (val result = authRepo.getUserProfile(uid)) {
                is Resource.Success -> {
                    accountCreatedAt = result.data.createdAt
                    setState {
                        copy(
                            hasInsoles = result.data.hasInsoles,
                            isNewUser = computeIsNewUser(recentOrders.isEmpty())
                        )
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * A user is "new" when their account was created within the last [NEW_USER_WINDOW_MS]
     * and no orders have been fetched for them yet. Called from every load path so whichever
     * of the two async sources (profile / orders) resolves last recomputes the flag.
     */
    private fun computeIsNewUser(ordersEmpty: Boolean): Boolean {
        if (welcomeDismissed) return false
        val createdAt = accountCreatedAt ?: return false
        val age = currentEpochMillis() - createdAt
        return age in 0..NEW_USER_WINDOW_MS && ordersEmpty
    }

    private fun dismissWelcome() {
        welcomeDismissed = true
        setState { copy(isNewUser = false) }
    }

    private fun handleLogout() {
        viewModelScope.launch { logout() }
    }

    companion object {
        /** Window after registration during which the welcome coach-mark may appear: 2 hours. */
        private const val NEW_USER_WINDOW_MS = 2L * 60 * 60 * 1000
    }
}

// ── State ─────────────────────────────────────────────────────────────────────
data class HomeUiState(
    val recentOrders  : List<OrderListItem> = emptyList(),
    val rehabSessions : List<GaitSession> = emptyList(),
    val hasInsoles    : Boolean           = false,
    val isNewUser     : Boolean           = false,
    val isLoading     : Boolean           = false,
    val isLoadingRehab: Boolean           = false,
    val ordersSyncStatus: OrderSyncStatus = OrderSyncStatus.IDLE
) : UiState

// ── Events ────────────────────────────────────────────────────────────────────
sealed class HomeUiEvent : UiEvent {
    object NavigateToNewOrder      : HomeUiEvent()
    object NavigateTo3D            : HomeUiEvent()
    object NavigateToAllOrders     : HomeUiEvent()
    object NavigateToRehabHistory       : HomeUiEvent()
    object NavigateToRecommendations    : HomeUiEvent()
    object NavigateToInsoleOrder        : HomeUiEvent()
    object NavigateToSensorCamera       : HomeUiEvent()
    data class NavigateToOrder(val orderId: String) : HomeUiEvent()
}

// ── Actions ───────────────────────────────────────────────────────────────────
sealed class HomeAction {
    object NewOrder          : HomeAction()
    object Open3D            : HomeAction()
    object SeeAllOrders      : HomeAction()
    object OpenRehabHistory      : HomeAction()
    object OpenRecommendations   : HomeAction()
    object OrderInsoles          : HomeAction()
    object StartRehabScan        : HomeAction()
    object DismissWelcome        : HomeAction()
    object Logout            : HomeAction()
    object RetryOrderSync        : HomeAction()
    data class OpenOrder(val orderId: String) : HomeAction()
}
