package dev.netcheck.app.ui

import dev.netcheck.app.data.MeasurementEntity
import dev.netcheck.app.data.EventEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.KOREA)
private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.KOREA)
fun timeText(epoch: Long?) = epoch?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(clockFormat) } ?: "—"
fun dateText(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(dateFormat)
fun numberText(value: Double?, digits: Int = 1): String = if (value != null && value.isFinite()) String.format(Locale.KOREA, "%.$digits" + "f", value) else "—"
fun bytesText(bytes: Long?): String = when {
    bytes == null || bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${numberText(bytes / 1024.0)} KiB"
    bytes < 1024L * 1024 * 1024 -> "${numberText(bytes / (1024.0 * 1024))} MiB"
    else -> "${numberText(bytes / (1024.0 * 1024 * 1024), 2)} GiB"
}
fun jsonObjects(raw: String): List<JSONObject> = runCatching { val array = JSONArray(raw); (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.getOrDefault(emptyList())
fun pingTimes(measurement: MeasurementEntity?): List<Double> = measurement?.let { jsonObjects(it.probesJson).mapNotNull { row -> if (row.isNull("rttMs")) null else row.optDouble("rttMs").takeIf { it.isFinite() && it >= 0 } } } ?: emptyList()
fun field(row: JSONObject, key: String): String = if (!row.has(key) || row.isNull(key)) "미제공" else row.opt(key)?.toString() ?: "미제공"
fun cellKey(row: JSONObject): String {
    val id = when { !row.isNull("nci") && row.has("nci") -> field(row, "nci"); else -> field(row, "ci") }
    return "${field(row, "rat")} · ${field(row, "mcc")}/${field(row, "mnc")} · $id"
}
fun cellKeys(measurement: MeasurementEntity) = jsonObjects(measurement.cellsJson).map(::cellKey)
fun statusText(status: String): String = when (status) {
    "SUCCESS", "OK" -> "응답 정상"
    "SLOW" -> "응답 지연"
    "PARTIAL" -> "일부 응답 없음"
    "FAILED", "ERROR" -> "응답 없음"
    "RUNNING" -> "측정 중"
    "COMPLETED", "STOPPED" -> "측정 종료"
    "INTERRUPTED" -> "중단됨"
    "CANCELLED" -> "취소됨"
    "TIMEOUT" -> "시간 초과"
    "UNSUPPORTED" -> "이 기기에서 핑 사용 불가"
    "LIMITED", "DATA_LIMIT", "DATA_LIMITED" -> "데이터 제한 측정"
    "SKIPPED_VPN" -> "VPN 사용으로 보류"
    "SKIPPED_SPEED_TEST", "SKIPPED_SPEED" -> "속도 측정 중 보류"
    "SKIPPED_NO_NETWORK", "SKIPPED_NO_CELLULAR", "NO_CELLULAR", "NO_NETWORK" -> "모바일망 연결 없음"
    "SKIPPED_ICMP_UNAVAILABLE" -> "이 기기에서 핑 사용 불가"
    "SKIPPED_NETWORK_BLOCKED" -> "모바일망 사용 제한"
    "SKIPPED_NETWORK_CHANGED", "NETWORK_CHANGED" -> "측정 중 연결 변경"
    "SKIPPED_NO_RESULT" -> "검사 결과 없음"
    "SKIPPED_LATE", "MISSED", "SKIPPED_DELAY" -> "실행 지연으로 미측정"
    else -> if (status.startsWith("SKIPPED")) "미측정" else status.replace('_', ' ')
}
fun speedStatusText(status: String): String = when (status) {
    "SUCCESS" -> "측정 완료"
    "PARTIAL" -> "일부 측정 완료"
    "FAILED" -> "측정 실패"
    else -> statusText(status)
}
fun eventText(event: EventEntity): String = when (event.kind) {
    "CELL_CHANGED_DURING_PROBE" -> "핑 검사 중 접속 셀이 바뀌었습니다. 시작·종료 셀 정보는 내보내기에 포함됩니다."
    "CELL_REFRESH_UNAVAILABLE" -> "최신 셀 정보를 받지 못했습니다. 기존 정보의 갱신 시각을 확인해 주세요."
    "RADIO_UNAVAILABLE" -> "기기에서 통신 정보를 제공하지 못했습니다."
    "LOCATION_UNAVAILABLE" -> "현재 위치를 확인하지 못했습니다."
    "SERVICE_STATE" -> when (event.detail.substringAfterLast(":").trim()) { "0" -> "모바일망에 등록되었습니다."; "1" -> "모바일망 서비스 범위를 벗어났습니다."; "2" -> "긴급 통화만 가능한 상태입니다."; "3" -> "모바일 무선 기능이 꺼졌습니다."; else -> "모바일망 등록 상태가 변경되었습니다." }
    "NETWORK_CHANGED" -> when (event.detail) { "CELLULAR_AVAILABLE" -> "모바일 데이터 연결을 확인했습니다."; "CELLULAR_LOST" -> "모바일 데이터 연결이 끊겼습니다."; "CELLULAR_UNAVAILABLE" -> "모바일 데이터 연결을 사용할 수 없습니다."; "CELLULAR_BLOCKED" -> "시스템이 모바일 데이터 사용을 제한했습니다."; "VPN_ACTIVE" -> "VPN이 감지되어 인터넷 검사를 보류합니다."; "VPN_CHANGED" -> "VPN 연결 상태가 바뀌었습니다."; "SIM_CHANGED" -> "기본 데이터 SIM이 변경되었습니다."; else -> "측정할 모바일망의 상태가 바뀌었습니다." }
    else -> event.detail
}
fun mapColor(status: String): String = when {
    status == "SUCCESS" || status == "OK" -> "#087F72"
    status == "SLOW" || status == "PARTIAL" -> "#C37713"
    status == "FAILED" || status == "ERROR" -> "#C74449"
    else -> "#7D8992"
}
fun validRoutePoint(m: MeasurementEntity): Boolean = m.latitude != null && m.longitude != null &&
    m.latitude.isFinite() && m.longitude.isFinite() && m.latitude in -90.0..90.0 && m.longitude in -180.0..180.0 &&
    m.accuracyM != null && m.accuracyM <= 100f && m.accuracyM >= 0f && m.locationAgeMs != null && m.locationAgeMs in 0..119999L

/** Break on missing, inaccurate, old positions and on sample/session gaps. Never invent tower coordinates. */
fun routeSegments(measurements: List<MeasurementEntity>): List<List<MeasurementEntity>> {
    val segments = mutableListOf<List<MeasurementEntity>>()
    var current = mutableListOf<MeasurementEntity>()
    var previous: MeasurementEntity? = null
    for (m in measurements.sortedBy { it.startedAt }) {
        val contiguous = previous?.let { it.sessionId == m.sessionId && m.startedAt - it.startedAt <= 120000L } ?: true
        if (!validRoutePoint(m) || !contiguous) {
            if (current.size > 1) segments += current.toList()
            current = mutableListOf()
        }
        if (validRoutePoint(m)) current += m
        previous = m
    }
    if (current.size > 1) segments += current.toList()
    return segments
}
