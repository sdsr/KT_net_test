package dev.netcheck.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.netcheck.app.BuildConfig
import dev.netcheck.app.data.MeasurementEntity

@Composable fun SettingsScreen(slowThresholdMs: Long, speedLimitMiB: Int, speedConsent: Boolean, latest: MeasurementEntity?, onThreshold: (Long) -> Unit, onLimit: (Int) -> Unit, onConsent: (Boolean) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val checks = remember(refresh) {
        listOf(
            "정확한 위치 권한" to (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
            "전화 상태 권한" to (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED),
            "휴대폰 위치 기능" to context.getSystemService(LocationManager::class.java).isLocationEnabled,
            "측정 알림" to (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
            "배터리 최적화 제외" to context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        )
    }
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { onMessage("이 설정 화면을 열 수 없습니다. 휴대폰 설정에서 직접 확인해 주세요.") } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("내 방식으로 기록하기", style = MaterialTheme.typography.headlineSmall)
        SectionCard("자동 점검", "매분 8.8.8.8 · 1.1.1.1에 각각 3회 핑") {
            Text("지연 표시 기준", style = MaterialTheme.typography.bodyMedium)
            ChoiceMenu("${slowThresholdMs} ms 이상", listOf(100L, 200L, 500L, 1000L).map { it.toString() to "$it ms 이상" }) { onThreshold(it.toLong()) }
            Text("이 기준은 기록을 살펴보기 위한 표시 기준입니다. 통신사의 품질 보증 기준을 뜻하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoRow("자동 삭제", "사용 안 함")
            InfoRow("백그라운드 실행", "사용자가 시작 · 중지")
        }
        SectionCard("수동 속도 측정", "다운로드와 업로드의 제한을 각각 적용합니다") {
            ChoiceMenu("방향별 최대 $speedLimitMiB MiB", listOf(5, 20, 100).map { it.toString() to "방향별 $it MiB · 총 ${it * 2} MiB" }) { onLimit(it.toInt()) }
            Text("방향별 최대 10초입니다. 설정한 데이터량 외에 연결·암호화 등의 통신량이 추가됩니다. 작은 한도에서는 최대 속도보다 낮게 측정될 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cloudflare 측정 안내 확인", Modifier.weight(1f))
                Switch(checked = speedConsent, onCheckedChange = { if (!it) onConsent(false) else onMessage("속도 측정 버튼을 눌러 안내를 확인해 주세요.") })
            }
            TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://speed.cloudflare.com/"))) }) { Text("Cloudflare 안내 보기"); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)) }
        }
        SectionCard("현재 기기 설정", "화면이 꺼져 있을 때의 기록을 확인해 주세요") {
            checks.forEach { (name, enabled) -> InfoRow(name, if (enabled) "사용 가능" else "확인 필요") }
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, Modifier.fillMaxWidth()) { Text("앱 권한 설정") }
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("위치 설정") }
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("배터리 최적화 설정") }
            Text("안드로이드 절전 기능이나 강제 종료로 측정이 늦어질 수 있습니다. 늦거나 빠진 회차는 통신 실패와 구분해 기록합니다. 재부팅 후에는 직접 다시 시작해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("데이터 사용과 보관") {
            InfoRow("최근 세션 수신량", bytesText(latest?.rxBytes))
            InfoRow("최근 세션 송신량", bytesText(latest?.txBytes))
            Text("이 세션의 앱 통신량에는 지도와 속도 검사가 포함됩니다. 휴대폰이 제공하는 값으로, 통신사 청구량과 다를 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("위치·셀·측정 기록은 이 휴대폰에 보관합니다. 클라우드 자동 백업은 사용하지 않습니다. 기록 화면에서 ZIP으로 내보내거나 종료한 세션을 삭제할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            Text("핑 대상, 지도 제공자, 속도 측정 서버에는 연결을 위한 IP가 전달됩니다. 지도에는 보고 있는 영역의 타일을 요청하며 위치·셀 기록 자체를 업로드하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("망체크 ${BuildConfig.VERSION_NAME}\n개인용 통신 상태 기록 도구 · KT 공식 앱이 아닙니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}
