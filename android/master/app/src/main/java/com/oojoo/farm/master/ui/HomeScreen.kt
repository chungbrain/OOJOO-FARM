package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
        val s = slaves.firstOrNull { it.online == 1 } ?: slaves.firstOrNull() ?: run { msg = "연결된 Farmer 없음!"; return }
        msg = "관수 지시 전송 중…"
        viewModelScope.launch {
            try {
                val wf = weather?.weatherFactor ?: 1.0
                api.sendCommand(CommandRequest(s.id, null, "water", (300 * wf).toInt(), wf))
                msg = "💧 관수 지시 전송! (Farmer: ${s.name})"
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
            CartoonAppBar(
                title = "🏡 OOJOO FARM",
                actions = {
                    IconButton(onClick = { nav.navigate("notifications") }) {
                        Icon(Icons.Default.Notifications, contentDescription = "알림", tint = Color.White)
                    }
                }
            )
        },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { WeatherCard(weather = vm.weather, region = Session.region) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🤖 Farmer (${vm.slaves.size})", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = OojooTheme.Ink)
                    Text("＋ 추가", color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clickable { nav.navigate("pairing") })
                }
            }
            if (vm.slaves.isEmpty() && !vm.loading) {
                item { CartoonEmptyCard(emoji = "🤖", text = "Farmer 연결하기! →") { nav.navigate("pairing") } }
            }
            items(vm.slaves) { s -> CartoonFarmerCard(s) { vm.quickWater() } }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🌱 내 식물 (${vm.plants.size})", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = OojooTheme.Ink)
                    Text("＋ 등록", color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clickable { nav.navigate("plant_register") })
                }
            }
            if (vm.plants.isEmpty() && !vm.loading) {
                item { CartoonEmptyCard(emoji = "🌱", text = "식물 등록하기! →") { nav.navigate("plant_register") } }
            }
            items(vm.plants.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { p -> CartoonPlantCard(p, Modifier.weight(1f)) { nav.navigate("plant_detail/${p.id}") } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                GradientButton(text = "💧 빠른 관수!", onClick = { vm.quickWater() }, enabled = vm.slaves.isNotEmpty(), modifier = Modifier.fillMaxWidth())
            }
            item { TextButton(onClick = { vm.refresh() }) { Text("🔄 새로고침", color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
            item { vm.msg?.let { Text(it, fontSize = 13.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
        }
    }
}

@Composable
private fun WeatherCard(weather: WeatherResponse?, region: String) {
    val w = weather
    Row(
        Modifier.fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .border(2.dp, OojooTheme.Ink, RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(OojooTheme.WeatherGradient))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(w?.region ?: region, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${w?.temp?.toInt() ?: "?"}°", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(8.dp))
                Text("💧 ${w?.humidity?.toInt() ?: "?"}%  🌧️ ${w?.precipitation ?: 0}mm", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(weatherEmoji(w?.weatherCode), fontSize = 28.sp)
            Surface(shape = OojooTheme.PillShape, color = Color.White.copy(alpha = 0.25f)) {
                Text("⚡ ×${"%.2f".format(w?.weatherFactor ?: 1.0)}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

private fun weatherEmoji(code: Int?): String = when {
    code == null -> "🌡️"
    code == 0 -> "☀️"
    code in 1..3 -> "⛅"
    code in 45..48 -> "🌫️"
    code in 51..67 -> "🌧️"
    code in 71..77 -> "🌨️"
    code in 80..82 -> "🌧️"
    code in 95..99 -> "⛈️"
    else -> "🌤️"
}

@Composable
private fun CartoonFarmerCard(s: Slave, onWater: () -> Unit) {
    Card(
        Modifier.fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(50)).border(2.dp, OojooTheme.Ink, RoundedCornerShape(50)).background(OojooTheme.GreenBg),
                contentAlignment = Alignment.Center
            ) { Text("🤖", fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
                val sub = buildString { append(if (s.online == 1) "🟢 온라인" else "⚪ 오프라인"); s.battery?.let { append(" · 🔋$it%") } }
                Text(sub, color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (s.online == 1) {
                OutlineButton(text = "💧 관수", onClick = onWater, modifier = Modifier)
            } else {
                Surface(shape = OojooTheme.PillShape, color = Color(0xFFECEFF1), border = BorderStroke(2.dp, OojooTheme.Ink)) {
                    Text("OFF", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color(0xFF607D8B), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun CartoonPlantCard(p: Plant, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val stageK = mapOf("seedling" to "묘목", "vegetative" to "영양생장", "flowering" to "개화", "fruiting" to "결실")
    val stageEmoji = when (p.stage) { "fruiting" -> "🍅"; "flowering" -> "🌸"; "vegetative" -> "🌿"; else -> "🌱" }
    Card(
        modifier
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape)
            .clip(OojooTheme.CardShape)
            .clickable { onClick() },
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stageEmoji, fontSize = 32.sp)
            Spacer(Modifier.height(6.dp))
            Text(p.name, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = OojooTheme.Ink)
            Text("${p.species ?: "?"} · ${stageK[p.stage] ?: p.stage ?: "?"}", color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(if (p.slave_id != null) "🤖 연결" else "🍴 미연결", color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CartoonEmptyCard(emoji: String, text: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape)
            .clip(OojooTheme.CardShape)
            .clickable { onClick() },
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 56.sp)
            Spacer(Modifier.height(14.dp))
            Text(text, color = OojooTheme.Muted, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
