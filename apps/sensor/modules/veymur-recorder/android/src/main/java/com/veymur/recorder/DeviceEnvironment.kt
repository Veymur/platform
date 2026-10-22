package com.veymur.recorder

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.veymur.recorder.core.RecorderError

internal fun systemInsetsDp(activity: Activity?): Map<String, Double> {
    val window = activity?.window ?: throw RecorderError("NO_ACTIVITY", "Open the app to read screen insets")
    val insets = ViewCompat.getRootWindowInsets(window.decorView)
        ?: throw RecorderError("NO_ACTIVITY", "Screen insets are not available yet; retry")
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    val density = activity.resources.displayMetrics.density.toDouble()
    return mapOf("top" to bars.top / density, "bottom" to bars.bottom / density, "left" to bars.left / density, "right" to bars.right / density)
}

internal fun networkStatus(capabilities: NetworkCapabilities?): Map<String, Any> {
    val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val validated = connected && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    return mapOf("connected" to connected, "validated" to validated)
}

internal fun networkStatus(context: Context): Map<String, Any> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return networkStatus(manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) })
}

internal class NetworkWatcher(private val context: Context, private val onChange: (Map<String, Any>) -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    fun start() {
        if (callback != null || manager == null) return
        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(networkStatus(manager.getNetworkCapabilities(network)))
            override fun onLost(network: Network) = onChange(networkStatus(null as NetworkCapabilities?))
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChange(networkStatus(capabilities))
        }
        try { manager.registerDefaultNetworkCallback(created); callback = created }
        catch (_: SecurityException) { callback = null }
        catch (_: RuntimeException) { callback = null }
    }
    fun stop() {
        val registered = callback ?: return
        callback = null
        runCatching { manager?.unregisterNetworkCallback(registered) }
    }
}
