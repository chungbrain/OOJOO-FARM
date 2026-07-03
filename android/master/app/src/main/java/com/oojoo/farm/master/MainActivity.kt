package com.oojoo.farm.master

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.oojoo.farm.master.ui.FarmerListScreen
import com.oojoo.farm.master.ui.HomeScreen
import com.oojoo.farm.master.ui.PairingScreen
import com.oojoo.farm.master.ui.PlantDetailScreen
import com.oojoo.farm.master.ui.PlantListScreen
import com.oojoo.farm.master.ui.PlantRegistrationScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainApp()
            }
        }
    }
}

data class BottomItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun MainApp() {
    val nav = rememberNavController()
    val items = listOf(
        BottomItem("home", "홈", Icons.Default.Home),
        BottomItem("plants", "식물", Icons.Default.Spa),
        BottomItem("farmers", "Farmer", Icons.Default.Agriculture)
    )
    val navStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = navStackEntry?.destination?.route
    val showBottomBar = currentRoute in items.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { p ->
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.padding(p)
        ) {
            composable("home") { HomeScreen(nav) }
            composable("plants") { PlantListScreen(nav) }
            composable("plant_register") { PlantRegistrationScreen(nav) }
            composable("plant_detail/{plantId}") { backStackEntry ->
                PlantDetailScreen(nav, backStackEntry.arguments?.getString("plantId") ?: "")
            }
            composable("farmers") { FarmerListScreen(nav) }
            composable("pairing") { PairingScreen(nav) }
        }
    }
}
