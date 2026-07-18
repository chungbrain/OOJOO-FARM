package com.oojoo.farm.master.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.oojoo.farm.master.data.LocationHelper
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.Plant
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.model.WeatherResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch
import java.util.Calendar

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var plants by mutableStateOf<List<Plant>>(emptyList())
    var weather by mutableStateOf<WeatherResponse?>(null)
    var regionLabel by mutableStateOf(Session.region)
    var loading by mutableStateOf(false)
    var locating by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)

    fun refresh(requestLocation: Boolean = true) {
        loading = true
        msg = null
        viewModelScope.launch {
            try {
                if (requestLocation) {
                    locating = true
                    try {
                        val loc = LocationHelper.resolve(getApplication())
                        if (loc != null) {
                            val w = api.weatherByCoords(loc.lat, loc.lon)
                            weather = w
                            regionLabel = w.label?.takeIf { it.isNotBlank() } ?: w.region
                            Session.updateRegion(getApplication(), w.region)
                        } else if (Session.region.isNotBlank()) {
                            weather = api.weather(Session.region)
                            regionLabel = weather?.label ?: Session.region
                        }
                    } catch (e: Exception) {
                        try {
                            weather = api.weather(Session.region)
                            regionLabel = weather?.label ?: Session.region
                        } catch (_: Exception) {
                            msg = e.message
                        }
                    }
                    locating = false
                } else {
                    weather = api.weather(Session.region)
                    regionLabel = weather?.label ?: Session.region
                }
                slaves = api.slaves(userId).slaves
                plants = api.plants(userId).plants
            } catch (e: Exception) {
                msg = e.message
            }
            loading = false
            locating = false
        }
    }

    init {
        refresh()
    }
}

private data class WeatherScene(
    val isNight: Boolean,
    val isRain: Boolean,
    val label: String,
    val gradient: List<Color>,
    val groundTop: Color,
    val groundBottom: Color,
)

