package com.oojoo.farm.slave

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.service.FarmerService
import com.oojoo.farm.slave.ui.DashboardScreen
import com.oojoo.farm.slave.ui.OojooSlaveTheme
import com.oojoo.farm.slave.ui.PairingScreen
import com.oojoo.farm.slave.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
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
