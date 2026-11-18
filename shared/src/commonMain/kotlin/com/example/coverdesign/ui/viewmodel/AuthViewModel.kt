package com.example.coverdesign.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.coverdesign.data.remote.GoogleAuthenticator
import com.example.coverdesign.domain.model.AppUser
import com.example.coverdesign.domain.model.UserRole
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.usecase.LogoutUseCase
import com.example.coverdesign.domain.util.Resource
import com.example.coverdesign.domain.util.currentEpochMillis
import com.example.coverdesign.ui.viewmodel.base.BaseViewModel
import com.example.coverdesign.ui.viewmodel.base.UiEvent
import com.example.coverdesign.ui.viewmodel.base.UiState
import dev.gitlive.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch


class AuthViewModel(
    private val authRepo: FireAuthRepository,
    private val googleAuthenticator: GoogleAuthenticator,
    private val logout: LogoutUseCase,
) : BaseViewModel<AuthUiState, AuthUiEvent>() {

    override fun initialState() = AuthUiState()

    init {
        // Sync Firebase auth state into UI state only — navigation is driven explicitly
        // by each sign-in/register handler so we never navigate on transient auth changes
        // (e.g. the brief Firebase sign-in that precedes a role-mismatch sign-out).
        viewModelScope.launch {
            authRepo.currentUser.collect { user ->
                setState { copy(currentUser = user) }
                if (user != null) {
                    when (val result = authRepo.getUserProfile(user.uid)) {
                        is Resource.Success -> setState { copy(appUser = result.data) }
                        else -> {}
                    }
                } else {
                    setState { copy(appUser = null) }
                }
            }
        }
    }

    fun onAction(action: AuthAction) {
        when (action) {
            is AuthAction.SignInWithEmail -> signInWithEmail(
                action.email,
                action.password,
                action.role
            )

            is AuthAction.RegisterWithEmail -> {
                val role = action.role
                if (role == null) {
                    setState { copy(error = "Please select a role before registering.") }
                } else {
                    registerWithEmail(action.email, action.password, action.fullName, action.phone, role)
                }
            }

            is AuthAction.SignInWithGoogle -> signInWithGoogle(action.context, action.role)
            is AuthAction.SignOut -> signOut()
            is AuthAction.ResetPassword -> resetPassword(action.email)
            is AuthAction.ClearError -> setState { copy(error = null) }
            is AuthAction.ToggleMode -> setState { copy(isRegisterMode = !isRegisterMode) }
            is AuthAction.PickRole -> setState { copy(role = action.role) }
        }
    }

    // ── Email / password ──────────────────────────────────────────────────────

    private fun signInWithEmail(email: String, password: String, role: UserRole?) {
        if (!validateEmailPassword(email, password)) return
        viewModelScope.launch {
            setState { copy(isLoading = true, error = null) }
            when (val result = authRepo.signInWithEmail(email, password, role)) {
                is Resource.Success -> {
                    setState { copy(isLoading = false) }
                    sendEvent(AuthUiEvent.NavigateToHome)
                }
                is Resource.Error -> setState { copy(isLoading = false, error = result.message) }
                else -> {}
            }
        }
    }

    private fun registerWithEmail(
        email: String, password: String, fullName: String, phone: String, role: UserRole
    ) {
        if (!validateEmailPassword(email, password)) return
        viewModelScope.launch {
            setState { copy(isLoading = true, error = null) }
            when (val result = authRepo.registerWithEmail(email, password, role)) {
                is Resource.Success -> {
                    val user = result.data
                    authRepo.createUserProfile(
                        AppUser(
                            uid = user.uid,
                            email = email,
                            displayName = fullName,
                            phone = phone,
                            role = role,
                            createdAt = currentEpochMillis()
                        )
                    )
                    setState { copy(isLoading = false) }
                    sendEvent(AuthUiEvent.NavigateToHome)
                }

                is Resource.Error -> setState {
                    copy(
                        isLoading = false, error = result.message
                    )
                }

                else -> {}
            }
        }
    }

    // ── Google Sign-In ────────────────────────────────────────────────────────
    // Step 1: GoogleAuthenticator.getIdToken() — platform-side (Credential Manager)
    // Step 2: authRepo.signInWithGoogle(idToken) — Firebase via GitLive

    private fun signInWithGoogle(context: Any, role: UserRole?) {
        if (role == null) {
            setState { copy(error = "Please select a role before continuing with Google.") }
            return
        }
        viewModelScope.launch {
            setState { copy(isLoading = true, error = null) }
            try {
                val idToken = googleAuthenticator.getIdToken(context)
                when (val result = authRepo.signInWithGoogle(idToken, role)) {
                    is Resource.Success -> {
                        setState { copy(isLoading = false) }
                        sendEvent(AuthUiEvent.NavigateToHome)
                    }
                    is Resource.Error -> setState { copy(isLoading = false, error = result.message) }
                    else -> {}
                }
            } catch (e: Exception) {
                setState { copy(isLoading = false, error = e.message ?: "Google sign-in failed") }
            }
        }
    }

    private fun signOut() {
        viewModelScope.launch {
            logout()
            setState { copy(currentUser = null) }
            sendEvent(AuthUiEvent.NavigateToLogin)
        }
    }

    private fun resetPassword(email: String) {
        if (email.isBlank()) {
            setState { copy(error = "Enter your email address to reset your password") }
            return
        }
        viewModelScope.launch {
            setState { copy(isLoading = true, error = null) }
            when (val result = authRepo.sendPasswordReset(email)) {
                is Resource.Success -> setState {
                    copy(isLoading = false, resetEmailSent = true)
                }

                is Resource.Error -> setState {
                    copy(isLoading = false, error = result.message)
                }

                else -> {}
            }
        }
    }

    private fun validateEmailPassword(email: String, password: String): Boolean {
        return when {
            email.isBlank() -> {
                setState { copy(error = "Email cannot be empty") }; false
            }

            !EMAIL_REGEX.matches(email) -> {
                setState { copy(error = "Enter a valid email address") }; false
            }

            password.length < 6 -> {
                setState { copy(error = "Password must be at least 6 characters") }; false
            }

            else -> true
        }
    }

    companion object {
        private val EMAIL_REGEX =
            Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$")
    }


}


// ── State ─────────────────────────────────────────────────────────────────────

data class AuthUiState(
    val currentUser: FirebaseUser? = null,
    val appUser: AppUser? = null,
    val isLoading: Boolean = false,
    val isRegisterMode: Boolean = false,
    val error: String? = null,
    val resetEmailSent: Boolean = false,
    val role: UserRole? = null,
) : UiState

// ── Events ────────────────────────────────────────────────────────────────────

sealed class AuthUiEvent : UiEvent {
    object NavigateToHome : AuthUiEvent()
    object NavigateToLogin : AuthUiEvent()
}

sealed class AuthAction {
    data class SignInWithEmail(val email: String, val password: String, val role: UserRole?) :
        AuthAction()

    data class PickRole(val role: UserRole) : AuthAction()
    data class RegisterWithEmail(
        val email: String,
        val password: String,
        val fullName: String,
        val phone: String,
        val role: UserRole?
    ) : AuthAction()

    data class SignInWithGoogle(val context: Any, val role: UserRole?) : AuthAction()
    object SignOut : AuthAction()
    data class ResetPassword(val email: String) : AuthAction()
    object ClearError : AuthAction()
    object ToggleMode : AuthAction()
}