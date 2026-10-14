package com.veymur.recorder.core

import android.Manifest
import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veymur.recorder.requireRecorderActivity
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Dedicated emulator: synthetic connection, isolated DB, real foreground service and writer. */
@RunWith(AndroidJUnit4::class)
class RecorderServiceBridgeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val host get() = instrumentation.targetContext
    private fun field(service: RecorderService, name: String) = RecorderService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun writer(service: RecorderService, action: () -> Unit) {
        val task = FutureTask<Unit> { action() }
        (field(service, "worker").get(service) as Handler).post(task); task.get(10, TimeUnit.SECONDS)
    }
    private fun command(service: RecorderService, name: String): Result<Map<String, Any?>> {
        val latch = CountDownLatch(1); var result: Result<Map<String, Any?>>? = null
        service.execute(name) { result = it; latch.countDown() }
        assertTrue("Command $name completed", latch.await(10, TimeUnit.SECONDS)); return result!!
    }
    private class Binding(val service: RecorderService, val connection: ServiceConnection)
    private fun bind(): Binding {
        val latch = CountDownLatch(1); var service: RecorderService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as RecorderService.LocalBinder).service; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        instrumentation.runOnMainSync { assertTrue(host.bindService(Intent(host, RecorderService::class.java), connection, Context.BIND_AUTO_CREATE)) }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); command(service!!, "getSnapshot").getOrThrow()
        return Binding(service!!, connection)
    }
    private fun unbind(binding: Binding) = instrumentation.runOnMainSync { host.unbindService(binding.connection) }
    private fun isolated(service: RecorderService): SQLiteDatabase {
        var db: SQLiteDatabase? = null
        writer(service) {
            val prefix = "stage5-service-${UUID.randomUUID()}-"
            val ctx = object : ContextWrapper(host) {
                override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name)
                override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix + name, mode)
                override fun getCacheDir() = File(baseContext.cacheDir, "exports/$prefix").also { it.mkdirs() }
            }
            val original = field(service, "journal").get(service) as Journal
            assertNull("Use a fresh test host, never a personal installation", original.latest()); original.close()
            val journal = Journal(ctx); field(service, "journal").set(service, journal)
            field(service, "engine").set(service, RecorderSession(journal, field(service, "clock").get(service) as RecordingClock))
            db = Journal::class.java.getDeclaredField("db").apply { isAccessible = true }.get(journal) as SQLiteDatabase
        }
        return db!!
    }
    private fun foreground(service: RecorderService) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).forEach { permission ->
            val descriptor = instrumentation.uiAutomation.executeShellCommand("pm grant ${host.packageName} $permission")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        instrumentation.runOnMainSync { host.startActivity(host.packageManager.getLaunchIntentForPackage(host.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
        writer(service) { field(service, "snapshot").set(service, service.snapshot.copy(connected = "SYNTHETIC", hr = 82, lastHrElapsed = SystemClock.elapsedRealtime() - 6000)) }
    }
    @Test fun snapshotsRevisionsStalenessReadinessMissingActivityAndStoppedServiceErrors() {
        val binding = bind(); val service = binding.service
        try {
            isolated(service)
            writer(service) { field(service, "snapshot").set(service, service.snapshot.copy(connected = "SYNTHETIC", hr = 82, battery = 50, lastHrElapsed = SystemClock.elapsedRealtime() - 6000)) }
            val first = command(service, "getSnapshot").getOrThrow()
            assertNull(first["hr"]); assertEquals("stale", first["hrStatus"]); assertTrue((first["hrAgeMs"] as Long) >= 6000)
            assertEquals(50, first["batteryPercent"])
            assertTrue((command(service, "getSnapshot").getOrThrow()["revision"] as Long) > (first["revision"] as Long))
            assertFalse(first.containsKey("key")); assertFalse(first.containsKey("records"))
            writer(service) { field(service, "snapshot").set(service, service.snapshot.copy(ready = false)) }
            assertEquals("NOT_READY", (command(service, "pause").exceptionOrNull() as RecorderError).code)
            writer(service) { field(service, "snapshot").set(service, service.snapshot.copy(ready = true)) }
            assertEquals("INVALID_STATE", (command(service, "pause").exceptionOrNull() as RecorderError).code)
            assertEquals("NO_ACTIVITY", assertThrows(RecorderError::class.java) { requireRecorderActivity(null) }.code)
        } finally { unbind(binding) }
        (field(service, "workerThread").get(service) as HandlerThread).join(5000)
        assertEquals("SERVICE_UNAVAILABLE", (command(service, "getSnapshot").exceptionOrNull() as RecorderError).code)
    }
    @Test fun startedRecordingSurvivesUnsubscribeAndUnbindThenRebindAndFinishCommit() {
        var binding = bind(); val service = binding.service
        try {
            isolated(service); foreground(service)
            val id = command(service, "start").getOrThrow()["sessionId"]; assertNotNull(id)
            val initial = CountDownLatch(1)
            val listener: (Map<String, Any?>) -> Unit = { if (it["sessionId"] == id) initial.countDown() }
            service.addListener(listener); assertTrue(initial.await(5, TimeUnit.SECONDS)); service.removeListener(listener)
            unbind(binding); SystemClock.sleep(1200); binding = bind(); assertSame(service, binding.service)
            var state = command(service, "getSnapshot").getOrThrow()
            val deadline = SystemClock.elapsedRealtime() + 5000
            while ((state["activeMs"] as Long) < 1000 && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100); state = command(service, "getSnapshot").getOrThrow()
            }
            assertEquals(id, state["sessionId"]); assertEquals("recording", state["sessionState"]); assertTrue((state["activeMs"] as Long) >= 1000)
            command(service, "pause").getOrThrow(); command(service, "pause").getOrThrow()
            command(service, "resume").getOrThrow(); command(service, "resume").getOrThrow()
            assertEquals(id, command(service, "finish").getOrThrow()["sessionId"])
            assertEquals("completed", command(service, "getSnapshot").getOrThrow()["sessionState"])
            assertEquals(id, command(service, "finish").getOrThrow()["sessionId"])
        } finally { command(service, "finish"); unbind(binding) }
    }
    @Test fun workerRejectsFailedStartAndFailedFinishWithoutFalseSuccess() {
        val binding = bind(); val service = binding.service
        try {
            val db = isolated(service); foreground(service)
            writer(service) { db.execSQL("CREATE TRIGGER reject_start BEFORE INSERT ON records WHEN NEW.kind='configuration' BEGIN SELECT RAISE(ABORT, 'start failure'); END") }
            assertEquals("STORAGE_ERROR", (command(service, "start").exceptionOrNull() as RecorderError).code)
            var state = command(service, "getSnapshot").getOrThrow()
            assertNull(state["sessionId"]); assertEquals("interrupted", state["sessionState"])
            writer(service) { db.execSQL("DROP TRIGGER reject_start") }
            val id = command(service, "start").getOrThrow()["sessionId"]
            writer(service) { db.execSQL("CREATE TRIGGER reject_finish BEFORE UPDATE ON sessions WHEN NEW.state='completed' BEGIN SELECT RAISE(ABORT, 'finish failure'); END") }
            assertEquals("STORAGE_ERROR", (command(service, "finish").exceptionOrNull() as RecorderError).code)
            state = command(service, "getSnapshot").getOrThrow()
            assertEquals(id, state["sessionId"]); assertEquals("interrupted", state["sessionState"]); assertEquals("STORAGE_ERROR", state["errorCode"])
            writer(service) { db.execSQL("DROP TRIGGER reject_finish") }
            assertEquals(id, command(service, "finish").getOrThrow()["sessionId"])
        } finally { command(service, "finish"); unbind(binding) }
    }
}
