package com.veymur.recorder.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** External two-phase test. Kill only a dedicated synthetic test host after the ready marker. */
@RunWith(AndroidJUnit4::class)
class DiagnosticProcessDeathTest {
    @Test fun persistedPrefixAfterExternalProcessDeath() {
        val phase = InstrumentationRegistry.getArguments().getString("diagnosticCrashPhase")
        assumeTrue("Requires external prepare/force-stop/verify protocol", phase == "prepare" || phase == "verify")
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(host.getExternalFilesDir(null), "polar-d3-death-ready.json")
        if (phase == "prepare") {
            val prefix = "d3-death-${UUID.randomUUID()}-"
            val journal = DiagnosticJournal(DiagnosticTestData.context(prefix), DiagnosticTestData.policy())
            val capture = DiagnosticTestData.start(journal)
            DiagnosticTestData.pair(capture, 1)
            val committed = DiagnosticTestData.await(journal.flush(capture))
            assertEquals(1L, committed.savedEvents); assertEquals(1L, committed.savedProcessing)
            repeat(10) { capture.offerInput(DiagnosticTestData.input(capture, it + 2L)) }
            assertEquals(11L, capture.status.receivedEvents); assertEquals(1L, capture.status.savedEvents)
            marker.writeText(JSONObject().put("prefix", prefix).put("capture_id", capture.captureId).toString())
            CountDownLatch(1).await(120, TimeUnit.SECONDS)
            fail("External driver did not kill the prepared process")
        } else {
            val prepared = JSONObject(marker.readText())
            val journal = DiagnosticJournal(DiagnosticTestData.context(prepared.getString("prefix")), DiagnosticTestData.policy())
            try {
                assertEquals(1, DiagnosticTestData.await(journal.ready))
                val status = DiagnosticTestData.await(journal.listCaptures()).single()
                assertEquals(prepared.getString("capture_id"), status.captureId)
                assertEquals("interrupted", status.state); assertTrue(status.unknownTail)
                assertNull(status.receivedEvents); assertNull(status.acceptedEvents)
                assertEquals(1L, status.savedEvents); assertEquals(1L, status.savedProcessing)
                assertEquals(0L, status.knownLostEvents); assertEquals(0L, status.knownLostProcessing)
                assertNotNull(DiagnosticTestData.await(journal.readInput(status.captureId, 1)))
                assertNull(DiagnosticTestData.await(journal.readInput(status.captureId, 2)))
            } finally { DiagnosticTestData.await(journal.closeAsync()) }
        }
    }
}
