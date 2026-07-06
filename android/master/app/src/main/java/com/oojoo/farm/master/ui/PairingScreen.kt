package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
        topBar = { TopAppBar(title = { Text("Farmer 연결", color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("마스터에서 생성한 코드를 Farmer에 입력하세요", color = OojooTheme.Ink, fontSize = 14.sp)
            Text("계정: ${Session.nickname.ifBlank { Session.userId }}", color = OojooTheme.Muted, fontSize = 13.sp)
            GradientButton(text = "페어링 코드 생성", onClick = { vm.generate() }, enabled = !vm.loading && vm.userId.isNotBlank(), modifier = Modifier.fillMaxWidth())
            if (vm.loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OojooTheme.Green) }
            vm.code?.let { c ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111827)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("페어링 코드", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(c, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 8.sp)
                    vm.expiresAt?.let { Spacer(Modifier.height(6.dp)); Text("유효: $it", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp) }
                }
                Text("유효시간 10분 · 1회용 — 이 코드를 Slave 앱에 입력하세요", color = OojooTheme.Muted.copy(alpha = 0.7f), fontSize = 11.sp)
            }
            vm.error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp) }
        }
    }
}
