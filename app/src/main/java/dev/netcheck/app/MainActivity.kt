package dev.netcheck.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.netcheck.app.monitor.MonitorController
import dev.netcheck.app.ui.NetCheckApp

class MainActivity : ComponentActivity() {
    private var pendingSpeed = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMeasurementPermissions()) performStart(pendingSpeed)
        else Toast.makeText(this, "측정하려면 정확한 위치와 전화 상태 접근을 허용해 주세요. 기록 조회는 계속 사용할 수 있습니다.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NetCheckApp(application as NetCheckApplication, onStart = { requestStart(false) }, onStartSpeed = { requestStart(true) }) }
    }

    private fun hasMeasurementPermissions(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStart(speed: Boolean) {
        pendingSpeed = speed
        if (!hasMeasurementPermissions()) {
            val permissions = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            permissionRequest.launch(permissions.toTypedArray())
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        performStart(speed)
    }

    private fun performStart(speed: Boolean) {
        if (!getSystemService(LocationManager::class.java).isLocationEnabled) {
            Toast.makeText(this, "휴대폰의 위치 기능을 켠 뒤 다시 시작해 주세요.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        runCatching {
            if (speed) MonitorController.startSpeedTest(this) else MonitorController.start(this)
        }.onFailure { Toast.makeText(this, "측정을 시작하지 못했습니다: ${it.localizedMessage ?: "다시 시도해 주세요"}", Toast.LENGTH_LONG).show() }
    }
}
