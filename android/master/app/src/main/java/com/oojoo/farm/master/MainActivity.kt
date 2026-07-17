package com.oojoo.farm.master

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.network.ApiClient
import com.oojoo.farm.master.ui.OojooMasterTheme
import com.oojoo.farm.master.ui.OojooTheme
import com.oojoo.farm.master.ui.LocalOojooUi
import com.oojoo.farm.master.ui.OojooUiState
import com.oojoo.farm.master.ui.ThemeEditorScreen
import com.oojoo.farm.master.ui.cartoonShadow
import com.oojoo.farm.master.ui.ThemeEditorScreen
import com.oojoo.farm.master.ui.FarmerListScreen
import com.oojoo.farm.master.ui.CartScreen
import com.oojoo.farm.master.ui.CommunityPostScreen
import com.oojoo.farm.master.ui.CommunityScreen
import com.oojoo.farm.master.ui.CommunityWriteScreen
import com.oojoo.farm.master.ui.HomeScreen
import com.oojoo.farm.master.ui.MarketScreen
import com.oojoo.farm.master.ui.NotificationScreen
import com.oojoo.farm.master.ui.OnboardingScreen
import com.oojoo.farm.master.ui.OrdersScreen
import com.oojoo.farm.master.ui.ProductDetailScreen
import com.oojoo.farm.master.ui.PairingScreen
import com.oojoo.farm.master.ui.PlantDetailScreen
import com.oojoo.farm.master.ui.PlantListScreen
import com.oojoo.farm.master.ui.PlantRegistrationScreen
import com.oojoo.farm.master.ui.LiveCameraScreen
import com.oojoo.farm.master.ui.GalleryScreen
import com.oojoo.farm.master.ui.ReportScreen
import com.oojoo.farm.master.ui.SubscriptionScreen

class MainActivity : ComponentActivity() {
    companion object {
        init {
            com.google.android.filament.utils.Utils.init()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ApiClient.setBaseUrl(Prefs.serverUrl(this))
        Session.load(this)
        setContent {
            val uiState = remember { mutableStateOf(OojooUiState(
                cornerRadius = Prefs.cornerRadius(this),
                shadowOffset = Prefs.shadowOffset(this),
                borderWidth = Prefs.borderWidth(this)
            )) }
            
            CompositionLocalProvider(LocalOojooUi provides uiState.value) {
                OojooMasterTheme {
                    MainApp(uiState)
                }
            }
        }
    }
}

data class BottomItem(val route: String, val label: String, val icon: String)

@Composable
fun MainApp(uiState: MutableState<OojooUiState>) {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val items = listOf(
        BottomItem("home", "홈", "🏡"),
        BottomItem("plants", "식물", "🌱"),
        BottomItem("farmers", "Farmer", "🤖"),
        BottomItem("market", "마켓", "🛒"),
        BottomItem("community", "이웃", "🏘️")
    )
    val navStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = navStackEntry?.destination?.route
    val showBottomBar = currentRoute in items.map { it.route }
    val startRoute = if (Prefs.isOnboarded(ctx)) "home" else "onboarding"

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 18.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = OojooTheme.Card,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .cartoonShadow(offsetX = OojooTheme.ShadowOffsetLg, offsetY = OojooTheme.ShadowOffsetLg, shape = RoundedCornerShape(28.dp))
                            .border(OojooTheme.Border, RoundedCornerShape(28.dp))
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            items.forEach { item ->
                                val isSelected = currentRoute == item.route
                                val scale by animateFloatAsState(targetValue = if (isSelected) 1.3f else 1f, label = "scale")
                                val offsetY by animateDpAsState(targetValue = if (isSelected) (-3).dp else 0.dp, label = "offsetY")
                                val color = if (isSelected) OojooTheme.GreenDark else OojooTheme.Muted

                                Column(
                                    Modifier
                                        .clickable(
                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            nav.navigate(item.route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                        .weight(1f)
                                        .padding(vertical = 6.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = item.icon,
                                        fontSize = 22.sp,
                                        modifier = Modifier.offset(y = offsetY).graphicsLayer(scaleX = scale, scaleY = scale)
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        item.label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = color
                                    )
                                    if (isSelected) {
                                        Spacer(Modifier.height(4.dp))
                                        Box(Modifier.size(6.dp).background(OojooTheme.GreenDark, CircleShape))
                                    } else {
                                        Spacer(Modifier.height(10.dp)) // To keep height consistent when indicator is missing
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = OojooTheme.Bg
    ) { p ->
        NavHost(
            nav,
            startDestination = startRoute,
            modifier = Modifier.padding(p)
        ) {
            composable("onboarding") { OnboardingScreen(nav) }
            composable("home") { HomeScreen(nav) }
            composable("plants") { PlantListScreen(nav) }
            composable("plant_register") { PlantRegistrationScreen(nav) }
            composable("plant_detail/{plantId}") { backStackEntry ->
                PlantDetailScreen(nav, backStackEntry.arguments?.getString("plantId") ?: "")
            }
            composable("farmers") { FarmerListScreen(nav) }
            composable("pairing") { PairingScreen(nav) }
            composable("notifications") { NotificationScreen(nav) }
            composable("market") { MarketScreen(nav) }
            composable("market_product/{id}") { b ->
                ProductDetailScreen(nav, b.arguments?.getString("id") ?: "")
            }
            composable("market_cart") { CartScreen(nav) }
            composable("market_orders") { OrdersScreen(nav) }
            composable("community") { CommunityScreen(nav) }
            composable("community_post/{id}") { b ->
                CommunityPostScreen(nav, b.arguments?.getString("id") ?: "")
            }
            composable("community_write") { CommunityWriteScreen(nav) }
            composable("report/{slaveId}") { b -> ReportScreen(nav, b.arguments?.getString("slaveId") ?: "") }
            composable("live_camera/{slaveId}/{slaveName}") { b ->
                LiveCameraScreen(nav, b.arguments?.getString("slaveId") ?: "", b.arguments?.getString("slaveName") ?: "Farmer")
            }
            composable("gallery") { GalleryScreen(nav) }
            composable("subscription") { SubscriptionScreen(nav) }
            composable("theme_editor") { ThemeEditorScreen(nav, uiState) }
        }
    }
}
