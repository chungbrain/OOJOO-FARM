package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
                    if (p.slave_id != null) try { events = api.events(p.slave_id).events } catch (_: Exception) {}
                }
                try { weather = api.weather(Session.region) } catch (_: Exception) {}
            } catch (e: Exception) { msg = e.message }
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
                msg = "관수 지시 전송 완료"; load(p.id)
            } catch (e: Exception) { msg = e.message }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantDetailScreen(nav: NavController, plantId: String, vm: PlantDetailViewModel = viewModel()) {
    LaunchedEffect(plantId) { vm.load(plantId) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("식물 상세", color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val pl = vm.plant
                if (pl != null) {
                    val stageK = mapOf("seedling" to "묘목", "vegetative" to "영양생장", "flowering" to "개화", "fruiting" to "결실")
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(pl.name, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = OojooTheme.Ink)
                            Text("종류: ${pl.species ?: "미상"}", color = OojooTheme.Muted, fontSize = 14.sp)
                            Text("식재일: ${pl.planted_at ?: "미상"}", color = OojooTheme.Muted, fontSize = 13.sp)
                            Text("단계: ${stageK[pl.stage] ?: pl.stage ?: "미상"}", color = OojooTheme.Muted, fontSize = 13.sp)
                            Text("Farmer: ${pl.slave_id?.take(8) ?: "미연결"}", color = OojooTheme.Muted, fontSize = 13.sp)
                        }
                    }
                } else if (vm.loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green) }
                } else { Text("식물을 찾을 수 없습니다") }
            }

            item {
                GradientButton(text = "빠른 관수 (원격 지시)", onClick = { vm.quickWater() }, enabled = vm.plant?.slave_id != null, modifier = Modifier.fillMaxWidth())
            }

            item {
                vm.weather?.let { w ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(OojooTheme.WeatherGradient)).padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(w.region, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                                Text("${w.temp?.toInt() ?: "?"}°", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Text("☀️", fontSize = 34.sp)
                        }
                        Text("습도 ${w.humidity?.toInt() ?: "?"}% · 관수 가중치 ×${"%.2f".format(w.weatherFactor)}", color = Color.White.copy(alpha = 0.95f), fontSize = 12.sp)
                    }
                }
            }

            item { Text("관수 이력", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
            if (vm.waterings.isEmpty()) { item { Text("관수 기록 없음", color = OojooTheme.Muted, fontSize = 13.sp) } }
            else {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp)) {
                            vm.waterings.forEach { w ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("${w.amount_ml}ml", fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
                                        Text("${if (w.source == "auto") "자율" else "수동"} · ${w.created_at ?: ""}", color = OojooTheme.Muted, fontSize = 11.sp)
                                    }
                                    Text("×${"%.1f".format(w.weather_factor)}", color = OojooTheme.Muted, fontSize = 13.sp)
                                }
                                if (w != vm.waterings.last()) HorizontalDivider(color = OojooTheme.Line)
                            }
                        }
                    }
                }
            }

            item { Text("최근 이벤트", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
            if (vm.events.isEmpty()) { item { Text("이벤트 없음", color = OojooTheme.Muted, fontSize = 13.sp) } }
            else {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp)) {
                            vm.events.take(20).forEach { e ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(notiLabel(e.type), fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 14.sp)
                                    Text(e.created_at ?: "", color = OojooTheme.Muted, fontSize = 11.sp)
                                }
                                if (e != vm.events.take(20).last()) HorizontalDivider(color = OojooTheme.Line)
                            }
                        }
                    }
                }
            }

            item {
                vm.msg?.let { Text(it, fontSize = 13.sp, color = OojooTheme.Green) }
                OutlineButton(text = "뒤로", onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

fun notiLabel(t: String): String = when (t) {
    "harvest_ready" -> "🍅 수확 적기"
    "pest_detected" -> "🐛 해충 감지"
    "auto_water" -> "💧 자율 관수"
    "manual_water" -> "💧 수동 관수"
    "anomaly" -> "⚠️ 이상 징후"
    "capture" -> "📷 캡처"
    else -> t
}
