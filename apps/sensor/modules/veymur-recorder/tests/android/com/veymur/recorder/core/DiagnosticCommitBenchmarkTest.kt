package com.veymur.recorder.core

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic comparison; it does not assert a hardware latency budget. Output contains timings only. */
@RunWith(AndroidJUnit4::class)
class DiagnosticCommitBenchmarkTest {
    @Test fun compareCommitPoliciesWithSparseOffers() {
        val rows = JSONArray()
        for ((batch, interval) in listOf(1 to 0L, 32 to 100L, 64 to 250L)) {
            val journal = DiagnosticJournal(DiagnosticTestData.context(), DiagnosticTestData.policy().copy(
                queueRecords = 1024, batchRecords = batch, commitIntervalMs = interval))
            try {
                val capture = DiagnosticTestData.start(journal)
                val offers = mutableListOf<Long>()
                val start = SystemClock.elapsedRealtimeNanos()
                repeat(100) { index ->
                    val input = DiagnosticTestData.input(capture, index + 1L)
                    val processing = DiagnosticTestData.processing(input)
                    val before = SystemClock.elapsedRealtimeNanos()
                    capture.offerInput(input); capture.offerProcessing(processing)
                    offers.add(SystemClock.elapsedRealtimeNanos() - before)
                    SystemClock.sleep(20)
                }
                DiagnosticTestData.waitUntil { capture.status.savedEvents == 100L && capture.status.savedProcessing == 100L }
                val status = DiagnosticTestData.await(capture.stop())
                assertEquals("complete", status.state); assertEquals(0L, status.knownLostEvents)
                val metrics = journal.metrics
                rows.put(JSONObject().put("batch_records", batch).put("commit_interval_ms", interval)
                    .put("input_count", 100).put("processing_count", 100).put("commits", metrics.commits)
                    .put("mean_commit_ms", metrics.totalCommitNs / metrics.commits / 1_000_000.0)
                    .put("max_commit_ms", metrics.maxCommitNs / 1_000_000.0)
                    .put("max_offer_to_commit_ms", metrics.maxOfferToCommitNs / 1_000_000.0)
                    .put("offer_pair_p95_us", offers.sorted()[94] / 1000.0)
                    .put("total_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0))
            } finally { DiagnosticTestData.await(journal.closeAsync()) }
        }
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        File(host.getExternalFilesDir(null), "polar-d3-commit-comparison.json").writeText(rows.toString(2))
    }
}
