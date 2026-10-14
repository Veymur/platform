package com.veymur.recorder.core

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Real host/SQLCipher/Keystore tests with explicitly synthetic streams, never a user journal. */
@RunWith(AndroidJUnit4::class)
class NativeCoreStorageTest {
    private val host get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixtureContext(): Context {
        val prefix = "instrumentation-stage4-${UUID.randomUUID()}-"
        return object : ContextWrapper(host) {
            override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name)
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + name, mode)
            // Inside the real FileProvider's exports subtree, without using the production journal.
            override fun getCacheDir(): File = File(baseContext.cacheDir, "exports/$prefix").also { it.mkdirs() }
        }
    }

    private fun onWriter(action: () -> Unit) {
        val thread = HandlerThread("veymur-storage-test").apply { start() }
        try {
            val task = FutureTask<Unit> { action() }
            Handler(thread.looper).post(task)
            task.get(30, TimeUnit.SECONDS)
        } finally {
            thread.quitSafely()
            thread.join(5000)
        }
    }

    private fun metadata(file: File): JSONObject = ZipFile(file).use { zip ->
        JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
    }

    private fun rows(file: File): List<String> = ZipFile(file).use { zip ->
        zip.getInputStream(zip.getEntry("records.csv")).bufferedReader().readLines()
    }

    @Test fun syntheticHrGpsPauseFinishReopenAndProviderExport() = onWriter {
        assertEquals("com.veymur.sensor", host.packageName)
        val context = fixtureContext()
        var time = 100L
        val clock = RecordingClock { time }
        val journal = Journal(context)
        val id = journal.begin("SYNTHETIC-STAGE4", true)
        clock.start()
        journal.append(id, "configuration", JSONObject().put("synthetic", true).put("route_requested", true).put("route_enabled", true))
        fun hr(bpm: Int) = journal.append(id, "hr", JSONObject().put("bpm", bpm).put("active_ms", clock.activeMs()).put("timestamp_source", "phone_receipt"))
        fun gps() = journal.append(id, "gps", JSONObject().put("latitude", 52.0).put("longitude", 21.0)
            .put("observed_utc_ms", System.currentTimeMillis()).put("source_elapsed_nanos", SystemClock.elapsedRealtimeNanos()))
        hr(82); gps()
        time = 1100; clock.pause()
        journal.append(id, "pause", JSONObject())
        journal.updateState(id, "paused", clock.activeMs())
        time = 9100
        assertEquals(1000L, clock.activeMs())
        assertEquals("paused", metadata(journal.export(id)).getString("state"))
        clock.resume(); journal.append(id, "resume", JSONObject())
        hr(85); gps()
        time = 10100; clock.pause()
        journal.append(id, "finish", JSONObject().put("active_ms", clock.activeMs()))
        journal.updateState(id, "completed", clock.activeMs())
        journal.close()

        val reopened = Journal(context)
        try {
            assertEquals(0, reopened.recoverInterrupted())
            val file = reopened.export(id)
            assertEquals(1, metadata(file).getInt("schema_version"))
            assertEquals("completed", metadata(file).getString("state"))
            assertEquals(2000L, metadata(file).getLong("active_ms"))
            assertEquals(2, rows(file).count { it.contains(",hr,") })
            assertEquals(2, rows(file).count { it.contains(",gps,") })
            val uri = FileProvider.getUriForFile(host, "${host.packageName}.exports", file)
            host.contentResolver.openInputStream(uri)!!.use { stream ->
                assertArrayEquals(file.readBytes(), stream.readBytes())
            }
            // Retain a deterministic, synthetic-only file for the independent Python analyzer.
            file.copyTo(File(host.getExternalFilesDir(null), "stage4-synthetic-completed.zip"), overwrite = true)
        } finally { reopened.close() }
    }

    @Test fun failedAppendRollsBackAndLaterCommitRemainsUsable() = onWriter {
        val context = fixtureContext()
        val journal = Journal(context)
        try {
            val id = journal.begin("SYNTHETIC-TRANSACTION", false)
            // Fault injection stays in tests; production Journal receives no test hooks.
            val field = Journal::class.java.getDeclaredField("db").apply { isAccessible = true }
            val db = field.get(journal) as SQLiteDatabase
            db.execSQL("CREATE TRIGGER reject_record BEFORE INSERT ON records WHEN NEW.kind='reject' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            val failure = assertThrows(Exception::class.java) { journal.append(id, "reject", JSONObject()) }
            assertTrue(failure.message.orEmpty().contains("injected failure"))
            journal.append(id, "hr", JSONObject().put("bpm", 82))
            val file = journal.export(id)
            assertEquals(3, rows(file).size) // header, start and committed HR
            assertFalse(rows(file).any { it.contains(",reject,") })
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0))
            }
        } finally { journal.close() }
    }

    @Test fun unfinishedSyntheticRecordingIsRecoveredAndReadableByProvider() = onWriter {
        val context = fixtureContext()
        val journal = Journal(context)
        val id = journal.begin("SYNTHETIC-INTERRUPTED", true)
        journal.append(id, "hr", JSONObject().put("bpm", 82))
        journal.append(id, "gps", JSONObject().put("latitude", 52.0).put("longitude", 21.0))
        journal.updateState(id, "recording", 1500)
        journal.close()
        val reopened = Journal(context)
        try {
            assertEquals(1, reopened.recoverInterrupted())
            assertEquals(0, reopened.recoverInterrupted())
            val file = reopened.export(id)
            assertEquals("interrupted", metadata(file).getString("state"))
            assertEquals(1500L, metadata(file).getLong("active_ms"))
            assertEquals(5, rows(file).size)
            file.copyTo(File(host.getExternalFilesDir(null), "stage4-synthetic-interrupted.zip"), overwrite = true)
        } finally { reopened.close() }
    }

    @Suppress("DEPRECATION")
    @Test fun manifestDeclaresPrivateServiceProviderPermissionsAndBackupProtection() {
        val pm = host.packageManager
        assertEquals("com.veymur.sensor", host.packageName)
        val service = pm.getServiceInfo(ComponentName(host, RecorderService::class.java), 0)
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
        val app = pm.getApplicationInfo(host.packageName, PackageManager.GET_META_DATA)
        assertEquals("stage0-777a33b-host-v1", app.metaData.getString("com.veymur.recorder.CORE_VERSION"))
        assertEquals(0, app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val rules = host.resources.getIdentifier("veymur_recorder_data_extraction_rules", "xml", host.packageName)
        assertTrue(rules != 0)
        var manifestRules = 0
        host.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "application") {
                    manifestRules = parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "dataExtractionRules", 0)
                }
                parser.next()
            }
        }
        assertEquals(rules, manifestRules)
        assertNotNull(pm.getLaunchIntentForPackage(host.packageName))
        val provider = pm.resolveContentProvider("${host.packageName}.exports", 0)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
        val permissions = pm.getPackageInfo(host.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS",
            "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "FOREGROUND_SERVICE_LOCATION", "WAKE_LOCK").forEach {
            assertTrue(it, "android.permission.$it" in permissions)
        }
        val outside = File(host.cacheDir, "instrumentation-private-${UUID.randomUUID()}.txt")
        outside.writeText("Synthetic private file")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(host, "${host.packageName}.exports", outside)
            }
        } finally { outside.delete() }
    }
}
