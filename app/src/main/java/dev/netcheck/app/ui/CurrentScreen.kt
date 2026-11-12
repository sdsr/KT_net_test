package dev.netcheck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.sp
import dev.netcheck.app.data.*

@Composable fun CurrentScreen(
    state: MonitorUiState,
    measurements: List<MeasurementEntity>,
    speedTests: List<SpeedTestEntity>,
    speedLimitMiB: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSpeed: () -> Unit,
    onCancelSpeed: () -> Unit,
    onMarkSlow: () -> Unit,
    onMeasurement: (MeasurementEntity) -> Unit,
    onSpeedDetail: (SpeedTestEntity) -> Unit
) {
    val latest = measurements.firstOrNull()
    val values = pingTimes(latest)
    val probes = latest?.let { jsonObjects(it.probesJson) }.orEmpty()
    val latestSpeed = speedTests.firstOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF103D39), RoundedCornerShape(26.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                StatePill(if (state.running) "기록 중 · 1분 간격" else "측정 대기", if (state.running) Color(0xFF80DECA) else Color(0xFFBDD1CD))
                Icon(Icons.Outlined.NetworkCheck, contentDescription = null, tint = Color(0xFF80DECA), modifier = Modifier.size(30.dp))
            }
            Text(if (state.running) "연결의 순간을\n기록하고 있어요" else "어디서 느려지는지\n기록해 보세요", fontSize = 27.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(state.message, color = Color(0xFFC2DAD5), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = if (state.running) onStop else onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = if (state.running) Color(0xFF355955) else Color(0xFFB1F2DB), contentColor = if (state.running) Color.White else Color(0xFF103D39))) {
                Icon(if (state.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null)
                Spacer(Modifier.width(7.dp))
                Text(if (state.running) "기록 중지" else "기록 시작", fontWeight = FontWeight.Bold)
            }
        }

        SectionCard("최근 핑", latest?.let { "${dateText(it.startedAt)} ${timeText(it.startedAt)} · ${it.operator.ifBlank { "통신사 미제공" }}" } ?: "매분 두 곳에 각각 3회 확인합니다") {
            if (latest == null) {
                EmptyState("아직 측정 기록이 없어요", "기록을 시작하면 응답 시간과 연결 상태를 확인할 수 있습니다.")
            } else {
                StatePill(statusText(latest.status), Color(android.graphics.Color.parseColor(mapColor(latest.status))))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(numberText(values.takeIf { it.isNotEmpty() }?.average()), "ms", "평균 응답", Modifier.weight(1f))
                    Metric(numberText(values.maxOrNull()), "ms", "가장 긴 응답", Modifier.weight(1f))
                    Metric("${values.size}/${probes.size}", "", "응답 / 시도", Modifier.weight(1f))
                }
                PingChart(measurements.take(30).reversed().map { pingTimes(it).takeIf { p -> p.isNotEmpty() }?.average() })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("최근 ${minOf(measurements.size, 30)}회 · 빈 구간은 미측정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onMeasurement(latest) }, contentPadding = PaddingValues(0.dp)) { Text("자세히"); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp)) }
                }
            }
            OutlinedButton(onClick = onMarkSlow, enabled = state.running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Flag, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("지금 느림 · 메모 남기기")
            }
        }

        SectionCard("접속망과 위치") {
            val cells = latest?.let { jsonObjects(it.cellsJson) }.orEmpty()
            val serving = cells.firstOrNull { it.optBoolean("registered") } ?: cells.firstOrNull()
            InfoRow("통신망", latest?.let { "${it.networkType} · 표시 ${it.displayType}" } ?: "—")
            InfoRow("신호 세기", serving?.let { field(it, "rsrp").let { v -> if (v == "미제공") v else "$v dBm" } } ?: "미제공")
            InfoRow("접속 셀", serving?.let(::cellKey) ?: "미제공")
            InfoRow("위치", if (latest?.latitude != null && latest.longitude != null) "${numberText(latest.latitude, 5)}, ${numberText(latest.longitude, 5)}" else "아직 확인되지 않음")
            if (latest?.latitude != null) InfoRow("위치 정확도", "${numberText(latest.accuracyM?.toDouble(), 0)} m · ${timeText(latest.locationAt)} 획득")
            if (latest?.cellChanged == true) Text("이번 검사 중 접속 셀이 변경되었습니다.", color = Amber, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("속도가 궁금할 때", "Cloudflare 대상 HTTP 전송 속도") {
            if (state.speedRunning) {
                Text(when (state.speedProgress.phase.uppercase()) { "DOWNLOAD" -> "다운로드 측정 중"; "UPLOAD" -> "업로드 측정 중"; "CONNECTING", "DNS" -> "측정 서버에 연결 중"; else -> "속도를 측정하고 있어요" }, fontWeight = FontWeight.Medium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                InfoRow("이번 방향 전송량", bytesText(state.speedProgress.bytes))
                InfoRow("현재 전송 속도", "${numberText(state.speedProgress.mbps)} Mbps")
                OutlinedButton(onClick = onCancelSpeed, Modifier.fillMaxWidth()) { Text("속도 측정 취소") }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Metric(numberText(latestSpeed?.downloadMbps), "Mbps", "다운로드", Modifier.weight(1f))
                    Metric(numberText(latestSpeed?.uploadMbps), "Mbps", "업로드", Modifier.weight(1f))
                }
                if (latestSpeed != null) TextButton(onClick = { onSpeedDetail(latestSpeed) }) { Text("${timeText(latestSpeed.startedAt)} 측정 결과 보기"); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp)) }
                Text("버튼을 누를 때만 측정합니다. 다운로드·업로드 각각 최대 ${speedLimitMiB} MiB의 데이터에 통신 부가량이 추가됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onSpeed, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Speed, null); Spacer(Modifier.width(8.dp)); Text("속도 측정") }
            }
        }
        Text("핑은 응답 시간과 응답 유무를 확인합니다. 핑 실패만으로 통신사 장애를 확정하지 않습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp))
        Spacer(Modifier.height(8.dp))
    }
}
