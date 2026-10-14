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

data class RecorderSnapshot(
    val status: String = "Preparing storage…", val devices: List<PolarDeviceInfo> = emptyList(),
    val connected: String? = null, val battery: Int? = null, val hr: Int? = null,
    val lastHrElapsed: Long = 0, val recording: Boolean = false, val paused: Boolean = false,
    val hrCount: Int = 0, val gpsCount: Int = 0, val activeMs: Long = 0, val lastCommit: Long = 0,
    val recovered: Int = 0, val gpsStatus: String = "Route disabled", val ready: Boolean = false
)

class RecorderService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@RecorderService }
    @Volatile var snapshot = RecorderSnapshot(); private set
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("veymur-recorder")
    private lateinit var worker: Handler
    private var journal: Journal? = null
    private var session: String? = null
    private val clock = RecordingClock { SystemClock.elapsedRealtime() }
    private lateinit var api: PolarBleApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var selected: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var routeEnabled = false
    private var starting = false
    private var nextWakeRenewal = 0L
    private var shuttingDown = false
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
            snapshot = snapshot.copy(ready = true, recovered = journal!!.recoverInterrupted(), status = "Turn on your sensor and search for it")
        }
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
        worker.post(tick)
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
                    snapshot = snapshot.copy(activeMs = clock.activeMs())
                    session?.let { journal!!.updateState(it, if (snapshot.paused) "paused" else "recording", clock.activeMs()) }
                    if (filesDir.usableSpace < 20L * 1024 * 1024) {
                        record("low_storage", JSONObject())
                        finishOnWorker("Low storage. Available recording saved")
                    }
                } catch (e: Exception) { fail(e) }
            }
            worker.postDelayed(this, 1000)
        }
    }
    private fun enqueue(action: () -> Unit) { worker.post { try { action() } catch (e: Exception) { fail(e) } } }
    private fun fail(error: Exception) {
        clock.pause()
        snapshot = snapshot.copy(recording = false, paused = false, ready = journal != null,
            status = "Error: ${error.javaClass.simpleName}: ${error.message}. Recording stopped; the saved portion is available for export")
        main.post { stopResources() }
    }
    private fun record(kind: String, payload: JSONObject) {
        session?.let { journal!!.append(it, kind, payload); snapshot = snapshot.copy(lastCommit = System.currentTimeMillis()) }
    }
    fun search() {
        if (snapshot.recording || starting) return
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
            catch (e: Exception) { enqueue { snapshot = snapshot.copy(status = "Search error: ${e.message}") } }
        }
    }
    fun cancelSearch() { searchJob?.cancel(); enqueue { snapshot = snapshot.copy(status = "Search stopped") } }
    fun connect(id: String) {
        if (snapshot.recording || starting) return
        cancelSearch()
        try {
            selected?.takeIf { it != id }?.let { api.disconnectFromDevice(it) }
            selected = id
            enqueue { snapshot = snapshot.copy(connected = null, hr = null, battery = null) }
            api.connectToDevice(id)
        } catch (e: Exception) { enqueue { snapshot = snapshot.copy(status = "Connection error: ${e.message}") } }
    }
    fun start(route: Boolean) {
        if (!snapshot.ready || snapshot.connected == null || snapshot.recording || starting) return
        starting = true
        val device = snapshot.connected!!
        try {
            routeEnabled = route && gps.isLocationEnabled &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val notification = Notification.Builder(this, "recorder").setContentTitle("Veymur: local recording in progress")
                .setContentText("Heart rate${if (routeEnabled) " and route" else ""} saved on this phone")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, requireNotNull(packageManager.getLaunchIntentForPackage(packageName)) { "Host launcher Activity unavailable" }, PendingIntent.FLAG_IMMUTABLE)).build()
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (routeEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0))
            startService(Intent(this, RecorderService::class.java))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "veymur:recorder").apply {
                setReferenceCounted(false); acquire(10 * 60 * 1000L)
            }
            nextWakeRenewal = SystemClock.elapsedRealtime() + 60000
            enqueue {
                session = journal!!.begin(device, routeEnabled)
                clock.start()
                snapshot = snapshot.copy(recording = true, paused = false, hrCount = 0, gpsCount = 0, activeMs = 0,
                    status = "Recording on this phone", gpsStatus = if (routeEnabled) "Waiting for coordinates" else if (route) "GPS unavailable; recording heart rate only" else "Route disabled")
                record("configuration", JSONObject().put("route_requested", route).put("route_enabled", routeEnabled)
                    .put("device_info", JSONObject(deviceDetails.toMap())).put("phone_battery_percent", phoneBattery()))
                main.post { starting = false }
            }
            if (routeEnabled) requestGps()
        } catch (e: Exception) { starting = false; enqueue { fail(e) } }
    }
    fun activityVisible(visible: Boolean) {
        if (visible && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) api.foregroundEntered()
        enqueue { record(if (visible) "activity_foreground" else "activity_background", JSONObject()) }
    }
    private fun requestGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            enqueue { snapshot = snapshot.copy(gpsStatus = "Precise location permission not granted") }; return
        }
        try { gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0f, locations, Looper.getMainLooper()) }
        catch (e: Exception) { enqueue { snapshot = snapshot.copy(gpsStatus = "Route unavailable: ${e.message}"); record("gps_error", JSONObject().put("type", e.javaClass.simpleName)) } }
    }
    fun pauseOrResume() {
        if (!snapshot.recording) return
        enqueue {
            if (snapshot.paused) {
                clock.resume(); record("resume", JSONObject()); snapshot = snapshot.copy(paused = false, status = "Recording on this phone")
                main.post { if (routeEnabled) requestGps() }
            } else {
                clock.pause(); record("pause", JSONObject()); snapshot = snapshot.copy(paused = true, status = "Paused")
                main.post { gps.removeUpdates(locations) }
            }
            journal!!.updateState(session!!, if (snapshot.paused) "paused" else "recording", clock.activeMs())
        }
    }
    fun finish() = enqueue { if (snapshot.recording) finishOnWorker("Recording saved. Ready to export") }
    private fun finishOnWorker(message: String) {
        clock.pause(); record("finish", JSONObject().put("active_ms", clock.activeMs()).put("phone_battery_percent", phoneBattery()))
        journal!!.updateState(session!!, "completed", clock.activeMs())
        session = null
        snapshot = snapshot.copy(recording = false, paused = false, activeMs = clock.activeMs(), status = message)
        main.post { stopResources() }
    }
    private fun phoneBattery(): Int = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    private fun stopResources() {
        gps.removeUpdates(locations)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    fun export(callback: (File?, String?) -> Unit) = enqueue {
        try {
            val id = journal?.latest() ?: error("No saved sessions yet")
            val file = journal!!.export(id)
            main.post { callback(file, null) }
        } catch (e: Exception) { main.post { callback(null, e.message) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        shuttingDown = true
        unregisterReceiver(screenReceiver)
        scope.cancel(); api.shutDown(); gps.removeUpdates(locations)
        wakeLock?.let { if (it.isHeld) it.release() }
        worker.removeCallbacks(tick)
        worker.post { journal?.close(); workerThread.quitSafely() }
        super.onDestroy()
    }
}
