package com.veymur.recorder.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veymur.recorder.core.localdata.aggregates.SessionAggregates
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated SQLCipher failures; never fills the emulator filesystem or touches user data. */
@RunWith(AndroidJUnit4::class)
class StorageFailureRecoveryTest {
    @Test fun failedFinishAndFailedRecoveryRetryNeverExtendCommittedTail() {
        val ctx = LocalValidationFixture.context("local-validation-${UUID.randomUUID()}-")
        var ms = 1000L
        var base = "first-run"
        fun stamp() = JournalStamp(1700000000000 + ms, ms * 1_000_000, base, "UTC")
        val id: String
        val saved: SessionAggregates
        Journal(ctx, ::stamp).use { j ->
            val db = LocalValidationFixture.db(j)
            val session = RecorderSession(j, RecordingClock { ms })
            id = session.start("SYNTHETIC", false, JSONObject())
            LocalValidationFixture.hr(j, id, stamp(), 80)
            ms = 2000; LocalValidationFixture.hr(j, id, stamp(), 100)
            ms = 3000; LocalValidationFixture.hr(j, id, stamp(), 120)
            val prefix = LocalValidationFixture.stream(j, id, "hr")
            val digest = LocalValidationFixture.digest(prefix)
            db.execSQL("CREATE TRIGGER reject_finish BEFORE UPDATE ON sessions WHEN NEW.state='completed' BEGIN SELECT RAISE(ABORT, 'injected commit failure'); END")
            ms = 4000
            assertThrows(Exception::class.java) { session.finish(50) }
            assertEquals("interrupted", session.state); assertEquals(id, session.id)
            assertEquals(3000L, session.clock.activeMs())
            db.execSQL("DROP TRIGGER reject_finish")
            db.execSQL("CREATE TRIGGER reject_recovery BEFORE UPDATE ON sessions WHEN NEW.state='interrupted' BEGIN SELECT RAISE(ABORT, 'injected recovery failure'); END")
            ms = 100000
            assertThrows(Exception::class.java) { session.finish(50) }
            assertEquals(id, session.id)
            assertTrue(LocalValidationFixture.stream(j, id, "events").none { it["kind"] == "interrupted" || it["kind"] == "finish" })
            db.execSQL("DROP TRIGGER reject_recovery")
            ms = 200000
            assertEquals(id, session.finish(50)); assertEquals(id, session.finish(50))
            assertNull(session.id); assertEquals("interrupted", session.state)
            assertEquals(digest, LocalValidationFixture.digest(LocalValidationFixture.stream(j, id, "hr")))
            assertEquals(1, LocalValidationFixture.stream(j, id, "events").count { it["kind"] == "interrupted" })
            saved = j.recalculate(id, LocalValidationFixture.parameters).aggregates!!
            assertTrue(saved.interrupted_tail_unknown)
            assertEquals(2000.0, saved.active_duration_ms, 0.0)
            assertEquals(2000.0, saved.hr_covered_duration_ms, 0.0)
            assertEquals(90.0, saved.average_hr_bpm!!, 0.0)
        }
        ms = 900000; base = "second-run"
        Journal(ctx, ::stamp).use { j ->
            assertEquals(0, j.recoverInterrupted())
            assertEquals(saved, j.recalculate(id, LocalValidationFixture.parameters).aggregates)
        }
    }

    @Test fun databaseFullRollsBackAndReopenPreservesOtherHistoryAndCommittedPrefix() {
        val ctx = LocalValidationFixture.context("local-validation-${UUID.randomUUID()}-")
        var ms = 1000L
        fun stamp() = JournalStamp(1700000000000 + ms, ms * 1_000_000, "full-run", "UTC")
        val id: String; val other: String; val digest: String
        Journal(ctx, ::stamp).use { j ->
            val db = LocalValidationFixture.db(j)
            other = j.begin("OTHER", false)
            j.transition(other, "finish", "completed", 0)
            id = j.begin("SYNTHETIC", false)
            LocalValidationFixture.hr(j, id, stamp(), 80)
            ms = 2000; LocalValidationFixture.hr(j, id, stamp(), 100)
            digest = LocalValidationFixture.digest(LocalValidationFixture.stream(j, id, "hr"))
            fun scalar(sql: String) = db.rawQuery(sql, null).use { check(it.moveToFirst()); it.getLong(0) }
            val maximum = scalar("PRAGMA max_page_count")
            val pages = scalar("PRAGMA page_count")
            assertEquals(pages, scalar("PRAGMA max_page_count=$pages"))
            try {
                val error = assertThrows(Exception::class.java) {
                    j.append(id, "storage_probe", JSONObject().put("test_payload", "x".repeat(2 * 1024 * 1024)))
                }
                assertEquals("SQLiteFullException", error.javaClass.simpleName)
                assertEquals(digest, LocalValidationFixture.digest(LocalValidationFixture.stream(j, id, "hr")))
                assertTrue(LocalValidationFixture.stream(j, id, "events").none { it["kind"] == "storage_probe" })
            } finally { scalar("PRAGMA max_page_count=$maximum") }
        }
        ms = 900000
        Journal(ctx, ::stamp).use { j ->
            assertEquals(1, j.recoverInterrupted()); assertEquals(0, j.recoverInterrupted())
            assertEquals(digest, LocalValidationFixture.digest(LocalValidationFixture.stream(j, id, "hr")))
            val a = j.recalculate(id, LocalValidationFixture.parameters).aggregates!!
            assertTrue(a.interrupted_tail_unknown); assertEquals(1000.0, a.active_duration_ms, 0.0)
            assertEquals(1000.0, a.hr_covered_duration_ms, 0.0); assertEquals(80.0, a.average_hr_bpm!!, 0.0)
            val history = LocalValidationFixture.op(j, "list", "limit" to 100, "before" to null)["items"] as List<*>
            assertEquals(setOf(id, other), history.map { (it as Map<*,*>)["id"] }.toSet())
            val otherSession = history.map { it as Map<*,*> }.single { it["id"] == other }
            assertEquals("completed", otherSession["state"])
        }
    }
}
