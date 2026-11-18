package com.example.coverdesign.ui.screens.patient.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.gait.GaitStore
import com.google.firebase.auth.FirebaseAuth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/** Account changes clear the previous patient's view immediately; file work stays off the UI thread. */
@Composable
internal fun rememberGaitHomeState(): GaitHomeUiState {
    val context = LocalContext.current.applicationContext
    val auth = remember { FirebaseAuth.getInstance() }
    val userIds = remember(auth) {
        callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
        }
    }
    val uid by userIds.collectAsStateWithLifecycle(initialValue = auth.currentUser?.uid)
    val revision by GaitStore.dataChanges.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    return key(uid) {
        val state by produceState(GaitHomeUiState(loading = true), uid, revision, zone) {
            val account = uid
            value = if (account == null) GaitHomeUiState() else withContext(Dispatchers.IO) {
                runCatching { buildGaitHomeState(GaitStore(context, account).sessions(), zone) }
                    .getOrElse { GaitHomeUiState(error = "Saved gait measurements could not be loaded. Open gait history to retry.") }
            }
        }
        state
    }
}
