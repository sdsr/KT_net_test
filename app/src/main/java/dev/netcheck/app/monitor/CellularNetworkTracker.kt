package dev.netcheck.app.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager

@SuppressLint("MissingPermission")
internal class CellularNetworkTracker(context: Context, private val changed: (String) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    var network: Network? = null
        private set
    var blocked = false
        private set
    private var subscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var closed = false
    val validated: Boolean get() = network?.let { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true
    val vpnActive: Boolean get() = runCatching {
        connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    fun start(id: Int) {
        closed = false
        vpnCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (!closed) changed("VPN_ACTIVE") }
            override fun onLost(network: Network) { if (!closed) changed("VPN_CHANGED") }
        }.also { callback ->
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            runCatching { connectivity.registerNetworkCallback(request, callback, handler) }
        }
        selectSubscription(id)
    }

    fun selectSubscription(id: Int) {
        if (subscription == id && callback != null) return
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        if (network != null) { network = null; changed("SIM_CHANGED") }
        subscription = id; blocked = false
        if (!SubscriptionManager.isValidSubscriptionId(id)) return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).apply {
            if (Build.VERSION.SDK_INT >= 30) setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(id).build())
            else @Suppress("DEPRECATION") setNetworkSpecifier(id.toString())
        }.build()
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) {
                if (subscription != id) return
                if (network != value) { network = value; blocked = false; changed("CELLULAR_AVAILABLE") }
            }
            override fun onLost(value: Network) {
                if (subscription != id) return
                if (network == value) { network = null; blocked = false; changed("CELLULAR_LOST") }
            }
            override fun onUnavailable() { if (subscription == id) { network = null; changed("CELLULAR_UNAVAILABLE") } }
            override fun onBlockedStatusChanged(value: Network, isBlocked: Boolean) {
                if (subscription != id) return
                if (network == value) { blocked = isBlocked; if (isBlocked) changed("CELLULAR_BLOCKED") }
            }
        }.also { callback ->
            try { connectivity.requestNetwork(request, callback, handler) }
            catch (error: RuntimeException) { changed("NETWORK_REQUEST_ERROR:${error.javaClass.simpleName}") }
        }
    }

    fun close() {
        closed = true
        subscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        vpnCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null; vpnCallback = null; network = null
    }
}
