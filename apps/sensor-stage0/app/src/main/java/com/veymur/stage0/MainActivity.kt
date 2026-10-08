package com.veymur.stage0

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider

class MainActivity : Activity() {
    private var service: RecorderService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var pulse: TextView
    private lateinit var details: TextView
    private lateinit var devices: LinearLayout
    private lateinit var route: CheckBox
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var finish: Button
    private lateinit var search: Button
    private var deviceIds = emptyList<String>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RecorderService.LocalBinder).service
            service?.activityVisible(true)
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 32)
        }
        setContentView(ScrollView(this).apply {
            addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        })
        fun label(text: String, size: Float = 17f) = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, 8, 0, 8); body.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }; body.addView(this)
        }
        label("Veymur · recording validation", 25f)
        label("Polar Verity Sense · Android\nData is stored on this phone. No account required.")
        status = label("Preparing…")
        pulse = label("No current heart rate", 32f)
        details = label("")
        search = button("Find Verity Sense") {
            if (bluetoothGranted()) service?.search()
            else AlertDialog.Builder(this).setMessage("Bluetooth is required to find and connect to your sensor.")
                .setPositiveButton("Continue") { _, _ -> requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 10) }
                .setNegativeButton("Cancel", null).show()
        }
        button("Cancel search") { service?.cancelSearch() }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        route = CheckBox(this).apply {
            text = "Record phone route"; body.addView(this)
            setOnCheckedChangeListener { _, checked ->
                if (checked && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
                }
            }
        }
        start = button("Start recording") {
            if (!bluetoothGranted()) { message("Grant Bluetooth permission by searching for a sensor"); return@button }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this).setMessage("Allow notifications for an ongoing recording? Denying permission does not block recording.")
                    .setPositiveButton("Allow") { _, _ -> requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12) }
                    .setNegativeButton("Continue without notifications") { _, _ -> service?.start(route.isChecked) }.show()
            } else service?.start(route.isChecked)
        }
        pause = button("Pause") { service?.pauseOrResume() }
        finish = button("Finish") {
            AlertDialog.Builder(this).setMessage("Finish recording and save the available data?")
                .setPositiveButton("Save") { _, _ -> service?.finish() }.setNegativeButton("Continue recording", null).show()
        }
        button("Export last session") {
            service?.export { file, error ->
                if (file == null) message(error ?: "Export unavailable")
                else {
                    val uri = FileProvider.getUriForFile(this, "$packageName.exports", file)
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Veymur recording", uri) }
                    startActivity(Intent.createChooser(send, "Save or share recording"))
                }
            }
        }
        button("App permissions") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
        label("Recording stops after a force stop. The saved portion is detected automatically on the next launch.\nThe export contains heart rate and, if the route is enabled, coordinates.", 14f)
    }
    private fun bluetoothGranted() = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        when (code) {
            10 -> if (bluetoothGranted()) service?.search() else message("Bluetooth permission denied. You can change it in settings.")
            11 -> if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                route.isChecked = false; message("You can continue without a route")
            }
            12 -> service?.start(route.isChecked)
        }
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, RecorderService::class.java), connection, BIND_AUTO_CREATE)
        handler.post(refresh)
    }
    override fun onStop() {
        handler.removeCallbacks(refresh)
        service?.activityVisible(false)
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
    private val refresh = object : Runnable {
        override fun run() {
            service?.snapshot?.let { state ->
                status.text = state.status
                val age = SystemClock.elapsedRealtime() - state.lastHrElapsed
                pulse.text = if (state.connected != null && state.hr != null && age in 0..5000) "${state.hr} bpm" else "No current heart rate"
                details.text = "Sensor: ${state.connected ?: "not connected"}\nSensor battery: ${state.battery?.let { "$it%" } ?: "unavailable"}\n" +
                    "Active time: ${state.activeMs / 1000} s\nSaved HR: ${state.hrCount} · GPS: ${state.gpsCount}\n" +
                    "${state.gpsStatus}\nLast commit: ${state.lastCommit.takeIf { it > 0 }?.let { java.text.DateFormat.getTimeInstance().format(java.util.Date(it)) } ?: "none"}\n" +
                    "Recovered interrupted recordings: ${state.recovered}"
                start.isEnabled = state.ready && state.connected != null && !state.recording
                pause.isEnabled = state.recording; pause.text = if (state.paused) "Resume" else "Pause"
                finish.isEnabled = state.recording; route.isEnabled = !state.recording; search.isEnabled = state.ready && !state.recording
                val ids = state.devices.map { it.deviceId }
                if (ids != deviceIds) {
                    deviceIds = ids; devices.removeAllViews()
                    state.devices.forEach { info -> devices.addView(Button(this@MainActivity).apply {
                        text = "${info.name} · ${info.deviceId}"; setOnClickListener { service?.connect(info.deviceId) }
                    }) }
                }
                for (i in 0 until devices.childCount) devices.getChildAt(i).isEnabled = !state.recording
            }
            handler.postDelayed(this, 500)
        }
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
