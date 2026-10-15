package com.veymur.recorder.core

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DiagnosticExportTest {
    private val host get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun context(): Context {
        val base = DiagnosticTestData.context()
        val cache = File(host.cacheDir, "d5-${UUID.randomUUID()}").also { it.mkdirs() }
        return object : ContextWrapper(base) { override fun getCacheDir() = cache }
    }
    private fun <T> await(f: CompletableFuture<T>) = DiagnosticTestData.await(f)
    private fun code(f: CompletableFuture<*>) = (assertThrows(ExecutionException::class.java) { await(f) }.cause as DiagnosticStorageError).code
    private fun manifest(file: File) = ZipFile(file).use { JSONObject(it.getInputStream(it.getEntry("manifest.json")).bufferedReader().readText()) }
    private fun save(file: File, name: String) { file.copyTo(File(host.getExternalFilesDir(null), name), overwrite = true) }
    private fun close(files: DiagnosticExports, journal: DiagnosticJournal) {
        await(files.closeAsync())
        try { await(journal.closeAsync()) } catch (error: ExecutionException) { assertEquals("write_failed", (error.cause as DiagnosticStorageError).code) }
    }
    @Test fun actualSdkCallbacksRoundTripExactOwnedJsonIncludingFloatBitsArraysErrorsAndFirmware() {
        PolarInputBoundaryTest().everySdkCallbackAndSearchSignalUsesOwnedLosslessPayloadBeforeWorker()
        val originals = JSONObject(File(host.getExternalFilesDir(null), "polar-d2-synthetic-contract.json").readText())
        var elapsed = 1_000_000_000L
        val ctx = context(); val journal = DiagnosticJournal(ctx, DiagnosticTestData.policy(), { 1000 }, { elapsed })
        val files = DiagnosticExports(ctx, journal)
        try {
            val capture = await(journal.startCapture(PolarInputBoundary.processRunId, DiagnosticTestData.config(), "{}"))
            val events = originals.getJSONArray("events"); val processing = originals.getJSONArray("processing")
            val expected = mutableListOf<String>()
            repeat(events.length()) { index ->
                val j = events.getJSONObject(index)
                val event = PolarInputEvent(capture.captureId, j.getString("process_run_id"), j.getLong("sequence"),
                    j.getLong("received_utc_ms"), j.getString("received_elapsed_ns").toLong(), j.getString("source"),
                    j.getString("event_type"), j.getString("callback_variant"), if (j.isNull("device_id")) null else j.getString("device_id"), j.getJSONObject("payload").toString())
                capture.offerInput(event)
                val p = processing.getJSONObject(index)
                capture.offerProcessing(PolarInputProcessing(event, p.getLong("processing_sequence"), p.getLong("processed_utc_ms"),
                    p.getString("processed_elapsed_ns").toLong(), null, p.getString("outcome"), null))
                expected.add(event.toJson().toString())
            }
            elapsed = 20_000_000_000L
            assertEquals("complete", await(capture.stop()).state)
            val zip = await(files.export(capture.captureId)); save(zip, "polar-d5-roundtrip.zip")
            ZipFile(zip).use { archive ->
                assertEquals(setOf("manifest.json", "events.jsonl", "processing.jsonl"), archive.entries().asSequence().map { it.name }.toSet())
                assertEquals(expected, archive.getInputStream(archive.getEntry("events.jsonl")).bufferedReader().readLines())
            }
            assertEquals(2, manifest(zip).getJSONArray("firmware").length())
            assertEquals(21, manifest(zip).getJSONObject("files").getJSONObject("events.jsonl").getInt("records"))
        } finally { close(files, journal) }
    }
    @Test fun emptyExportRejectsActiveCaptureExpiryAndDeletionRemoveDatabaseAndTemporaryCopies() {
        val ctx = context(); val journal = DiagnosticJournal(ctx, DiagnosticTestData.policy())
        var now = System.currentTimeMillis(); val files = DiagnosticExports(ctx, journal, { now })
        try {
            val capture = DiagnosticTestData.start(journal)
            assertEquals("capture_active", code(files.export(capture.captureId)))
            assertEquals("capture_active", code(files.delete(capture.captureId)))
            await(capture.stop())
            val zip = await(files.export(capture.captureId)); save(zip, "polar-d5-empty.zip")
            assertEquals(0, manifest(zip).getJSONObject("checkpoint").getInt("saved_events"))
            val partial = File(zip.parentFile, "stale.part").apply { writeText("synthetic") }
            now = zip.lastModified() + DiagnosticExports.TTL_MS + 1
            await(files.cleanup()); assertFalse(zip.exists()); assertFalse(partial.exists())
            val fresh = await(files.export(capture.captureId))
            val blocked = File(fresh.parentFile, "${capture.captureId}-blocked.part").apply { mkdirs() }
            File(blocked, "child").writeText("synthetic")
            assertEquals("export_cleanup_failed", code(files.delete(capture.captureId)))
            assertEquals(1, await(journal.listCaptures()).size)
            blocked.deleteRecursively()
            assertTrue(await(files.delete(capture.captureId))); assertFalse(fresh.exists())
            assertTrue(await(journal.listCaptures()).isEmpty()); assertFalse(await(files.delete(capture.captureId)))
            assertEquals("invalid_capture_id", code(files.export("../escape")))
        } finally { close(files, journal) }
    }
    @Test fun interruptedExportPreservesUnknownEndAndMissingProcessing() {
        val ctx = context(); val id = UUID.randomUUID().toString()
        val store = DiagnosticStore(ctx, DiagnosticTestData.policy())
        val event = PolarInputEvent(id, DiagnosticTestData.PROCESS, 7, 1000, 1_000_000_001, "polar_sdk", "bluetooth", "blePowerStateChanged", null, "{\"powered\":false}")
        val row = DiagnosticRow(7, null, event.toJson().toString())
        val initial = DiagnosticCaptureStatus(id, DiagnosticTestData.PROCESS, "active", 1000, 1_000_000_000)
        store.create(initial, DiagnosticTestData.config(), "{}", "{}")
        store.commit(initial.copy(savedEvents = 1, lastCommittedSequence = 7, savedBytes = row.bytes), listOf(row)); store.close()
        val journal = DiagnosticJournal(ctx, DiagnosticTestData.policy()); val files = DiagnosticExports(ctx, journal)
        try {
            assertEquals(1, await(journal.ready))
            val zip = await(files.export(id)); save(zip, "polar-d5-interrupted.zip")
            val manifest = manifest(zip)
            assertEquals("interrupted", manifest.getString("state")); assertTrue(manifest.isNull("ended_elapsed_ns"))
            assertTrue(manifest.getJSONObject("checkpoint").getBoolean("unknown_tail"))
            assertTrue(manifest.getJSONObject("checkpoint").isNull("received_events"))
            assertEquals(1, manifest.getJSONObject("checkpoint").getInt("unprocessed_inputs"))
        } finally { close(files, journal) }
    }
    @Test fun largePagedExportCanRunAlongsideAnotherCaptureAndLeasePreventsDeletion() {
        val ctx = context(); val journal = DiagnosticJournal(ctx, DiagnosticTestData.policy()); val files = DiagnosticExports(ctx, journal)
        try {
            val capture = DiagnosticTestData.start(journal)
            repeat(2000) { index ->
                val n = index + 1L
                val event = DiagnosticTestData.input(capture, n, JSONObject().put("key", "model").put("value", "Ω\n".repeat(500)).toString())
                    .copy(eventType = "device_info", callbackVariant = "disInformationReceived.key", deviceId = "D5-SYNTHETIC")
                capture.offerInput(event); capture.offerProcessing(DiagnosticTestData.processing(event))
                if (n % 32 == 0L) await(journal.flush(capture))
            }
            assertEquals("complete", await(capture.stop()).state)
            await(journal.beginExport(capture.captureId))
            assertEquals("export_active", code(journal.deleteCapture(capture.captureId)))
            assertEquals(16, await(journal.exportPage(capture.captureId, false, 0)).size)
            await(journal.endExport(capture.captureId))
            val active = DiagnosticTestData.start(journal)
            val exporting = files.export(capture.captureId)
            repeat(100) { DiagnosticTestData.pair(active, it + 1L); if (it % 32 == 31) await(journal.flush(active)) }
            val zip = await(exporting); save(zip, "polar-d5-large.zip")
            assertTrue(manifest(zip).getJSONObject("files").getJSONObject("events.jsonl").getLong("bytes") > 4 * 1024 * 1024)
            assertEquals("active", active.status.state); assertEquals(0L, active.status.knownLostEvents)
            assertEquals("complete", await(active.stop()).state)
        } finally { close(files, journal) }
    }
    @Test fun failedFinalCheckpointExportsHonestAvailablePrefixAndFailedOutputNeverReturnsFile() {
        val ctx = context(); val setup = DiagnosticStore(ctx, DiagnosticTestData.policy())
        val db = DiagnosticStore::class.java.getDeclaredField("db").apply { isAccessible = true }.get(setup) as SQLiteDatabase
        db.execSQL("CREATE TRIGGER d5_reject_final BEFORE UPDATE ON diagnostic_captures WHEN json_extract(NEW.checkpoint_json, '$.state')='complete' BEGIN SELECT RAISE(ABORT,'synthetic final failure'); END")
        setup.close()
        val journal = DiagnosticJournal(ctx, DiagnosticTestData.policy()); val files = DiagnosticExports(ctx, journal)
        try {
            val capture = DiagnosticTestData.start(journal); DiagnosticTestData.pair(capture, 1); await(journal.flush(capture))
            assertEquals("write_failed", code(capture.stop()))
            val zip = await(files.export(capture.captureId)); save(zip, "polar-d5-checkpoint-failed.zip")
            assertEquals("incomplete", manifest(zip).getString("state"))
            assertFalse(manifest(zip).getJSONObject("checkpoint").getBoolean("checkpoint_persisted"))
            assertTrue(await(files.delete(capture.captureId)))
        } finally { close(files, journal) }
        val ctx2 = context(); val journal2 = DiagnosticJournal(ctx2, DiagnosticTestData.policy()); val files2 = DiagnosticExports(ctx2, journal2)
        try {
            val capture = DiagnosticTestData.start(journal2); await(capture.stop())
            val parent = File(ctx2.cacheDir, "exports").apply { mkdirs() }
            File(parent, "polar").writeText("synthetic file blocking directory creation")
            assertEquals("export_directory_failed", code(files2.export(capture.captureId)))
            assertEquals(1, await(journal2.listCaptures()).size)
        } finally { close(files2, journal2) }
    }
}
