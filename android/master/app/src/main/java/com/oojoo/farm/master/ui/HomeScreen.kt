package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
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

class HomeViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var plants by mutableStateOf<List<Plant>>(emptyList())
    var weather by mutableStateOf<WeatherResponse?>(null)
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)

    fun refresh() {
        loading = true; msg = null
        viewModelScope.launch {
            try {
                slaves = api.slaves(userId).slaves
                plants = api.plants(userId).plants
                try { weather = api.weather(Session.region) } catch (_: Exception) {}
            } catch (e: Exception) { msg = e.message }
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
            } catch (e: Exception) { msg = e.message }
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
                title = { Text("OOJOO FARM", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { nav.navigate("notifications") }) {
                        Icon(Icons.Default.Notifications, contentDescription = "알림", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)
            )
        },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                WeatherCard(weather = vm.weather, region = Session.region)
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Farmer 기기 (${vm.slaves.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
                    Text("+ 추가", color = OojooTheme.Green, fontSize = 13.sp, modifier = Modifier.clickable { nav.navigate("pairing") })
                }
            }
            if (vm.slaves.isEmpty() && !vm.loading) {
                item { EmptyCard(text = "Farmer 연결하기 →") { nav.navigate("pairing") } }
            }
            items(vm.slaves) { s -> FarmerRowCard(s) { vm.quickWater() } }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("내 식물 (${vm.plants.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
                    Text("+ 등록", color = OojooTheme.Green, fontSize = 13.sp, modifier = Modifier.clickable { nav.navigate("plant_register") })
                }
            }
            if (vm.plants.isEmpty() && !vm.loading) {
                item { EmptyCard(text = "식물 등록하기 →") { nav.navigate("plant_register") } }
            }
            items(vm.plants.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { p ->
                        PlantGridCard(p, Modifier.weight(1f)) { nav.navigate("plant_detail/${p.id}") }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                GradientButton(
                    text = "빠른 관수 (원격 지시)",
                    onClick = { vm.quickWater() },
                    enabled = vm.slaves.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { TextButton(onClick = { vm.refresh() }) { Text("새로고침", color = OojooTheme.Green) } }
            item { vm.msg?.let { Text(it, fontSize = 13.sp, color = OojooTheme.Green) } }
        }
    }
}

@Composable
private fun WeatherCard(weather: WeatherResponse?, region: String) {
    val w = weather
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(OojooTheme.WeatherGradient))
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text(w?.region ?: region, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                Text("${w?.temp?.toInt() ?: "?"}°", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("☀️", fontSize = 38.sp)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("💧 ${w?.humidity?.toInt() ?: "?"}%", color = Color.White.copy(alpha = 0.95f), fontSize = 12.sp)
            Text("·", color = Color.White.copy(alpha = 0.95f), fontSize = 12.sp)
            Text("🌧️ ${w?.precipitation ?: 0}mm", color = Color.White.copy(alpha = 0.95f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text("⚡ 권장 관수 가중치 ×${"%.2f".format(w?.weatherFactor ?: 1.0)}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FarmerRowCard(s: Slave, onWater: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().shadow(OojooTheme.CardElevation, OojooTheme.CardShape).clip(OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🤖", fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
                val sub = buildString {
                    append(if (s.online == 1) "온라인" else "오프라인")
                    s.battery?.let { append(" · 🔋$it%") }
                }
                Text(sub, color = OojooTheme.Muted, fontSize = 13.sp)
            }
            if (s.online == 1) {
                OutlineButton(text = "관수", onClick = onWater, modifier = Modifier)
            } else {
                AssistChip(onClick = {}, label = { Text("오프라인", fontSize = 11.sp) }, colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFECEFF1), labelColor = Color(0xFF607D8B)))
            }
        }
    }
}

@Composable
private fun PlantGridCard(p: Plant, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val stageK = mapOf("seedling" to "묘목", "vegetative" to "영양생장", "flowering" to "개화", "fruiting" to "결실")
    val stageEmoji = when (p.stage) { "fruiting" -> "🍅"; "flowering" -> "🌸"; "vegetative" -> "🌿"; else -> "🌱" }
    Card(
        modifier.shadow(OojooTheme.CardElevation, OojooTheme.CardShape).clip(OojooTheme.CardShape).clickable { onClick() },
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stageEmoji, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
            Text("${p.species ?: "?"} · ${stageK[p.stage] ?: p.stage ?: "?"}", color = OojooTheme.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(if (p.slave_id != null) "🤖 연결" else "🍴 미연결", color = OojooTheme.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EmptyCard(text: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().shadow(OojooTheme.CardElevation, OojooTheme.CardShape).clip(OojooTheme.CardShape).clickable { onClick() },
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Text(text, Modifier.padding(16.dp), color = OojooTheme.Ink)
    }
}
