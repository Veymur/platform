package com.veymur.recorder.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile
import android.content.Context
import android.content.ContextWrapper
import java.io.File

@RunWith(AndroidJUnit4::class)
class JournalTest {
    private fun isolatedContext(): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getDatabasePath(name: String): File = super.getDatabasePath("instrumentation-$name")
        override fun deleteDatabase(name: String): Boolean = baseContext.deleteDatabase("instrumentation-$name")
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("instrumentation-$name", mode)
        override fun getCacheDir(): File = File(super.getCacheDir(), "instrumentation").also { it.mkdirs() }
    }
    @Test fun encryptedJournalSurvivesReopenAndExportsInterruptedSession() {
        // Dedicated filenames in the target sandbox; never erase the user's journal.
        val context = isolatedContext()
        context.deleteDatabase("stage0.db")
        val journal = Journal(context)
        val id = journal.begin("TEST-VERITY", true)
        journal.append(id, "hr", JSONObject().put("bpm", 82))
        journal.append(id, "gps", JSONObject().put("latitude", 52.0).put("longitude", 21.0))
        journal.close()
        val header = context.getDatabasePath("stage0.db").inputStream().use { stream -> ByteArray(16).also { stream.read(it) } }
        assertFalse(String(header).startsWith("SQLite format 3"))
        val reopened = Journal(context)
        assertEquals(1, reopened.recoverInterrupted())
        assertEquals(0, reopened.recoverInterrupted())
        assertEquals(id, reopened.latest())
        ZipFile(reopened.export(id)).use { zip ->
            val metadata = JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
            assertEquals("interrupted", metadata.getString("state"))
            val rows = zip.getInputStream(zip.getEntry("records.csv")).bufferedReader().readLines()
            assertEquals(5, rows.size) // header + start + HR + GPS + interrupted
            assertTrue(rows[2].contains(",hr,"))
            assertTrue(rows[3].contains(",gps,"))
        }
        reopened.close()
    }
    @Test fun completedSessionIsNotRecoveredAsInterrupted() {
        val context = isolatedContext()
        val journal = Journal(context)
        val id = journal.begin("TEST", false)
        journal.updateState(id, "completed", 2500)
        journal.close()
        val reopened = Journal(context)
        assertEquals(0, reopened.recoverInterrupted())
        ZipFile(reopened.export(id)).use { zip ->
            val metadata = JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
            assertEquals(2500, metadata.getLong("active_ms"))
        }
        reopened.close()
    }
}
