package com.veymur.recorder.core

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class DiagnosticJournalTest {
    private fun <T> await(f: CompletableFuture<T>) = DiagnosticTestData.await(f)
    private fun close(j: DiagnosticJournal) { runCatching { await(j.closeAsync()) } }
    private fun code(f: CompletableFuture<*>): String = (assertThrows(ExecutionException::class.java) { await(f) }.cause as DiagnosticStorageError).code
    @Test fun stopDrainsQueuedAndLateProcessingWithoutAcceptingNewInputsThenReopensAndDeletes() {
        val context = DiagnosticTestData.context()
        val journal = DiagnosticJournal(context, DiagnosticTestData.policy())
        val capture = DiagnosticTestData.start(journal)
        val input = DiagnosticTestData.input(capture, 1)
        capture.offerInput(input)
        val stopped = capture.stop(); assertFalse(stopped.isDone)
        capture.offerInput(DiagnosticTestData.input(capture, 2))
        capture.offerProcessing(DiagnosticTestData.processing(input))
        val status = await(stopped)
        assertSame(stopped, capture.stop()); assertEquals("complete", status.state)
        assertEquals(1L, status.receivedEvents); assertEquals(1L, status.acceptedEvents)
        assertEquals(1L, status.savedEvents); assertEquals(1L, status.savedProcessing)
        assertEquals(0L, status.knownLostEvents); assertEquals(0L, status.knownLostProcessing)
        assertEquals(0L, status.unprocessedInputs); assertFalse(status.unknownTail)
        assertEquals(1L, status.lastCommittedSequence); assertTrue(status.savedBytes > 0)
        await(journal.closeAsync())
        val reopened = DiagnosticJournal(context, DiagnosticTestData.policy())
        try {
            assertEquals(0, await(reopened.ready)); assertEquals("complete", await(reopened.listCaptures()).single().state)
            val restored = JSONObject(await(reopened.readInput(capture.captureId, 1))!!)
            assertFalse(restored.getJSONObject("payload").getBoolean("powered"))
            assertNull(await(reopened.readInput(capture.captureId, 2)))
            assertTrue(await(reopened.deleteCapture(capture.captureId)))
            assertFalse(await(reopened.deleteCapture(capture.captureId)))
            assertNull(await(reopened.readInput(capture.captureId, 1))); assertTrue(await(reopened.listCaptures()).isEmpty())
        } finally { close(reopened) }
    }
    @Test fun recordAndByteBoundsStopIncompleteAndKeepKnownCounts() {
        for (bytes in listOf(2L * 1024 * 1024, 1024L)) {
            val policy = DiagnosticTestData.policy().copy(queueRecords = 2, batchRecords = 2, queueBytes = bytes)
            val journal = DiagnosticJournal(DiagnosticTestData.context(), policy)
            try {
                val capture = DiagnosticTestData.start(journal)
                capture.offerInput(DiagnosticTestData.input(capture, 1))
                capture.offerInput(DiagnosticTestData.input(capture, 2))
                if (bytes > 1024) capture.offerInput(DiagnosticTestData.input(capture, 3))
                val status = await(capture.stop())
                assertEquals("incomplete", status.state); assertEquals("queue_overflow", status.lastErrorCode)
                assertEquals(1L, status.knownLostEvents); assertEquals(0L, status.knownLostProcessing)
                assertEquals(if (bytes == 1024L) 1L else 2L, status.savedEvents)
                assertEquals(status.savedEvents, status.unprocessedInputs)
                assertEquals(status.savedEvents + status.knownLostEvents, status.receivedEvents)
                assertFalse(status.unknownTail)
            } finally { close(journal) }
        }
    }
    @Test fun malformedSerializationAndRecordAndCaptureLimitsAreVisibleWithoutPlaintextLogging() {
        val cases = listOf(
            Triple(DiagnosticTestData.policy(), "{", "serialization_failed"),
            Triple(DiagnosticTestData.policy().copy(maxRecordChars = 1), "{\"powered\":false}", "size_limit"),
            Triple(DiagnosticTestData.policy().copy(maxCaptureBytes = 1), "{\"powered\":false}", "size_limit"))
        for ((policy, json, error) in cases) {
            val journal = DiagnosticJournal(DiagnosticTestData.context(), policy)
            try {
                val capture = DiagnosticTestData.start(journal)
                DiagnosticTestData.pair(capture, 1, json)
                val status = await(capture.stop())
                assertEquals("incomplete", status.state); assertEquals(error, status.lastErrorCode)
                assertEquals(0L, status.savedEvents); assertEquals(1L, status.knownLostEvents)
                assertEquals(0L, status.savedProcessing)
                assertEquals(if (policy.maxRecordChars == 1) 0L else 1L, status.knownLostProcessing)
                assertNull(await(journal.readInput(capture.captureId, 1)))
                assertTrue(status.checkpointPersisted)
            } finally { close(journal) }
        }
    }
    @Test fun lowStorageReserveStopsOnlyDiagnosticsAndRecorderDatabaseStillCommits() {
        val context = DiagnosticTestData.context()
        val free = AtomicLong(Long.MAX_VALUE)
        val journal = DiagnosticJournal(context, DiagnosticTestData.policy().copy(minFreeBytes = 64 * 1024 * 1024), freeBytes = { free.get() })
        val recorder = Journal(context)
        try {
            val id = recorder.begin("SYNTHETIC", false)
            val capture = DiagnosticTestData.start(journal)
            free.set(0); DiagnosticTestData.pair(capture, 1)
            val status = await(capture.stop())
            assertEquals("incomplete", status.state); assertEquals("low_storage", status.lastErrorCode)
            assertEquals(1L, status.knownLostEvents); assertEquals(1L, status.knownLostProcessing)
            recorder.append(id, "hr", JSONObject().put("bpm", 82))
            recorder.transition(id, "finish", "completed", 1000)
            assertEquals(id, recorder.latest())
            assertTrue(recorder.export(id).exists())
        } finally { recorder.close(); close(journal) }
    }
    @Test fun durationLimitRunsWithoutCallbacksAndMissingProcessingIsNotInventedAsReceivedLoss() {
        val journal = DiagnosticJournal(DiagnosticTestData.context(), DiagnosticTestData.policy().copy(maxDurationMs = 100))
        try {
            val capture = DiagnosticTestData.start(journal)
            DiagnosticTestData.waitUntil { capture.status.state != "active" }
            val status = await(capture.stop())
            assertEquals("incomplete", status.state); assertEquals("duration_limit", status.lastErrorCode)
            assertEquals(0L, status.receivedEvents); assertEquals(0L, status.knownLostEvents)
        } finally { close(journal) }
        val delayed = DiagnosticJournal(DiagnosticTestData.context(), DiagnosticTestData.policy())
        try {
            val capture = DiagnosticTestData.start(delayed)
            capture.offerInput(DiagnosticTestData.input(capture, 1)); await(delayed.flush(capture))
            assertEquals(1L, await(delayed.listCaptures()).single().unprocessedInputs)
            val status = await(capture.stop())
            assertEquals("incomplete", status.state); assertEquals("processing_timeout", status.lastErrorCode)
            assertEquals(1L, status.unprocessedInputs); assertEquals(0L, status.receivedProcessing)
            assertEquals(0L, status.knownLostProcessing); assertFalse(status.unknownTail)
        } finally { close(delayed) }
    }
    @Test fun activeDeletionRetentionAndSecondWriterAreExplicitlyRejected() {
        val context = DiagnosticTestData.context()
        val journal = DiagnosticJournal(context, DiagnosticTestData.policy().copy(maxCaptures = 1))
        try {
            val capture = DiagnosticTestData.start(journal)
            assertEquals("capture_active", code(journal.deleteCapture(capture.captureId)))
            val second = DiagnosticJournal(context, DiagnosticTestData.policy())
            assertEquals("already_open", code(second.ready)); close(second)
            await(capture.stop())
            assertEquals("retention_limit", code(journal.startCapture(DiagnosticTestData.PROCESS, DiagnosticTestData.config(), "{}")))
            assertTrue(await(journal.deleteCapture(capture.captureId)))
            await(DiagnosticTestData.start(journal).stop())
        } finally { close(journal) }
    }
    @Test fun actualSqliteFullPreservesCommittedPrefixAndStopsWithExplicitWriteError() {
        val context = DiagnosticTestData.context()
        val journal = DiagnosticJournal(context, DiagnosticTestData.policy().copy(maxDatabaseBytes = 1024 * 1024))
        val capture = DiagnosticTestData.start(journal)
        val payload = JSONObject().put("sentinel", DiagnosticTestData.SENTINEL).put("value", "x".repeat(60000)).toString()
        try {
            var sequence = 0L
            while (capture.status.lastErrorCode == null && sequence < 100) {
                DiagnosticTestData.pair(capture, ++sequence, payload)
                runCatching { await(journal.flush(capture)) }
            }
            assertEquals("write_failed", capture.status.lastErrorCode)
            assertTrue(capture.status.savedEvents > 0); assertTrue(capture.status.knownLostEvents > 0)
            assertEquals("write_failed", code(capture.stop()))
            assertTrue(context.getDatabasePath("polar-diagnostic.db").length() <= 1024 * 1024)
        } finally { close(journal) }
        val reopened = DiagnosticJournal(context, DiagnosticTestData.policy().copy(maxDatabaseBytes = 1024 * 1024))
        try {
            await(reopened.ready)
            val status = await(reopened.listCaptures()).single()
            assertTrue(status.state in setOf("incomplete", "interrupted"))
            assertEquals(capture.status.savedEvents, status.savedEvents)
            assertNotNull(await(reopened.readInput(capture.captureId, 1)))
        } finally { close(reopened) }
    }
    @Test fun parallelSdkOffersPersistEveryInputAndProcessingOnIndependentWriter() {
        val journal = DiagnosticJournal(DiagnosticTestData.context(), DiagnosticTestData.policy().copy(queueRecords = 4096))
        try {
            val capture = DiagnosticTestData.start(journal, PolarInputBoundary.processRunId)
            lateinit var boundary: PolarInputBoundary
            boundary = PolarInputBoundary({ System.currentTimeMillis() }, { SystemClock.elapsedRealtimeNanos() }) {
                boundary.process(it, { null }) { true }
            }
            boundary.setSink(capture)
            val callbacks = PolarSdkCallbacks(boundary)
            val sample = com.polar.sdk.api.model.PolarHrData.PolarHrSample(0, 0, 0, emptyList(), emptyList(), false, false, true)
            val start = java.util.concurrent.CountDownLatch(1)
            val done = java.util.concurrent.CountDownLatch(8)
            val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            repeat(8) {
                Thread {
                    try { start.await(); repeat(40) { callbacks.hrNotificationReceived("SYNTHETIC", sample) } }
                    catch (error: Throwable) { failures.add(error) }
                    finally { done.countDown() }
                }.start()
            }
            start.countDown(); assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            boundary.setSink(null)
            val status = await(capture.stop())
            assertEquals("complete", status.state); assertEquals(320L, status.receivedEvents)
            assertEquals(320L, status.savedEvents); assertEquals(320L, status.savedProcessing)
            assertEquals(0L, status.knownLostEvents); assertEquals(0L, status.knownLostProcessing)
            val last = JSONObject(await(journal.readInput(capture.captureId, status.lastCommittedSequence!!))!!)
            assertEquals(0, last.getJSONObject("payload").getInt("hr"))
        } finally { close(journal) }
    }

    @Test fun terminalCheckpointFailureIsNeverReportedAsSuccessfulStop() {
        val context = DiagnosticTestData.context()
        fun db(store: DiagnosticStore) = DiagnosticStore::class.java.getDeclaredField("db").apply { isAccessible = true }
            .get(store) as net.zetetic.database.sqlcipher.SQLiteDatabase
        val setup = DiagnosticStore(context, DiagnosticTestData.policy())
        db(setup).execSQL("CREATE TRIGGER diagnostic_reject_checkpoint BEFORE UPDATE ON diagnostic_captures BEGIN SELECT RAISE(ABORT,'synthetic checkpoint failure'); END")
        setup.close()
        val journal = DiagnosticJournal(context, DiagnosticTestData.policy())
        val capture = DiagnosticTestData.start(journal)
        try {
            DiagnosticTestData.pair(capture, 1)
            assertEquals("write_failed", code(journal.flush(capture)))
            assertEquals("write_failed", code(capture.stop()))
            assertEquals("incomplete", capture.status.state)
            assertFalse(capture.status.checkpointPersisted); assertTrue(capture.status.unknownTail)
            assertEquals(0L, capture.status.savedEvents); assertEquals(1L, capture.status.knownLostEvents)
            assertNull(await(journal.readInput(capture.captureId, 1)))
        } finally { close(journal) }
        val repaired = DiagnosticStore(context, DiagnosticTestData.policy())
        assertEquals("active", repaired.get(capture.captureId)!!.state)
        assertEquals(0L, repaired.get(capture.captureId)!!.savedEvents)
        db(repaired).execSQL("DROP TRIGGER diagnostic_reject_checkpoint"); repaired.close()
        val recovered = DiagnosticJournal(context, DiagnosticTestData.policy())
        try {
            assertEquals(1, await(recovered.ready))
            val status = await(recovered.listCaptures()).single()
            assertEquals("interrupted", status.state); assertTrue(status.unknownTail)
            assertNull(status.receivedEvents); assertEquals(0L, status.savedEvents)
            assertEquals(0L, status.knownLostEvents) // The failed final transaction never persisted its in-memory loss count.
        } finally { close(recovered) }
    }

}
