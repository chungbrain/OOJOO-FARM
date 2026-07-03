package com.oojoo.farm.slave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.ui.DashboardScreen
import com.oojoo.farm.slave.ui.PairingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ApiClient.setBaseUrl(Prefs.serverUrl(this))
        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val start = if (Prefs.isPaired(this)) "dashboard" else "pairing"
                NavHost(nav, startDestination = start) {
                    composable("pairing") { PairingScreen(nav) }
                    composable("dashboard") { DashboardScreen(nav) }
                }
            }
        }
    }
}
