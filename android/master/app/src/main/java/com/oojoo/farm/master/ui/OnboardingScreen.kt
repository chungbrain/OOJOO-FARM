package com.oojoo.farm.master.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.UserRequest
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

/**
 * 최초 실행 온보딩: 닉네임/재배 지역/서버 주소를 입력받아 계정을 생성한다.
 * 하드코딩된 userId("u1") 를 대체하며, 지역은 홈 날씨 조회에 사용된다.
 */
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

    Scaffold(topBar = { TopAppBar(title = { Text("OOJOO FARM 시작하기") }) }) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("계정 만들기", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text("닉네임과 재배 지역을 입력하세요. 지역은 날씨 기반 관수량 계산에 사용됩니다.",
                style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("닉네임") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = region,
                onValueChange = { region = it },
                label = { Text("재배 지역 (예: Seoul, Busan, 수원)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("서버 주소") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (region.isBlank()) { error = "재배 지역을 입력하세요"; return@Button }
                    loading = true
                    error = null
                    Prefs.setServerUrl(ctx, serverUrl.trim())
                    ApiClient.setBaseUrl(serverUrl.trim())
                    scope.launch {
                        try {
                            val user = ApiClient.api.createUser(
                                UserRequest(nickname = nickname.trim().ifBlank { null }, region = region.trim())
                            )
                            Prefs.saveAccount(ctx, user.id, user.nickname, region.trim())
                            Session.set(user.id, user.nickname ?: "", region.trim())
                            nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                        } catch (e: Exception) {
                            error = e.message ?: "계정 생성 실패 (서버 주소를 확인하세요)"
                        }
                        loading = false
                    }
                },
                enabled = !loading && region.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("시작하기")
            }
            error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}
