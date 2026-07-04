package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val r = api.pairCode(PairingCodeRequest(userId.trim()))
                code = r.code
                expiresAt = r.expiresAt
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
    Scaffold(topBar = { TopAppBar(title = { Text("Farmer 연결") }) }) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("마스터에서 생성한 코드를 Farmer에 입력하세요", style = MaterialTheme.typography.bodyMedium)
            Text("계정: ${Session.nickname.ifBlank { Session.userId }}", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { vm.generate() },
                enabled = !vm.loading && vm.userId.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("페어링 코드 생성")
            }
            vm.code?.let { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("페어링 코드", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(c, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        vm.expiresAt?.let { Text("유효: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            vm.error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth()) { Text("뒤로") }
        }
    }
}
