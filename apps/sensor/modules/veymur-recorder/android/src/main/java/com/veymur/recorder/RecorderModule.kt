package com.veymur.recorder

import android.content.*
import android.os.*
import androidx.core.content.FileProvider
import com.veymur.recorder.core.RecorderError
import com.veymur.recorder.core.RecorderService
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File

class RecorderModule : Module() {
    private val main = Handler(Looper.getMainLooper())
    private var service: RecorderService? = null
    private var context: Context? = null
    private var bound = false
    private var observing = false
    private var destroyed = false
    private val pending = mutableListOf<Pair<Promise, (RecorderService) -> Unit>>()
    private val listener: (Map<String, Any?>) -> Unit = { if (observing && !destroyed) runCatching { sendEvent("onSnapshot", it) }; Unit }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = (binder as RecorderService.LocalBinder).service
            service = connected
            if (observing) connected.addListener(listener)
            val requests = pending.toList(); pending.clear()
            requests.forEach { (_, action) -> action(connected) }
        }
        override fun onServiceDisconnected(name: ComponentName) = releaseBinding("Recorder service disconnected")
        override fun onBindingDied(name: ComponentName) = releaseBinding("Recorder binding died; retry")
        override fun onNullBinding(name: ComponentName) = releaseBinding("Recorder binding unavailable; retry")
    }
    override fun definition() = ModuleDefinition {
        Name("VeymurRecorder")
        Events("onSnapshot")
        AsyncFunction("getModuleInfo") {
            mapOf("moduleName" to "VeymurRecorder", "platform" to "android", "contractVersion" to 2, "recorderImplemented" to true)
        }
        AsyncFunction("getSnapshot") { promise: Promise -> command("getSnapshot", promise) }
        AsyncFunction("search") { promise: Promise -> command("search", promise) }
        AsyncFunction("cancelSearch") { promise: Promise -> command("cancelSearch", promise) }
        AsyncFunction("connect") { id: String, promise: Promise -> command("connect", promise, argument = id) }
        AsyncFunction("start") { route: Boolean, promise: Promise -> command("start", promise, route = route) }
        AsyncFunction("pause") { promise: Promise -> command("pause", promise) }
        AsyncFunction("resume") { promise: Promise -> command("resume", promise) }
        AsyncFunction("finish") { promise: Promise -> command("finish", promise) }
        AsyncFunction("exportLatest") { promise: Promise ->
            withService(promise) { recorder ->
                recorder.execute("exportLatest") { result ->
                    result.fold(onSuccess = { value ->
                        try {
                            val ctx = requireNotNull(context)
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.exports", File(value["path"] as String))
                            promise.resolve(mapOf("uri" to uri.toString(), "mimeType" to "application/zip"))
                        } catch (error: Exception) { reject(promise, error) }
                    }, onFailure = { reject(promise, it) })
                }
            }
        }
        AsyncFunction("shareExport") { uri: String, promise: Promise -> main.post {
            try {
                val activity = requireRecorderActivity(appContext.currentActivity, visible = true)
                val parsed = android.net.Uri.parse(uri)
                if (parsed.scheme != "content" || parsed.authority != "${activity.packageName}.exports")
                    throw RecorderError("INVALID_ARGUMENT", "Use the URI returned by exportLatest")
                activity.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { }
                    ?: throw RecorderError("INVALID_ARGUMENT", "Export file unavailable")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, parsed)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("Diagnostic export", parsed)
                }
                activity.startActivity(Intent.createChooser(intent, "Share diagnostic ZIP"))
                promise.resolve(mapOf("chooserOpened" to true))
            } catch (error: Exception) { reject(promise, error) }
        } }
        OnStartObserving { main.post { observing = true; service?.addListener(listener) } }
        OnStopObserving { main.post { observing = false; service?.removeListener(listener) } }
        OnDestroy { main.post { destroyed = true; releaseBinding("JS bridge detached") } }
    }
    private fun command(name: String, promise: Promise, argument: String? = null, route: Boolean = false) {
        withService(promise) { recorder ->
            if (name == "start") {
                try { requireRecorderActivity(appContext.currentActivity, visible = true) }
                catch (error: RecorderError) { reject(promise, error); return@withService }
            }
            recorder.execute(name, argument, route) { result ->
                result.fold(onSuccess = { promise.resolve(it) }, onFailure = { reject(promise, it) })
            }
        }
    }
    private fun withService(promise: Promise, action: (RecorderService) -> Unit) {
        main.post {
            if (destroyed) { reject(promise, RecorderError("SERVICE_UNAVAILABLE", "Bridge detached")); return@post }
            service?.let { action(it); return@post }
            val ctx = appContext.reactContext?.applicationContext
            if (ctx == null) { reject(promise, RecorderError("SERVICE_UNAVAILABLE", "React context unavailable")); return@post }
            val request = promise to action
            pending.add(request)
            try {
                if (!bound) {
                    context = ctx
                    bound = ctx.bindService(Intent(ctx, RecorderService::class.java), connection, Context.BIND_AUTO_CREATE)
                    if (!bound) throw RecorderError("SERVICE_UNAVAILABLE", "Unable to bind recorder; retry")
                }
                main.postDelayed({
                    if (pending.remove(request)) {
                        reject(promise, RecorderError("SERVICE_UNAVAILABLE", "Recorder binding timed out; retry"))
                        if (pending.isEmpty() && service == null) releaseBinding("Binding timed out")
                    }
                }, 10000)
            } catch (error: Exception) { pending.remove(request); reject(promise, error) }
        }
    }
    private fun releaseBinding(reason: String) {
        service?.removeListener(listener); service = null
        if (bound) { runCatching { context?.unbindService(connection) }; bound = false }
        pending.toList().forEach { reject(it.first, RecorderError("SERVICE_UNAVAILABLE", reason)) }; pending.clear()
        // A started foreground service owns recording; detaching JS only releases its binding.
    }
    private fun reject(promise: Promise, error: Throwable) {
        val code = when (error) {
            is RecorderError -> error.code
            is SecurityException -> "PERMISSION_DENIED"
            else -> "SERVICE_UNAVAILABLE"
        }
        promise.reject(code, error.message ?: "Recorder command failed", error)
    }
}
