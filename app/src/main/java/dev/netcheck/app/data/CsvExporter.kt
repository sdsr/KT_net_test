package dev.netcheck.app.data

import android.content.Context
import androidx.room.withTransaction
import dev.netcheck.app.NetCheckApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CsvCodec {
    fun cell(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return ""
        var text = value.toString()
        // Text cells must remain text when opened by a spreadsheet. Numeric values stay numeric.
        if (value is String && text.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) text = "'$text"
        return "\"" + text.replace("\"", "\"\"") + "\""
    }
    fun row(values: List<Any?>): String = values.joinToString(",", transform = ::cell) + "\r\n"
}

class CsvExporter(private val context: Context) {
    suspend fun export(sessionId: Long? = null): File = withContext(Dispatchers.IO) {
        val app = context.applicationContext as NetCheckApplication
        val dao = app.dao
        val snapshot = app.database.withTransaction {
            ExportSnapshot(dao.allSessions().filter { sessionId == null || it.id == sessionId },
                dao.allMeasurements(sessionId), dao.allEvents(sessionId), dao.allSpeedTests(sessionId))
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val target = File(dir, "netcheck-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.zip")
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                fun text(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                fun csv(name: String, headers: List<String>, rows: Sequence<List<Any?>>) {
                    zip.putNextEntry(ZipEntry(name))
                    val writer = OutputStreamWriter(zip, Charsets.UTF_8)
                    writer.write("\uFEFF")
                    writer.write(CsvCodec.row(headers))
                    rows.forEach { writer.write(CsvCodec.row(it)) }
                    writer.flush()
                    zip.closeEntry()
                }
                text("manifest.json", JSONObject().put("schemaVersion", 1).put("exportedAt", Instant.now().toString())
                    .put("app", "망체크").put("sessionId", sessionId ?: JSONObject.NULL)
                    .put("timestamps", "Unix epoch milliseconds UTC; cell modem timestamps are elapsedRealtime milliseconds")
                    .put("speedMethod", "Cloudflare HTTP payload transfer; not Cloudflare website engine")
                    .put("containsLocation", true).toString(2))
                csv("sessions.csv", listOf("session_id","started_at_ms","ended_at_ms","status"), snapshot.sessions.asSequence().map { listOf(it.id,it.startedAt,it.endedAt,it.status) })
                csv("measurements.csv", listOf("measurement_id","session_id","scheduled_at_ms","started_at_ms","finished_at_ms","status","operator","sim_id","network_type","display_type","latitude","longitude","accuracy_m","location_at_ms","location_age_ms","speed_mps","battery_percent","charging","power_save","screen_on","vpn","validated","uid_rx_bytes","uid_tx_bytes","cell_changed","note"), snapshot.measurements.asSequence().map {
                    listOf(it.id,it.sessionId,it.scheduledAt,it.startedAt,it.finishedAt,it.status,it.operator,it.simId,it.networkType,it.displayType,it.latitude,it.longitude,it.accuracyM,it.locationAt,it.locationAgeMs,it.speedMps,it.batteryPct,it.charging,it.powerSave,it.screenOn,it.vpn,it.validated,it.rxBytes,it.txBytes,it.cellChanged,it.note)
                })
                csv("cells.csv", listOf("measurement_id","cell_index","rat","mcc","mnc","ci","nci","pci","tac","bands","channel","registered","connection_status","rsrp","rsrq","sinr","age_ms","raw_json"), snapshot.measurements.asSequence().flatMap { measurement ->
                    objects(measurement.cellsJson).mapIndexed { index, cell -> listOf(measurement.id,index,cell.value("rat"),cell.value("mcc"),cell.value("mnc"),cell.value("ci"),cell.value("nci"),cell.value("pci"),cell.value("tac"),cell.value("bands"),cell.value("channel"),cell.value("registered"),cell.value("connectionStatus"),cell.value("rsrp"),cell.value("rsrq"),cell.value("sinr"),cell.value("ageMs"),cell.toString()) }
                })
                csv("pings.csv", listOf("measurement_id","address","sequence","rtt_ms","status","error"), snapshot.measurements.asSequence().flatMap { measurement ->
                    objects(measurement.probesJson).map { ping -> listOf(measurement.id,ping.value("address"),ping.value("sequence"),ping.value("rttMs"),ping.value("status"),ping.value("error")) }
                })
                csv("speed_tests.csv", listOf("speed_test_id","session_id","started_at_ms","finished_at_ms","download_mbps","upload_mbps","download_bytes","upload_bytes","status","server","network_type","latitude","longitude","cells_json","error","details_json"), snapshot.speeds.asSequence().map {
                    listOf(it.id,it.sessionId,it.startedAt,it.finishedAt,it.downloadMbps,it.uploadMbps,it.downloadBytes,it.uploadBytes,it.status,it.server,it.networkType,it.latitude,it.longitude,it.cellsJson,it.error,it.detailsJson)
                })
                csv("events.csv", listOf("event_id","session_id","at_ms","kind","detail"), snapshot.events.asSequence().map { listOf(it.id,it.sessionId,it.at,it.kind,it.detail) })
                text("README.txt", "망체크 기록 내보내기 / 형식 버전 1\n\nCSV 파일은 UTF-8 BOM 형식입니다. measurement_id와 session_id로 연결합니다. 빈 값은 미제공 값이며 0이 아닙니다.\n측정 누락·VPN 보류·속도 측정 중 보류는 핑 실패로 계산하지 마세요.\n셀 좌표는 포함하지 않습니다. latitude/longitude는 휴대폰의 관측 위치입니다. 셀 변경은 관측 시점이며 정확한 핸드오버 시각을 보장하지 않습니다.\n속도는 Cloudflare 서버 대상 HTTP 전송 측정값입니다. 한도 도달·취소·짧은 전송 결과는 회선의 최대 속도를 뜻하지 않습니다.\n이 파일에는 이동 경로와 셀 정보가 포함됩니다. 공유할 대상을 직접 선택하세요.\n")
            }
            target
        } catch (error: Exception) { target.delete(); throw error }
    }
    private fun objects(raw: String): Sequence<JSONObject> = sequence {
        val array = JSONArray(raw)
        for (index in 0 until array.length()) array.optJSONObject(index)?.let { yield(it) }
    }
    private fun JSONObject.value(key: String): Any? = opt(key).let { if (it == JSONObject.NULL) null else it }
    private data class ExportSnapshot(val sessions: List<SessionEntity>, val measurements: List<MeasurementEntity>, val events: List<EventEntity>, val speeds: List<SpeedTestEntity>)
}
