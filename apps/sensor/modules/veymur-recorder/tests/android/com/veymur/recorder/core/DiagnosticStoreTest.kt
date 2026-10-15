package com.veymur.recorder.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DiagnosticStoreTest {
    private fun db(store: DiagnosticStore) = DiagnosticStore::class.java.getDeclaredField("db").apply { isAccessible = true }.get(store) as SQLiteDatabase
    private fun initial(id: String) = DiagnosticCaptureStatus(id, DiagnosticTestData.PROCESS, "active", 1000, 1000000000)
    @Test fun encryptedStorageSeparateKeyReopenAndRecoveryKeepOnlyCommittedKnowledge() {
        val context = DiagnosticTestData.context()
        val store = DiagnosticStore(context, DiagnosticTestData.policy())
        val initial = initial("synthetic-open")
        val row = DiagnosticRow(7, null, JSONObject().put("sentinel", DiagnosticTestData.SENTINEL).toString())
        store.create(initial, DiagnosticTestData.config(), "{}", "{}")
        store.commit(initial.copy(receivedEvents = 1, acceptedEvents = 1, savedEvents = 1, savedBytes = row.bytes, lastCommittedSequence = 7), listOf(row))
        val finished = initial("synthetic-complete").copy(state = "complete", endedUtcMs = 2000, endedElapsedNs = 2000000000)
        store.create(finished, DiagnosticTestData.config(), "{}", "{}")
        db(store).beginTransaction()
        try {
            db(store).execSQL("INSERT INTO diagnostic_events VALUES (?,?,?)", arrayOf<Any>(initial.captureId, 8, DiagnosticTestData.SENTINEL))
            val rollback = context.getDatabasePath("polar-diagnostic.db-journal")
            if (rollback.exists()) assertFalse(String(rollback.readBytes(), Charsets.ISO_8859_1).contains(DiagnosticTestData.SENTINEL))
        } finally { db(store).endTransaction() }
        store.close()
        val bytes = context.getDatabasePath("polar-diagnostic.db").readBytes()
        assertFalse(String(bytes.take(16).toByteArray()).startsWith("SQLite format 3"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(DiagnosticTestData.SENTINEL))
        assertTrue(context.getSharedPreferences("polar-diagnostic-wrapped-key", 0).contains("ciphertext"))
        assertFalse(context.getSharedPreferences("wrapped-key", 0).contains("ciphertext"))
        val reopened = DiagnosticStore(context, DiagnosticTestData.policy())
        try {
            assertEquals(1, reopened.recoverInterrupted()); assertEquals(0, reopened.recoverInterrupted())
            val recovered = reopened.get(initial.captureId)!!
            assertEquals("interrupted", recovered.state); assertTrue(recovered.unknownTail)
            assertNull(recovered.receivedEvents); assertNull(recovered.acceptedEvents)
            assertNull(recovered.endedElapsedNs); assertEquals(1L, recovered.savedEvents)
            assertEquals(1L, recovered.unprocessedInputs); assertEquals(0L, recovered.knownLostEvents)
            assertEquals(7L, recovered.lastCommittedSequence)
            assertEquals(row.json, reopened.readInput(initial.captureId, 7)); assertNull(reopened.readInput(initial.captureId, 8))
            assertEquals("complete", reopened.get(finished.captureId)!!.state)
        } finally { reopened.close() }
    }
    @Test fun batchAndCheckpointRollbackTogetherAndForeignKeysPreventOrphanProcessing() {
        val store = DiagnosticStore(DiagnosticTestData.context(), DiagnosticTestData.policy())
        try {
            val initial = initial("rollback")
            store.create(initial, DiagnosticTestData.config(), "{}", "{}")
            db(store).execSQL("CREATE TRIGGER diagnostic_reject BEFORE INSERT ON diagnostic_events WHEN NEW.sequence=2 BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
            assertThrows(Exception::class.java) { store.commit(initial.copy(savedEvents = 2, lastCommittedSequence = 2),
                listOf(DiagnosticRow(1, null, "{}"), DiagnosticRow(2, null, "{}"))) }
            assertNull(store.readInput(initial.captureId, 1)); assertEquals(0L, store.get(initial.captureId)!!.savedEvents)
            assertNull(store.get(initial.captureId)!!.lastCommittedSequence)
            assertThrows(Exception::class.java) { store.commit(initial, listOf(DiagnosticRow(3, 99, "{}"))) }
            db(store).execSQL("DROP TRIGGER diagnostic_reject")
            store.commit(initial.copy(savedEvents = 1, lastCommittedSequence = 1), listOf(DiagnosticRow(1, null, "{}")))
            assertEquals("{}", store.readInput(initial.captureId, 1))
        } finally { store.close() }
    }
    @Test fun wrongThreadAndUnknownSchemaAreRejectedWithoutResettingDatabase() {
        val context = DiagnosticTestData.context()
        val store = DiagnosticStore(context, DiagnosticTestData.policy())
        val task = FutureTask<Boolean> { try { store.list(); false } catch (_: IllegalStateException) { true } }
        Thread(task).start(); assertTrue(task.get(5, TimeUnit.SECONDS))
        store.create(initial("preserved"), DiagnosticTestData.config(), "{}", "{}")
        db(store).execSQL("PRAGMA user_version=99"); store.close()
        val preserved = context.getDatabasePath("polar-diagnostic.db").readBytes()
        assertThrows(IllegalStateException::class.java) { DiagnosticStore(context, DiagnosticTestData.policy()) }
        assertArrayEquals(preserved, context.getDatabasePath("polar-diagnostic.db").readBytes())
    }
    @Test fun missingWrappedKeyDoesNotGenerateReplacementOverExistingDatabase() {
        val context = DiagnosticTestData.context()
        DiagnosticStore(context, DiagnosticTestData.policy()).close()
        val before = context.getDatabasePath("polar-diagnostic.db").readBytes()
        context.getSharedPreferences("polar-diagnostic-wrapped-key", 0).edit().clear().commit()
        assertThrows(IllegalStateException::class.java) { DiagnosticStore(context, DiagnosticTestData.policy()) }
        assertArrayEquals(before, context.getDatabasePath("polar-diagnostic.db").readBytes())
        assertFalse(context.getSharedPreferences("polar-diagnostic-wrapped-key", 0).contains("ciphertext"))
    }
}
