package com.example.coverdesign.data.remote

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

// ─────────────────────────────────────────────────────────────────────────────
// GoogleAuthenticator — androidMain
//
// Uses the modern Credential Manager API (not the deprecated play-services-auth).
// Requires:
//   androidMain deps:
//     implementation("androidx.credentials:credentials:1.3.0")
//     implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
//     implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
//
// WEB_CLIENT_ID: the OAuth 2.0 Web Client ID from Firebase Console →
//   Authentication → Sign-in method → Google → Web SDK configuration.
//   It looks like "123456789-abc.apps.googleusercontent.com".
//   Store it in local.properties or BuildConfig, not hardcoded.
// ─────────────────────────────────────────────────────────────────────────────

actual class GoogleAuthenticator(
    private val webClientId  : String   // injected via Koin
) {
    actual suspend fun getIdToken(context: Any): String {
        check(webClientId.isNotBlank()) {
            "Google sign-in is not configured for this Firebase project. Use email and password."
        }
        context as Context
        val credentialManager = CredentialManager.create(context)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)   // show all accounts, not just previously used
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)             // always show account picker
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(
            request = request,
            context = context
        )

        val credential = result.credential
        if (
            credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential
                .createFrom(credential.data)
                .idToken
        }

        throw IllegalStateException("Unexpected credential type: ${credential.type}")
    }
}
