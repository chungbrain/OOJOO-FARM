package com.oojoo.farm.slave

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.ui.DashboardScreen
import com.oojoo.farm.slave.ui.PairingScreen
import com.oojoo.farm.slave.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ApiClient.setBaseUrl(Prefs.serverUrl(this))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme {
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
}
