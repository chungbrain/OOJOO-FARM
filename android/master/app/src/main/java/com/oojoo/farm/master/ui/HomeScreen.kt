package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavController
import androidx.compose.material.icons.filled.Settings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch
import java.util.Calendar

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
                    IconButton(onClick = { nav.navigate("theme_editor") }) {
                        Icon(Icons.Default.Settings, contentDescription = "UI 커스터마이징", tint = Color.White)
                    }
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
            item { DynamicWeatherCard(weather = vm.weather, region = Session.region) }

            item { Farm3DCard(plants = vm.plants, onClickPlant = { nav.navigate("plant_detail/${it.id}") }) }

            item {
                GradientButton(text = "💧 빠른 관수!", onClick = { vm.quickWater() }, enabled = vm.slaves.isNotEmpty(), modifier = Modifier.fillMaxWidth())
            }
            item { TextButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("🔄 새로고침", color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
            item { vm.msg?.let { Text(it, fontSize = 13.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) } }
        }
    }
}

@Composable
private fun DynamicWeatherCard(weather: WeatherResponse?, region: String) {
    val w = weather
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val isNight = hour >= 18 || hour < 6
    val isRain = w?.weatherCode in listOf(51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82)

    val bgGradient = if (isNight) {
        listOf(Color(0xFF3949AB), Color(0xFF1A237E))
    } else if (isRain) {
        listOf(Color(0xFF78909C), Color(0xFF37474F))
    } else {
        listOf(Color(0xFF4FC3F7), Color(0xFF42A5F5))
    }

    Box(
        Modifier.fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .border(2.dp, OojooTheme.Ink, RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(bgGradient))
    ) {
        if (isRain) {
            RainAnimation()
        }

        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    if (isNight) (if (isRain) "밤 🌙 🌧️ 비 옴" else "밤 🌙 맑음") 
                    else (if (isRain) "낮 ☀️ 🌧️ 비 옴" else "낮 ☀️ 맑음"),
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${w?.temp?.toInt() ?: "--"}°C", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(15.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val hum = if (isRain) "85" else if (isNight) "60" else "45"
                    val sun = if (isNight) "0" else if (isRain) "150" else "850"
                    MetricChip("💧 $hum%")
                    MetricChip("☀️ $sun lx")
                }
            }
        }
    }
}

@Composable
private fun MetricChip(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = 0.2f), border = BorderStroke(2.dp, Color.White.copy(alpha = 0.4f))) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun RainAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "rain")
    val fall by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "fall"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val count = 45
        val width = size.width
        val height = size.height
        for (i in 0 until count) {
            val startX = (i * (width / count)) + (i * 13 % 20)
            val yOffset = ((fall + (i * 47)) % height)
            drawLine(
                color = Color.White.copy(alpha = 0.7f),
                start = Offset(startX, yOffset),
                end = Offset(startX, yOffset + 40f),
                strokeWidth = 4f
            )
        }
    }
}

