package dev.netcheck.app.ui

import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netcheck.app.NetCheckApplication
import dev.netcheck.app.data.*
import dev.netcheck.app.monitor.MonitorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun NetCheckApp(app: NetCheckApplication, onStart: () -> Unit, onStartSpeed: () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF80D7C6), onPrimary = Color(0xFF00382F), primaryContainer = Color(0xFF155044), secondary = Color(0xFFB0CCC3), secondaryContainer = Color(0xFF304D43), onSecondaryContainer = Color(0xFFD4F1E5), surface = Color(0xFF101715), surfaceContainerLow = Color(0xFF19211E), surfaceContainerHigh = Color(0xFF26312D), surfaceContainerHighest = Color(0xFF303D37), background = Color(0xFF101715))
    else lightColorScheme(primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFD6F1E6), secondary = Color(0xFF4B655B), secondaryContainer = Color(0xFFD9EBE2), onSecondaryContainer = Color(0xFF153D30), surface = Color(0xFFFAFCF9), background = Color(0xFFFAFCF9), surfaceContainerLow = Color(0xFFF0F5F0), surfaceContainerHigh = Color(0xFFE6EEE7), surfaceContainerHighest = Color(0xFFDDE8DF), outlineVariant = Color(0xFFD5DED6))
    MaterialTheme(colorScheme = colors) { NetCheckContent(app, onStart, onStartSpeed) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NetCheckContent(app: NetCheckApplication, onStart: () -> Unit, onStartSpeed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dao = app.dao
    val state by MonitorController.state.collectAsStateWithLifecycle()
    val measurements by remember { dao.observeMeasurements() }.collectAsStateWithLifecycle(emptyList())
    val sessions by remember { dao.observeSessions() }.collectAsStateWithLifecycle(emptyList())
    val events by remember { dao.observeEvents() }.collectAsStateWithLifecycle(emptyList())
    val speeds by remember { dao.observeSpeedTests() }.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var slowThreshold by remember { mutableLongStateOf(app.settings.slowThresholdMs) }
    var speedLimit by remember { mutableIntStateOf(app.settings.speedLimitMiB) }
    var speedConsent by remember { mutableStateOf(app.settings.speedConsent) }
    var speedDialog by remember { mutableStateOf(false) }
    var markDialog by remember { mutableStateOf(false) }
    var markNote by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<MeasurementEntity?>(null) }
    var speedDetail by remember { mutableStateOf<SpeedTestEntity?>(null) }
    var selectedSession by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var cellQuery by rememberSaveable { mutableStateOf("") }
    var archive by remember { mutableStateOf<List<MeasurementEntity>>(emptyList()) }
    var archiveEvents by remember { mutableStateOf<List<EventEntity>>(emptyList()) }
    var archiveSpeeds by remember { mutableStateOf<List<SpeedTestEntity>>(emptyList()) }
    var archiveLoading by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    LaunchedEffect(tab, selectedSession, measurements.firstOrNull()?.id, events.firstOrNull()?.id, speeds.firstOrNull()?.id, sessions.size) {
        if (tab != 1 && tab != 2) return@LaunchedEffect
        archiveLoading = true
        try {
            val data = withContext(Dispatchers.IO) { Triple(dao.allMeasurements(selectedSession).asReversed(), dao.allEvents(selectedSession).asReversed(), dao.allSpeedTests(selectedSession).asReversed()) }
            archive = data.first; archiveEvents = data.second; archiveSpeeds = data.third
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { message("기록을 읽지 못했습니다. 다시 시도해 주세요.") }
        finally { archiveLoading = false }
    }
    val filtered = remember(archive, selectedDate, cellQuery) { archive.filter { (selectedDate == null || dateText(it.startedAt) == selectedDate) && (cellQuery.isBlank() || cellKeys(it).any { key -> key.contains(cellQuery.trim(), ignoreCase = true) }) } }
    val filteredSpeeds = remember(archiveSpeeds, selectedDate, cellQuery) { archiveSpeeds.filter { (selectedDate == null || dateText(it.startedAt) == selectedDate) && (cellQuery.isBlank() || jsonObjects(it.cellsJson).any { c -> cellKey(c).contains(cellQuery.trim(), ignoreCase = true) }) } }
    val filteredEvents = remember(archiveEvents, selectedDate, cellQuery) { archiveEvents.filter { (selectedDate == null || dateText(it.at) == selectedDate) && (cellQuery.isBlank() || it.detail.contains(cellQuery.trim(), ignoreCase = true)) } }
    val dates = remember(archive) { archive.map { dateText(it.startedAt) }.distinct() }
    val canDelete = selectedSession != null && selectedSession != state.sessionId && !deleting
    fun export() {
        scope.launch {
            exporting = true
            try {
                val file = withContext(Dispatchers.IO) { CsvExporter(context).export(selectedSession) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply { type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                context.startActivity(Intent.createChooser(intent, "측정 기록 내보내기"))
            } catch (e: Exception) { message("내보내기에 실패했습니다: ${e.localizedMessage ?: "다시 시도해 주세요"}") }
            finally { exporting = false }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) { Icon(Icons.Outlined.NetworkCheck, null, tint = MaterialTheme.colorScheme.primary); Text("망체크", fontWeight = FontWeight.Bold) } }, actions = {
                if (state.running) StatePill("기록 중") else Text("연결의 기록", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(20.dp))
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val tabs = listOf("현재" to Icons.Outlined.Dashboard, "지도" to Icons.Outlined.Map, "기록" to Icons.Outlined.History, "설정" to Icons.Outlined.Settings)
                tabs.forEachIndexed { i, item -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) }) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 1 || tab == 2) FilterPanel(sessions, selectedSession, dates, selectedDate, cellQuery, onSession = { selectedSession = it; selectedDate = null }, onDate = { selectedDate = it }, onCellQuery = { cellQuery = it })
            when (tab) {
                0 -> CurrentScreen(state, measurements, speeds, speedLimit, onStart, onStop = { MonitorController.stop(context) }, onSpeed = { if (speedConsent) onStartSpeed() else speedDialog = true }, onCancelSpeed = { MonitorController.cancelSpeedTest(context) }, onMarkSlow = { markDialog = true }, onMeasurement = { detail = it }, onSpeedDetail = { speedDetail = it })
                1 -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatePill("정상", Teal); StatePill("지연·일부 실패", Amber); StatePill("응답 없음", Red) }
                    if (archiveLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    MeasurementMap(filtered, "$selectedSession/$selectedDate/$cellQuery", state.speedRunning, Modifier.weight(1f)) { detail = it }
                    Text("휴대폰의 측정 위치 · 점을 누르면 상세 보기\n회색은 미측정 또는 부정확한 위치입니다. 오래된 위치와 기록 공백은 선으로 잇지 않습니다.", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                2 -> RecordScreen(filtered, filteredEvents, filteredSpeeds, selectedSession, canDelete, exporting, archiveLoading, onExport = ::export, onDelete = { deleteDialog = true }, onMeasurement = { detail = it }, onSpeed = { speedDetail = it })
                3 -> SettingsScreen(slowThreshold, speedLimit, speedConsent, measurements.firstOrNull(), onThreshold = { slowThreshold = it; app.settings.slowThresholdMs = it }, onLimit = { speedLimit = it; app.settings.speedLimitMiB = it }, onConsent = { speedConsent = it; app.settings.speedConsent = it }, onMessage = ::message)
            }
        }
    }
    detail?.let { MeasurementDialog(it) { detail = null } }
    speedDetail?.let { SpeedDialog(it) { speedDetail = null } }
    if (speedDialog) AlertDialog(onDismissRequest = { speedDialog = false }, title = { Text("속도 측정 안내") }, text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cloudflare 서버로 실제 데이터를 주고받아 다운로드·업로드 속도를 확인합니다. 방향별 최대 $speedLimit MiB이며 통신 부가량이 추가됩니다.")
        Text("Cloudflare는 연결 IP를 받아 대략적인 지역·통신망을 파악하고 익명화된 측정 정보를 공유한다고 안내합니다. 앱의 위치·셀 기록은 전송하지 않습니다.")
        Text("측정 중 자동 핑과 지도 다운로드는 잠시 멈춥니다.${if (!state.running) " 기록 세션도 함께 시작합니다." else ""}", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = { speedConsent = true; app.settings.speedConsent = true; speedDialog = false; onStartSpeed() }) { Text("확인하고 측정") } }, dismissButton = { TextButton(onClick = { speedDialog = false }) { Text("취소") } })
    if (markDialog) AlertDialog(onDismissRequest = { markDialog = false }, title = { Text("지금 느린 상황을 남겨요") }, text = { OutlinedTextField(value = markNote, onValueChange = { markNote = it.take(500) }, label = { Text("메모 (선택)") }, placeholder = { Text("영상 멈춤, 웹페이지 안 열림 등") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { MonitorController.markSlow(context, markNote.ifBlank { "지금 느림" }); markDialog = false; markNote = ""; message("느린 시점과 메모를 기록했습니다.") }) { Text("기록") } }, dismissButton = { TextButton(onClick = { markDialog = false }) { Text("취소") } })
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("이 세션을 삭제할까요?") }, text = { Text("선택한 세션의 위치·셀·핑·속도 검사·메모가 함께 삭제됩니다. 내보낸 ZIP 파일에는 영향을 주지 않습니다.") }, confirmButton = { TextButton(enabled = canDelete, onClick = {
        val id = selectedSession ?: return@TextButton
        if (id == MonitorController.state.value.sessionId) return@TextButton
        deleteDialog = false; deleting = true
        scope.launch { try { withContext(Dispatchers.IO) { dao.deleteSession(id) }; selectedSession = null; message("세션을 삭제했습니다.") } catch (e: Exception) { message("세션을 삭제하지 못했습니다.") } finally { deleting = false } }
    }) { Text("삭제", color = Red) } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("취소") } })
}
