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
import com.veymur.recorder.core.localdata.*
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import com.polar.sdk.api.*
import com.polar.sdk.api.model.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

data class RecorderSnapshot(
    val status: String = "Preparing storage…", val devices: List<PolarDeviceInfo> = emptyList(),
    val connected: String? = null, val battery: Int? = null, val hr: Int? = null,
    val lastHrElapsed: Long = 0, val lastHrElapsedNs: Long = 0, val recording: Boolean = false, val paused: Boolean = false,
    val hrCount: Int = 0, val gpsCount: Int = 0, val activeMs: Long = 0, val lastCommit: Long = 0,
    val alertsStatus: String = "disabled", val storageWarning: String? = null,
    val revision: Long = 0, val errorCode: String? = null, val recovered: Int = 0, val gpsStatus: String = "Route disabled", val ready: Boolean = false
)

class RecorderService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@RecorderService }
    @Volatile var snapshot = RecorderSnapshot(); private set
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("veymur-recorder")
    private lateinit var worker: Handler
    private var journal: Journal? = null
    private var diagnosticJournal: DiagnosticJournal? = null
    private lateinit var diagnosticExports: DiagnosticExports
    private var nextExportCleanup = 0L
    @Volatile private var diagnosticCapture: DiagnosticCapture? = null
    @Volatile private var diagnosticStatus: DiagnosticCaptureStatus? = null
    @Volatile private var diagnosticPending = false
    @Volatile private var diagnosticError: String? = null
    private val diagnosticStopCallbacks = mutableListOf<(Result<Map<String, Any?>>) -> Unit>()
    private fun diagnosticOwnsService() = diagnosticPending || diagnosticCapture?.status?.state in setOf("active", "stopping")

    @Volatile private var engine: RecorderSession? = null
    private val session get() = engine?.id
    private val listeners = CopyOnWriteArraySet<(Map<String, Any?>) -> Unit>()
    companion object { private val revisions = AtomicLong() }
    private val clock = RecordingClock { SystemClock.elapsedRealtime() }
    private lateinit var api: PolarBleApi
    private val observationNormalizer = ObservationNormalizer()
    private val polarInputs: PolarInputBoundary = PolarInputBoundary(
        utcNow = { System.currentTimeMillis() }, elapsedNowNs = { SystemClock.elapsedRealtimeNanos() },
        dispatch = ::enqueuePolarInput)
    private val polarCallbacks = PolarSdkCallbacks(polarInputs)
    private val polarSearch = PolarSearchInput(polarInputs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var lastActivityVisible: Boolean? = null
    @Volatile private var selected: String? = null
    @Volatile private var activeSearchId: String? = null
    private var rangeAlerts: RangeAlerts? = null
    private var rangeNotifications: RangeAlertNotifications? = null
    private var alertSegmentStartNs = Long.MAX_VALUE
    private var gpsExpectedSinceNs = 0L
    private var lastGpsReceiptNs = 0L
    private var gpsGap = false
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
        override fun onLocationChanged(location: Location) {
            val receipt = Receipt(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
                PolarInputBoundary.processRunId, java.time.ZoneId.systemDefault().id)
            // Own the callback values before queueing; optional Android fields require hasX checks.
            val input = locationObservationInput(location)
            enqueue {
                if (routeEnabled && snapshot.recording && !snapshot.paused) {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        checkGpsAvailability(); return@enqueue
                    }
                    if (receipt.elapsed_ns < gpsExpectedSinceNs || (input.elapsed_ns != null && input.elapsed_ns < gpsExpectedSinceNs)) return@enqueue
                    val sample = try { observationNormalizer.gps(input, receipt) }
                    catch (error: InvalidObservation) {
                        record("gps_rejected", JSONObject().put("reason", error.reason)
                            .put("latitude_raw", input.latitude.toString()).put("longitude_raw", input.longitude.toString())
                            .put("received_utc_ms", receipt.utc_ms).put("received_elapsed_ns", receipt.elapsed_ns.toString())
                            .put("timebase_id", receipt.timebase_id))
                        snapshot = snapshot.copy(gpsStatus = "Invalid coordinates; heart rate recording continues")
                        return@enqueue
                    }
                    record("gps", JSONObject().put("latitude", sample.value.latitude).put("longitude", sample.value.longitude)
                        .put("accuracy_m", sample.value.horizontal_accuracy_m ?: JSONObject.NULL)
                        .put("altitude_m", sample.value.altitude_m ?: JSONObject.NULL).put("speed_m_s", sample.value.speed_m_s ?: JSONObject.NULL)
                        .put("observed_utc_ms", input.utc_ms ?: JSONObject.NULL)
                        .put("source_elapsed_nanos", input.elapsed_ns ?: JSONObject.NULL).put("mock", input.mock)
                        .put("observation_metadata", observationMetadata(sample.time, sample.quality)))
                    lastGpsReceiptNs = receipt.elapsed_ns; gpsGap = false
                    snapshot = snapshot.copy(gpsCount = snapshot.gpsCount + 1, gpsStatus = "Saving coordinates")
                }
            }
        }
        override fun onProviderDisabled(provider: String) = enqueue {
            if (!routeEnabled || !snapshot.recording || snapshot.paused) return@enqueue
            gpsGap = true
            snapshot = snapshot.copy(gpsStatus = "GPS disabled; heart rate recording continues")
            record("gps_unavailable", JSONObject().put("provider", provider).put("stream", "gps")
                .put("quality_flags", org.json.JSONArray(listOf("missing"))))
        }
        override fun onProviderEnabled(provider: String) = enqueue {
            if (routeEnabled && snapshot.recording && !snapshot.paused) {
                gpsGap = false; gpsExpectedSinceNs = SystemClock.elapsedRealtimeNanos(); lastGpsReceiptNs = 0L
                snapshot = snapshot.copy(gpsStatus = "Waiting for coordinates")
            }
        }
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
        // Opening/recovery is independent of recorder storage and never enables capture.
        val diagnostics = DiagnosticJournal(this)
        diagnosticJournal = diagnostics
        diagnostics.ready.thenCompose { diagnostics.listCaptures() }.whenComplete { captures, error ->
            worker.post {
                if (diagnosticJournal !== diagnostics) return@post
                if (error != null) diagnosticError = "open_failed"
                else if (diagnosticCapture == null && !diagnosticPending) diagnosticStatus = captures.lastOrNull()
                publish()
            }
        }
        diagnosticExports = DiagnosticExports(this, diagnosticJournal!!)
        worker.post(tick)
    }
    private fun initializePolar() {
        if (::api.isInitialized) return
        api = PolarBleApiDefaultImpl.defaultImplementation(applicationContext, setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO))
        api.setAutomaticReconnection(true)
        api.setApiCallback(polarCallbacks)
    }
    private fun enqueuePolarInput(delivery: PolarInputDelivery): Unit = enqueue {
        polarInputs.process(delivery, { session }, ::processPolarInput)
    }
    private val control = RecorderControl({ engine!! }, { snapshot }, { snapshot = it }, System::currentTimeMillis)
    private val processor = PolarRecorderProcessor({ snapshot }, { snapshot = it }, clock, deviceDetails, observationNormalizer, ::record)
    private fun processPolarInput(event: PolarInputEvent): Boolean {
        if (session != null && engine?.state == "interrupted") return false
        if (event.eventType.startsWith("search_")) {
            if (event.payload().optString("subscription_id") != activeSearchId) return false
            if (event.eventType in setOf("search_error", "search_ended", "search_completed")) activeSearchId = null
        } else if (event.deviceId != null && event.deviceId != selected) return false
        if (event.eventType in setOf("hr", "battery_level") && snapshot.connected != event.deviceId) return false
        val applied = processor.process(event)
        if (applied && event.eventType in setOf("disconnected", "bluetooth")) resetRangeAlerts()
        return applied
    }
    private fun resetRangeAlerts() {
        rangeAlerts?.reset(); rangeNotifications?.clear()
        alertSegmentStartNs = SystemClock.elapsedRealtimeNanos()
    }
    private fun configureRangeAlerts() {
        rangeNotifications?.clear(); rangeAlerts = null; rangeNotifications = null
        val preferences = journal!!.localData(JSONObject().put("operation", "preferences"))
        val settings = preferences["settings"] as? Map<*, *>
        val alerts = settings?.get("alerts") as? Map<*, *>
        if (alerts?.get("enabled") != true) { snapshot = snapshot.copy(alertsStatus = "disabled"); return }
        val ranges = preferences["hr_ranges"] as? Map<*, *>
        if (settings["hr_ranges_id"] != ranges?.get("id") || ranges == null) {
            snapshot = snapshot.copy(alertsStatus = "no_selected_ranges"); return
        }
        val intervals = (ranges["ranges"] as List<*>).map { value ->
            val r = value as Map<*, *>
            AlertRange(r["id"] as String, r["label"] as String, (r["lower_bpm_inclusive"] as? Number)?.toInt(), (r["upper_bpm_exclusive"] as? Number)?.toInt())
        }
        if (intervals.isEmpty()) { snapshot = snapshot.copy(alertsStatus = "no_selected_ranges"); return }
        rangeAlerts = RangeAlerts(RangeAlertConfiguration(intervals, (alerts["debounce_ms"] as Number).toLong(),
            (alerts["hysteresis_bpm"] as Number).toInt(), (alerts["repeat_ms"] as? Number)?.toLong()))
        rangeNotifications = RangeAlertNotifications(this, alerts["sound"] == true, alerts["vibration"] == true)
        snapshot = snapshot.copy(alertsStatus = if (intervals.isEmpty()) "no_selected_ranges" else rangeNotifications!!.status())
        resetRangeAlerts()
    }
    private fun checkGpsAvailability() {
        if (!routeEnabled || !snapshot.recording || snapshot.paused || gpsGap) return
        val denied = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (denied || SystemClock.elapsedRealtimeNanos() - maxOf(lastGpsReceiptNs, gpsExpectedSinceNs) > 10_000_000_000L) {
            gpsGap = true
            record("gps_unavailable", JSONObject().put("reason", if (denied) "permission_denied" else "coordinates_stale"))
            snapshot = snapshot.copy(gpsStatus = if (denied) "Location permission denied; heart rate recording continues" else "No recent coordinates; heart rate recording continues")
        }
    }
    private fun updateRangeAlerts() {
        val alerts = rangeAlerts ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        snapshot = snapshot.copy(alertsStatus = rangeNotifications!!.status())
        val current = snapshot.heartRateAt(now)
        val bpm = current.value.takeIf { snapshot.recording && !snapshot.paused && snapshot.lastHrElapsedNs >= alertSegmentStartNs }
        if (bpm == null) { alerts.sample(null, now / 1_000_000); rangeNotifications?.clear(); return }
        alerts.sample(bpm, now / 1_000_000)?.let { rangeNotifications?.show(it) }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (shuttingDown) return
            if (SystemClock.elapsedRealtime() >= nextExportCleanup) {
                nextExportCleanup = SystemClock.elapsedRealtime() + 60000
                diagnosticExports.cleanup().whenComplete { _, error -> if (error != null) worker.post { diagnosticFailure(error); publish() } }
            }
            diagnosticCapture?.let { capture ->
                diagnosticStatus = capture.status
                if (capture.status.state !in setOf("active", "stopping")) {
                    polarInputs.setSink(null)
                    diagnosticCapture = null
                    main.post { stopResources() }
                }
            }
            if (snapshot.recording || diagnosticOwnsService()) {
                if (SystemClock.elapsedRealtime() >= nextWakeRenewal) {
                    nextWakeRenewal = SystemClock.elapsedRealtime() + 60000
                    main.post { if (snapshot.recording || diagnosticOwnsService()) wakeLock?.acquire(10 * 60 * 1000L) }
                }
            }
            if (snapshot.recording) {
                try {
                    val active = clock.activeMs()
                    session?.let {
                        journal!!.updateState(it, if (snapshot.paused) "paused" else "recording", active)
                        snapshot = snapshot.copy(activeMs = active, lastCommit = System.currentTimeMillis())
                    }
                    val space = filesDir.usableSpace
                    snapshot = snapshot.copy(storageWarning = if (space < 50L * 1024 * 1024) "low_space" else null)
                    checkGpsAvailability()
                    if (space < 20L * 1024 * 1024) {
                        record("low_storage", JSONObject())
                        finishOnWorker("Low storage. Available recording saved")
                    }
                } catch (e: Exception) { fail(e) }
            }
            updateRangeAlerts()
            publish()
            worker.postDelayed(this, 1000)
        }
    }
    private fun enqueue(action: () -> Unit) { worker.post { try { action() } catch (e: Exception) { fail(e) }; publish() } }
    private fun fail(error: Exception) {
        engine?.failed(); resetRangeAlerts()
        snapshot = snapshot.copy(errorCode = "STORAGE_ERROR", recording = false, paused = false, ready = snapshot.ready,
            status = "Local storage error (${error.javaClass.simpleName}). Recording stopped; the saved portion is available for export")
        main.post { stopResources() }
    }
    private fun record(kind: String, payload: JSONObject) {
        // Once storage fails, no later callback/lifecycle event may move the saved cutoff.
        if (engine?.state !in setOf("recording", "paused")) return
        session?.let { journal!!.append(it, kind, payload); snapshot = snapshot.copy(lastCommit = System.currentTimeMillis()) }
    }
    private fun search() {
        if (snapshot.recording || starting || session != null) throw RecorderError("INVALID_STATE", "Finish the current session before searching")
        searchJob?.cancel()
        enqueue { snapshot = snapshot.copy(devices = emptyList(), errorCode = null, status = "Searching for Verity Sense…") }
        val subscriptionId = java.util.UUID.randomUUID().toString()
        activeSearchId = subscriptionId
        searchJob = scope.launch {
            try {
                withTimeout(15000) {
                    api.searchForDevice().collect { info -> polarSearch.result(subscriptionId, info) }
                    polarSearch.completed(subscriptionId)
                }
            } catch (error: TimeoutCancellationException) {
                polarSearch.cancelled(subscriptionId, "timeout", error)
            } catch (error: CancellationException) {
                polarSearch.cancelled(subscriptionId, "cancelled", error)
            } catch (error: Exception) {
                polarSearch.error(subscriptionId, error)
            }
        }
    }
    private fun cancelSearch() { activeSearchId = null; searchJob?.cancel(); enqueue { snapshot = snapshot.copy(status = "Search stopped") } }
    private fun connect(id: String) {
        if (session != null && engine?.state == "interrupted") throw RecorderError("INVALID_STATE", "Save the available portion before reconnecting")
        if ((snapshot.recording || starting || session != null) && (id != selected || snapshot.connected != null))
            throw RecorderError("INVALID_STATE", "Only reconnecting the selected sensor is allowed during a session")
        if (id != selected && snapshot.devices.none { it.deviceId == id })
            throw RecorderError("INVALID_ARGUMENT", "Search and explicitly select a listed sensor")
        cancelSearch()
        try {
            val switching = selected != id
            selected?.takeIf { it != id }?.let { api.disconnectFromDevice(it) }
            selected = id
            enqueue {
                if (switching) deviceDetails.clear()
                resetRangeAlerts()
                snapshot = snapshot.copy(connected = null, hr = null, battery = null, errorCode = null)
            }
            api.connectToDevice(id)
        } catch (e: Exception) { throw RecorderError("CONNECTION_ERROR", "Unable to connect to selected sensor") }
    }
    private fun start(route: Boolean, command: PolarInputDelivery?, done: (Result<Map<String, Any?>>) -> Unit) {
        if (snapshot.connected == null) throw RecorderError("NOT_CONNECTED", "Connect a sensor first")
        if (snapshot.recording || starting || finishing || session != null) throw RecorderError("INVALID_STATE", "A session is already active")
        starting = true
        try {
            routeEnabled = route && gps.isLocationEnabled &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            holdResources()
            workerCommand(done, command) {
                configureRangeAlerts()
                val id = control.start(route, routeEnabled, JSONObject().put("route_requested", route).put("route_enabled", routeEnabled)
                    .put("device_info", JSONObject(deviceDetails.toMap())).put("phone_battery_percent", phoneBattery()))
                alertSegmentStartNs = SystemClock.elapsedRealtimeNanos()
                gpsExpectedSinceNs = alertSegmentStartNs; lastGpsReceiptNs = 0L; gpsGap = false
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
            enqueue { gpsGap = false; checkGpsAvailability() }; return
        }
        try { gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0f, locations, Looper.getMainLooper()) }
        catch (e: Exception) { enqueue { snapshot = snapshot.copy(gpsStatus = "Route unavailable: ${e.message}"); record("gps_error", JSONObject().put("type", e.javaClass.simpleName)) } }
    }
    private fun finishOnWorker(message: String): Map<String, Any?> {
        val hadSession = session != null
        finishing = true
        if (hadSession) { snapshot = snapshot.copy(status = "Saving recording…"); publish() }
        resetRangeAlerts()
        val id = try { control.finish(phoneBattery(), message) } catch (e: Exception) { finishing = false; throw e }
        main.post { stopResources(); finishing = false }
        return mapOf("sessionId" to id, "state" to engine!!.state)
    }
    fun execute(name: String, argument: String? = null, route: Boolean = false, done: (Result<Map<String, Any?>>) -> Unit) {
        val command = if (name in setOf("start", "pause", "resume", "finish")) polarInputs.command(name, route) else null
        main.post {
            try {
                if (shuttingDown) throw RecorderError("SERVICE_UNAVAILABLE", "Recorder service stopped")
                if (!snapshot.ready && name !in setOf("getSnapshot", "startDiagnostic", "stopDiagnostic", "listDiagnosticCaptures", "exportDiagnostic", "deleteDiagnostic")) throw RecorderError("NOT_READY", "Recorder storage is not ready; retry")
                if (name in setOf("search", "connect", "start", "startDiagnostic")) {
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
                    "start" -> start(route, command, done)
                    "startDiagnostic" -> startDiagnostic(done)
                    "stopDiagnostic" -> stopDiagnostic(done)
                    "listDiagnosticCaptures" -> diagnosticCommand(diagnosticJournal!!.listCaptures().thenApply { captures ->
                        mapOf("captures" to captures.map { diagnosticDto(it, null, false) })
                    }, done)
                    "exportDiagnostic" -> diagnosticCommand(diagnosticExports.export(argument ?: "").thenApply { file -> mapOf("path" to file.absolutePath) }, done)
                    "deleteDiagnostic" -> {
                        val id = argument ?: throw RecorderError("INVALID_ARGUMENT", "Capture ID required")
                        worker.post {
                            val live = diagnosticCapture
                            if (live?.captureId == id && live.status.state in setOf("complete", "interrupted", "incomplete")) {
                                diagnosticStatus = live.status; diagnosticCapture = null; polarInputs.setSink(null)
                            }
                            diagnosticCommand(diagnosticExports.delete(id).thenApply { deleted -> mapOf("deleted" to deleted) }, done, id)
                        }
                    }
                    else -> workerCommand(done, command) {
                        when (name) {
                            "localData" -> {
                                try {
                                    if (argument == null || argument.length > 65536) throw RecorderError("INVALID_ARGUMENT", "Invalid local request")
                                    val request = JSONObject(argument)
                                    val result = journal!!.localData(request)
                                    if (request.optString("operation") == "saveSettings") {
                                        val alerts = request.getJSONObject("value").getJSONObject("alerts")
                                        if (!alerts.getBoolean("enabled")) {
                                            rangeAlerts = null; rangeNotifications?.clear(); rangeNotifications = null
                                            snapshot = snapshot.copy(alertsStatus = "disabled")
                                        } else if (rangeAlerts != null) {
                                            // Delivery/quiet choices take effect immediately; range rules stay session-scoped.
                                            rangeNotifications?.clear()
                                            rangeNotifications = RangeAlertNotifications(this, alerts.getBoolean("sound"), alerts.getBoolean("vibration"))
                                            snapshot = snapshot.copy(alertsStatus = rangeNotifications!!.status())
                                        }
                                    }
                                    result
                                } catch (e: RecorderError) { throw e }
                                catch (e: IllegalArgumentException) { throw RecorderError("INVALID_ARGUMENT", "Invalid local request") }
                                catch (e: org.json.JSONException) { throw RecorderError("INVALID_ARGUMENT", "Invalid local request") }
                                catch (e: Exception) { throw RecorderError("STORAGE_ERROR", "Local operation failed; retry without resetting storage") }
                            }
                            "getSnapshot" -> dto()
                            "pause", "resume" -> {
                                val previousState = engine!!.state
                                control.pauseOrResume(name)
                                if (previousState != engine!!.state) {
                                    resetRangeAlerts()
                                    gpsExpectedSinceNs = SystemClock.elapsedRealtimeNanos(); lastGpsReceiptNs = 0L; gpsGap = false
                                    snapshot = snapshot.copy(gpsStatus = if (!routeEnabled) "Route disabled" else if (snapshot.paused) "Paused" else "Waiting for coordinates")
                                }
                                main.post {
                                    if (snapshot.paused) gps.removeUpdates(locations) else if (routeEnabled && snapshot.recording) requestGps()
                                    if (snapshot.recording || diagnosticOwnsService()) refreshForegroundNotification()
                                }
                                mapOf("sessionId" to engine!!.id, "state" to engine!!.state)
                            }
                            "finish" -> finishOnWorker("Recording saved. Ready to export")
                            "exportSession" -> {
                                try {
                                    if (argument == null || argument.length > 65536) throw RecorderError("INVALID_ARGUMENT", "Invalid export request")
                                    journal!!.exportUserFile(JSONObject(argument))
                                } catch (e: RecorderError) { throw e }
                                catch (e: IllegalArgumentException) { throw RecorderError("INVALID_ARGUMENT", "Invalid export request") }
                                catch (e: org.json.JSONException) { throw RecorderError("INVALID_ARGUMENT", "Invalid export request") }
                                catch (e: Exception) { throw RecorderError("STORAGE_ERROR", "Export could not be prepared; saved sessions and recording are not affected") }
                            }
                            "exportLatest" -> {
                                val id = journal!!.latest() ?: throw RecorderError("NO_SESSIONS", "No saved sessions")
                                mapOf("path" to journal!!.export(id).absolutePath)
                            }
                            else -> throw RecorderError("INVALID_ARGUMENT", "Unknown recorder command")
                        }
                    }
                }
            } catch (e: Exception) { polarInputs.commandResult(command, session, "rejected", e); done(Result.failure(e)) }
        }
    }
    private fun workerCommand(done: (Result<Map<String, Any?>>) -> Unit, command: PolarInputDelivery? = null, action: () -> Map<String, Any?>) {
        polarInputs.commandResult(command, session, "accepted")
        worker.post {
            try { val result = polarInputs.commandProcess(command, { session }, action); publish(); main.post { done(Result.success(result)) } }
            catch (e: Exception) {
                if (command?.event?.payload()?.optString("name") == "start") main.post { starting = false; stopResources() }
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
        val heartRate = snapshot.heartRateAt(SystemClock.elapsedRealtimeNanos())
        return mapOf("revision" to snapshot.revision, "ready" to snapshot.ready, "status" to snapshot.status,
            "sessionId" to engine?.id, "sessionState" to (if (finishing && session != null) "finishing" else engine?.state ?: "preparation"),
            "connectedDeviceId" to snapshot.connected, "batteryPercent" to snapshot.battery.takeIf { snapshot.connected != null },
            "hr" to heartRate.value, "hrAgeMs" to heartRate.ageMs,
            "hrStatus" to heartRate.status,
            "devices" to snapshot.devices.map { mapOf("id" to it.deviceId, "name" to it.name, "address" to it.address) },
            "hrCount" to snapshot.hrCount, "gpsCount" to snapshot.gpsCount, "activeMs" to snapshot.activeMs,
            "lastCommitUtcMs" to snapshot.lastCommit.takeIf { it > 0 }, "gpsStatus" to snapshot.gpsStatus,
            "alertsStatus" to snapshot.alertsStatus, "storageWarning" to snapshot.storageWarning,
            "selectedDeviceId" to selected, "discoveryActive" to (activeSearchId != null),
            "routeEnabled" to routeEnabled, "recoveredCount" to snapshot.recovered, "errorCode" to snapshot.errorCode, "diagnostic" to diagnosticDto())
    }
    private fun phoneBattery(): Int = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    private fun diagnosticDto(
        status: DiagnosticCaptureStatus? = diagnosticCapture?.status ?: diagnosticStatus,
        error: String? = diagnosticError,
        pending: Boolean = diagnosticPending,
    ): Map<String, Any?> {
        return mapOf("captureId" to status?.captureId,
            "state" to (if (pending) "starting" else status?.state ?: "off"),
            "savedEvents" to (status?.savedEvents ?: 0L), "savedProcessing" to (status?.savedProcessing ?: 0L),
            "knownLostEvents" to (status?.knownLostEvents ?: 0L), "knownLostProcessing" to (status?.knownLostProcessing ?: 0L),
            "unknownTail" to (status?.unknownTail ?: false), "checkpointPersisted" to (status?.checkpointPersisted ?: true),
            "lastErrorCode" to (error ?: status?.lastErrorCode))
    }
    private fun diagnosticCommand(
        future: java.util.concurrent.CompletableFuture<Map<String, Any?>>,
        done: (Result<Map<String, Any?>>) -> Unit,
        deletedId: String? = null,
    ) {
        future.whenComplete { value, error -> worker.post {
            val result = if (error == null) {
                if (deletedId != null && diagnosticStatus?.captureId == deletedId) {
                    diagnosticStatus = null; diagnosticError = null
                }
                Result.success(value)
            } else {
                var cause: Throwable = error
                while (cause is java.util.concurrent.CompletionException && cause.cause != null) cause = cause.cause!!
                Result.failure(if ((cause as? DiagnosticStorageError)?.code in setOf("capture_active", "export_active"))
                    RecorderError("INVALID_STATE", "Stop this diagnostic capture before exporting or deleting it")
                    else diagnosticFailure(error))
            }
            publish(); main.post { done(result) }
        } }
    }
    private fun diagnosticFailure(error: Throwable): RecorderError {
        var cause = error
        while (cause.cause != null && cause is java.util.concurrent.CompletionException) cause = cause.cause!!
        diagnosticError = (cause as? DiagnosticStorageError)?.code ?: "operation_failed"
        return RecorderError("DIAGNOSTIC_ERROR", "Diagnostic capture failed ($diagnosticError); check capture status")
    }
    /** Explicit visible-app action; never initializes the SDK or starts discovery/subscriptions. */
    private fun startDiagnostic(done: (Result<Map<String, Any?>>) -> Unit) {
        if (diagnosticPending || diagnosticStopCallbacks.isNotEmpty()) throw RecorderError("INVALID_STATE", "Diagnostic capture is changing state")
        diagnosticCapture?.let {
            if (it.status.state == "active") { done(Result.success(diagnosticDto())); return }
            if (it.status.state == "stopping") throw RecorderError("INVALID_STATE", "Diagnostic capture is stopping")
        }
        if (lastActivityVisible != true) throw RecorderError("NO_ACTIVITY", "Start diagnostics from the visible app")
        diagnosticPending = true; diagnosticError = null
        try { holdResources() } catch (error: Exception) { diagnosticPending = false; stopResources(); throw error }
        worker.post {
            val configuration = JSONObject().put("session_state", if (snapshot.recording) { if (snapshot.paused) "paused" else "recording" } else "preparation")
                .put("session_id", session ?: JSONObject.NULL).put("connected_device_id", snapshot.connected ?: JSONObject.NULL)
                .put("route_enabled", routeEnabled && snapshot.recording).put("automatic_reconnection", true)
                .put("hr_stale_after_ms", 5000).put("active_ms", clock.activeMs()).put("last_hr", snapshot.hr ?: JSONObject.NULL)
                .put("last_hr_received_elapsed_ns", if (snapshot.lastHrElapsedNs > 0) snapshot.lastHrElapsedNs.toString() else JSONObject.NULL)
            val environment = JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android_api", Build.VERSION.SDK_INT).put("android_release", Build.VERSION.RELEASE)
                .put("app_version", packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown")
                .put("core_version", "stage0-777a33b-polar-replay-d7").put("device_info", JSONObject(deviceDetails.toMap()))
            diagnosticJournal!!.startCapture(PolarInputBoundary.processRunId, configuration.toString(), environment.toString()).whenComplete { capture, error ->
                worker.post {
                    diagnosticPending = false
                    if (shuttingDown) {
                        capture?.stop()
                        main.post { done(Result.failure(RecorderError("SERVICE_UNAVAILABLE", "Service stopped while starting diagnostics"))) }
                        return@post
                    }
                    val result = if (error != null) Result.failure<Map<String, Any?>>(diagnosticFailure(error)) else {
                        diagnosticCapture = capture; diagnosticStatus = capture.status; polarInputs.setSink(capture)
                        Result.success(diagnosticDto())
                    }
                    publish()
                    main.post { stopResources(); done(result) }
                }
            }
        }
    }
    private fun stopDiagnostic(done: (Result<Map<String, Any?>>) -> Unit) {
        if (diagnosticPending) throw RecorderError("INVALID_STATE", "Diagnostic capture is starting")
        if (diagnosticStopCallbacks.isNotEmpty()) { diagnosticStopCallbacks.add(done); return }
        val capture = diagnosticCapture
        if (capture == null) {
            if (diagnosticStatus?.checkpointPersisted == false || diagnosticStatus?.state in setOf("incomplete", "interrupted")) done(Result.failure(RecorderError("DIAGNOSTIC_ERROR", "Diagnostic capture is incomplete; check capture status")))
            else done(Result.success(diagnosticDto()))
            return
        }
        // Deliveries already queued retain their sink and may finish processing after this cutoff.
        diagnosticStopCallbacks.add(done)
        polarInputs.setSink(null)
        capture.stop().whenComplete { status, error ->
            worker.post {
                if (diagnosticCapture === capture || diagnosticCapture == null) {
                    diagnosticStatus = status ?: capture.status
                    diagnosticCapture = null
                }
                val result = if (error != null) Result.failure<Map<String, Any?>>(diagnosticFailure(error))
                    else if (status.state != "complete") Result.failure(RecorderError("DIAGNOSTIC_ERROR", "Diagnostic capture is incomplete; check capture status"))
                    else Result.success(diagnosticDto())
                publish(); main.post {
                    stopResources()
                    val callbacks = diagnosticStopCallbacks.toList(); diagnosticStopCallbacks.clear()
                    callbacks.forEach { it(result) }
                }
            }
        }
    }
    private fun foregroundNotification(): Notification = Notification.Builder(this, "recorder")
            .setContentTitle(when {
                snapshot.paused -> "Veymur: recording paused"
                snapshot.recording || starting -> "Veymur: local recording in progress"
                else -> "Veymur: diagnostic capture enabled"
            })
            .setContentText("${if (snapshot.recording || starting) "Recording" else "Sensor diagnostics"} saved on this phone")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, requireNotNull(packageManager.getLaunchIntentForPackage(packageName)), PendingIntent.FLAG_IMMUTABLE)).build()
    private fun refreshForegroundNotification() {
        try { getSystemService(NotificationManager::class.java).notify(1, foregroundNotification()) }
        catch (_: SecurityException) { /* Notification denial must not stop local recording. */ }
    }
    private fun holdResources() {
        startForeground(1, foregroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (routeEnabled && (snapshot.recording || starting)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0))
        startService(Intent(this, RecorderService::class.java))
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "veymur:recorder").apply {
            setReferenceCounted(false); acquire(10 * 60 * 1000L)
        }
        nextWakeRenewal = SystemClock.elapsedRealtime() + 60000
    }
    private fun stopResources() {
        if (!snapshot.recording) gps.removeUpdates(locations)
        if (snapshot.recording || diagnosticOwnsService()) {
            refreshForegroundNotification()
            return
        }
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        shuttingDown = true
        rangeNotifications?.clear()
        unregisterReceiver(screenReceiver)
        scope.cancel(); if (::api.isInitialized) api.shutDown(); gps.removeUpdates(locations)
        wakeLock?.let { if (it.isHeld) it.release() }
        worker.removeCallbacks(tick)
        polarInputs.setSink(null)
        worker.post {
            try { journal?.close() } finally {
                diagnosticExports.closeAsync().whenComplete { _, _ ->
                    diagnosticJournal?.closeAsync()?.whenComplete { _, _ -> workerThread.quitSafely() }
                        ?: workerThread.quitSafely()
                }
            }
        }
        super.onDestroy()
    }
}