private fun weatherScene(weather: WeatherResponse?): WeatherScene {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val isNight = hour >= 18 || hour < 6
    val isRain = weather?.weatherCode in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82)
    val label = when {
        isNight && isRain -> "밤 🌙 🌧️ 비 옴"
        isNight -> "밤 🌙 맑음"
        isRain -> "낮 ☀️ 🌧️ 비 옴"
        else -> "낮 ☀️ 맑음"
    }
    val gradient = when {
        isNight && isRain -> listOf(Color(0xFF1A237E), Color(0xFF283593), Color(0xFF37474F))
        isNight -> listOf(Color(0xFF1A237E), Color(0xFF3949AB), Color(0xFF5C6BC0))
        isRain -> listOf(Color(0xFF546E7A), Color(0xFF78909C), Color(0xFF90A4AE))
        else -> listOf(Color(0xFF29B6F6), Color(0xFF4FC3F7), Color(0xFF81D4FA))
    }
    val groundTop = when {
        isNight -> Color(0xFF1B5E20)
        isRain -> Color(0xFF33691E)
        else -> Color(0xFF66BB6A)
    }
    val groundBottom = when {
        isNight -> Color(0xFF0D3B12)
        isRain -> Color(0xFF1B5E20)
        else -> Color(0xFF2E7D32)
    }
    return WeatherScene(isNight, isRain, label, gradient, groundTop, groundBottom)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = viewModel()) {
    val ctx = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refresh(requestLocation = true) }

    LaunchedEffect(Unit) {
        if (!LocationHelper.hasLocationPermission(ctx)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

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
        Column(
            Modifier
                .fillMaxSize()
                .padding(p)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FarmWeatherCard(
                weather = vm.weather,
                region = vm.regionLabel,
                locating = vm.locating,
                plants = vm.plants,
                onClickPlant = { nav.navigate("plant_detail/${it.id}") }
            )
            TextButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                Text("🔄 새로고침", color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
            }
            vm.msg?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = OojooTheme.GreenDark,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun FarmWeatherCard(
    weather: WeatherResponse?,
    region: String,
    locating: Boolean,
    plants: List<Plant>,
    onClickPlant: (Plant) -> Unit
) {
    val scene = weatherScene(weather)
    val w = weather
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val farmHeight = (screenH * 0.55f).coerceIn(520.dp, 780.dp)
    val healths = listOf(
        Pair("100% 건강", "아주 건강해요! 😊"),
        Pair("70% 아픔", "조금 아파요 😢"),
        Pair("25% 죽기직전", "살려주세요... 😱"),
        Pair("50% 위험", "위험해요 🚨"),
        Pair("0% 사망", "...")
    )
    val healthColors = listOf(Color(0xFF2E7D32), Color(0xFFF57C00), Color(0xFFC2185B), Color(0xFFD32F2F), Color(0xFF616161))
    val stageEmoji = { stage: String? ->
        when (stage) {
            "fruiting" -> "🍅"
            "flowering" -> "🌸"
            "vegetative" -> "🌿"
            else -> "🌱"
        }
    }
    val hum = w?.humidity?.toInt()?.toString()
        ?: if (scene.isRain) "85" else if (scene.isNight) "60" else "45"
    val sun = if (scene.isNight) "0" else if (scene.isRain) "150" else "850"

    Card(
        Modifier
            .fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🚜 나의 농장", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = OojooTheme.Ink)
                if (locating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OojooTheme.GreenDark)
                        Spacer(Modifier.width(6.dp))
                        Text("위치 확인 중", fontSize = 12.sp, color = OojooTheme.Muted, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        region.ifBlank { "위치 자동 설정" },
                        fontSize = 12.sp,
                        color = OojooTheme.Muted,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(farmHeight)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, OojooTheme.Ink.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(scene.gradient))
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.38f)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(listOf(scene.groundTop.copy(alpha = 0.85f), scene.groundBottom))
                        )
                )
                if (scene.isRain) RainAnimation()
                if (scene.isNight && !scene.isRain) StarField()

                FarmSceneView(
                    modifier = Modifier.fillMaxSize(),
                    plants = plants,
                    isNight = scene.isNight,
                    isRain = scene.isRain
                )

                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp)
                ) {
                    Text(scene.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "${w?.temp?.toInt() ?: "--"}°C",
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "📍 ${region.ifBlank { "위치 확인 중" }}",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricChip("💧 $hum%")
                        MetricChip("☀️ $sun lx")
                    }
                }
            }

            Column(Modifier.padding(20.dp)) {
                Text("📋 식물 건강 상태", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = OojooTheme.Ink)
                Spacer(Modifier.height(10.dp))
                if (plants.isEmpty()) {
                    Text("등록된 식물이 없습니다.", color = OojooTheme.Muted, fontSize = 14.sp)
                } else {
                    plants.forEachIndexed { i, p ->
                        val hIdx = i % healths.size
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .border(BorderStroke(1.dp, OojooTheme.Line), RoundedCornerShape(8.dp))
                                .clickable { onClickPlant(p) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stageEmoji(p.stage), fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
                            }
                            Text(
                                "\"${healths[hIdx].second}\" (${healths[hIdx].first})",
                                color = healthColors[hIdx],
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = OojooTheme.Line, thickness = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "※ 상태 범례\n100% 건강 | 70% 아픔 | 50% 위험\n25% 죽기직전 | 0% 사망",
                    fontSize = 12.sp,
                    color = OojooTheme.Muted,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun MetricChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.22f),
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.45f))
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun RainAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "rain")
    val fall by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Restart),
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
private fun StarField() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stars = listOf(
            Offset(0.12f, 0.10f), Offset(0.28f, 0.18f), Offset(0.45f, 0.08f),
            Offset(0.62f, 0.15f), Offset(0.78f, 0.09f), Offset(0.88f, 0.22f),
            Offset(0.18f, 0.28f), Offset(0.55f, 0.25f), Offset(0.72f, 0.30f)
        )
        stars.forEachIndexed { i, n ->
            drawCircle(
                color = Color.White.copy(alpha = if (i % 2 == 0) 0.9f else 0.55f),
                radius = if (i % 3 == 0) 3.5f else 2.2f,
                center = Offset(n.x * size.width, n.y * size.height)
            )
        }
    }
}

@Composable
fun SpeechBubble(text: String, textColor: Color, bg: Color = OojooTheme.Card) {
    Box(Modifier.offset(y = (-10).dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bg,
            border = BorderStroke(2.dp, OojooTheme.Ink),
            shadowElevation = 2.dp
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
