package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.PairingCodeRequest
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PairingViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var code by mutableStateOf<String?>(null)
    var expiresAt by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun generate() {
        loading = true; error = null
        viewModelScope.launch {
            try { val r = api.pairCode(PairingCodeRequest(userId.trim())); code = r.code; expiresAt = r.expiresAt }
            catch (e: Exception) { error = e.message ?: "오류" }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(nav: NavController, vm: PairingViewModel = viewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("🤝 Farmer 연결", color = Color.White, fontWeight = FontWeight.Black) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🤝", fontSize = 48.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Farmer 페어링", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = OojooTheme.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text("마스터에서 만든 코드를\nFarmer 앱에 입력하세요!", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                }
            }
            Text("계정: ${Session.nickname.ifBlank { Session.userId }}", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            GradientButton(text = "🔑 페어링 코드 생성", onClick = { vm.generate() }, enabled = !vm.loading && vm.userId.isNotBlank(), modifier = Modifier.fillMaxWidth())
            if (vm.loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = OojooTheme.Green) }
            vm.code?.let { c ->
                Column(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffsetLg, OojooTheme.CardShape).clip(OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).background(Brush.linearGradient(listOf(OojooTheme.Ink, OojooTheme.Ink2))).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("페어링 코드", color = OojooTheme.Lime.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(c, color = OojooTheme.Lime, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 12.sp)
                    vm.expiresAt?.let { Spacer(Modifier.height(8.dp)); Text("유효: $it", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
                Text("유효시간 10분 · 1회용 — 이 코드를 Farmer 앱에 입력!", color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            vm.error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
