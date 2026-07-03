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
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.model.WateringCommandRequest
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    private val api = ApiClient.api
    val userId = "u1"
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)

    init { refresh() }

    fun refresh() {
        loading = true
        msg = null
        viewModelScope.launch {
            try {
                slaves = api.slaves(userId).slaves
            } catch (e: Exception) {
                msg = e.message
            }
            loading = false
        }
    }

    fun quickWater() {
        val s = slaves.firstOrNull() ?: run { msg = "연결된 Farmer 없음"; return }
        msg = "Farmer로 관수 지시 전송 중…"
        viewModelScope.launch {
            try {
                api.wateringCommand(WateringCommandRequest(s.id))
                msg = "관수 지시 전송 완료 (Farmer: ${s.name})"
            } catch (e: Exception) {
                msg = e.message
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = viewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OOJOO FARM") },
                actions = { TextButton(onClick = { nav.navigate("pairing") }) { Text("Farmer 연결") } }
            )
        }
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Farmer 기기", style = MaterialTheme.typography.titleMedium)
            if (vm.loading) CircularProgressIndicator()
            vm.slaves.forEach { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold)
                            Text(if (s.online == 1) "온라인" else "오프라인", style = MaterialTheme.typography.bodySmall)
                        }
                        if (s.online == 1) {
                            AssistChip(onClick = { vm.quickWater() }, label = { Text("관수") })
                        }
                    }
                }
            }
            Button(
                onClick = { vm.quickWater() },
                enabled = vm.slaves.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("빠른 관수 (원격 지시)") }
            TextButton(onClick = { vm.refresh() }) { Text("새로고침") }
            vm.msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
