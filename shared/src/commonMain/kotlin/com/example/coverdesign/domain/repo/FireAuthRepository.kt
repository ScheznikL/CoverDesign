package com.example.coverdesign.domain.repo


import com.example.coverdesign.domain.model.AppUser
import com.example.coverdesign.domain.model.UserRole
import com.example.coverdesign.domain.util.Resource
import dev.gitlive.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// AuthRepository — commonMain interface
// Implementations live in commonMain (GitLive SDK handles platform differences)
// except for Google Sign-In which needs expect/actual for Credential Manager.
// ─────────────────────────────────────────────────────────────────────────────

interface FireAuthRepository {

    /** Currently signed-in user, or null. Emits on auth state changes. */
    val currentUser: Flow<FirebaseUser?>

    suspend fun getUserProfile(uid: String): Resource<AppUser>
    suspend fun createUserProfile(user: AppUser): Resource<Unit>

    /** Updates the signed-in patient's gait-sharing consent flag on their `users/{uid}` profile. */
    suspend fun setGaitSharingConsent(enabled: Boolean): Resource<Unit>


    /** Returns the current user's uid, or null if not signed in. */
    fun getCurrentUserId(): String?

    // ── Email / password ──────────────────────────────────────────────────────

    suspend fun signInWithEmail(
        email    : String,
        password : String,
        role     : UserRole?
    ): Resource<FirebaseUser>

    suspend fun registerWithEmail(
        email    : String,
        password : String,
        role: UserRole?
    ): Resource<FirebaseUser>

    // ── Google Sign-In ────────────────────────────────────────────────────────
    // idToken is acquired platform-side (Credential Manager on Android,
    // Google SDK on iOS) then passed here for Firebase authentication.

    suspend fun signInWithGoogle(idToken: String, role: UserRole?): Resource<FirebaseUser>

    // ── Session ───────────────────────────────────────────────────────────────

    suspend fun signOut(): Resource<Unit>

    suspend fun sendPasswordReset(email: String): Resource<Unit>
    fun isAuthenticated() :Flow <Boolean>
}