package com.example.coverdesign.gait

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One process-wide uploader; Firebase Auth remains the authority for each owner path. */
internal object GaitCloud {
    @Volatile private var instance: GaitCloudRepository? = null
    fun get(context: Context): GaitCloudRepository = instance ?: synchronized(this) {
        instance ?: GaitCloudRepository(context.applicationContext).also { instance = it }
    }
}

internal class GaitCloudRepository(context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("gait_cloud_ack_v1", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(GaitSyncState(userId = auth.currentUser?.uid))
    val state = mutableState.asStateFlow()
    private var ownerJob: Job? = null
    private val authListener = FirebaseAuth.AuthStateListener { firebase ->
        val uid = firebase.currentUser?.uid
        ownerJob?.cancel()
        mutableState.value = GaitSyncState(userId = uid, message = if (uid == null) "Sign in to sync results." else "Checking saved results…")
        if (uid != null) ownerJob = scope.launch {
            val encodedSessions = mutableMapOf<String, Pair<String, EncodedGaitSession>>()
            syncRequests.trySend(Unit)
            for (request in syncRequests) {
                delay(200)
                syncOwner(uid, encodedSessions)
            }
        }
    }

    init {
        auth.addAuthStateListener(authListener)
        scope.launch { GaitStore.dataChanges.collect { syncRequests.trySend(Unit) } }
        scope.launch { while (isActive) { delay(30_000); syncRequests.trySend(Unit) } }
    }

    fun syncNow() { syncRequests.trySend(Unit) }
    internal fun close() { auth.removeAuthStateListener(authListener); scope.cancel(); syncRequests.close() }
    private fun owner(): String = auth.currentUser?.uid ?: error("Sign in to continue.")
    private fun requireOwner(uid: String) { check(owner() == uid) { "The signed-in account changed. Please retry." } }
    private fun ackPrefix(uid: String) = gaitDigest("${db.app.options.projectId}:$uid")

    private suspend fun syncOwner(uid: String, encodedSessions: MutableMap<String, Pair<String, EncodedGaitSession>>) {
        try {
            requireOwner(uid)
            val store = GaitStore(appContext, uid)
            val localSessions = store.sessions()
            encodedSessions.keys.retainAll(localSessions.map { it.id }.toSet())
            val reports = localSessions.map { session ->
                currentCoroutineContext().ensureActive()
                val fingerprint = GaitCloudSessionCodec.fingerprint(session)
                val encoded = encodedSessions[session.id]?.takeIf { it.first == fingerprint }?.second
                    ?: GaitCloudSessionCodec.encode(session).also { encodedSessions[session.id] = fingerprint to it }
                GaitCloudReport.fromSession(uid, session).copy(detailManifest = encoded.manifest)
            }
            if (reports.isNotEmpty()) ensurePatientProfile(uid)
            val prefix = ackPrefix(uid)
            val changed = reports.filter { prefs.getString("$prefix:${it.sessionId}", null) != gaitDigest(it.toMap().toString()) }
            val deleted = store.deletedSessionIds()
            var pending = changed.size + deleted.size
            fun publish(message: String, at: Long? = mutableState.value.lastSyncedAt) {
                if (auth.currentUser?.uid == uid) mutableState.value = GaitSyncState(uid, message, pending, at)
            }
            publish(if (pending > 0) "Uploading numerical results…" else "Saved results are synced.",
                prefs.getLong("$prefix:last", 0).takeIf { it > 0 })
            for (id in deleted) {
                require(validDocumentId(id)); requireOwner(uid)
                val ref = db.collection("gaitReports").document(uid).collection("sessions").document(id)
                val previous = ref.get(Source.SERVER).gaitAwait()
                requireOwner(uid)
                val previousCount = previous.get("detailManifest")?.let { raw ->
                    @Suppress("UNCHECKED_CAST")
                    GaitSessionManifest.fromMap(raw as Map<String, Any?>).chunkCount
                } ?: 0
                val batch = db.batch()
                repeat(previousCount) { index -> batch.delete(ref.collection("details").document(index.toString())) }
                batch.delete(ref)
                batch.commit().gaitAwait()
                requireOwner(uid)
                prefs.edit().remove("$prefix:$id").apply()
                store.acknowledgeCloudDeletions(setOf(id))
                pending--; publish("Syncing results…")
            }
            for (report in changed) {
                requireOwner(uid)
                // A user can delete while an earlier upload is in flight. Its durable tombstone
                // is handled by the next sync, rather than silently resurrecting a local session.
                if (report.sessionId in store.deletedSessionIds()) { pending--; continue }
                val encoded = requireNotNull(encodedSessions[report.sessionId]).second
                val ref = db.collection("gaitReports").document(uid).collection("sessions").document(report.sessionId)
                val previous = ref.get(Source.SERVER).gaitAwait()
                requireOwner(uid)
                currentCoroutineContext().ensureActive()
                val previousCount = previous.get("detailManifest")?.let { raw ->
                    @Suppress("UNCHECKED_CAST")
                    GaitSessionManifest.fromMap(raw as Map<String, Any?>).chunkCount
                } ?: 0
                // Publish the index and every chunk atomically: a doctor can never see a
                // newly announced detail manifest paired with a half-uploaded session.
                val batch = db.batch().set(ref, report.toMap())
                encoded.chunks.forEachIndexed { index, payload ->
                    batch.set(ref.collection("details").document(index.toString()), mapOf("index" to index, "payload" to payload))
                }
                for (index in encoded.chunks.size until previousCount) batch.delete(ref.collection("details").document(index.toString()))
                batch.commit().gaitAwait()
                requireOwner(uid)
                val now = System.currentTimeMillis()
                prefs.edit().putString("$prefix:${report.sessionId}", gaitDigest(report.toMap().toString())).putLong("$prefix:last", now).apply()
                pending--; publish("Syncing results…", now)
            }
            publish(if (reports.isEmpty() && deleted.isEmpty()) "No saved numerical results to upload." else "Saved results are synced.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (auth.currentUser?.uid == uid) mutableState.value = mutableState.value.copy(message = cloudError(e))
        }
    }

    suspend fun doctorCode(): String {
        val uid = owner()
        val profile = db.collection("users").document(uid).get(Source.SERVER).gaitAwait()
        requireOwner(uid)
        check(profile.getString("role") == "DOCTOR") { "A doctor account is required." }
        val key = "${ackPrefix(uid)}:doctorCode"
        prefs.getString(key, null)?.let { existing ->
            val saved = db.collection("gaitDoctorCodes").document(existing).get(Source.SERVER).gaitAwait()
            requireOwner(uid)
            if (saved.getString("doctorUid") == uid) return existing
        }
        val code = UUID.randomUUID().toString().replace("-", "").uppercase()
        db.collection("gaitDoctorCodes").document(code).set(mapOf(
            "doctorUid" to uid, "doctorDisplayName" to (profile.getString("displayName")?.take(120)?.ifBlank { "Doctor" } ?: "Doctor"),
            "createdAt" to System.currentTimeMillis()
        )).gaitAwait()
        requireOwner(uid)
        prefs.edit().putString(key, code).apply()
        return code
    }

    suspend fun shareWithDoctor(code: String): String {
        val uid = owner()
        val normalized = code.trim().replace(" ", "").replace("-", "").uppercase()
        require(normalized.matches(Regex("[A-F0-9]{32}"))) { "Enter the full invitation code from your doctor." }
        ensurePatientProfile(uid)
        val invitation = db.collection("gaitDoctorCodes").document(normalized).get(Source.SERVER).gaitAwait()
        requireOwner(uid)
        val doctorUid = invitation.getString("doctorUid") ?: error("Doctor invitation not found. Check the code.")
        check(doctorUid != uid) { "Use a different doctor account." }
        val doctorName = invitation.getString("doctorDisplayName") ?: "Doctor"
        val profile = db.collection("users").document(uid).get(Source.SERVER).gaitAwait()
        requireOwner(uid)
        db.collection("gaitShares").document(uid).set(mapOf(
            "patientUid" to uid, "doctorUid" to doctorUid, "doctorCode" to normalized,
            "patientDisplayName" to (profile.getString("displayName")?.take(120)?.ifBlank { "Patient" } ?: "Patient"),
            "doctorDisplayName" to doctorName, "active" to true, "updatedAt" to System.currentTimeMillis()
        )).gaitAwait()
        requireOwner(uid)
        syncNow()
        return doctorName
    }

    suspend fun revokeDoctor() {
        val uid = owner()
        db.collection("gaitShares").document(uid).delete().gaitAwait()
        requireOwner(uid)
    }

    fun myShare(): Flow<GaitPatientLink?> = callbackFlow {
        val uid = owner()
        val account = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) { trySend(null); close() } }
        auth.addAuthStateListener(account)
        val listener = db.collection("gaitShares").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (auth.currentUser?.uid != uid) { trySend(null); close() }
            else if (error != null) { trySend(null); close(error) }
            else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                trySend(snapshot.data?.let(::readLink)?.takeIf { it.active })
            }
        }
        awaitClose { listener.remove(); auth.removeAuthStateListener(account) }
    }

    /** Never emits cached clinician records before the server confirms this account's grant. */
    fun patients(): Flow<List<GaitPatientLink>> = callbackFlow {
        val uid = owner()
        val account = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) { trySend(emptyList()); close() } }
        auth.addAuthStateListener(account)
        val listener = db.collection("gaitShares").whereEqualTo("doctorUid", uid).whereEqualTo("active", true)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (auth.currentUser?.uid != uid) { trySend(emptyList()); close() }
                else if (error != null) { trySend(emptyList()); close(error) }
                else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    trySend(snapshot.documents.mapNotNull { it.data?.let(::readLink) }.filter { it.active && it.doctorUid == uid })
                } else if (snapshot != null) trySend(emptyList())
            }
        awaitClose { listener.remove(); auth.removeAuthStateListener(account) }
    }

    fun reports(patientUid: String): Flow<List<GaitCloudReport>> = callbackFlow {
        require(validDocumentId(patientUid))
        val uid = owner()
        var reportListener: ListenerRegistration? = null
        val grantGeneration = AtomicLong(0)
        val account = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) {
            grantGeneration.incrementAndGet(); trySend(emptyList()); close()
        } }
        auth.addAuthStateListener(account)
        val grantListener = db.collection("gaitShares").document(patientUid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { grant, error ->
                // Cached or revoked grants must clear data as well as stop future reads.
                val generation = grantGeneration.incrementAndGet()
                reportListener?.remove(); reportListener = null
                trySend(emptyList())
                if (auth.currentUser?.uid != uid) close()
                else if (error != null) close(error)
                else if (grant != null && !grant.metadata.isFromCache && !grant.metadata.hasPendingWrites() &&
                    grant.getString("doctorUid") == uid && grant.getBoolean("active") == true) {
                    reportListener = db.collection("gaitReports").document(patientUid).collection("sessions")
                        .addSnapshotListener(MetadataChanges.INCLUDE) { records, failure ->
                            if (generation != grantGeneration.get()) return@addSnapshotListener
                            if (auth.currentUser?.uid != uid) { trySend(emptyList()); close() }
                            else if (failure != null) { trySend(emptyList()); close(failure) }
                            else if (records != null && !records.metadata.isFromCache && !records.metadata.hasPendingWrites()) {
                                val parsed = runCatching { records.documents.map { doc ->
                                    GaitCloudReport.fromMap(requireNotNull(doc.data), patientUid, doc.id)
                                }.sortedByDescending { it.createdAt } }
                                parsed.onSuccess { trySend(it) }.onFailure { trySend(emptyList()); close(it) }
                            }
                        }
                }
            }
        awaitClose { grantGeneration.incrementAndGet(); grantListener.remove(); reportListener?.remove(); auth.removeAuthStateListener(account) }
    }

    /** On-demand full report; reports() first confirms the current server-side grant. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun session(patientUid: String, sessionId: String): Flow<GaitSession?> {
        require(validDocumentId(sessionId))
        return reports(patientUid).mapLatest { reports ->
            reports.firstOrNull { it.sessionId == sessionId && it.detailManifest != null }?.let { readFullSession(it) }
        }
    }

    /** Full compatible records are needed for the patient's exact comparisons and ranges. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sessionHistory(patientUid: String, comparisonGroupId: String): Flow<List<GaitSession>> =
        reports(patientUid).mapLatest { reports ->
            reports.filter { it.comparisonGroupId == comparisonGroupId && it.detailManifest != null }
                .map { readFullSession(it) }
        }

    /**
     * Care-linked read: lists a patient's reports directly. Authorization is enforced by the
     * Firestore rules' `gaitCareLinkedDoctor` (a `care_links` entry + the patient's consent flag),
     * so no `gaitShares` grant listener is needed. A denied read surfaces as an error/empty list.
     */
    fun careReports(patientUid: String): Flow<List<GaitCloudReport>> = callbackFlow {
        require(validDocumentId(patientUid))
        val uid = owner()
        val account = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) { trySend(emptyList()); close() } }
        auth.addAuthStateListener(account)
        val listener = db.collection("gaitReports").document(patientUid).collection("sessions")
            .addSnapshotListener(MetadataChanges.INCLUDE) { records, failure ->
                if (auth.currentUser?.uid != uid) { trySend(emptyList()); close() }
                else if (failure != null) { trySend(emptyList()); close(failure) }
                else if (records != null && !records.metadata.isFromCache && !records.metadata.hasPendingWrites()) {
                    val parsed = runCatching { records.documents.map { doc ->
                        GaitCloudReport.fromMap(requireNotNull(doc.data), patientUid, doc.id)
                    }.sortedByDescending { it.createdAt } }
                    parsed.onSuccess { trySend(it) }.onFailure { trySend(emptyList()); close(it) }
                }
            }
        awaitClose { listener.remove(); auth.removeAuthStateListener(account) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun careSession(patientUid: String, sessionId: String): Flow<GaitSession?> {
        require(validDocumentId(sessionId))
        return careReports(patientUid).mapLatest { reports ->
            reports.firstOrNull { it.sessionId == sessionId && it.detailManifest != null }?.let { readFullSession(it) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun careSessionHistory(patientUid: String, comparisonGroupId: String): Flow<List<GaitSession>> =
        careReports(patientUid).mapLatest { reports ->
            reports.filter { it.comparisonGroupId == comparisonGroupId && it.detailManifest != null }
                .map { readFullSession(it) }
        }

    private suspend fun readFullSession(report: GaitCloudReport): GaitSession {
        val uid = owner()
        val manifest = requireNotNull(report.detailManifest)
        val snapshot = db.collection("gaitReports").document(report.patientUid).collection("sessions")
            .document(report.sessionId).collection("details")
            .whereLessThan("index", manifest.chunkCount).get(Source.SERVER).gaitAwait()
        requireOwner(uid)
        currentCoroutineContext().ensureActive()
        check(snapshot.documents.size == manifest.chunkCount) { "This session report is incomplete. Ask the patient to sync again." }
        val indexed = snapshot.documents.associate { document ->
            val index = (document.get("index") as? Number)?.toInt()
                ?: error("Invalid session report chunk.")
            require(index in 0 until manifest.chunkCount && document.id == index.toString())
            index to (document.getString("payload") ?: error("Invalid session report data."))
        }
        val chunks = (0 until manifest.chunkCount).map { requireNotNull(indexed[it]) }
        return withContext(Dispatchers.Default) {
        val decoded = GaitCloudSessionCodec.decode(manifest, chunks, report.sessionId)
        // Bind the decoded time series to the same owner path, acquisition context and
        // exact numerical summaries displayed in the list. Mismatches never become graphs.
        check(GaitCloudReport.fromSession(report.patientUid, decoded).copy(detailManifest = manifest) == report) {
            "Session details do not match the published report. Ask the patient to sync again."
        }
        decoded
        }
    }

    private fun readLink(data: Map<String, Any>): GaitPatientLink? = runCatching {
        GaitPatientLink(data["patientUid"] as String, data["doctorUid"] as String,
            data["patientDisplayName"] as String, data["doctorDisplayName"] as String, data["active"] as Boolean)
    }.getOrNull()

    /** Older test accounts could be created in Auth before this project's database existed. */
    private suspend fun ensurePatientProfile(uid: String) {
        requireOwner(uid)
        val ref = db.collection("users").document(uid)
        val profile = ref.get(Source.SERVER).gaitAwait()
        requireOwner(uid)
        if (!profile.exists()) {
            val user = requireNotNull(auth.currentUser)
            // A transaction cannot overwrite a role/profile created concurrently by registration.
            db.runTransaction { tx ->
                if (!tx.get(ref).exists()) tx.set(ref, mapOf(
                    "uid" to uid, "email" to (user.email ?: ""),
                    "displayName" to (user.displayName?.take(120)?.ifBlank { "Patient" } ?: "Patient"),
                    "phone" to (user.phoneNumber ?: ""), "role" to "PATIENT",
                    "createdAt" to (user.metadata?.creationTimestamp ?: System.currentTimeMillis()), "hasInsoles" to false
                ))
            }.gaitAwait()
            requireOwner(uid)
        }
    }
}

internal fun cloudError(error: Throwable): String = when {
    error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
        "Cloud access denied. The Firebase gait access rules or your account permissions need to be checked. Results remain saved on this phone."
    error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
        "Cannot reach Firebase. Results remain saved on this phone; sync will retry."
    else -> error.message?.take(220) ?: "Could not sync results. Please retry."
}

private suspend fun <T> Task<T>.gaitAwait(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
