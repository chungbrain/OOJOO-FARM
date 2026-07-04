package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var plants by mutableStateOf<List<Plant>>(emptyList())
    var weather by mutableStateOf<WeatherResponse?>(null)
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)

    fun refresh() {
        loading = true
        msg = null
        viewModelScope.launch {
            try {
                slaves = api.slaves(userId).slaves
                plants = api.plants(userId).plants
                try { weather = api.weather(Session.region) } catch (_: Exception) {}
            } catch (e: Exception) {
                msg = e.message
            }
            loading = false
        }
    }

    fun quickWater() {
        val s = slaves.firstOrNull { it.online == 1 } ?: slaves.firstOrNull() ?: run { msg = "연결된 Farmer 없음"; return }
        msg = "관수 지시 전송 중…"
        viewModelScope.launch {
            try {
                val wf = weather?.weatherFactor ?: 1.0
                api.sendCommand(CommandRequest(s.id, null, "water", (300 * wf).toInt(), wf))
                msg = "관수 지시 전송 완료 (Farmer: ${s.name})"
            } catch (e: Exception) {
                msg = e.message
            }
        }
    }

    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = viewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OOJOO FARM") },
                actions = { TextButton(onClick = { nav.navigate("notifications") }) { Text("알림") } }
            )
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                vm.weather?.let { w ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("날씨: ${w.region}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("${w.temp?.toInt() ?: "?"}°C / 습도 ${w.humidity?.toInt() ?: "?"}% / 강수 ${w.precipitation ?: 0}mm", style = MaterialTheme.typography.bodyMedium)
                            Text("관수 가중치: ${"%.2f".format(w.weatherFactor)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Text("Farmer 기기 (${vm.slaves.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            if (vm.slaves.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate("pairing") }) {
                        Text("Farmer 연결하기 →", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(vm.slaves) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold)
                            Text(if (s.online == 1) "온라인" else "오프라인", style = MaterialTheme.typography.bodySmall)
                        }
                        if (s.online == 1) AssistChip(onClick = { vm.quickWater() }, label = { Text("관수") })
                    }
                }
            }

            item { Text("내 식물 (${vm.plants.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            if (vm.plants.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate("plant_register") }) {
                        Text("식물 등록하기 →", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(vm.plants) { p ->
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("plant_detail/${p.id}") }) {
                    Row(Modifier.padding(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text("${p.species ?: "?"}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(p.slave_id?.take(6) ?: "미연결", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Button(onClick = { vm.quickWater() }, enabled = vm.slaves.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("빠른 관수 (원격 지시)")
                }
            }
            item { TextButton(onClick = { vm.refresh() }) { Text("새로고침") } }
            item { vm.msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
        }
    }
}
