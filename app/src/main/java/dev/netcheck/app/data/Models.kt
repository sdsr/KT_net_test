package dev.netcheck.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startedAt: Long, val endedAt: Long? = null, val status: String = "RUNNING")

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long, val scheduledAt: Long, val startedAt: Long, val finishedAt: Long,
    val status: String, val operator: String = "", val simId: Int = -1,
    val networkType: String = "UNKNOWN", val displayType: String = "UNKNOWN",
    val cellsJson: String = "[]", val probesJson: String = "[]",
    val latitude: Double? = null, val longitude: Double? = null,
    val accuracyM: Float? = null, val locationAt: Long? = null, val locationAgeMs: Long? = null,
    val speedMps: Float? = null, val batteryPct: Int? = null,
    val charging: Boolean = false, val powerSave: Boolean = false, val screenOn: Boolean = false,
    val vpn: Boolean = false, val validated: Boolean = false,
    val rxBytes: Long? = null, val txBytes: Long? = null,
    val cellChanged: Boolean = false, val note: String = ""
)

@Entity(tableName = "events")
data class EventEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: Long? = null, val at: Long = System.currentTimeMillis(), val kind: String, val detail: String)

@Entity(tableName = "speed_tests")
data class SpeedTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: Long? = null,
    val startedAt: Long, val finishedAt: Long,
    val downloadMbps: Double? = null, val uploadMbps: Double? = null,
    val downloadBytes: Long = 0, val uploadBytes: Long = 0,
    val status: String, val server: String = "speed.cloudflare.com",
    val networkType: String = "UNKNOWN", val latitude: Double? = null, val longitude: Double? = null,
    val cellsJson: String = "[]", val error: String? = null,
    val detailsJson: String = "{}"
)

data class PingAttempt(val address: String, val sequence: Int, val rttMs: Double? = null, val status: String, val error: String? = null)
data class SpeedProgress(val phase: String = "", val bytes: Long = 0, val mbps: Double? = null)
data class SpeedResult(val downloadMbps: Double?, val uploadMbps: Double?, val downloadBytes: Long, val uploadBytes: Long, val status: String, val error: String? = null, val detailsJson: String = "{}")
data class MonitorUiState(val running: Boolean = false, val sessionId: Long? = null, val speedRunning: Boolean = false, val speedProgress: SpeedProgress = SpeedProgress(), val message: String = "측정을 시작하면 기록이 쌓입니다", val lastSampleAt: Long? = null)
