package com.example.coverdesign.gait

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Uses unique test account namespaces only; never signs in or reads actual user history. */
@RunWith(AndroidJUnit4::class)
class GaitStorageTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testFiles = mutableListOf<File>()

    private data class Account(val uid: String, val store: GaitStore, val file: File)

    private fun newTestAccount(): Account {
        val uid = "gait-storage-test-${UUID.randomUUID()}"
        val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = File(File(context.noBackupFilesDir, "gait"), "$hash.json")
        // Register each exact path before opening the store, so a failing assertion still cleans up.
        testFiles += file
        return Account(uid, GaitStore(context, uid), file)
    }

    @After
    fun removeOnlyThisTestsAccountFiles() {
        testFiles.forEach { file ->
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { artifact ->
                if (artifact.exists()) assertTrue("Could not remove test storage file", artifact.delete())
            }
        }
        // The shared gait directory and all other account files are deliberately retained.
    }

    @Test
    fun profileOnboardingAndSamplesSurviveReopeningWithoutPendingAtomicFiles() {
        val account = newTestAccount()
        assertNull(account.store.profile())
        assertFalse(account.store.instructionSeen)
        assertTrue(account.store.sessions().isEmpty())

        val profile = GaitProfile(181, 79, "Male", 37, 6, 20)
        account.store.instructionSeen = true
        account.store.saveProfile(profile)
        val first = sampleSession("older", 1_000, profile)
        val second = sampleSession("newer", 2_000, profile).copy(manualLevel = true, completed = false)
        account.store.saveSession(first)
        account.store.saveSession(second)

        val reopened = GaitStore(context, account.uid)
        assertTrue(reopened.instructionSeen)
        assertEquals(profile, reopened.profile())
        assertEquals(listOf(second, first), reopened.sessions())
        assertTrue(account.file.isFile)
        assertFalse("Atomic write should have completed", File(account.file.path + ".new").exists())
        assertFalse("Successful writes should not leave a backup", File(account.file.path + ".bak").exists())
    }

    @Test
    fun sessionUpdateAndDeletePreserveProfileAndOtherSessions() {
        val account = newTestAccount()
        val profile = GaitProfile(168, 63, "Prefer not to say", 42, 4, 12)
        account.store.saveProfile(profile)
        account.store.instructionSeen = true
        val older = sampleSession("older", 1_000, profile)
        val newer = sampleSession("newer", 2_000, profile)
        account.store.saveSession(older)
        account.store.saveSession(newer)
        val updated = older.copy(frontSteps = 14, acceptedFrames = 50)
        account.store.saveSession(updated)

        assertEquals("Updating a session ID must not duplicate it", listOf(newer, updated), account.store.sessions())
        account.store.deleteSession(newer.id)
        val reopened = GaitStore(context, account.uid)
        assertEquals(listOf(updated), reopened.sessions())
        assertEquals(profile, reopened.profile())
        assertTrue(reopened.instructionSeen)
        reopened.deleteSession("a-session-that-never-existed")
        assertEquals(listOf(updated), reopened.sessions())
    }

    @Test
    fun accountsHaveIndependentOnboardingProfilesAndHistory() {
        val first = newTestAccount()
        val second = newTestAccount()
        first.store.instructionSeen = true
        first.store.saveProfile(GaitProfile(179, 80, "Other", 31))
        first.store.saveSession(sampleSession("account-one-only", 1_000, first.store.profile()!!))

        assertFalse(second.store.instructionSeen)
        assertNull(second.store.profile())
        assertTrue(second.store.sessions().isEmpty())
        second.store.saveProfile(GaitProfile(161, 54, "Female", 26))
        second.store.saveSession(sampleSession("account-two-only", 2_000, second.store.profile()!!))

        val reopenedFirst = GaitStore(context, first.uid)
        val reopenedSecond = GaitStore(context, second.uid)
        assertEquals(179, reopenedFirst.profile()!!.heightCm)
        assertEquals(161, reopenedSecond.profile()!!.heightCm)
        assertEquals("account-one-only", reopenedFirst.sessions().single().id)
        assertEquals("account-two-only", reopenedSecond.sessions().single().id)
        reopenedFirst.deleteSession("account-one-only")
        assertEquals("Deleting in one account must not delete another's history", 1, reopenedSecond.sessions().size)
    }

    @Test
    fun rejectedNonFiniteMeasurementDoesNotOverwriteSavedHistory() {
        val account = newTestAccount()
        val valid = sampleSession("valid", 1_000, GaitProfile())
        account.store.saveSession(valid)
        val before = account.file.readBytes()
        val invalid = valid.copy(id = "invalid", samples = listOf(
            GaitSample(100, GaitView.FRONT, mapOf(GaitMetric.PELVIS to Double.NaN), 1)
        ))
        try {
            account.store.saveSession(invalid)
            fail("Non-finite measurements must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: validation must run before the atomic file is replaced.
        }
        assertArrayEquals(before, account.file.readBytes())
        assertEquals(listOf(valid), GaitStore(context, account.uid).sessions())
    }

    @Test
    fun unreadableHistoryIsReportedAndPreservedRatherThanReset() {
        val account = newTestAccount()
        account.store.saveProfile(GaitProfile())
        val corrupt = "not-valid-json".toByteArray()
        account.file.writeBytes(corrupt)
        try {
            GaitStore(context, account.uid).saveProfile(GaitProfile(183, 83, "Male", 40))
            fail("Unreadable history must not be silently replaced")
        } catch (_: IOException) {
            // The caller can now show a recovery message without losing the original data.
        }
        assertArrayEquals(corrupt, account.file.readBytes())
    }

    @Test
    fun editingAnExistingProfileDoesNotRewriteTheProfileCapturedInEarlierResults() {
        val account = newTestAccount()
        val original = GaitProfile(173, 72, "Male", 34, 4, 12)
        account.store.saveProfile(original)
        val session = sampleSession("profile-snapshot", 1_000, original)
        account.store.saveSession(session)
        val updated = original.copy(weightKg = 69, passSteps = 6, targetStepsPerView = 20)
        account.store.saveProfile(updated)

        val reopened = GaitStore(context, account.uid)
        assertEquals(updated, reopened.profile())
        assertEquals("Earlier angle results retain the settings actually used", original, reopened.sessions().single().profile)
        assertEquals(session, reopened.sessions().single())
    }

    @Test
    fun failingToStartAnAtomicWritePreservesHistoryAndCanBeRetriedWithoutDuplication() {
        val account = newTestAccount()
        val original = sampleSession("already-saved", 1_000, GaitProfile())
        account.store.saveSession(original)
        val before = account.file.readBytes()
        val pending = File(account.file.path + ".new")
        assertTrue(pending.mkdir())
        // openRead removes a stale .new before startWrite. A nonempty directory survives that
        // cleanup and reliably prevents FileOutputStream from opening the staging-file path.
        val blocker = File(pending, "keep-directory-nonempty")
        blocker.writeText("test fixture")
        val next = sampleSession("retry-this-result", 2_000, GaitProfile())
        var writeFailed = false
        try {
            account.store.saveSession(next)
        } catch (_: IOException) {
            writeFailed = true
        } finally {
            if (blocker.exists()) assertTrue("Could not remove this test's staging blocker", blocker.delete())
            if (pending.exists()) assertTrue("Could not remove this test's staging directory", pending.delete())
        }

        assertTrue("The nonempty directory must cause a real write failure", writeFailed)
        assertArrayEquals(before, account.file.readBytes())
        assertEquals(listOf(original), GaitStore(context, account.uid).sessions())
        account.store.saveSession(next)
        account.store.saveSession(next) // A UI retry is idempotent for this exact result.
        assertEquals(listOf(next, original), GaitStore(context, account.uid).sessions())
    }

    @Test
    fun interruptedAtomicReplacementRecoversTheExistingBackup() {
        val account = newTestAccount()
        val original = sampleSession("safe-backup", 1_000, GaitProfile())
        account.store.saveSession(original)
        val backup = File(account.file.path + ".bak")
        assertTrue(account.file.renameTo(backup))
        account.file.writeText("interrupted replacement")

        assertEquals(listOf(original), GaitStore(context, account.uid).sessions())
        assertTrue(account.file.isFile)
        assertFalse("AtomicFile should have restored the backup", backup.exists())
    }

    @Test
    fun legacyAndCurrentProtocolVersionsRoundTripWithoutRelabellingOlderResults() {
        val account = newTestAccount()
        val legacy = sampleSession("legacy-mediapipe-predecessor", 1_000, GaitProfile()).copy(protocolVersion = 1)
        val previous = sampleSession("previous-protocol", 1_500, GaitProfile()).copy(protocolVersion = 2)
        val adaptive = sampleSession("adaptive-protocol", 1_750, GaitProfile()).copy(protocolVersion = 3)
        val bodyPredecessor = sampleSession("body-predecessor", 1_800, GaitProfile()).copy(protocolVersion = 4)
        val markerCalibration = sampleSession("marker-calibration-protocol", 1_900, GaitProfile()).copy(protocolVersion = 5)
        val timedPredecessor = sampleSession("timed-marker-window-protocol", 1_950, GaitProfile()).copy(protocolVersion = 6)
        val current = sampleSession("current-protocol", 2_000, GaitProfile())
        assertEquals("New measurements identify the updated acquisition protocol", 7, current.protocolVersion)
        account.store.saveSession(legacy)
        account.store.saveSession(previous)
        account.store.saveSession(adaptive)
        account.store.saveSession(bodyPredecessor)
        account.store.saveSession(markerCalibration)
        account.store.saveSession(timedPredecessor)
        account.store.saveSession(current)

        val reopened = GaitStore(context, account.uid)
        assertEquals(listOf(current, timedPredecessor, markerCalibration, bodyPredecessor, adaptive, previous, legacy), reopened.sessions())
        reopened.saveProfile(GaitProfile(weightKg = 68))
        reopened.saveSession(current) // Saving a current result again cannot migrate an old result.
        assertEquals(1, GaitStore(context, account.uid).sessions().single { it.id == legacy.id }.protocolVersion)
        assertEquals(legacy, GaitStore(context, account.uid).sessions().single { it.id == legacy.id })
        assertEquals(previous, GaitStore(context, account.uid).sessions().single { it.id == previous.id })
        assertEquals(adaptive, GaitStore(context, account.uid).sessions().single { it.id == adaptive.id })
        assertEquals(bodyPredecessor, GaitStore(context, account.uid).sessions().single { it.id == bodyPredecessor.id })
        assertEquals(markerCalibration, GaitStore(context, account.uid).sessions().single { it.id == markerCalibration.id })
    }

    @Test
    fun genuinelyOldJsonWithoutTimingFieldsLoadsWithoutRelabellingOrRewritingItsSession() {
        val account = newTestAccount()
        account.file.writeText("""
            {
              "schemaVersion":1,"instructionSeen":true,
              "profile":{"heightCm":181,"weightKg":79,"sex":"Male","age":37,"passSteps":6,"targetStepsPerView":20},
              "sessions":[{
                "id":"legacy-no-timing","createdAt":1000,
                "profile":{"heightCm":181,"weightKg":79,"sex":"Male","age":37,"passSteps":6,"targetStepsPerView":20},
                "frontSteps":8,"backSteps":4,"acceptedFrames":1,"rejectedFrames":0,"manualLevel":false,"completed":false,"protocolVersion":5,
                "samples":[{"atMs":100,"view":"FRONT","stepIndex":3,"angles":{"SHOULDERS":2.75,"PELVIS":-1.25,"KNEES":0.5}}]
              }]
            }
        """.trimIndent())
        val bytesBefore = account.file.readBytes()
        val reopened = GaitStore(context, account.uid)
        val profile = requireNotNull(reopened.profile())
        val legacy = reopened.sessions().single()
        assertTrue(reopened.instructionSeen)
        assertEquals(GaitProfile(181, 79, "Male", 37, 6, 20), profile)
        assertEquals(2, profile.passDurationSeconds)
        assertEquals(6, profile.targetDurationSecondsPerView)
        assertEquals(5, legacy.protocolVersion)
        assertEquals(8, legacy.frontSteps)
        assertEquals(4, legacy.backSteps)
        assertEquals(3, legacy.samples.single().stepIndex)
        assertEquals(0, legacy.samples.single().passIndex)
        assertEquals(0L, legacy.frontValidDurationMs)
        assertEquals(0L, legacy.backValidDurationMs)
        assertEquals(0, legacy.frontCompletedPasses)
        assertEquals(0, legacy.backCompletedPasses)
        assertFalse(legacy.frontStepsPartial)
        assertFalse(legacy.backStepsPartial)
        assertEquals("LIVE", legacy.acquisitionSource)
        assertEquals(GaitMethod.MARKERS, legacy.measurementMethod)
        assertEquals(0L, legacy.sourceDurationMs)
        assertFalse(legacy.sourceMirrored)
        assertTrue(legacy.videoViews.isEmpty())
        assertArrayEquals("Read-only loading cannot migrate the old file", bytesBefore, account.file.readBytes())
        reopened.saveProfile(profile.copy(weightKg = 80))
        val sessionJson = JSONObject(account.file.readText()).getJSONArray("sessions").getJSONObject(0)
        assertEquals(5, sessionJson.getInt("protocolVersion"))
        assertFalse(sessionJson.has("frontValidDurationMs"))
        assertFalse(sessionJson.getJSONObject("profile").has("passDurationSeconds"))
        assertFalse(sessionJson.getJSONArray("samples").getJSONObject(0).has("passIndex"))
        assertEquals(legacy, GaitStore(context, account.uid).sessions().single())
    }

    @Test
    fun timedPartialMeasurementsRoundTripWithNoObservedStepsAndExplicitPassAndDurationUnits() {
        val account = newTestAccount()
        val profile = GaitProfile(passDurationSeconds = 3, targetDurationSecondsPerView = 9)
        account.store.saveProfile(profile)
        val timed = GaitSession("timed-zero-visible-steps", 2_000, profile, listOf(
            GaitSample(100, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 2.0, GaitMetric.PELVIS to -1.0, GaitMetric.KNEES to .5), 0, 1),
            GaitSample(200, GaitView.BACK, mapOf(GaitMetric.SHOULDERS to 1.0, GaitMetric.PELVIS to 2.0, GaitMetric.SPINE to -.5), 0, 2)
        ), 0, 0, 2, 1, false, false,
            frontValidDurationMs = 1_200, backValidDurationMs = 800,
            frontCompletedPasses = 1, backCompletedPasses = 0,
            frontStepsPartial = true, backStepsPartial = true)
        account.store.saveSession(timed)
        val reopened = GaitStore(context, account.uid)
        assertEquals(profile, reopened.profile())
        assertEquals(timed, reopened.sessions().single())
        val json = JSONObject(account.file.readText())
        assertEquals(3, json.getJSONObject("profile").getInt("passDurationSeconds"))
        assertEquals(9, json.getJSONObject("profile").getInt("targetDurationSecondsPerView"))
        // Legacy names remain separate rather than silently acquiring second-based meanings.
        assertEquals(4, json.getJSONObject("profile").getInt("passSteps"))
        assertEquals(12, json.getJSONObject("profile").getInt("targetStepsPerView"))
        val saved = json.getJSONArray("sessions").getJSONObject(0)
        assertEquals(7, saved.getInt("protocolVersion"))
        assertEquals(1_200L, saved.getLong("frontValidDurationMs"))
        assertEquals(800L, saved.getLong("backValidDurationMs"))
        assertEquals(1, saved.getInt("frontCompletedPasses"))
        assertEquals(0, saved.getInt("backCompletedPasses"))
        assertTrue(saved.getBoolean("frontStepsPartial"))
        assertTrue(saved.getBoolean("backStepsPartial"))
        assertEquals(0, saved.getJSONArray("samples").getJSONObject(1).getInt("stepIndex"))
        assertEquals(2, saved.getJSONArray("samples").getJSONObject(1).getInt("passIndex"))
    }

    @Test
    fun negativeTimedProgressCannotOverwriteStoredHistory() {
        val account = newTestAccount()
        val original = sampleSession("safe", 1_000, GaitProfile())
        account.store.saveSession(original)
        val before = account.file.readBytes()
        val invalid = listOf(
            original.copy(frontValidDurationMs = -1), original.copy(backValidDurationMs = -1),
            original.copy(frontCompletedPasses = -1), original.copy(backCompletedPasses = -1),
            original.copy(samples = original.samples.map { it.copy(passIndex = -1) })
        )
        for (session in invalid) {
            try {
                account.store.saveSession(session)
                fail("Negative recorded progress must be rejected")
            } catch (_: IllegalArgumentException) { }
            assertArrayEquals(before, account.file.readBytes())
        }
        assertEquals(listOf(original), GaitStore(context, account.uid).sessions())
    }

    private fun sampleSession(id: String, createdAt: Long, profile: GaitProfile): GaitSession = GaitSession(
        id = id,
        createdAt = createdAt,
        profile = profile,
        // Synthetic coordinates/results stay strictly inside the random test-account namespace.
        samples = listOf(
            GaitSample(100, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 2.75, GaitMetric.PELVIS to -1.25, GaitMetric.KNEES to 0.5), 1),
            GaitSample(200, GaitView.BACK, mapOf(GaitMetric.SHOULDERS to -2.0, GaitMetric.PELVIS to 1.0, GaitMetric.SPINE to -3.5), 2)
        ),
        frontSteps = 12,
        backSteps = 12,
        acceptedFrames = 2,
        rejectedFrames = 1,
        manualLevel = false,
        completed = true
    )

    @Test fun videoMetadataAndPartialAnglesRoundTripWithoutSourceMedia() {
        val account = newTestAccount()
        val video = sampleSession("imported", 2_000, GaitProfile()).copy(
            samples = listOf(GaitSample(2_000, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 2.0), 0, 1)),
            frontSteps = 0, backSteps = 0, acceptedFrames = 1, manualLevel = true, completed = false,
            protocolVersion = 8, frontValidDurationMs = 1_200, frontStepsPartial = true, backStepsPartial = true,
            acquisitionSource = "VIDEO", sourceDurationMs = 40_000, sourceMirrored = true,
            videoViews = setOf(GaitView.FRONT))
        account.store.saveSession(video)
        assertEquals(video, GaitStore(context, account.uid).sessions().single())
        val saved = JSONObject(account.file.readText()).getJSONArray("sessions").getJSONObject(0)
        assertEquals("VIDEO", saved.getString("acquisitionSource"))
        assertEquals(40_000L, saved.getLong("sourceDurationMs"))
        assertTrue(saved.getBoolean("sourceMirrored"))
        assertEquals("FRONT", saved.getJSONArray("videoViews").getString(0))
        assertFalse(saved.has("uri"))
        assertFalse(saved.has("fileName"))
        assertFalse(saved.has("video"))
        val before = account.file.readBytes()
        for (invalid in listOf(video.copy(sourceDurationMs = -1), video.copy(acquisitionSource = "UNKNOWN"))) {
            try { account.store.saveSession(invalid); fail("Invalid video metadata must be rejected") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(before, account.file.readBytes())
        }
    }

    @Test fun poseAndMarkerSessionsRoundTripAsDistinctMethodsWithoutSpineSubstitution() {
        val account = newTestAccount()
        val marker = sampleSession("marker", 1_000, GaitProfile())
        val pose = marker.copy(id = "pose", createdAt = 2_000, protocolVersion = 9,
            measurementMethod = GaitMethod.POSE,
            samples = marker.samples.map { it.copy(angles = it.angles - GaitMetric.SPINE) })
        account.store.saveSession(marker)
        account.store.saveSession(pose)
        val reopened = GaitStore(context, account.uid).sessions()
        assertEquals(listOf(pose, marker), reopened)
        assertEquals(GaitMethod.POSE, reopened.first().measurementMethod)
        assertEquals(GaitMethod.MARKERS, reopened.last().measurementMethod)
        assertNull(GaitMath.summary(reopened.first(), GaitMetric.SPINE, GaitView.BACK))
        assertEquals(-3.5, requireNotNull(GaitMath.summary(reopened.last(), GaitMetric.SPINE, GaitView.BACK)).mean, .0001)
        val stored = JSONObject(account.file.readText()).getJSONArray("sessions")
        assertEquals("POSE", stored.getJSONObject(1).getString("measurementMethod"))
        account.store.deleteSession(pose.id)
        assertEquals(listOf(marker), GaitStore(context, account.uid).sessions())
    }

    @Test fun poseCannotSaveAnInventedSpineMeasurementOrOverwritePreviousHistory() {
        val account = newTestAccount()
        val marker = sampleSession("safe", 1_000, GaitProfile())
        account.store.saveSession(marker)
        val before = account.file.readBytes()
        try {
            account.store.saveSession(marker.copy(id = "invalid-pose", protocolVersion = 9,
                measurementMethod = GaitMethod.POSE))
            fail("A pose session must not contain a C7–coccyx angle")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, account.file.readBytes())
        assertEquals(listOf(marker), GaitStore(context, account.uid).sessions())
    }

    @Test fun anUnknownMeasurementMethodIsReportedWithoutSilentlyRelabellingIt() {
        val account = newTestAccount()
        account.store.saveSession(sampleSession("future", 1_000, GaitProfile()))
        val root = JSONObject(account.file.readText())
        root.getJSONArray("sessions").getJSONObject(0).put("measurementMethod", "UNKNOWN")
        account.file.writeText(root.toString())
        val before = account.file.readBytes()
        try {
            GaitStore(context, account.uid).sessions()
            fail("Unknown methods must not be presented as marker measurements")
        } catch (_: IOException) { }
        assertArrayEquals(before, account.file.readBytes())
    }
}
