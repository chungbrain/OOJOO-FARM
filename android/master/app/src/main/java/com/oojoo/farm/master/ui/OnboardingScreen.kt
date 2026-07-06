package com.oojoo.farm.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("OOJOO FARM 시작하기", color = OojooTheme.Green, fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Card)
        )
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("계정 만들기", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = OojooTheme.Ink)
            Text(
                "닉네임과 재배 지역을 입력하세요. 지역은 날씨 기반 관수량 계산에 사용됩니다.",
                color = OojooTheme.Muted, fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))

            Text("닉네임", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(value = nickname, onValueChange = { nickname = it }, placeholder = "예: 홍길동")

            Text("재배 지역", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(value = region, onValueChange = { region = it }, placeholder = "예: Seoul, Busan, 수원")

            Text("서버 주소", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(value = serverUrl, onValueChange = { serverUrl = it }, placeholder = "http://10.0.2.2:4000/")

            GradientButton(
                text = "시작하기",
                onClick = {
                    if (region.isBlank()) { error = "재배 지역을 입력하세요"; return@GradientButton }
                    loading = true; error = null
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
            )
            if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = OojooTheme.Green)
            }
            error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp) }
            Text(
                "Live 연결 시 실제 /api/users 로 계정이 생성됩니다.",
                color = OojooTheme.Muted.copy(alpha = 0.7f), fontSize = 11.sp
            )
        }
    }
}

@Composable
fun OojooField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = OojooTheme.Muted) },
        singleLine = singleLine,
        shape = OojooTheme.FieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = OojooTheme.Green,
            unfocusedBorderColor = OojooTheme.Line,
            focusedContainerColor = OojooTheme.Card,
            unfocusedContainerColor = OojooTheme.Card
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
