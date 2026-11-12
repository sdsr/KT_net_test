package dev.netcheck.app.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.netcheck.app.NetCheckApplication
import dev.netcheck.app.data.EventEntity
import dev.netcheck.app.data.MonitorUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object MonitorController {
    internal val mutableState = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = mutableState.asStateFlow()

    fun start(context: Context) = command(context, MonitorService.ACTION_START, foreground = true)
    fun stop(context: Context) {
        if (state.value.running) command(context, MonitorService.ACTION_STOP)
    }
    fun startSpeedTest(context: Context) = command(context, MonitorService.ACTION_SPEED, foreground = true)
    fun cancelSpeedTest(context: Context) {
        if (state.value.running) command(context, MonitorService.ACTION_CANCEL_SPEED)
    }
    fun markSlow(context: Context, note: String) {
        val app = context.applicationContext as NetCheckApplication
        val session = state.value.sessionId
        app.scope.launch {
            app.recovery.join()
            app.dao.insertEvent(EventEntity(sessionId = session, kind = "USER_SLOW", detail = note.trim().take(1000).ifEmpty { "지금 느림" }))
        }
    }

    private fun command(context: Context, action: String, foreground: Boolean = false) {
        try {
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        } catch (error: RuntimeException) {
            mutableState.update { it.copy(message = "측정을 시작할 수 없습니다. 권한과 배터리 설정을 확인하세요: ${error.javaClass.simpleName}") }
        }
    }
}
