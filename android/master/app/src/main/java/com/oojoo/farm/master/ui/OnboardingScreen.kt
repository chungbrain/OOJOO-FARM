package com.oojoo.farm.master.ui

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
    var region by remember { mutableStateOf("Seoul") }
    var serverUrl by remember { mutableStateOf(Prefs.serverUrl(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
                Text("누구나 집에서 키우는\n재미있는 스마트 농장 🌱", color = OojooTheme.Muted, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text("닉네임", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OojooField(nickname, { nickname = it }, "예: 농부민준")
            Text("재배 지역", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OojooField(region, { region = it }, "Seoul, Busan, 수원")
            Text("서버 주소", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OojooField(serverUrl, { serverUrl = it }, "http://10.0.2.2:4000/")
            GradientButton(text = "🚀 시작하기!", onClick = {
                if (region.isBlank()) { error = "재배 지역을 입력하세요!"; return@GradientButton }
                loading = true; error = null
                Prefs.setServerUrl(ctx, serverUrl.trim())
                ApiClient.setBaseUrl(serverUrl.trim())
                scope.launch {
                    try {
                        val user = ApiClient.api.createUser(UserRequest(nickname = nickname.trim().ifBlank { null }, region = region.trim()))
                        Prefs.saveAccount(ctx, user.id, user.nickname, region.trim())
                        Session.set(user.id, user.nickname ?: "", region.trim())
                        nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                    } catch (e: Exception) { error = e.message ?: "계정 생성 실패 (서버 주소 확인!)" }
                    loading = false
                }
            }, enabled = !loading && region.isNotBlank(), modifier = Modifier.fillMaxWidth())
            if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = OojooTheme.Green) }
            error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Text("Live 모드면 실제 백엔드에 계정이 생성됩니다", color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
