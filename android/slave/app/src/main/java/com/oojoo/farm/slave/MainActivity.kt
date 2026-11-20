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

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLocale.initialize(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ApiClient.setBaseUrl(Prefs.serverUrl(this))
        ApiClient.setSessionKey(Prefs.sessionKey(this))

        requestRuntimePermissions()

        // 주의: 여기서 FarmerService 를 미리 시작하면 안 된다. 콜드 스타트 시
        // 메인 스레드가 Compose 첫 프레임으로 혼잡해 FGS 10초 윈도우
        // (startForegroundService → startForeground) 를 놓쳐 앱이
        // ForegroundServiceDidNotStartInTimeException 으로 죽는다.
        // 서비스는 첫 프레임 이후(DashboardScreen LaunchedEffect) 또는
        // 부팅 후(BootReceiver)에 기동한다.

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
