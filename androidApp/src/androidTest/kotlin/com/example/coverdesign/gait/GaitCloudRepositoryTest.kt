package com.example.coverdesign.gait

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Source
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in end-to-end checks against LOCAL Firebase emulators only.
 * Run only this class with GaitTestRunner and -e gaitCloudEmulator true.
 * Every client is a newly named FirebaseApp in a demo project; default Firebase is never used.
 */
@RunWith(AndroidJUnit4::class)
class GaitCloudRepositoryTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<Fixture>()
    private data class Fixture(
        val app: FirebaseApp,
        val auth: FirebaseAuth,
        val db: FirebaseFirestore,
        val uid: String,
        val localFile: File,
        val store: GaitStore,
        val repo: GaitCloudRepository
    )

    @Before fun requireExplicitEmulatorRunAndSafeRunner() {
        assumeTrue("Local Firebase emulator tests are opt-in.",
            InstrumentationRegistry.getArguments().getString("gaitCloudEmulator") == "true")
        assertTrue("GaitTestRunner must suppress MainApplication's default uploader for this run.",
            InstrumentationRegistry.getInstrumentation() is GaitTestRunner)
        check(PROJECT_ID.startsWith("demo-"))
    }

    private suspend fun fixture(role: String? = null): Fixture {
        val token = UUID.randomUUID().toString().replace("-", "")
        val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT_ID)
            .setApplicationId("1:1234567890:android:0123456789abcdef")
            .setApiKey("AIzaSy" + "E".repeat(33))
            .build()
        val app = FirebaseApp.initializeApp(context, options, "gait-emulator-$token")
        check(app.name != FirebaseApp.DEFAULT_APP_NAME && app.options.projectId == PROJECT_ID)
        synchronized(secondaryApps) { secondaryApps += app }
        val auth = FirebaseAuth.getInstance(app)
        auth.useEmulator(EMULATOR_HOST, AUTH_PORT)
        val db = FirebaseFirestore.getInstance(app)
        db.firestoreSettings = FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build()
        db.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        check(db.firestoreSettings.host == "$EMULATOR_HOST:$FIRESTORE_PORT")
        check(!db.firestoreSettings.isSslEnabled && !db.firestoreSettings.isPersistenceEnabled)
        val result = withTimeout(25_000) {
            auth.createUserWithEmailAndPassword("gait-$token@emulator.invalid", "emulator-password-1234").testAwait()
        }
        val uid = requireNotNull(result.user).uid
        val localFile = File(File(context.noBackupFilesDir, "gait"), "${gaitDigest(uid)}.json")
        // An emulator UID must never collide with an existing local account namespace.
        check(listOf(localFile, File(localFile.path + ".bak"), File(localFile.path + ".new")).none { it.exists() })
        if (role != null) db.collection("users").document(uid).set(mapOf(
            "uid" to uid, "email" to "gait-$token@emulator.invalid", "displayName" to "Dr Emulator",
            "phone" to "", "role" to role, "createdAt" to System.currentTimeMillis(), "hasInsoles" to false
        )).testAwait()
        return Fixture(app, auth, db, uid, localFile, GaitStore(context, uid),
            GaitCloudRepository(context, auth = auth, db = db)).also { fixtures += it }
    }

    private fun recording(id: String): GaitSession {
        val samples = GaitView.entries.flatMap { view -> (1..3).map { index ->
            GaitSample(index * 200L, view,
                expectedGaitMetrics(view, GaitMethod.POSE).associateWith { if (index == 2) -1.0 else 2.0 },
                0, view.ordinal + 1)
        } }
        return GaitSession(id, System.currentTimeMillis(), GaitProfile(), samples,
            0, 0, samples.size, 1, true, true, protocolVersion = 9,
            frontValidDurationMs = 6_000, backValidDurationMs = 6_000, measurementMethod = GaitMethod.POSE)
    }

    private suspend fun reportDocument(fixture: Fixture, sessionId: String, exists: Boolean = true): DocumentSnapshot =
        withTimeout(30_000) {
            var result: DocumentSnapshot
            do {
                result = fixture.db.collection("gaitReports").document(fixture.uid)
                    .collection("sessions").document(sessionId).get(Source.SERVER).testAwait()
                if (result.exists() != exists) delay(150)
            } while (result.exists() != exists)
            result
        }

    private suspend fun <T> nextMatching(channel: Channel<T>, predicate: (T) -> Boolean): T = withTimeout(25_000) {
        var next: T
        do { next = channel.receive() } while (!predicate(next))
        next
    }

    private suspend fun awaitLocalDeletionAcknowledged(store: GaitStore) = withTimeout(25_000) {
        while (store.deletedSessionIds().isNotEmpty()) delay(100)
    }

    @Test(timeout = 150_000)
    fun savedResultAutomaticallyUploadsSharesWithDoctorAndRevocationClearsLiveAccess() = runBlocking {
        val doctor = fixture("DOCTOR")
        val patient = fixture()
        val record = recording("repo-sync-${UUID.randomUUID()}")
        patient.store.saveSession(record) // No manual sync call: dataChanges must trigger upload.
        val document = reportDocument(patient, record.id)
        val payload = requireNotNull(document.data)
        assertEquals(GaitCloudReport.fromSession(patient.uid, record),
            GaitCloudReport.fromMap(payload, patient.uid, record.id).copy(detailManifest = null))
        assertNotNull(GaitCloudReport.fromMap(payload, patient.uid, record.id).detailManifest)
        assertEquals("PATIENT", patient.db.collection("users").document(patient.uid)
            .get(Source.SERVER).testAwait().getString("role"))
        assertEquals(5, (payload.getValue("typicalInclinations") as Map<*, *>).size)
        assertFalse((payload.getValue("typicalInclinations") as Map<*, *>).containsKey("BACK_SPINE"))
        assertFalse(payload.keys.any { it in setOf("samples", "profile", "heightCm", "weightKg", "age", "sex", "video", "uri", "markers") })

        val patients = Channel<List<GaitPatientLink>>(Channel.UNLIMITED)
        val reports = Channel<List<GaitCloudReport>>(Channel.UNLIMITED)
        val sessions = Channel<GaitSession?>(Channel.UNLIMITED)
        val fullHistory = Channel<List<GaitSession>>(Channel.UNLIMITED)
        val revoking = AtomicBoolean(false)
        val jobs = mutableListOf<Job>()
        jobs += launch(Dispatchers.Default) {
            doctor.repo.patients().collect { patients.send(it) }
        }
        try {
            val code = doctor.repo.doctorCode()
            assertTrue(code.matches(Regex("[A-F0-9]{32}")))
            assertEquals("Dr Emulator", patient.repo.shareWithDoctor(code))
            val listed = nextMatching(patients) { list -> list.any { it.patientUid == patient.uid } }
            assertEquals(doctor.uid, listed.single { it.patientUid == patient.uid }.doctorUid)
            jobs += launch(Dispatchers.Default) {
                doctor.repo.reports(patient.uid).catch { error ->
                    if (revoking.get() && error is FirebaseFirestoreException &&
                        error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) emit(emptyList())
                    else throw error
                }.collect { reports.send(it) }
            }
            val visible = nextMatching(reports) { list -> list.any { it.sessionId == record.id } }
            assertEquals(patient.uid, visible.single { it.sessionId == record.id }.patientUid)
            assertEquals(GaitMethod.POSE, visible.single().measurementMethod)

            jobs += launch(Dispatchers.Default) {
                doctor.repo.session(patient.uid, record.id).catch { error ->
                    if (revoking.get() && error is FirebaseFirestoreException &&
                        error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) emit(null) else throw error
                }.collect { sessions.send(it) }
            }
            jobs += launch(Dispatchers.Default) {
                doctor.repo.sessionHistory(patient.uid, visible.single().comparisonGroupId).catch { error ->
                    if (revoking.get() && error is FirebaseFirestoreException &&
                        error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) emit(emptyList()) else throw error
                }.collect { fullHistory.send(it) }
            }
            val full = nextMatching(sessions) { it?.id == record.id }
            assertEquals("The doctor's report must preserve every original sample and profile field.", record, full)
            assertEquals(record, nextMatching(fullHistory) { list -> list.any { it.id == record.id } }.single())
            assertEquals(GaitInsights.overview(record), GaitInsights.overview(requireNotNull(full)))

            revoking.set(true)
            patient.repo.revokeDoctor()
            nextMatching(reports) { it.isEmpty() }
            nextMatching(patients) { list -> list.none { it.patientUid == patient.uid } }
            nextMatching(sessions) { it == null }
            nextMatching(fullHistory) { it.isEmpty() }
            val denied = runCatching {
                doctor.db.collection("gaitReports").document(patient.uid).collection("sessions")
                    .document(record.id).get(Source.SERVER).testAwait()
            }.exceptionOrNull()
            assertTrue("Revoked doctor must be denied even for a previously read report.", denied is FirebaseFirestoreException)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, (denied as FirebaseFirestoreException).code)

            patient.store.deleteSession(record.id)
            reportDocument(patient, record.id, exists = false)
            awaitLocalDeletionAcknowledged(patient.store)
            assertTrue(patient.db.collection("gaitReports").document(patient.uid).collection("sessions")
                .document(record.id).collection("details").get(Source.SERVER).testAwait().isEmpty)
            assertTrue(patient.store.sessions().isEmpty())
        } finally {
            withContext(NonCancellable) { jobs.forEach { it.cancelAndJoin() } }
            patients.close(); reports.close(); sessions.close(); fullHistory.close()
        }
    }

    @Test(timeout = 150_000)
    fun offlineSaveAndDeleteSurviveUntilNetworkReturnsAndSyncRetries() = runBlocking {
        val patient = fixture()
        val initial = recording("online-${UUID.randomUUID()}")
        patient.store.saveSession(initial)
        reportDocument(patient, initial.id)
        withTimeout(15_000) { patient.repo.state.first { it.lastSyncedAt != null && it.pending == 0 } }
        patient.db.disableNetwork().testAwait()
        val offline = recording("offline-${UUID.randomUUID()}")
        try {
            patient.store.saveSession(offline)
            patient.store.deleteSession(initial.id)
            assertEquals(listOf(offline.id), GaitStore(context, patient.uid).sessions().map { it.id })
            assertEquals(setOf(initial.id), GaitStore(context, patient.uid).deletedSessionIds())
            // Wait for an actual failed offline attempt, rather than reconnecting before debounce.
            withTimeout(20_000) { patient.repo.state.first { it.message.startsWith("Cannot reach Firebase.") } }
            assertEquals(listOf(offline), patient.store.sessions())
        } finally {
            patient.db.enableNetwork().testAwait()
        }
        patient.repo.syncNow()
        reportDocument(patient, offline.id)
        reportDocument(patient, initial.id, exists = false)
        awaitLocalDeletionAcknowledged(patient.store)
        assertEquals(listOf(offline), patient.store.sessions())
    }

    @Test(timeout = 150_000)
    fun legacyCompactReportUpgradesToAnExactMultiChunkSessionWithoutDownsampling() = runBlocking {
        val patient = fixture("PATIENT")
        val random = kotlin.random.Random(829)
        val original = recording("legacy-upgrade-${UUID.randomUUID()}").copy(
            profile = GaitProfile(184, 82, "Male", 39),
            samples = (0 until 10_000).map { index ->
                val view = if (index < 5_000) GaitView.FRONT else GaitView.BACK
                GaitSample(index * 40L, view, expectedGaitMetrics(view, GaitMethod.POSE)
                    .associateWith { random.nextDouble(-12.0, 12.0) }, index / 20, index / 50)
            }, acceptedFrames = 10_000, rejectedFrames = 101,
            frontSteps = 32, backSteps = 28, frontCompletedPasses = 4, backCompletedPasses = 3,
            frontStepsPartial = true, backStepsPartial = true)
        val ref = patient.db.collection("gaitReports").document(patient.uid).collection("sessions").document(original.id)
        ref.set(GaitCloudReport.fromSession(patient.uid, original).toMap()).testAwait()
        assertNull(ref.get(Source.SERVER).testAwait().get("detailManifest"))
        patient.store.saveSession(original)
        withTimeout(30_000) { patient.repo.state.first { it.lastSyncedAt != null && it.pending == 0 } }
        val report = GaitCloudReport.fromMap(requireNotNull(ref.get(Source.SERVER).testAwait().data), patient.uid, original.id)
        val manifest = requireNotNull(report.detailManifest)
        assertTrue("The large real numerical series must exercise more than one chunk.", manifest.chunkCount >= 2)
        val chunks = ref.collection("details").get(Source.SERVER).testAwait().documents
            .sortedBy { (it.get("index") as Number).toInt() }.map { requireNotNull(it.getString("payload")) }
        assertEquals(manifest.chunkCount, chunks.size)
        assertTrue(chunks.all { it.length <= 131_072 })
        assertEquals(original, GaitCloudSessionCodec.decode(manifest, chunks, original.id))
        assertEquals(10_000, GaitCloudSessionCodec.decode(manifest, chunks, original.id).samples.size)
    }

    @After fun cleanOnlySecondaryAppsAndTheirExactLocalNamespaces() = runBlocking {
        fixtures.forEach { it.repo.close() }
        fixtures.forEach { fixture ->
            runCatching { withTimeout(5_000) { fixture.db.enableNetwork().testAwait() } }
            runCatching { withTimeout(5_000) { fixture.db.terminate().testAwait() } }
            fixture.auth.signOut()
            // Firebase Auth's emulator registry can retain same-key app callbacks. Keep the
            // named app alive until no later test can call useEmulator with that fake API key.
            listOf(fixture.localFile, File(fixture.localFile.path + ".bak"), File(fixture.localFile.path + ".new")).forEach {
                if (it.exists()) assertTrue("Could not clean this emulator test's local history.", it.delete())
            }
            val prefs = context.getSharedPreferences("gait_cloud_ack_v1", Context.MODE_PRIVATE)
            val prefix = "${gaitDigest("$PROJECT_ID:${fixture.uid}")}:"
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { edit.remove(it) }
            assertTrue(edit.commit())
        }
        fixtures.clear()
    }

    private suspend fun <T> Task<T>.testAwait(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        private const val PROJECT_ID = "demo-coverdesign-gait"
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val AUTH_PORT = 9098
        private const val FIRESTORE_PORT = 8185
        private val secondaryApps = mutableListOf<FirebaseApp>()

        @JvmStatic
        @AfterClass
        fun deleteSecondaryAppsAfterAllEmulatorClientsFinish() {
            val apps = synchronized(secondaryApps) { secondaryApps.toList().also { secondaryApps.clear() } }
            apps.forEach { app ->
                check(app.name.startsWith("gait-emulator-") && app.name != FirebaseApp.DEFAULT_APP_NAME)
                check(app.options.projectId == PROJECT_ID)
                app.delete()
            }
        }
    }
}
