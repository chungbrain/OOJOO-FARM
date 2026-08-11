package com.oojoo.farm.master.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.LocationHelper
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.UserRequest
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val S = LocalAppStrings.current
    var nickname by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(S.locationChecking) }
    var locationReady by remember { mutableStateOf(false) }
    var locationSource by remember { mutableStateOf<String?>(null) }
    var serverUrl by remember { mutableStateOf(Prefs.serverUrl(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun detectLocation() {
        detecting = true
        error = null
        scope.launch {
            try {
                val loc = LocationHelper.resolve(ctx)
                if (loc != null) {
                    val weather = ApiClient.api.weatherByCoords(loc.lat, loc.lon)
                    region = weather.label?.takeIf { it.isNotBlank() } ?: weather.region
                    locationSource = loc.source
                    locationReady = true
                    Prefs.setRegion(ctx, weather.region)
                    Session.updateRegion(ctx, weather.region)
                } else {
                    region = S.seoulFallback
                    locationSource = null
                    locationReady = true
                    error = S.locationFallback
                }
            } catch (e: Exception) {
                region = S.seoulFallback
                locationReady = true
                error = "${S.locationWeatherFailPrefix}${e.message}"
            }
            detecting = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { detectLocation() }

    LaunchedEffect(Unit) {
        if (LocationHelper.hasLocationPermission(ctx)) {
            detectLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("🎨 ${S.appName}", color = Color.White, fontWeight = FontWeight.Black) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🌻", fontSize = 72.sp)
                Spacer(Modifier.height(14.dp))
                Text(S.hello, fontWeight = FontWeight.Black, fontSize = 28.sp, color = OojooTheme.Ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${S.welcomeSubtitle} 🌱",
                    color = OojooTheme.Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(S.nickname, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OojooField(nickname, { nickname = it }, S.nicknamePh)

            Text(S.locationAuto, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            Surface(
                shape = OojooTheme.CardShape,
                color = OojooTheme.GreenBg,
                border = OojooTheme.BorderThin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (detecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OojooTheme.GreenDark)
                        Spacer(Modifier.width(10.dp))
                        Text(S.locationDetecting, color = OojooTheme.Ink, fontWeight = FontWeight.Bold)
                    } else {
                        Text("📍", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(region, color = OojooTheme.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            val srcLabel = when (locationSource) {
                                "gps" -> S.sourceGps
                                "network" -> S.sourceNetwork
                                "ip" -> S.sourceIp
                                else -> S.sourceAuto
                            }
                            Text("$srcLabel${S.setBySuffix}", color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { detectLocation() }) {
                            Text(S.locationRetry, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Text(S.serverAddress, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OojooField(serverUrl, { serverUrl = it }, "http://10.0.2.2:4000/")
            GradientButton(
                text = S.startApp,
                onClick = {
                    if (!locationReady || region.isBlank() || region == S.locationChecking) {
                        error = S.locationWaitError
                        return@GradientButton
                    }
                    loading = true; error = null
                    Prefs.setServerUrl(ctx, serverUrl.trim())
                    ApiClient.setBaseUrl(serverUrl.trim())
                    scope.launch {
                        try {
                            val user = ApiClient.api.createUser(
                                UserRequest(
                                    nickname = nickname.trim().ifBlank { null },
                                    region = Prefs.region(ctx)
                                )
                            )
                            Prefs.saveAccount(ctx, user.id, user.nickname, Prefs.region(ctx))
                            Session.set(user.id, user.nickname ?: "", Prefs.region(ctx))
                            nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                        } catch (e: Exception) {
                            error = e.message ?: S.accountFail
                        }
                        loading = false
                    }
                },
                enabled = !loading && !detecting && locationReady,
                modifier = Modifier.fillMaxWidth()
            )
            if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = OojooTheme.Green)
            }
            error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Text(S.autoLocationNotice, color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
