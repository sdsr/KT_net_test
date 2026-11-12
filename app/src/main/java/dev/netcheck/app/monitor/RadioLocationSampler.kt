package dev.netcheck.app.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.telephony.*
import org.json.JSONArray
import org.json.JSONObject

internal data class RadioSnapshot(val operator: String, val simId: Int, val networkType: String, val displayType: String, val cellsJson: String, val servingKey: String)

@SuppressLint("MissingPermission", "NewApi")
internal class RadioLocationSampler(private val context: Context, private val onEvent: (String, String) -> Unit) {
    private val rootTelephony = context.getSystemService(TelephonyManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var telephony: TelephonyManager? = null
    private var modern: TelephonyCallback? = null
    private var legacy: PhoneStateListener? = null
    private var cells: List<CellInfo> = emptyList()
    private var strength: SignalStrength? = null
    private var strengthReceivedAt: Long = 0
    private var lastServingKey = ""
    private var displayType = "UNKNOWN"
    var simId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        private set
    var location: Location? = null
        private set
    private var locationsStarted = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(value: Location) { location = Location(value) }
        override fun onProviderEnabled(provider: String) { onEvent("LOCATION_PROVIDER", "$provider 켜짐") }
        override fun onProviderDisabled(provider: String) { onEvent("LOCATION_PROVIDER", "$provider 꺼짐") }
        @Deprecated("Android compatibility") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun start() { switchSubscription(SubscriptionManager.getDefaultDataSubscriptionId()); startLocations() }

    fun switchSubscription(id: Int) {
        if (telephony != null && simId == id) return
        unregisterTelephony()
        simId = id
        cells = emptyList(); strength = null; displayType = "UNKNOWN"; lastServingKey = ""
        if (!SubscriptionManager.isValidSubscriptionId(id)) return
        telephony = rootTelephony?.createForSubscriptionId(id)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                modern = object : TelephonyCallback(), TelephonyCallback.CellInfoListener,
                    TelephonyCallback.SignalStrengthsListener, TelephonyCallback.DisplayInfoListener,
                    TelephonyCallback.ServiceStateListener {
                    override fun onCellInfoChanged(value: MutableList<CellInfo>) = receiveCells(value, id)
                    override fun onSignalStrengthsChanged(value: SignalStrength) { if (simId == id) { strength = value; strengthReceivedAt = SystemClock.elapsedRealtime() } }
                    override fun onDisplayInfoChanged(value: TelephonyDisplayInfo) { if (simId == id) displayType = displayName(value) }
                    override fun onServiceStateChanged(value: ServiceState) { if (simId == id) onEvent("SERVICE_STATE", "망 등록 상태: ${value.state}") }
                }.also { telephony?.registerTelephonyCallback(context.mainExecutor, it) }
            } else {
                legacy = if (Build.VERSION.SDK_INT >= 30) object : LegacyListener(id) {
                    override fun onDisplayInfoChanged(value: TelephonyDisplayInfo) { if (simId == id) displayType = displayName(value) }
                } else LegacyListener(id)
                @Suppress("DEPRECATION")
                telephony?.listen(legacy, PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE or if (Build.VERSION.SDK_INT >= 30) PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED else 0)
            }
            refresh()
        } catch (error: RuntimeException) { onEvent("RADIO_UNAVAILABLE", error.javaClass.simpleName) }
    }

    @Suppress("DEPRECATION")
    private open inner class LegacyListener(private val expectedSim: Int) : PhoneStateListener() {
        override fun onCellInfoChanged(value: MutableList<CellInfo>?) = receiveCells(value.orEmpty(), expectedSim)
        override fun onSignalStrengthsChanged(value: SignalStrength?) { if (simId == expectedSim) { strength = value; strengthReceivedAt = SystemClock.elapsedRealtime() } }
        override fun onServiceStateChanged(value: ServiceState?) { if (simId == expectedSim) onEvent("SERVICE_STATE", "망 등록 상태: ${value?.state}") }
    }

    fun refresh() {
        val manager = telephony ?: return
        val expectedSim = simId
        try {
            manager.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(value: MutableList<CellInfo>) = receiveCells(value, expectedSim)
                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (simId == expectedSim) onEvent("CELL_REFRESH_UNAVAILABLE", "셀 갱신 미제공: $errorCode")
                }
            })
            // getAllCellInfo() can aggregate several subscriptions; only accept the scoped callback.
        } catch (error: RuntimeException) { onEvent("CELL_REFRESH_UNAVAILABLE", error.javaClass.simpleName) }
    }

    private fun receiveCells(value: List<CellInfo>, expectedSim: Int) {
        if (expectedSim != simId) return
        cells = value.toList()
        val key = servingKey(value)
        if (key != lastServingKey) {
            if (lastServingKey.isNotEmpty()) onEvent("CELL_CHANGED", "$lastServingKey → ${key.ifEmpty { "확인 불가" }}")
            lastServingKey = key
        }
    }

    fun snapshot(): RadioSnapshot {
        val now = SystemClock.elapsedRealtime()
        val values = JSONArray()
        for (cell in cells) values.put(cellJson(cell, now))
        if (cells.isEmpty()) strength?.cellSignalStrengths?.forEach { signal ->
            values.put(signalJson(signal).apply {
                put("source", "signal_callback"); put("registered", JSONObject.NULL)
                put("ageMs", (now - strengthReceivedAt).coerceAtLeast(0)); put("connectionStatus", "UNKNOWN")
            })
        }
        val manager = telephony
        val operator = runCatching { manager?.networkOperatorName.orEmpty() }.getOrDefault("")
        val type = runCatching { networkName(manager?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN) }.getOrDefault("UNKNOWN")
        return RadioSnapshot(operator, simId, type, displayType, values.toString(), servingKey(cells))
    }

    private fun servingKey(items: List<CellInfo>): String = items.filter { it.isRegistered || it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING || it.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING }
        .map { cell ->
            when (cell) {
                is CellInfoLte -> cell.cellIdentity.let { "LTE:${it.mccString}:${it.mncString}:${valid(it.ci)}" }
                is CellInfoNr -> (cell.cellIdentity as CellIdentityNr).let { "NR:${it.mccString}:${it.mncString}:${valid(it.nci)}" }
                else -> cell.javaClass.simpleName
            }
        }.sorted().joinToString("|")

    private fun cellJson(cell: CellInfo, now: Long): JSONObject {
        @Suppress("DEPRECATION") val receivedAt = if (Build.VERSION.SDK_INT >= 30) cell.timestampMillis else cell.timeStamp / 1_000_000
        val signal = when (cell) {
            is CellInfoLte -> cell.cellSignalStrength
            is CellInfoNr -> cell.cellSignalStrength
            is CellInfoGsm -> cell.cellSignalStrength
            is CellInfoWcdma -> cell.cellSignalStrength
            else -> null
        }
        return (signal?.let(::signalJson) ?: JSONObject()).apply {
            put("source", "cell_info"); put("registered", cell.isRegistered)
            put("timestampMillis", receivedAt); put("ageMs", (now - receivedAt).coerceAtLeast(0))
            put("connectionStatus", when (cell.cellConnectionStatus) {
                CellInfo.CONNECTION_PRIMARY_SERVING -> "PRIMARY"
                CellInfo.CONNECTION_SECONDARY_SERVING -> "SECONDARY"
                CellInfo.CONNECTION_NONE -> "NONE"
                else -> "UNKNOWN"
            })
            when (cell) {
                is CellInfoLte -> cell.cellIdentity.let { id ->
                    put("rat", "LTE"); put("mcc", id.mccString ?: JSONObject.NULL); put("mnc", id.mncString ?: JSONObject.NULL)
                    put("ci", valid(id.ci)); put("pci", valid(id.pci)); put("tac", valid(id.tac)); put("channel", valid(id.earfcn))
                    put("bandwidthKhz", valid(id.bandwidth))
                    put("bands", if (Build.VERSION.SDK_INT >= 30) JSONArray(id.bands.toList()) else JSONArray())
                }
                is CellInfoNr -> (cell.cellIdentity as CellIdentityNr).let { id ->
                    put("rat", "NR"); put("mcc", id.mccString ?: JSONObject.NULL); put("mnc", id.mncString ?: JSONObject.NULL)
                    put("nci", valid(id.nci)); put("pci", valid(id.pci)); put("tac", valid(id.tac)); put("channel", valid(id.nrarfcn))
                    put("bands", if (Build.VERSION.SDK_INT >= 30) JSONArray(id.bands.toList()) else JSONArray())
                }
            }
        }
    }

    private fun signalJson(signal: CellSignalStrength): JSONObject = JSONObject().apply {
        put("dbm", valid(signal.dbm)); put("level", signal.level)
        when (signal) {
            is CellSignalStrengthLte -> { put("rat", "LTE"); put("rsrp", valid(signal.rsrp)); put("rsrq", valid(signal.rsrq)); put("sinr", valid(signal.rssnr)); put("rssi", valid(signal.rssi)) }
            is CellSignalStrengthNr -> { put("rat", "NR"); put("rsrp", valid(signal.ssRsrp)); put("rsrq", valid(signal.ssRsrq)); put("sinr", valid(signal.ssSinr)); put("csiRsrp", valid(signal.csiRsrp)); put("csiRsrq", valid(signal.csiRsrq)); put("csiSinr", valid(signal.csiSinr)) }
            is CellSignalStrengthGsm -> put("rat", "GSM")
            is CellSignalStrengthWcdma -> put("rat", "WCDMA")
            else -> put("rat", "UNKNOWN")
        }
    }

    private fun startLocations() {
        if (locationsStarted) return
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { locationManager.allProviders.contains(it) }
            providers.forEach { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()?.let { previous ->
                    if (location == null || previous.elapsedRealtimeNanos > location!!.elapsedRealtimeNanos) location = Location(previous)
                }
                locationManager.requestLocationUpdates(provider, 60_000L, 0f, locationListener, Looper.getMainLooper())
            }
            locationsStarted = true
        } catch (error: RuntimeException) { onEvent("LOCATION_UNAVAILABLE", error.javaClass.simpleName) }
    }

    fun close() { unregisterTelephony(); simId = SubscriptionManager.INVALID_SUBSCRIPTION_ID; runCatching { locationManager.removeUpdates(locationListener) }; locationsStarted = false }

    private fun unregisterTelephony() {
        try {
            if (Build.VERSION.SDK_INT >= 31) modern?.let { telephony?.unregisterTelephonyCallback(it) }
            @Suppress("DEPRECATION") legacy?.let { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) }
        } catch (_: RuntimeException) { }
        modern = null; legacy = null; telephony = null
    }

    private fun displayName(info: TelephonyDisplayInfo): String = when (info.overrideNetworkType) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G 표시 (NSA)"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+ 표시"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE+ 표시"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE Advanced Pro 표시"
        else -> networkName(info.networkType)
    }

    private fun networkName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
        else -> "UNKNOWN"
    }

    private fun valid(value: Int): Any = if (value == CellInfo.UNAVAILABLE) JSONObject.NULL else value
    private fun valid(value: Long): Any = if (value == CellInfo.UNAVAILABLE_LONG) JSONObject.NULL else value
}
