package com.oojoo.farm.master

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.oojoo.farm.master.ui.HomeScreen
import com.oojoo.farm.master.ui.PairingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") { HomeScreen(nav) }
                    composable("pairing") { PairingScreen(nav) }
                }
            }
        }
    }
}
