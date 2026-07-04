package com.oojoo.farm.slave.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    fun applyServer(ctx: Context) {
        Prefs.setServerUrl(ctx, serverUrl)
        ApiClient.setBaseUrl(serverUrl)
    }

    fun verify(ctx: Context, onDone: () -> Unit) {
        if (code.trim().length < 6) { error = "6자리 코드를 입력하세요"; return }
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val r = ApiClient.api.pairVerify(PairingVerifyRequest(code.trim()))
                Prefs.saveSession(ctx, r.slaveId, r.sessionKey, r.userId)
                ApiClient.setSessionKey(r.sessionKey)
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "오류"
            }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(nav: NavController, vm: PairingViewModel = viewModel()) {
    val ctx = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("마스터 연결") }) }) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("마스터 앱에서 받은 페어링 코드 입력", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = vm.code,
                onValueChange = { vm.code = it.uppercase() },
                label = { Text("페어링 코드 (6자리)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.serverUrl,
                onValueChange = { vm.serverUrl = it },
                label = { Text("서버 주소") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.applyServer(ctx); vm.verify(ctx) { nav.navigate("dashboard") { popUpTo("pairing") { inclusive = true } } } },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("연결")
            }
            vm.error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}
