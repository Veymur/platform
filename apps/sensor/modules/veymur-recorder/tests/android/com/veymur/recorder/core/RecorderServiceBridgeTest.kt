package com.veymur.recorder.core

import android.Manifest
import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
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
    private fun command(service: RecorderService, name: String, route: Boolean = false): Result<Map<String, Any?>> {
        val latch = CountDownLatch(1); var result: Result<Map<String, Any?>>? = null
        service.execute(name, route = route) { result = it; latch.countDown() }
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
            instrumentation.runOnMainSync {
                service.activityVisible(true); service.activityVisible(false); service.activityVisible(false); service.activityVisible(true)
            }
            writer(service) {
                val journal = field(service, "journal").get(service) as Journal
                java.util.zip.ZipFile(journal.export(id as String)).use { zip ->
                    val rows = zip.getInputStream(zip.getEntry("records.csv")).bufferedReader().readLines()
                    assertEquals(2, rows.count { it.contains(",activity_foreground,") })
                    assertEquals(1, rows.count { it.contains(",activity_background,") })
                    assertEquals(0, rows.count { it.contains(",screen_off,") })
                }
            }
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
    @Test fun routeDeniedStillStartsHrOnlyWithCorrectForegroundType() {
        val binding = bind(); val service = binding.service
        try {
            isolated(service); foreground(service)
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, service.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertNotNull(command(service, "start", route = true).getOrThrow()["sessionId"])
            val state = command(service, "getSnapshot").getOrThrow()
            assertEquals(false, state["routeEnabled"])
            assertTrue((state["gpsStatus"] as String).contains("GPS unavailable"))
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
            command(service, "finish").getOrThrow()
        } finally { command(service, "finish"); unbind(binding) }
    }

    @Test fun polarInputCapturePrecedesDiscoveryFiltersAndSurvivesSessionBoundariesAndPause() {
        val binding = bind(); val service = binding.service
        try {
            isolated(service); foreground(service)
            val input = field(service, "polarInputs").get(service) as PolarInputBoundary
            val callbacks = field(service, "polarCallbacks").get(service) as PolarSdkCallbacks
            val search = field(service, "polarSearch").get(service) as PolarSearchInput
            val sink = PolarInputBoundaryTest.Sink("synthetic-service")
            input.setSink(sink)
            val other = PolarDeviceInfo("OTHER", "00:00:00:00:00:00", -90, "Other sensor", true)
            val unavailable = other.copy(deviceId = "UNAVAILABLE", name = "Sense", isConnectable = false)
            val eligible = other.copy(deviceId = "SYNTHETIC", name = "Sense", rssi = -60)
            search.result("search-test", other); search.result("search-test", unavailable)
            search.result("search-test", eligible); search.result("search-test", eligible.copy(rssi = -40))
            fun hr(bpm: Int) = callbacks.hrNotificationReceived("SYNTHETIC",
                PolarHrData.PolarHrSample(bpm, 0, 0, emptyList(), emptyList(), false, false, true))
            hr(80); hr(0)
            var state = command(service, "getSnapshot").getOrThrow()
            assertNull(state["hr"]); assertEquals(0, state["hrCount"])
            assertEquals(1, service.snapshot.devices.size); assertEquals(-60, service.snapshot.devices.single().rssi)
            assertEquals(4, sink.inputs.count { it.eventType == "search_result" })
            val id = command(service, "start").getOrThrow()["sessionId"] as String
            hr(82); command(service, "pause").getOrThrow(); hr(83)
            state = command(service, "getSnapshot").getOrThrow()
            assertEquals("paused", state["sessionState"]); assertEquals(1, state["hrCount"])
            command(service, "resume").getOrThrow(); hr(84)
            command(service, "finish").getOrThrow(); hr(85)
            state = command(service, "getSnapshot").getOrThrow()
            assertEquals("completed", state["sessionState"]); assertEquals(2, state["hrCount"])
            assertEquals(listOf(80, 0, 82, 83, 84, 85), sink.inputs.filter { it.eventType == "hr" }.map { it.payload().getInt("hr") })
            assertEquals(sink.inputs.size, sink.processing.size)
            assertEquals(2, sink.processing.count { it.input.eventType == "search_result" && it.outcome == "ignored" })
            writer(service) {
                val journal = field(service, "journal").get(service) as Journal
                java.util.zip.ZipFile(journal.export(id)).use { zip ->
                    val rows = zip.getInputStream(zip.getEntry("records.csv")).bufferedReader().readLines()
                    assertEquals(2, rows.count { it.contains(",hr,") })
                    assertTrue(rows.any { it.contains("82") && it.contains(",hr,") })
                    assertTrue(rows.any { it.contains("84") && it.contains(",hr,") })
                }
            }
            input.setSink(null)
        } finally { unbind(binding) }
    }

    @Test fun delayedPolarInputUsesReceiptAgeInsteadOfWorkerTimeForFreshness() {
        val binding = bind(); val service = binding.service
        val release = CountDownLatch(1)
        try {
            isolated(service)
            val callbacks = field(service, "polarCallbacks").get(service) as PolarSdkCallbacks
            val input = field(service, "polarInputs").get(service) as PolarInputBoundary
            val sink = PolarInputBoundaryTest.Sink("synthetic-delayed"); input.setSink(sink)
            val blocked = CountDownLatch(1)
            (field(service, "worker").get(service) as Handler).post { blocked.countDown(); release.await(10, TimeUnit.SECONDS) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            callbacks.deviceConnected(PolarDeviceInfo("SYNTHETIC", "00:00:00:00:00:00", -50, "Sense", true))
            callbacks.hrNotificationReceived("SYNTHETIC", PolarHrData.PolarHrSample(82, 0, 0, emptyList(), emptyList(), false, true, true))
            assertEquals(2, sink.inputs.size); assertTrue(sink.processing.isEmpty())
            SystemClock.sleep(5100); release.countDown()
            val state = command(service, "getSnapshot").getOrThrow()
            assertNull(state["hr"]); assertEquals("stale", state["hrStatus"])
            val processing = sink.processing.single { it.input.eventType == "hr" }
            assertTrue(processing.processedElapsedNs - processing.input.receivedElapsedNs >= 5_000_000_000L)
            input.setSink(null)
        } finally { release.countDown(); unbind(binding) }
    }

}
