package com.oojoo.farm.slave.ui

import android.content.Context
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.model.PairingVerifyRequest
import com.oojoo.farm.slave.network.ApiClient
import kotlinx.coroutines.launch

class PairingViewModel : ViewModel() {
    var code by mutableStateOf("")
    var serverUrl by mutableStateOf("http://10.0.2.2:4000/")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun initUrl(ctx: Context) {
        serverUrl = com.oojoo.farm.slave.data.Prefs.serverUrl(ctx)
    }

    fun applyServer(ctx: Context) { Prefs.setServerUrl(ctx, serverUrl); ApiClient.setBaseUrl(serverUrl) }
    fun verify(ctx: Context, onDone: () -> Unit) {
        if (code.trim().length < 6) { error = "6자리 코드를 입력하세요"; return }
        loading = true; error = null
        viewModelScope.launch {
            try {
                val r = ApiClient.api.pairVerify(PairingVerifyRequest(code.trim()))
                Prefs.saveSession(ctx, r.slaveId, r.sessionKey, r.userId)
                ApiClient.setSessionKey(r.sessionKey)
                onDone()
            } catch (e: Exception) { error = e.message ?: "오류" }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(nav: NavController, vm: PairingViewModel = viewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.initUrl(ctx) }
    Column(Modifier.fillMaxSize().background(OojooTheme.Bg)) {
        TopAppBar(title = { Text("마스터 연결", color = Color.White, fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Teal))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("마스터 앱에서 받은 페어링 코드 입력", color = OojooTheme.Ink, fontSize = 14.sp)
            Text("페어링 코드 (6자리)", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OutlinedTextField(value = vm.code, onValueChange = { vm.code = it.uppercase() }, placeholder = { Text("ABC123", color = OojooTheme.Muted, fontWeight = FontWeight.Bold) }, singleLine = true, shape = OojooTheme.FieldShape, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Teal, unfocusedBorderColor = OojooTheme.Line, focusedContainerColor = OojooTheme.Card, unfocusedContainerColor = OojooTheme.Card), textStyle = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = 8.sp), modifier = Modifier.fillMaxWidth())
            Text("서버 주소", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.serverUrl, { vm.serverUrl = it }, "http://10.0.2.2:4000/")
            GradientButton(text = "연결", onClick = { vm.applyServer(ctx); vm.verify(ctx) { nav.navigate("dashboard") { popUpTo("pairing") { inclusive = true } } } }, enabled = !vm.loading, modifier = Modifier.fillMaxWidth())
            if (vm.loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OojooTheme.Teal) }
            vm.error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp) }
        }
    }
}
