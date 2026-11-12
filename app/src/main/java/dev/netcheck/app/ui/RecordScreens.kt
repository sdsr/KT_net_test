package dev.netcheck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.netcheck.app.data.*

@Composable fun ChoiceMenu(label: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(label, maxLines = 1); Spacer(Modifier.width(5.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(key) }) }
        }
    }
}

@Composable fun FilterPanel(sessions: List<SessionEntity>, selectedSession: Long?, dates: List<String>, selectedDate: String?, cellQuery: String, onSession: (Long?) -> Unit, onDate: (String?) -> Unit, onCellQuery: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceMenu(selectedSession?.let { id -> sessions.firstOrNull { it.id == id }?.let { "${dateText(it.startedAt)} ${timeText(it.startedAt)}" } } ?: "모든 세션", listOf("" to "모든 세션") + sessions.map { it.id.toString() to "${dateText(it.startedAt)} ${timeText(it.startedAt)} · ${statusText(it.status)}" }) { onSession(it.toLongOrNull()) }
            ChoiceMenu(selectedDate ?: "모든 날짜", listOf("" to "모든 날짜") + dates.map { it to it }) { onDate(it.ifBlank { null }) }
        }
        OutlinedTextField(value = cellQuery, onValueChange = onCellQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp), label = { Text("셀 ID 검색") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = if (cellQuery.isNotBlank()) {{ IconButton(onClick = { onCellQuery("") }) { Icon(Icons.Outlined.Close, "검색 지우기") } }} else null)
    }
}

