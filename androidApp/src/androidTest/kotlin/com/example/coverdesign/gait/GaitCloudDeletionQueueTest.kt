package com.example.coverdesign.gait

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local random namespaces only. No Firebase repository, sign-in, cloud writes or real history. */
@RunWith(AndroidJUnit4::class)
class GaitCloudDeletionQueueTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()
    private data class Account(val uid: String, val store: GaitStore)
    private fun account(): Account {
        val uid = "gait-cloud-deletion-test-${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        files += File(File(context.noBackupFilesDir, "gait"), "$digest.json")
        return Account(uid, GaitStore(context, uid))
    }
    private fun session(id: String) = GaitSession(id, 1_000_000, GaitProfile(),
        listOf(GaitSample(200, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 1.0), 0)),
        0, 0, 1, 0, true, false)

    @After fun cleanOnlyRandomTestFiles() {
        files.forEach { file -> listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach {
            if (it.exists()) assertTrue(it.delete())
        } }
    }

    @Test fun deletedIdsSurviveRestartAndDuplicateDeletionIsIdempotent() {
        val account = account()
        account.store.saveSession(session("first"))
        account.store.saveSession(session("second"))
        account.store.deleteSession("first")
        account.store.deleteSession("first")
        val reopened = GaitStore(context, account.uid)
        assertEquals(setOf("first"), reopened.deletedSessionIds())
        assertEquals(listOf("second"), reopened.sessions().map { it.id })
    }

    @Test fun acknowledgingOneCloudDeletionDoesNotLoseOtherPendingDeletesOrSavedData() {
        val account = account()
        account.store.saveSession(session("keep"))
        account.store.deleteSession("first")
        account.store.deleteSession("second")
        account.store.acknowledgeCloudDeletions(setOf("first", "unknown"))
        val reopened = GaitStore(context, account.uid)
        assertEquals(setOf("second"), reopened.deletedSessionIds())
        assertEquals(listOf("keep"), reopened.sessions().map { it.id })
        reopened.acknowledgeCloudDeletions(emptySet())
        assertEquals(setOf("second"), reopened.deletedSessionIds())
        reopened.acknowledgeCloudDeletions(setOf("second"))
        assertTrue(GaitStore(context, account.uid).deletedSessionIds().isEmpty())
    }

    @Test fun identicalSessionIdsInDifferentAccountsHaveIndependentDeletionQueues() {
        val first = account()
        val second = account()
        first.store.saveSession(session("same-id"))
        second.store.saveSession(session("same-id"))
        first.store.deleteSession("same-id")
        assertEquals(setOf("same-id"), GaitStore(context, first.uid).deletedSessionIds())
        assertTrue(GaitStore(context, second.uid).deletedSessionIds().isEmpty())
        assertEquals(listOf("same-id"), GaitStore(context, second.uid).sessions().map { it.id })
    }
}
