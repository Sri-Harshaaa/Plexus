package com.plexus.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler

/** Observe medium changes, never use Internet availability to gate Nearby. */
internal class NearbyMediumMonitor(private val context: Context, private val handler: Handler,
                                   private val changed: (String) -> Unit) {
    private var running = false
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networks = mutableSetOf<Network>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (running && networks.add(network)) changed("Wi-Fi network available")
        }
        override fun onLost(network: Network) {
            if (running && networks.remove(network)) changed("Wi-Fi network lost")
        }
    }
    fun start() {
        if (running) return
        running = true
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, handler)
        }
    }
    fun stop() {
        running = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        networks.clear()
    }
}