@Composable fun RecordScreen(measurements: List<MeasurementEntity>, events: List<EventEntity>, speedTests: List<SpeedTestEntity>, selectedSession: Long?, canDelete: Boolean, exporting: Boolean, loading: Boolean, onExport: () -> Unit, onDelete: () -> Unit, onMeasurement: (MeasurementEntity) -> Unit, onSpeed: (SpeedTestEntity) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("기록 ${measurements.size}건", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("내보내기: 선택 세션 전체", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row {
                    IconButton(onClick = onExport, enabled = !exporting) { if (exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Share, "선택한 세션 내보내기") }
                    if (selectedSession != null) IconButton(onClick = onDelete, enabled = canDelete) { Icon(Icons.Outlined.DeleteOutline, "선택한 세션 삭제") }
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (measurements.isEmpty() && !loading) item { EmptyState("표시할 기록이 없어요", "측정을 시작하거나 날짜·세션·셀 필터를 바꿔 보세요.") }
        items(measurements, key = { "m${it.id}" }) { m ->
            Card(Modifier.fillMaxWidth().clickable { onMeasurement(m) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(timeText(m.startedAt), fontWeight = FontWeight.SemiBold); Text(dateText(m.startedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("${m.networkType} · ${statusText(m.status)}", style = MaterialTheme.typography.bodySmall, color = Color(android.graphics.Color.parseColor(mapColor(m.status))))
                        val serving = jsonObjects(m.cellsJson).firstOrNull { it.optBoolean("registered") }
                        Text(serving?.let(::cellKey) ?: "셀 정보 미제공", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    val values = pingTimes(m)
                    Column(horizontalAlignment = Alignment.End) { Text(numberText(values.takeIf { it.isNotEmpty() }?.average()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("ms 평균", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (speedTests.isNotEmpty()) {
            item { Text("속도 측정", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(speedTests, key = { "s${it.id}" }) { speed ->
                Card(Modifier.fillMaxWidth().clickable { onSpeed(speed) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${dateText(speed.startedAt)} ${timeText(speed.startedAt)}", style = MaterialTheme.typography.labelMedium)
                        Text("다운로드 ${numberText(speed.downloadMbps)} · 업로드 ${numberText(speed.uploadMbps)} Mbps", fontWeight = FontWeight.Medium)
                        Text(speedStatusText(speed.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (events.isNotEmpty()) {
            item { Text("변화와 메모", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(events.take(100), key = { "e${it.id}" }) { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(timeText(event.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(eventText(event), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (events.size > 100) item { Text("화면에는 최근 변화 100건을 표시합니다. 내보내기에는 전체 기록이 포함됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable fun MeasurementDialog(m: MeasurementEntity, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${timeText(m.startedAt)} 측정 기록") }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatePill(statusText(m.status), Color(android.graphics.Color.parseColor(mapColor(m.status))))
            InfoRow("측정 날짜", dateText(m.startedAt))
            InfoRow("예정 / 실행", "${timeText(m.scheduledAt)} / ${timeText(m.startedAt)}")
            InfoRow("통신사 / SIM", "${m.operator.ifBlank { "미제공" }} / ${m.simId}")
            InfoRow("접속 / 표시", "${m.networkType} / ${m.displayType}")
            InfoRow("위치", if (m.latitude != null && m.longitude != null) "${numberText(m.latitude, 6)}, ${numberText(m.longitude, 6)}" else "미수집")
            InfoRow("위치 정확도", "${numberText(m.accuracyM?.toDouble(), 0)} m")
            InfoRow("위치 획득", "${timeText(m.locationAt)} · ${m.locationAgeMs?.div(1000) ?: "—"}초 전")
            InfoRow("배터리", "${m.batteryPct ?: "—"}% · ${if (m.charging) "충전 중" else "충전 안 함"}")
            InfoRow("절전 / 화면", "${if (m.powerSave) "절전 켜짐" else "절전 꺼짐"} / ${if (m.screenOn) "켜짐" else "꺼짐"}")
            if (m.vpn) Text("VPN이 감지된 기록입니다.", color = Amber)
            if (m.cellChanged) Text("검사 도중 셀이 변경되어 한 셀의 성능으로 단정할 수 없습니다.", color = Amber)
            if (m.note.isNotBlank()) Text(m.note)
            HorizontalDivider()
            Text("대상별 핑", fontWeight = FontWeight.Bold)
            val probes = jsonObjects(m.probesJson)
            if (probes.isEmpty()) Text("실행된 핑이 없습니다.")
            probes.forEach { p ->
                val value = if (p.isNull("rttMs")) statusText(p.optString("status")) else "${numberText(p.optDouble("rttMs"))} ms"
                InfoRow("${p.optString("address")} #${p.optInt("sequence")}", value)
                if (!p.isNull("error") && p.optString("error").isNotBlank()) Text(p.optString("error"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Text("당시 관측한 셀", fontWeight = FontWeight.Bold)
            val cells = jsonObjects(m.cellsJson)
            if (cells.isEmpty()) Text("이 기기 또는 현재 상태에서 셀 정보가 제공되지 않았습니다.")
            cells.forEach { c ->
                Text(cellKey(c), fontWeight = FontWeight.SemiBold)
                InfoRow("등록 / 역할", "${if (c.optBoolean("registered")) "등록됨" else "주변 셀"} / ${field(c, "connectionStatus")}")
                InfoRow("PCI / TAC", "${field(c, "pci")} / ${field(c, "tac")}")
                InfoRow("밴드 / 채널", "${field(c, "bands")} / ${field(c, "channel")}")
                InfoRow("RSRP / RSRQ", "${field(c, "rsrp")} dBm / ${field(c, "rsrq")} dB")
                InfoRow("SINR", "${field(c, "sinr")} dB")
                InfoRow("정보 경과 시간", if (c.isNull("ageMs")) "미제공" else "${c.optLong("ageMs") / 1000}초")
                HorizontalDivider()
            }
            Text("위치는 휴대폰의 위치입니다. 셀 ID는 기지국의 설치 좌표를 뜻하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
    })
}

@Composable fun SpeedDialog(speed: SpeedTestEntity, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("속도 측정 결과") }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${dateText(speed.startedAt)} ${timeText(speed.startedAt)}", style = MaterialTheme.typography.bodySmall)
            InfoRow("다운로드", "${numberText(speed.downloadMbps)} Mbps")
            InfoRow("업로드", "${numberText(speed.uploadMbps)} Mbps")
            InfoRow("받은 데이터", bytesText(speed.downloadBytes))
            InfoRow("보낸 데이터", bytesText(speed.uploadBytes))
            InfoRow("결과", speedStatusText(speed.status))
            InfoRow("소요 시간", "${numberText((speed.finishedAt - speed.startedAt) / 1000.0)}초")
            InfoRow("측정 대상", speed.server)
            InfoRow("접속망", speed.networkType)
            if (speed.latitude != null && speed.longitude != null) InfoRow("당시 위치", "${numberText(speed.latitude, 5)}, ${numberText(speed.longitude, 5)}")
            speed.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = Red) }
            Text("Cloudflare 서버까지 HTTP 데이터를 전송한 결과입니다. 공식 웹사이트 측정값과 다를 수 있으며, 짧은 검사나 데이터 제한 측정은 최대 속도를 충분히 보여주지 못할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        }
    })
}
