package com.oojoo.farm.slave

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.oojoo.farm.slave.data.AppLocale
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.service.FarmerService
import com.oojoo.farm.slave.ui.DashboardScreen
import com.oojoo.farm.slave.ui.OojooSlaveTheme
import com.oojoo.farm.slave.ui.PairingScreen
import com.oojoo.farm.slave.ui.SettingsScreen

class MainActivity : AppCompatActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onStart() {
        super.onStart()
        // 앱이 화면에 보이는 시점에 서비스를 재지시 → FGS camera 타입 재요청 +
        // 카메라 재바인딩 (부팅/시스템 재시작 직후 백그라운드 시작으로 카메라가
        // 비활성 상태였던 경우 복구).
        if (Prefs.isPaired(this)) {
            FarmerService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLocale.initialize(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ApiClient.setBaseUrl(Prefs.serverUrl(this))
        ApiClient.setSessionKey(Prefs.sessionKey(this))

        requestRuntimePermissions()

        // 이미 페어링된 기기면 자율 관리 서비스를 즉시 기동 (헤드리스/상시 동작)
        if (Prefs.isPaired(this)) {
            FarmerService.start(this)
        }

        setContent {
            OojooSlaveTheme {
                val nav = rememberNavController()
                val start = if (Prefs.isPaired(this)) "dashboard" else "pairing"
                NavHost(nav, startDestination = start) {
                    composable("pairing") { PairingScreen(nav) }
                    composable("dashboard") { DashboardScreen(nav) }
                    composable("settings") { SettingsScreen(nav) }
                    composable("roi") { com.oojoo.farm.slave.ui.RoiEditorScreen(nav) }
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}