@Composable
private fun Farm3DCard(plants: List<Plant>, onClickPlant: (Plant) -> Unit) {
    val healths = listOf(
        Pair("100% 건강", "아주 건강해요! 😊"),
        Pair("70% 아픔", "조금 아파요 😢"),
        Pair("25% 죽기직전", "살려주세요... 😱"),
        Pair("50% 위험", "위험해요 🚨"),
        Pair("0% 사망", "...")
    )
    val healthColors = listOf(Color(0xFF2E7D32), Color(0xFFF57C00), Color(0xFFC2185B), Color(0xFFD32F2F), Color(0xFF616161))

    val stageEmoji = { stage: String? -> when (stage) { "fruiting" -> "🍅"; "flowering" -> "🌸"; "vegetative" -> "🌿"; else -> "🌱" } }

    Card(
        Modifier.fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🚜 나의 농장", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = OojooTheme.Ink)
                Text("실시간 자동 관리", fontSize = 12.sp, color = OojooTheme.Muted, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(25.dp))
            
            // 3D Farm Render
            Box(Modifier.fillMaxWidth().height(220.dp).graphicsLayer { rotationX = 40f }) {
                // Ground
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).border(2.dp, OojooTheme.Ink, RoundedCornerShape(12.dp)).background(Color(0xFF8D6E63))) {
                    Canvas(Modifier.fillMaxSize()) {
                        // draw grid
                        val step = 40.dp.toPx()
                        for (x in 0..size.width.toInt() step step.toInt()) drawLine(Color.Black.copy(alpha=0.15f), Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), 4f)
                        for (y in 0..size.height.toInt() step step.toInt()) drawLine(Color.Black.copy(alpha=0.15f), Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 4f)
                    }
                }
                
                // Bobbing Animation
                val infiniteTransition = rememberInfiniteTransition(label="bobbing")
                val bobOffset by infiniteTransition.animateFloat(initialValue = 0f, targetValue = -8f, animationSpec = infiniteRepeatable(animation = tween(1500, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label="bob")
                
                // Plants
                val positions = listOf(Pair(0.1f, 0.15f), Pair(0.45f, 0.45f), Pair(0.75f, 0.10f), Pair(0.2f, 0.8f), Pair(0.8f, 0.6f))
                plants.forEachIndexed { i, p ->
                    val hIdx = i % healths.size
                    val pos = positions[i % positions.size]
                    Box(
                        Modifier.fillMaxSize().padding(start = (pos.first * 300).dp, top = (pos.second * 150).dp).clickable { onClickPlant(p) },
                        contentAlignment = Alignment.TopStart
                    ) {
                        // Flat ground shadow
                        Box(Modifier.offset(x = 10.dp, y = 5.dp).size(26.dp, 8.dp).background(Color.Black.copy(alpha=0.3f), RoundedCornerShape(50)))
                        
                        // Standing item
                        Box(Modifier.graphicsLayer { rotationX = -40f; translationY = bobOffset + (i % 3) * 2f }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-50).dp)) {
                                Text(stageEmoji(p.stage), fontSize = 42.sp, modifier = Modifier.shadow(3.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black).background(Color.Transparent))
                                SpeechBubble(healths[hIdx].second, healthColors[hIdx])
                            }
                        }
                    }
                }

                // Robot
                val rx by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 250f, animationSpec = infiniteRepeatable(animation = tween(10000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label="rx")
                Box(Modifier.fillMaxSize().padding(start = rx.dp, top = 100.dp), contentAlignment = Alignment.TopStart) {
                    Box(Modifier.offset(x = 12.dp, y = 5.dp).size(30.dp, 10.dp).background(Color.Black.copy(alpha=0.4f), RoundedCornerShape(50)))
                    Box(Modifier.graphicsLayer { rotationX = -40f; translationY = bobOffset }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-50).dp)) {
                            Text("🤖", fontSize = 45.sp, modifier = Modifier.shadow(4.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black).background(Color.Transparent))
                            SpeechBubble("열일중 💦", Color(0xFFF57C00), bg = Color(0xFFFFD54F))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("📋 식물 건강 상태", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = OojooTheme.Ink)
            Spacer(Modifier.height(10.dp))
            if (plants.isEmpty()) {
                Text("등록된 식물이 없습니다.", color = OojooTheme.Muted, fontSize = 14.sp)
            } else {
                plants.forEachIndexed { i, p ->
                    val hIdx = i % healths.size
                    Row(Modifier.fillMaxWidth().border(BorderStroke(1.dp, OojooTheme.Line), RoundedCornerShape(8.dp)).padding(10.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stageEmoji(p.stage), fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
                        }
                        Text("\"${healths[hIdx].second}\" (${healths[hIdx].first})", color = healthColors[hIdx], fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = OojooTheme.Line, thickness = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text("※ 상태 범례\n100% 건강 | 70% 아픔 | 50% 위험\n25% 죽기직전 | 0% 사망", fontSize = 12.sp, color = OojooTheme.Muted, lineHeight = 18.sp)
        }
    }
}

@Composable
fun SpeechBubble(text: String, textColor: Color, bg: Color = OojooTheme.Card) {
    Box(Modifier.offset(y = (-10).dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = bg, border = BorderStroke(2.dp, OojooTheme.Ink), shadowElevation = 2.dp) {
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = textColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
