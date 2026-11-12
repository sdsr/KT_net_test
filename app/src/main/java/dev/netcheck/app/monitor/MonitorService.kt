package dev.netcheck.app.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.*
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.netcheck.app.MainActivity
import dev.netcheck.app.NetCheckApplication
import dev.netcheck.app.R
import dev.netcheck.app.data.*
import dev.netcheck.app.network.CloudflareSpeedTester
import dev.netcheck.app.network.PingEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Only user initiated sessions. START_NOT_STICKY avoids silent reboot/background resurrection. */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as NetCheckApplication
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private lateinit var radio: RadioLocationSampler
    private lateinit var cellular: CellularNetworkTracker
    private var sessionId: Long? = null
    private var startJob: Job? = null
    private var tickerJob: Job? = null
    private var pingJob: Deferred<List<PingAttempt>>? = null
    private var speedJob: Job? = null
    private var speedPendingJob: Job? = null
    // Main-thread tokens; byte observations below remain local to each test.
    private var speedRunId = 0L
    private var speedProgressToken = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private var wantSpeed = false
    private var connectionGeneration = 0L
    private var cancelReason = "CANCELLED"
    private var rxBase = -1L
    private var txBase = -1L
    private var cleanedUp = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "통신 품질 측정", NotificationManager.IMPORTANCE_LOW).apply { description = "1분 점검 상태와 중지 버튼" })
        radio = RadioLocationSampler(this, ::recordEvent)
        cellular = CellularNetworkTracker(this) { reason ->
            connectionGeneration++
            cancelReason = reason
            pingJob?.cancel(CancellationException(reason))
            invalidateSpeedProgress()
            speedJob?.cancel(CancellationException(reason))
            recordEvent("NETWORK_CHANGED", reason)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_STOP -> { stopSession(); return START_NOT_STICKY }
            ACTION_CANCEL_SPEED -> { wantSpeed = false; speedPendingJob?.cancel(); cancelReason = "USER_CANCELLED"; invalidateSpeedProgress(); speedJob?.cancel(CancellationException(cancelReason)); return START_NOT_STICKY }
        }
        if (stopping) return START_NOT_STICKY
        if (!hasRequiredPermissions()) {
            MonitorController.mutableState.update { it.copy(running = false, message = "정확한 위치와 전화 상태 권한을 허용해야 측정할 수 있습니다") }
            stopSelf(); return START_NOT_STICKY
        }
        try { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("측정 준비 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
        catch (error: RuntimeException) {
            MonitorController.mutableState.update { it.copy(running = false, message = "위치 서비스를 켜고 앱 화면에서 다시 시작하세요: ${error.javaClass.simpleName}") }
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SPEED) wantSpeed = true
        if (sessionId != null) { if (wantSpeed) queueSpeedTest(); return START_NOT_STICKY }
        if (startJob?.isActive == true) return START_NOT_STICKY
        MonitorController.mutableState.update { it.copy(running = true, message = "측정 준비 중") }
        startJob = scope.launch {
            try {
                app.recovery.join()
                withContext(NonCancellable) { sessionId = app.dao.insertSession(SessionEntity(startedAt = System.currentTimeMillis())) }
                currentCoroutineContext().ensureActive()
                if (stopping) return@launch
                rxBase = TrafficStats.getUidRxBytes(Process.myUid()); txBase = TrafficStats.getUidTxBytes(Process.myUid())
                radio.start()
                cellular.start(radio.simId)
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetCheck:Monitoring").apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT_MS) }
                MonitorController.mutableState.value = MonitorUiState(running = true, sessionId = sessionId, message = "모바일망 점검 준비 중")
                recordEvent("SESSION_STARTED", if (power.isIgnoringBatteryOptimizations(packageName)) "측정 시작 · 배터리 최적화 제외" else "측정 시작 · 절전 정책에 따라 주기가 지연될 수 있음")
                tickerJob = scope.launch { runSchedule() }
                if (wantSpeed) queueSpeedTest()
            } catch (error: Exception) {
                if (error !is CancellationException) MonitorController.mutableState.update { it.copy(message = "측정 시작 실패: ${error.javaClass.simpleName}") }
                stopSession()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSchedule() {
        var dueElapsed = SystemClock.elapsedRealtime()
        var nextMaintenance = dueElapsed
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextMaintenance) {
                if (!hasRequiredPermissions()) { recordEvent("PERMISSION_REVOKED", "측정 권한이 회수되어 종료합니다"); stopSession(); return }
                val currentSim = SubscriptionManager.getDefaultDataSubscriptionId()
                if (currentSim != radio.simId) {
                    cancelReason = "SIM_CHANGED"; connectionGeneration++
                    invalidateSpeedProgress()
                    pingJob?.cancel(CancellationException(cancelReason)); speedJob?.cancel(CancellationException(cancelReason))
                    radio.switchSubscription(currentSim); cellular.selectSubscription(currentSim)
                    recordEvent("SIM_CHANGED", "기본 데이터 SIM: $currentSim")
                }
                wakeLock?.acquire(WAKE_TIMEOUT_MS)
                nextMaintenance = now + 5_000L
            }
            if (now >= dueElapsed) {
                val missed = MeasurementPolicy.missedIntervals(dueElapsed, now)
                if (missed > 0) recordEvent("SCHEDULE_GAP", "${missed}개 주기 미실행 · 앱/OS 실행 지연")
                val scheduledWall = System.currentTimeMillis() - (now - dueElapsed)
                try { takeMeasurement(scheduledWall) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    MonitorController.mutableState.update { it.copy(message = "기록 저장 실패: ${error.javaClass.simpleName}") }
                    stopSession(); return
                }
                dueElapsed = MeasurementPolicy.nextDue(dueElapsed, SystemClock.elapsedRealtime())
            }
            delay(minOf(1_000L, (dueElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(100L)))
        }
    }

    private suspend fun takeMeasurement(scheduledWall: Long) {
        val session = sessionId ?: return
        radio.refresh()
        val startedAt = System.currentTimeMillis()
        val startRadio = radio.snapshot()
        val generation = connectionGeneration
        val network = cellular.network
        val vpn = cellular.vpnActive
        var note = ""
        var attempts = emptyList<PingAttempt>()
        var status = when {
            speedJob?.isActive == true -> "SKIPPED_SPEED_TEST"
            vpn -> "SKIPPED_VPN"
            cellular.blocked -> "SKIPPED_NETWORK_BLOCKED"
            network == null -> "SKIPPED_NO_CELLULAR"
            else -> "PENDING"
        }
        if (status == "PENDING" && network != null) {
            try {
                val job = scope.async { PingEngine().run(network) }
                pingJob = job
                attempts = job.await()
                status = MeasurementPolicy.status(attempts, app.settings.slowThresholdMs)
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                status = if (speedJob?.isActive == true) "SKIPPED_SPEED_TEST" else "SKIPPED_NETWORK_CHANGED"
                note = cancelReason
            } catch (error: Exception) {
                status = "SKIPPED_ICMP_UNAVAILABLE"; note = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            } finally { pingJob = null }
        }
        if (generation != connectionGeneration && !status.startsWith("SKIPPED")) { status = "SKIPPED_NETWORK_CHANGED"; note = "검사 중 네트워크 변경" }
        val endRadio = radio.snapshot()
        val cellChanged = startRadio.servingKey != endRadio.servingKey
        if (cellChanged) {
            note += " 검사 중 셀 변경 · 단일 셀 성능으로 집계하지 않음"
            recordEvent("CELL_CHANGED_DURING_PROBE", JSONObject().put("startedAt", startedAt).put("startCells", JSONArray(startRadio.cellsJson)).put("endCells", JSONArray(endRadio.cellsJson)).toString())
        }
        val location = radio.location
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val rawRx = TrafficStats.getUidRxBytes(Process.myUid())
        val rawTx = TrafficStats.getUidTxBytes(Process.myUid())
        val finishedAt = System.currentTimeMillis()
        app.dao.insertMeasurement(MeasurementEntity(
            sessionId = session, scheduledAt = scheduledWall, startedAt = startedAt, finishedAt = finishedAt,
            status = status, operator = startRadio.operator, simId = startRadio.simId, networkType = startRadio.networkType, displayType = startRadio.displayType,
            cellsJson = startRadio.cellsJson, probesJson = probesJson(attempts),
            latitude = location?.latitude, longitude = location?.longitude,
            accuracyM = location?.takeIf { it.hasAccuracy() }?.accuracy, locationAt = location?.time,
            locationAgeMs = location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000L },
            speedMps = location?.takeIf { it.hasSpeed() }?.speed,
            batteryPct = if (level >= 0 && scale > 0) (100 * level / scale) else null,
            charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
            powerSave = power.isPowerSaveMode, screenOn = power.isInteractive, vpn = vpn,
            validated = cellular.validated,
            rxBytes = if (rxBase >= 0 && rawRx >= rxBase) rawRx - rxBase else null,
            txBytes = if (txBase >= 0 && rawTx >= txBase) rawTx - txBase else null,
            cellChanged = cellChanged, note = note.trim()
        ))
        val label = when (status) {
            "SUCCESS" -> "두 곳 모두 핑 응답"
            "SLOW" -> "핑 응답 지연"
            "PARTIAL" -> "일부 핑 응답 없음"
            "FAILED" -> "핑 응답 없음"
            "SKIPPED_SPEED_TEST" -> "속도 측정 중 · 핑 검사 보류"
            "SKIPPED_VPN" -> "VPN 사용 중 · 핑 검사 보류"
            "SKIPPED_NO_CELLULAR" -> "셀룰러 연결 없음"
            "SKIPPED_NETWORK_CHANGED" -> "망 변경으로 검사 보류"
            else -> "검사 미실행 · $status"
        }
        MonitorController.mutableState.update { it.copy(lastSampleAt = finishedAt, message = label) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(label))
    }

    private fun queueSpeedTest() {
        if (speedPendingJob?.isActive == true || speedJob?.isActive == true) { wantSpeed = false; return }
        speedPendingJob = scope.launch {
            withTimeoutOrNull(10_000L) { while (cellular.network == null && !cellular.vpnActive) delay(200L) }
            if (!stopping && wantSpeed) launchSpeedTest()
        }
    }

    private fun launchSpeedTest() {
        wantSpeed = false
        if (speedJob?.isActive == true) return
        if (!app.settings.speedConsent) { MonitorController.mutableState.update { it.copy(message = "속도 측정 안내에 동의한 뒤 시작하세요") }; return }
        val network = cellular.network
        if (network == null || cellular.vpnActive || cellular.blocked) {
            MonitorController.mutableState.update { it.copy(message = if (cellular.vpnActive) "VPN을 끈 뒤 속도를 측정하세요" else "셀룰러 연결을 확인한 뒤 다시 측정하세요") }
            return
        }
        cancelReason = "SPEED_TEST_STARTED"
        pingJob?.cancel(CancellationException(cancelReason))
        val beginRadio = radio.snapshot()
        val location = radio.location
        val startedAt = System.currentTimeMillis()
        val testSession = sessionId
        val testGeneration = connectionGeneration
        val runId = ++speedRunId
        val progressToken = ++speedProgressToken
        val observedDownload = AtomicLong()
        val observedUpload = AtomicLong()
        MonitorController.mutableState.update { it.copy(speedRunning = true, speedProgress = SpeedProgress(phase = "준비"), message = "Cloudflare 대상 전송 속도 측정 중") }
        speedJob = scope.launch {
            var result: SpeedResult
            try {
                result = CloudflareSpeedTester().run(network, app.settings.speedLimitMiB * 1024L * 1024L) { progress ->
                    when (progress.phase) {
                        "DOWNLOAD" -> observedDownload.updateAndGet { maxOf(it, progress.bytes) }
                        "UPLOAD" -> observedUpload.updateAndGet { maxOf(it, progress.bytes) }
                    }
                    // The network worker may finish a progress callback during cancellation.
                    // Preserve its local byte count, but never publish it into a later test/session.
                    scope.launch {
                        if (!stopping && speedRunId == runId && speedProgressToken == progressToken) {
                            MonitorController.mutableState.update { current ->
                                if (current.sessionId == testSession && current.speedRunning) current.copy(speedProgress = progress) else current
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                result = SpeedResult(null, null, observedDownload.get(), observedUpload.get(), "CANCELLED", cancelReason,
                    JSONObject().put("incomplete", true).put("bytesAreLastObservedProgress", true).toString())
            } catch (error: Exception) {
                result = SpeedResult(null, null, observedDownload.get(), observedUpload.get(), "FAILED", "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                    JSONObject().put("incomplete", true).put("bytesAreLastObservedProgress", true).toString())
            }
            if (speedRunId == runId) invalidateSpeedProgress()
            withContext(NonCancellable) {
                try {
                    val endRadio = radio.snapshot()
                    val details = runCatching { JSONObject(result.detailsJson) }.getOrElse { JSONObject() }
                        .put("startCells", JSONArray(beginRadio.cellsJson))
                        .put("endCells", JSONArray(endRadio.cellsJson))
                        .put("cellChanged", beginRadio.servingKey != endRadio.servingKey)
                        .put("networkChanged", testGeneration != connectionGeneration)
                        .put("subscriptionId", beginRadio.simId)
                        .put("networkHandle", network.networkHandle.toString())
                        .put("locationAt", location?.time ?: JSONObject.NULL)
                        .put("locationAccuracyM", location?.takeIf { it.hasAccuracy() }?.accuracy ?: JSONObject.NULL)
                        .put("locationAgeMsAtFinish", location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000L } ?: JSONObject.NULL)
                    app.dao.insertSpeedTest(SpeedTestEntity(
                        sessionId = testSession, startedAt = startedAt, finishedAt = System.currentTimeMillis(),
                        downloadMbps = result.downloadMbps, uploadMbps = result.uploadMbps,
                        downloadBytes = result.downloadBytes, uploadBytes = result.uploadBytes,
                        status = result.status, error = result.error,
                        networkType = beginRadio.networkType, latitude = location?.latitude, longitude = location?.longitude,
                        cellsJson = beginRadio.cellsJson, detailsJson = details.toString()
                    ))
                } catch (error: Exception) {
                    if (!stopping && speedRunId == runId) MonitorController.mutableState.update {
                        if (it.sessionId == testSession) it.copy(message = "속도 결과 저장 실패: ${error.javaClass.simpleName}") else it
                    }
                } finally {
                    if (!stopping && speedRunId == runId) MonitorController.mutableState.update {
                        if (it.sessionId == testSession) it.copy(speedRunning = false, speedProgress = SpeedProgress(), message = "속도 측정 ${if (result.status == "CANCELLED") "취소" else "종료"} · ${result.status}") else it
                    }
                }
            }
        }
    }

    private fun recordEvent(kind: String, detail: String) {
        val session = sessionId ?: return
        app.scope.launch { runCatching { app.dao.insertEvent(EventEntity(sessionId = session, kind = kind, detail = detail)) } }
    }

    private fun stopSession() {
        if (stopping) return
        stopping = true
        invalidateSpeedProgress()
        cancelReason = "USER_STOPPED"
        startJob?.cancel(); tickerJob?.cancel(); pingJob?.cancel(); speedPendingJob?.cancel(); speedJob?.cancel()
        cleanupResources()
        scope.launch {
            withContext(NonCancellable) {
                startJob?.join()
                speedJob?.join()
                sessionId?.let { runCatching { app.dao.endSession(it, System.currentTimeMillis(), "STOPPED") } }
                MonitorController.mutableState.value = MonitorUiState(message = "측정을 중지했습니다")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        invalidateSpeedProgress()
        cleanupResources()
        val startup = startJob
        val markInterrupted = !stopping
        scope.cancel()
        if (markInterrupted) app.scope.launch {
            startup?.join()
            sessionId?.let { runCatching { app.dao.endSession(it, System.currentTimeMillis(), "INTERRUPTED") } }
        }
        MonitorController.mutableState.update { it.copy(running = false, sessionId = null, speedRunning = false) }
        super.onDestroy()
    }

    private fun cleanupResources() {
        if (cleanedUp) return
        cleanedUp = true
        if (::radio.isInitialized) radio.close()
        if (::cellular.isInitialized) cellular.close()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }

    private fun invalidateSpeedProgress() { speedProgressToken++ }

    private fun hasRequiredPermissions(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("망체크 · 1분 간격 측정 중").setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, "중지", stop).build()
    }

    private fun probesJson(attempts: List<PingAttempt>): String = JSONArray().apply {
        attempts.forEach { value -> put(JSONObject().apply {
            put("address", value.address); put("sequence", value.sequence); put("rttMs", value.rttMs ?: JSONObject.NULL)
            put("status", value.status); put("error", value.error ?: JSONObject.NULL)
        }) }
    }.toString()

    companion object {
        const val ACTION_START = "dev.netcheck.app.START"
        const val ACTION_STOP = "dev.netcheck.app.STOP"
        const val ACTION_SPEED = "dev.netcheck.app.SPEED"
        const val ACTION_CANCEL_SPEED = "dev.netcheck.app.CANCEL_SPEED"
        private const val CHANNEL = "monitoring"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_TIMEOUT_MS = 120_000L
    }
}
