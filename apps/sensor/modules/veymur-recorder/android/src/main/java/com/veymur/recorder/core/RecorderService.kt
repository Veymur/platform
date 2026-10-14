package com.veymur.recorder.core

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import com.polar.sdk.api.*
import com.polar.sdk.api.model.*
import com.polar.androidcommunications.api.ble.model.DisInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

data class RecorderSnapshot(
    val status: String = "Preparing storage…", val devices: List<PolarDeviceInfo> = emptyList(),
    val connected: String? = null, val battery: Int? = null, val hr: Int? = null,
    val lastHrElapsed: Long = 0, val recording: Boolean = false, val paused: Boolean = false,
    val hrCount: Int = 0, val gpsCount: Int = 0, val activeMs: Long = 0, val lastCommit: Long = 0,
    val revision: Long = 0, val errorCode: String? = null, val recovered: Int = 0, val gpsStatus: String = "Route disabled", val ready: Boolean = false
)

class RecorderService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@RecorderService }
    @Volatile var snapshot = RecorderSnapshot(); private set
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("veymur-recorder")
    private lateinit var worker: Handler
    private var journal: Journal? = null
    @Volatile private var engine: RecorderSession? = null
    private val session get() = engine?.id
    private val listeners = CopyOnWriteArraySet<(Map<String, Any?>) -> Unit>()
    companion object { private val revisions = AtomicLong() }
    private val clock = RecordingClock { SystemClock.elapsedRealtime() }
    private lateinit var api: PolarBleApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var lastActivityVisible: Boolean? = null
    private var selected: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var routeEnabled = false
    @Volatile private var starting = false
    @Volatile private var finishing = false
    private var nextWakeRenewal = 0L
    @Volatile private var shuttingDown = false
    private val deviceDetails = mutableMapOf<String, String>()
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = enqueue {
            record(if (intent.action == Intent.ACTION_SCREEN_OFF) "screen_off" else "screen_on", JSONObject())
        }
    }
    private val gps by lazy { getSystemService(LocationManager::class.java) }
    private val locations = object : LocationListener {
        override fun onLocationChanged(location: Location) = enqueue {
            if (snapshot.recording && !snapshot.paused) {
                record("gps", JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
                    .put("accuracy_m", location.accuracy).put("observed_utc_ms", location.time)
                    .put("source_elapsed_nanos", location.elapsedRealtimeNanos).put("mock", location.isMock))
                snapshot = snapshot.copy(gpsCount = snapshot.gpsCount + 1, gpsStatus = "Saving coordinates")
            }
        }
        override fun onProviderDisabled(provider: String) = enqueue {
            snapshot = snapshot.copy(gpsStatus = "GPS disabled; heart rate recording continues")
            record("gps_unavailable", JSONObject().put("provider", provider))
        }
        override fun onProviderEnabled(provider: String) = enqueue { snapshot = snapshot.copy(gpsStatus = "Waiting for coordinates") }
    }
    override fun onBind(intent: Intent) = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        workerThread.start(); worker = Handler(workerThread.looper)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }, Context.RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("recorder", "Sensor recording", NotificationManager.IMPORTANCE_LOW))
        enqueue {
            journal = Journal(this)
            engine = RecorderSession(journal!!, clock)
            snapshot = snapshot.copy(ready = true, recovered = journal!!.recoverInterrupted(), status = "Turn on your sensor and search for it")
        }
        worker.post(tick)
    }
    private fun initializePolar() {
        if (::api.isInitialized) return
        api = PolarBleApiDefaultImpl.defaultImplementation(applicationContext, setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO))
        api.setAutomaticReconnection(true)
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) = enqueue {
                if (!powered) snapshot = snapshot.copy(status = "Bluetooth disabled", connected = null, hr = null)
                record("bluetooth", JSONObject().put("powered", powered))
            }
            override fun deviceConnecting(info: PolarDeviceInfo) = enqueue { snapshot = snapshot.copy(status = "Connecting to ${info.deviceId}…") }
            override fun deviceConnected(info: PolarDeviceInfo) = enqueue {
                snapshot = snapshot.copy(connected = info.deviceId, status = "Sensor connected")
                record("connected", JSONObject().put("device", info.deviceId))
            }
            override fun deviceDisconnected(info: PolarDeviceInfo, diagnostics: PolarBleDisconnectInfo) = disconnected(info)
            @Suppress("DEPRECATION")
            override fun deviceDisconnected(info: PolarDeviceInfo) = disconnected(info)
            override fun batteryLevelReceived(identifier: String, level: Int) = enqueue {
                snapshot = snapshot.copy(battery = level)
                record("sensor_battery", JSONObject().put("percent", level))
            }
            override fun disInformationReceived(identifier: String, uuid: java.util.UUID, value: String) = enqueue {
                deviceDetails[uuid.toString()] = value
                record("device_info", JSONObject().put("uuid", uuid).put("value", value))
            }
            override fun disInformationReceived(identifier: String, disInfo: DisInfo) = enqueue {
                deviceDetails["dis"] = disInfo.toString()
                record("device_info", JSONObject().put("details", disInfo.toString()))
            }
            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) { /* Not part of this prototype. */ }
            override fun hrNotificationReceived(identifier: String, data: PolarHrData.PolarHrSample) = enqueue {
                val now = SystemClock.elapsedRealtime()
                snapshot = snapshot.copy(hr = data.hr.takeIf { it > 0 }, lastHrElapsed = now)
                if (snapshot.recording && !snapshot.paused) {
                    record("hr", JSONObject().put("bpm", data.hr).put("contact", data.contactStatus)
                        .put("contact_supported", data.contactStatusSupported).put("ppg_quality", data.ppgQuality)
                        .put("active_ms", clock.activeMs()).put("timestamp_source", "phone_receipt"))
                    snapshot = snapshot.copy(hrCount = snapshot.hrCount + 1)
                }
            }
        })
    }
    private fun disconnected(info: PolarDeviceInfo) = enqueue {
        snapshot = snapshot.copy(connected = null, hr = null, status = "Connection lost. The SDK is reconnecting")
        record("disconnected", JSONObject().put("device", info.deviceId))
    }
    private val tick = object : Runnable {
        override fun run() {
            if (shuttingDown) return
            if (snapshot.recording) {
                try {
                    if (SystemClock.elapsedRealtime() >= nextWakeRenewal) {
                        nextWakeRenewal = SystemClock.elapsedRealtime() + 60000
                        main.post { if (snapshot.recording) wakeLock?.acquire(10 * 60 * 1000L) }
                    }
                    val active = clock.activeMs()
                    session?.let {
                        journal!!.updateState(it, if (snapshot.paused) "paused" else "recording", active)
                        snapshot = snapshot.copy(activeMs = active, lastCommit = System.currentTimeMillis())
                    }
                    if (filesDir.usableSpace < 20L * 1024 * 1024) {
                        record("low_storage", JSONObject())
                        finishOnWorker("Low storage. Available recording saved")
                    }
                } catch (e: Exception) { fail(e) }
            }
            publish()
            worker.postDelayed(this, 1000)
        }
    }
    private fun enqueue(action: () -> Unit) { worker.post { try { action() } catch (e: Exception) { fail(e) }; publish() } }
    private fun fail(error: Exception) {
        engine?.failed()
        snapshot = snapshot.copy(errorCode = "STORAGE_ERROR", recording = false, paused = false, ready = snapshot.ready,
            status = "Local storage error (${error.javaClass.simpleName}). Recording stopped; the saved portion is available for export")
        main.post { stopResources() }
    }
    private fun record(kind: String, payload: JSONObject) {
        session?.let { journal!!.append(it, kind, payload); snapshot = snapshot.copy(lastCommit = System.currentTimeMillis()) }
    }
    private fun search() {
        if (snapshot.recording || starting) throw RecorderError("INVALID_STATE", "Recording is active")
        searchJob?.cancel()
        enqueue { snapshot = snapshot.copy(devices = emptyList(), status = "Searching for Verity Sense…") }
        searchJob = scope.launch {
            try {
                withTimeout(15000) {
                    api.searchForDevice().collect { info ->
                        if (info.name.contains("Sense", ignoreCase = true) && info.isConnectable) enqueue {
                            snapshot = snapshot.copy(devices = (snapshot.devices + info).distinctBy { it.deviceId })
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                enqueue { snapshot = snapshot.copy(status = if (snapshot.devices.isEmpty()) "Sensor not found. Turn it on and search again" else "Select your sensor") }
            } catch (_: CancellationException) { /* Explicit cancel. */ }
            catch (e: Exception) { enqueue { snapshot = snapshot.copy(errorCode = "DISCOVERY_ERROR", status = "Sensor discovery failed; retry") } }
        }
    }
    private fun cancelSearch() { searchJob?.cancel(); enqueue { snapshot = snapshot.copy(status = "Search stopped") } }
    private fun connect(id: String) {
        if (snapshot.recording || starting) throw RecorderError("INVALID_STATE", "Recording is active")
        cancelSearch()
        try {
            selected?.takeIf { it != id }?.let { api.disconnectFromDevice(it) }
            selected = id
            enqueue { snapshot = snapshot.copy(connected = null, hr = null, battery = null) }
            api.connectToDevice(id)
        } catch (e: Exception) { throw RecorderError("CONNECTION_ERROR", "Unable to connect to selected sensor") }
    }
    private fun start(route: Boolean, done: (Result<Map<String, Any?>>) -> Unit) {
        if (snapshot.connected == null) throw RecorderError("NOT_CONNECTED", "Connect a sensor first")
        if (snapshot.recording || starting || finishing || session != null) throw RecorderError("INVALID_STATE", "A session is already active")
        starting = true
        val device = snapshot.connected!!
        try {
            routeEnabled = route && gps.isLocationEnabled &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val notification = Notification.Builder(this, "recorder").setContentTitle("Veymur: local recording in progress")
                .setContentText("Heart rate${if (routeEnabled) " and route" else ""} saved on this phone")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, requireNotNull(packageManager.getLaunchIntentForPackage(packageName)), PendingIntent.FLAG_IMMUTABLE)).build()
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (routeEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0))
            startService(Intent(this, RecorderService::class.java))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "veymur:recorder").apply {
                setReferenceCounted(false); acquire(10 * 60 * 1000L)
            }
            nextWakeRenewal = SystemClock.elapsedRealtime() + 60000
            workerCommand(done) {
                val id = engine!!.start(device, routeEnabled, JSONObject().put("route_requested", route).put("route_enabled", routeEnabled)
                    .put("device_info", JSONObject(deviceDetails.toMap())).put("phone_battery_percent", phoneBattery()))
                snapshot = snapshot.copy(recording = true, paused = false, errorCode = null, hrCount = 0, gpsCount = 0, activeMs = 0,
                    lastCommit = System.currentTimeMillis(), status = "Recording on this phone",
                    gpsStatus = if (routeEnabled) "Waiting for coordinates" else if (route) "GPS unavailable; recording heart rate only" else "Route disabled")
                main.post { starting = false; if (routeEnabled && snapshot.recording && !snapshot.paused) requestGps() }
                mapOf("sessionId" to id)
            }
        } catch (e: Exception) {
            starting = false; stopResources(); throw e
        }
    }
    fun activityVisible(visible: Boolean) {
        if (lastActivityVisible == visible) return
        lastActivityVisible = visible
        if (::api.isInitialized && visible && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) api.foregroundEntered()
        enqueue { record(if (visible) "activity_foreground" else "activity_background", JSONObject()) }
    }
    private fun requestGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            enqueue { snapshot = snapshot.copy(gpsStatus = "Precise location permission not granted") }; return
        }
        try { gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0f, locations, Looper.getMainLooper()) }
        catch (e: Exception) { enqueue { snapshot = snapshot.copy(gpsStatus = "Route unavailable: ${e.message}"); record("gps_error", JSONObject().put("type", e.javaClass.simpleName)) } }
    }
    private fun finishOnWorker(message: String): Map<String, Any?> {
        val hadSession = session != null
        finishing = true
        if (hadSession) { snapshot = snapshot.copy(status = "Saving recording…"); publish() }
        val id = try { engine!!.finish(phoneBattery()) } catch (e: Exception) { finishing = false; throw e }
        snapshot = snapshot.copy(recording = false, paused = false, errorCode = null, activeMs = clock.activeMs(),
            lastCommit = if (hadSession) System.currentTimeMillis() else snapshot.lastCommit, status = message)
        main.post { stopResources(); finishing = false }
        return mapOf("sessionId" to id, "state" to "completed")
    }
    fun execute(name: String, argument: String? = null, route: Boolean = false, done: (Result<Map<String, Any?>>) -> Unit) {
        main.post {
            try {
                if (shuttingDown) throw RecorderError("SERVICE_UNAVAILABLE", "Recorder service stopped")
                if (!snapshot.ready && name != "getSnapshot") throw RecorderError("NOT_READY", "Recorder storage is not ready; retry")
                if (name in setOf("search", "connect", "start")) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                        throw RecorderError("PERMISSION_DENIED", "Grant Bluetooth permissions before this action")
                }
                when (name) {
                    "search" -> { initializePolar(); search(); done(Result.success(mapOf("accepted" to true))) }
                    "cancelSearch" -> { cancelSearch(); done(Result.success(mapOf("accepted" to true))) }
                    "connect" -> {
                        val id = argument?.takeIf { it.isNotBlank() } ?: throw RecorderError("INVALID_ARGUMENT", "Device identifier required")
                        initializePolar(); connect(id); done(Result.success(mapOf("accepted" to true)))
                    }
                    "start" -> start(route, done)
                    else -> workerCommand(done) {
                        when (name) {
                            "getSnapshot" -> dto()
                            "pause", "resume" -> {
                                val previousState = engine!!.state
                                if (name == "pause") engine!!.pause() else engine!!.resume()
                                snapshot = snapshot.copy(paused = engine!!.state == "paused", activeMs = clock.activeMs(),
                                    lastCommit = if (previousState != engine!!.state) System.currentTimeMillis() else snapshot.lastCommit, errorCode = null,
                                    status = if (engine!!.state == "paused") "Paused" else "Recording on this phone")
                                main.post { if (snapshot.paused) gps.removeUpdates(locations) else if (routeEnabled && snapshot.recording) requestGps() }
                                mapOf("sessionId" to engine!!.id, "state" to engine!!.state)
                            }
                            "finish" -> finishOnWorker("Recording saved. Ready to export")
                            "exportLatest" -> {
                                val id = journal!!.latest() ?: throw RecorderError("NO_SESSIONS", "No saved sessions")
                                mapOf("path" to journal!!.export(id).absolutePath)
                            }
                            else -> throw RecorderError("INVALID_ARGUMENT", "Unknown recorder command")
                        }
                    }
                }
            } catch (e: Exception) { done(Result.failure(e)) }
        }
    }
    private fun workerCommand(done: (Result<Map<String, Any?>>) -> Unit, action: () -> Map<String, Any?>) {
        worker.post {
            try { val result = action(); publish(); main.post { done(Result.success(result)) } }
            catch (e: Exception) {
                if (e !is RecorderError) { fail(e); main.post { starting = false }; publish() }
                main.post { done(Result.failure(if (e is RecorderError) e else RecorderError("STORAGE_ERROR", "Local storage operation failed; saved portion retained"))) }
            }
        }
    }
    fun addListener(listener: (Map<String, Any?>) -> Unit) { listeners.add(listener); enqueue { } }
    fun removeListener(listener: (Map<String, Any?>) -> Unit) { listeners.remove(listener) }
    private fun publish() {
        snapshot = snapshot.copy(revision = revisions.incrementAndGet())
        val state = dto()
        main.post { listeners.forEach { it(state) } }
    }
    private fun dto(): Map<String, Any?> {
        val age = snapshot.lastHrElapsed.takeIf { it > 0 }?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) }
        val fresh = snapshot.connected != null && snapshot.hr != null && age != null && age <= 5000
        return mapOf("revision" to snapshot.revision, "ready" to snapshot.ready, "status" to snapshot.status,
            "sessionId" to engine?.id, "sessionState" to (if (finishing && session != null) "finishing" else engine?.state ?: "preparation"),
            "connectedDeviceId" to snapshot.connected, "batteryPercent" to snapshot.battery.takeIf { snapshot.connected != null },
            "hr" to snapshot.hr.takeIf { fresh }, "hrAgeMs" to age,
            "hrStatus" to if (fresh) "fresh" else if (snapshot.hr != null && snapshot.connected != null) "stale" else "missing",
            "devices" to snapshot.devices.map { mapOf("id" to it.deviceId, "name" to it.name, "address" to it.address) },
            "hrCount" to snapshot.hrCount, "gpsCount" to snapshot.gpsCount, "activeMs" to snapshot.activeMs,
            "lastCommitUtcMs" to snapshot.lastCommit.takeIf { it > 0 }, "gpsStatus" to snapshot.gpsStatus,
            "routeEnabled" to routeEnabled, "recoveredCount" to snapshot.recovered, "errorCode" to snapshot.errorCode)
    }
    private fun phoneBattery(): Int = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    private fun stopResources() {
        gps.removeUpdates(locations)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        shuttingDown = true
        unregisterReceiver(screenReceiver)
        scope.cancel(); if (::api.isInitialized) api.shutDown(); gps.removeUpdates(locations)
        wakeLock?.let { if (it.isHeld) it.release() }
        worker.removeCallbacks(tick)
        worker.post { journal?.close(); workerThread.quitSafely() }
        super.onDestroy()
    }
}
