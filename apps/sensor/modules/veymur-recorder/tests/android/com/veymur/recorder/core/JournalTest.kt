package com.veymur.recorder.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import com.veymur.recorder.core.localdata.*
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
        val normalizer = ObservationNormalizer()
        val receipt = Receipt(1700000000000, 1000000001, "journal-test", "Europe/Warsaw")
        val hr = normalizer.hr(82, false, true, receipt)
        val gps = normalizer.gps(GpsInput(52.0, 21.0, null, null, null, 1699999999900, 900000001, false), receipt)
        journal.append(id, "hr", JSONObject().put("bpm", hr.value)
            .put("observation_metadata", observationMetadata(hr.time, hr.quality)))
        journal.append(id, "gps", JSONObject().put("latitude", gps.value.latitude).put("longitude", gps.value.longitude)
            .put("accuracy_m", JSONObject.NULL).put("observation_metadata", observationMetadata(gps.time, gps.quality)))
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
            fun payload(row: String, kind: String) = JSONObject(row.substringAfter(",$kind,")
                .removeSurrounding("\"").replace("\"\"", "\""))
            val savedHr = payload(rows[2], "hr").getJSONObject("observation_metadata")
            assertTrue(savedHr.has("observed_at_utc")); assertTrue(savedHr.isNull("observed_at_utc"))
            assertEquals("2023-11-14T22:13:20Z", savedHr.getString("recorded_at_utc"))
            assertEquals("journal-test", savedHr.getString("timebase_id"))
            assertEquals("sensor_contact_lost", savedHr.getJSONArray("quality_reasons").getString(0))
            val savedGps = payload(rows[3], "gps")
            assertTrue(savedGps.has("accuracy_m")); assertTrue(savedGps.isNull("accuracy_m"))
            assertEquals("900000001", savedGps.getJSONObject("observation_metadata").getString("source_elapsed_realtime_ns"))
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
