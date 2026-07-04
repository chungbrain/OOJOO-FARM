package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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

class PlantDetailViewModel : ViewModel() {
    private val api get() = ApiClient.api

    var plant by mutableStateOf<Plant?>(null)
    var waterings by mutableStateOf<List<Watering>>(emptyList())
    var events by mutableStateOf<List<FarmEvent>>(emptyList())
    var weather by mutableStateOf<WeatherResponse?>(null)
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)

    fun load(plantId: String) {
        loading = true
        viewModelScope.launch {
            try {
                plant = api.plant(plantId)
                plant?.let { p ->
                    try { waterings = api.waterings(p.id).waterings } catch (_: Exception) {}
                    if (p.slave_id != null) {
                        try { events = api.events(p.slave_id).events } catch (_: Exception) {}
                    }
                }
                try { weather = api.weather(Session.region) } catch (_: Exception) {}
            } catch (e: Exception) {
                msg = e.message
            }
            loading = false
        }
    }

    fun quickWater() {
        val p = plant ?: return
        val sId = p.slave_id ?: run { msg = "담당 Farmer 없음"; return }
        msg = "관수 지시 전송 중…"
        viewModelScope.launch {
            try {
                val wf = weather?.weatherFactor ?: 1.0
                api.sendCommand(CommandRequest(sId, p.id, "water", (300 * wf).toInt(), wf))
                msg = "관수 지시 전송 완료"
                load(p.id)
            } catch (e: Exception) {
                msg = e.message
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantDetailScreen(
    nav: NavController,
    plantId: String,
    vm: PlantDetailViewModel = viewModel()
) {
    LaunchedEffect(plantId) { vm.load(plantId) }

    Scaffold(topBar = { TopAppBar(title = { Text("식물 상세") }) }) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                vm.plant?.let { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text("종류: ${p.species ?: "미상"}", style = MaterialTheme.typography.bodyMedium)
                            Text("식재일: ${p.planted_at ?: "미상"}", style = MaterialTheme.typography.bodySmall)
                            val stageLabels = mapOf("seedling" to "묘목", "vegetative" to "영양생장", "flowering" to "개화", "fruiting" to "결실")
                            Text("단계: ${stageLabels[p.stage] ?: p.stage ?: "미상"}", style = MaterialTheme.typography.bodySmall)
                            Text("Farmer: ${p.slave_id?.take(8) ?: "미연결"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } ?: if (vm.loading) { CircularProgressIndicator() } else { Text("식물을 찾을 수 없습니다") }
            }

            item {
                Button(onClick = { vm.quickWater() }, enabled = vm.plant?.slave_id != null, modifier = Modifier.fillMaxWidth()) {
                    Text("빠른 관수 (원격 지시)")
                }
            }

            item {
                vm.weather?.let { w ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("날씨: ${w.region}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("기온: ${w.temp?.toInt() ?: "?"}°C / 습도: ${w.humidity?.toInt() ?: "?"}% / 강수: ${w.precipitation ?: 0}mm", style = MaterialTheme.typography.bodySmall)
                            Text("관수 가중치: ${"%.2f".format(w.weatherFactor)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item { Text("관수 이력", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            if (vm.waterings.isEmpty()) {
                item { Text("관수 기록 없음", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(vm.waterings) { w ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("${w.amount_ml}ml", fontWeight = FontWeight.Bold)
                                Text("${if (w.source == "auto") "자율" else "수동"} / ${w.created_at ?: ""}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("×${"%.1f".format(w.weather_factor)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)); Text("최근 이벤트", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            if (vm.events.isEmpty()) {
                item { Text("이벤트 없음", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(vm.events.take(20)) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(e.type, fontWeight = FontWeight.Bold)
                            Text(e.created_at ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                vm.msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                OutlinedButton(onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth()) { Text("뒤로") }
            }
        }
    }
}
