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
    var nickname by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("위치 확인 중…") }
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
                    region = "서울"
                    locationSource = null
                    locationReady = true
                    error = "위치를 찾지 못해 기본 지역(서울)을 사용합니다"
                }
            } catch (e: Exception) {
                region = "서울"
                locationReady = true
                error = "위치/날씨 자동 설정 실패: ${e.message}"
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
            title = { Text("🎨 OOJOO FARM", color = Color.White, fontWeight = FontWeight.Black) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🌻", fontSize = 72.sp)
                Spacer(Modifier.height(14.dp))
                Text("안녕하세요!", fontWeight = FontWeight.Black, fontSize = 28.sp, color = OojooTheme.Ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "누구나 집에서 키우는\n재미있는 스마트 농장 🌱",
                    color = OojooTheme.Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("닉네임", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OojooField(nickname, { nickname = it }, "예: 농부민준")

            Text("재배 지역 (자동)", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
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
                        Text("위치·날씨 자동 설정 중…", color = OojooTheme.Ink, fontWeight = FontWeight.Bold)
                    } else {
                        Text("📍", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(region, color = OojooTheme.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            val srcLabel = when (locationSource) {
                                "gps" -> "GPS"
                                "network" -> "네트워크 위치"
                                "ip" -> "IP 기반 위치"
                                else -> "자동"
                            }
                            Text("$srcLabel 으로 설정됨", color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { detectLocation() }) {
                            Text("다시 감지", color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Text("서버 주소", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OojooField(serverUrl, { serverUrl = it }, "http://10.0.2.2:4000/")
            GradientButton(
                text = "🚀 시작하기!",
                onClick = {
                    if (!locationReady || region.isBlank() || region == "위치 확인 중…") {
                        error = "위치 설정을 기다려 주세요"
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
                            error = e.message ?: "계정 생성 실패 (서버 주소 확인!)"
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
            Text("위치와 날씨는 자동으로 설정됩니다", color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
