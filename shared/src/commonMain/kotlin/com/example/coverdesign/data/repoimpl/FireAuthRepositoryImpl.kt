package com.example.coverdesign.data.repoimpl

import com.example.coverdesign.domain.model.AppUser
import com.example.coverdesign.domain.model.UserRole
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.util.Resource
import com.example.coverdesign.domain.util.currentEpochMillis
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.FirebaseUser
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

class FireAuthRepositoryImpl : FireAuthRepository {
    private val auth = Firebase.auth
    override val currentUser: Flow<FirebaseUser?> = auth.authStateChanged

    private val firestore = Firebase.firestore

    @Serializable
    private data class UserDocument(
        val uid: String = "",
        val email: String = "",
        val displayName: String = "",
        val phone: String = "",
        val role: String = UserRole.PATIENT.name,
        val createdAt: Long = 0L,
        // Default on (opt-out). Absent on older docs → true, matching the gait rules' default-consent check.
        val shareGaitWithCareTeam: Boolean = true
)

    private fun usersCollection() = firestore.collection("users")

    private fun Exception.toAuthMessage(fallback: String): String {
        val raw = message ?: return fallback
        println("Registration check $raw")
        return when {
            raw.contains("NETWORK_ERROR", ignoreCase = true)
                    || raw.contains("network error", ignoreCase = true)
                    || raw.contains("Unable to resolve host", ignoreCase = true)
                    || raw.contains("Failed to connect", ignoreCase = true) ->
                "Network error: $raw" // Changed to show the actual error for debugging

            raw.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true)
                    || raw.contains("INVALID_PASSWORD", ignoreCase = true)
                    || raw.contains("WRONG_PASSWORD", ignoreCase = true) ->
                "Incorrect email or password."

            raw.contains("EMAIL_NOT_FOUND", ignoreCase = true)
                    || raw.contains("USER_NOT_FOUND", ignoreCase = true) ->
                "No account found with this email."

            raw.contains("USER_DISABLED", ignoreCase = true) ->
                "This account has been disabled. Contact support."

            raw.contains("TOO_MANY_REQUESTS", ignoreCase = true) ->
                "Too many failed attempts. Please wait a moment and try again."

            raw.contains("EMAIL_EXISTS", ignoreCase = true)
                    || raw.contains("email address is already in use", ignoreCase = true) ->
                "An account with this email already exists."

            raw.contains("WEAK_PASSWORD", ignoreCase = true) ->
                "Password is too weak. Use at least 6 characters."

            else -> "Auth error: $raw"
        }
    }

    override suspend fun createUserProfile(user: AppUser): Resource<Unit> = try {
        val doc = UserDocument(
            uid = user.uid,
            email = user.email,
            displayName = user.displayName,
            phone = user.phone,
            role = user.role.name,
            createdAt = user.createdAt,
            shareGaitWithCareTeam = user.shareGaitWithCareTeam
        )
        usersCollection().document(user.uid).set(doc)
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(e.toAuthMessage("Failed to create user profile"), e)
    }

    override suspend fun getUserProfile(uid: String): Resource<AppUser> = try {
        val snapshot = usersCollection().document(uid).get()

        if (!snapshot.exists) {
            return Resource.Error("profile_not_found")
        }

        val doc = snapshot.data<UserDocument>()
        Resource.Success(
            AppUser(
                uid = doc.uid,
                email = doc.email,
                displayName = doc.displayName,
                phone = doc.phone,
                role = runCatching { UserRole.valueOf(doc.role) }.getOrDefault(UserRole.PATIENT),
                createdAt = doc.createdAt,
                shareGaitWithCareTeam = doc.shareGaitWithCareTeam
            )
        )
    } catch (e: Exception) {
        Resource.Error(e.toAuthMessage("Failed to load user profile"), e)
    }

    override suspend fun setGaitSharingConsent(enabled: Boolean): Resource<Unit> = try {
        val uid = auth.currentUser?.uid ?: return Resource.Error("Not signed in.")
        val ref = usersCollection().document(uid)
        val snapshot = ref.get()
        if (!snapshot.exists) return Resource.Error("profile_not_found")
        // Read-modify-write the whole profile doc (GitLive commonMain uses typed set()).
        ref.set(snapshot.data<UserDocument>().copy(shareGaitWithCareTeam = enabled))
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(e.toAuthMessage("Failed to update sharing setting"), e)
    }

    override fun getCurrentUserId(): String? = auth.currentUser?.uid

    override suspend fun signInWithEmail(
        email: String, password: String, role: UserRole?
    ): Resource<FirebaseUser> = try {
        val result = auth.signInWithEmailAndPassword(email, password)
        val user = result.user ?: return Resource.Error("Sign-in returned no user")

        if (role != null) {
            when (val profileResult = getUserProfile(user.uid)) {
                is Resource.Success -> if (profileResult.data.role != role) {
                    auth.signOut()
                    val actual = profileResult.data.role.name
                        .lowercase().replaceFirstChar { it.uppercase() }
                    return Resource.Error(
                        "This account is registered as a $actual. Please go back and select the correct role."
                    )
                }

                is Resource.Error -> {
                    auth.signOut()
                    return Resource.Error(
                        if (profileResult.message == "profile_not_found")
                            "Could not find your account profile. Please contact support."
                        else
                            profileResult.message ?: "Could not verify your account role."
                    )
                }

                else -> {}
            }
        } else {
            auth.signOut()
            return Resource.Error(
                "Could not verify your account role."
            )
        }

        Resource.Success(user)
    } catch (e: Exception) {
        Resource.Error(e.toAuthMessage("Sign-in failed"), e)
    }

    override suspend fun registerWithEmail(
        email: String, password: String, role: UserRole?
    ): Resource<FirebaseUser> = try {
        val result = auth.createUserWithEmailAndPassword(email, password)
        val user = result.user ?: return Resource.Error("Registration returned no user")
        
        // Note: Profile creation is handled in ViewModel to ensure all data (name/phone) is available
        Resource.Success(user)
    } catch (e: Exception) {
        Resource.Error(e.toAuthMessage("Registration failed"), e)
    }

    override suspend fun signInWithGoogle(
        idToken: String,
        role: UserRole?
    ): Resource<FirebaseUser> = try {
        println("[DEBUG] inside signInWithGoogle")
        val credential = GoogleAuthProvider.credential(idToken, accessToken = null)
        val result = auth.signInWithCredential(credential)
        val user = result.user ?: return Resource.Error("Google sign-in returned no user")

        if (result.additionalUserInfo?.isNewUser == true) {
            println("[DEBUG] inside signInWithGoogle - new user $user")
            createUserProfile(
                AppUser(
                    uid = user.uid,
                    email = user.email ?: "",
                    displayName = user.displayName ?: "",
                    role = role ?: UserRole.PATIENT,
                    createdAt = currentEpochMillis()
                )
            )
        } else if (role != null) {
            println("[DEBUG] inside signInWithGoogle - not new user $user")
            when (val profileResult = getUserProfile(user.uid)) {
                is Resource.Success -> if (profileResult.data.role != role) {
                    auth.signOut()
                    val actual = profileResult.data.role.name
                        .lowercase().replaceFirstChar { it.uppercase() }
                    return Resource.Error(
                        "This account is registered as a $actual. Please go back and select the correct role."
                    )
                }

                is Resource.Error -> {
                    auth.signOut()
                    return Resource.Error(
                        if (profileResult.message == "profile_not_found")
                            "Could not find your account profile. Please contact support."
                        else
                            profileResult.message ?: "Could not verify your account role."
                    )
                }

                else -> {}
            }
        }

        Resource.Success(user)
    } catch (e: Exception) {
        Resource.Error(e.toAuthMessage("Google sign-in failed"), e)
    }

    override suspend fun signOut(): Resource<Unit> = try {
        auth.signOut()
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(e.toAuthMessage("Sign-out failed"), e)
    }

    override suspend fun sendPasswordReset(email: String): Resource<Unit> = try {
        auth.sendPasswordResetEmail(email)
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(e.toAuthMessage("Password reset failed"), e)
    }

    override fun isAuthenticated(): Flow<Boolean> =
        auth.authStateChanged.map { it != null }
}
