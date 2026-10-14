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
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class RecorderSessionTest {
    private fun fixture(action: (Journal, SQLiteDatabase) -> Unit) {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "stage5-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(host) {
            override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name)
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + name, mode)
            override fun getCacheDir() = File(baseContext.cacheDir, "exports/$prefix").also { it.mkdirs() }
        }
        val journal = Journal(context)
        try {
            val db = Journal::class.java.getDeclaredField("db").apply { isAccessible = true }.get(journal) as SQLiteDatabase
            action(journal, db)
        } finally { journal.close() }
    }
    private fun count(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
    @Test fun repeatedCommandsAreIdempotentAndPauseTimeExcluded() = fixture { journal, db ->
        var now = 100L
        val session = RecorderSession(journal, RecordingClock { now })
        assertEquals("INVALID_STATE", assertThrows(RecorderError::class.java) { session.pause() }.code)
        assertThrows(RecorderError::class.java) { session.resume() }
        assertThrows(RecorderError::class.java) { session.finish(50) }
        val id = session.start("SYNTHETIC", false, JSONObject())
        assertThrows(RecorderError::class.java) { session.start("SYNTHETIC", false, JSONObject()) }
        now = 1100; session.pause(); now = 9100; session.pause()
        assertEquals(1000L, session.clock.activeMs())
        session.resume(); session.resume(); now = 10100
        assertEquals(id, session.finish(50)); assertEquals(id, session.finish(50))
        assertEquals("completed", session.state)
        assertNull(session.id)
        listOf("pause", "resume", "finish").forEach { kind -> assertEquals(1, count(db, "SELECT count(*) FROM records WHERE kind='$kind'")) }
        ZipFile(journal.export(id)).use { zip ->
            val meta = JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
            assertEquals("completed", meta.getString("state")); assertEquals(2000L, meta.getLong("active_ms"))
        }
    }
    @Test fun failedStartRollsBackSessionAndConfigurationAndCanRetry() = fixture { journal, db ->
        val session = RecorderSession(journal, RecordingClock { 100L })
        db.execSQL("CREATE TRIGGER reject_configuration BEFORE INSERT ON records WHEN NEW.kind='configuration' BEGIN SELECT RAISE(ABORT, 'injected start failure'); END")
        assertThrows(Exception::class.java) { session.start("SYNTHETIC", false, JSONObject()) }
        assertNull(session.id); assertEquals("preparation", session.state)
        assertEquals(0, count(db, "SELECT count(*) FROM sessions")); assertEquals(0, count(db, "SELECT count(*) FROM records"))
        db.execSQL("DROP TRIGGER reject_configuration")
        assertNotNull(session.start("SYNTHETIC", false, JSONObject()))
    }
    @Test fun failedFinishDoesNotReportCompletedOrPersistFinishEventAndCanRetry() = fixture { journal, db ->
        val session = RecorderSession(journal, RecordingClock { 100L })
        val id = session.start("SYNTHETIC", false, JSONObject())
        db.execSQL("CREATE TRIGGER reject_finish BEFORE UPDATE ON sessions WHEN NEW.state='completed' BEGIN SELECT RAISE(ABORT, 'injected finish failure'); END")
        assertThrows(Exception::class.java) { session.finish(50) }
        assertEquals(id, session.id); assertEquals("recording", session.state)
        assertEquals(0, count(db, "SELECT count(*) FROM records WHERE kind='finish'"))
        assertEquals(0, count(db, "SELECT count(*) FROM sessions WHERE state='completed'"))
        session.failed(); assertEquals("interrupted", session.state)
        db.execSQL("DROP TRIGGER reject_finish")
        assertEquals(id, session.finish(50)); assertEquals("completed", session.state)
        assertEquals(1, count(db, "SELECT count(*) FROM records WHERE kind='finish'"))
    }
    @Test fun failedPauseAndResumeDoNotCommitHalfTransitions() = fixture { journal, db ->
        val session = RecorderSession(journal, RecordingClock { 100L })
        session.start("SYNTHETIC", false, JSONObject())
        db.execSQL("CREATE TRIGGER reject_transition BEFORE UPDATE ON sessions BEGIN SELECT RAISE(ABORT, 'injected state failure'); END")
        assertThrows(Exception::class.java) { session.pause() }
        assertEquals("recording", session.state); assertEquals(0, count(db, "SELECT count(*) FROM records WHERE kind='pause'"))
        db.execSQL("DROP TRIGGER reject_transition"); session.pause()
        db.execSQL("CREATE TRIGGER reject_resume BEFORE UPDATE ON sessions WHEN NEW.state='recording' BEGIN SELECT RAISE(ABORT, 'injected resume failure'); END")
        assertThrows(Exception::class.java) { session.resume() }
        assertEquals("paused", session.state); assertEquals(0, count(db, "SELECT count(*) FROM records WHERE kind='resume'"))
    }
}
