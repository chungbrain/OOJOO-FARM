package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PlantDetailViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var plant by mutableStateOf<Plant?>(null)
    var waterings by mutableStateOf<List<Watering>>(emptyList())
    var events by mutableStateOf<List<FarmEvent>>(emptyList())
    var latestAnalysis by mutableStateOf<AnalysisResponse?>(null)
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
                    try { latestAnalysis = api.latestAnalysis(p.id) } catch (_: Exception) {}
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
    val S = LocalAppStrings.current
    LaunchedEffect(plantId) { vm.load(plantId) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(S.plantDetail, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val pl = vm.plant
                if (pl != null) {
                    val stageK = mapOf(
                        "seedling" to S.growthStageSeedling,
                        "vegetative" to S.growthStageVegetative,
                        "flowering" to S.growthStageFlowering,
                        "fruiting" to S.growthStageFruiting
                    )
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(pl.name, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = OojooTheme.Ink)
                            Text("${S.typeLabel}: ${pl.species ?: S.unknown}", color = OojooTheme.Muted, fontSize = 14.sp)
                            Text("${S.datePlantedLabel}: ${pl.planted_at ?: S.unknown}", color = OojooTheme.Muted, fontSize = 13.sp)
                            Text("${S.stageLabel}: ${stageK[pl.stage] ?: pl.stage ?: S.unknown}", color = OojooTheme.Muted, fontSize = 13.sp)
                            Text("${S.farmerLabel}: ${pl.slave_id?.take(8) ?: S.unconnected}", color = OojooTheme.Muted, fontSize = 13.sp)
                        }
                    }
                } else if (vm.loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green) }
                } else { Text(S.plantNotFound) }
            }

            item {
                GradientButton(text = S.quickWater, onClick = { vm.quickWater() }, enabled = vm.plant?.slave_id != null, modifier = Modifier.fillMaxWidth())
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

            item {
                vm.latestAnalysis?.analysis?.let { a ->
                    Text(S.healthInfo, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
                    Spacer(Modifier.height(4.dp))
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(S.overallStatus, color = OojooTheme.Muted, fontSize = 13.sp)
                                val color = if (a.healthStatus.contains("건강") || a.healthStatus.contains("양호")) OojooTheme.Teal else OojooTheme.Orange
                                Text(a.healthStatus, fontWeight = FontWeight.Bold, color = color, fontSize = 14.sp)
                            }
                            HorizontalDivider(color = OojooTheme.Line)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(S.waterNeed, color = OojooTheme.Muted, fontSize = 13.sp)
                                Text(if (a.needWater) S.waterNeedYes else S.waterNeedNo, fontWeight = FontWeight.Bold, color = if (a.needWater) OojooTheme.Red else OojooTheme.Ink, fontSize = 14.sp)
                            }
                            HorizontalDivider(color = OojooTheme.Line)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(S.pestSuspect, color = OojooTheme.Muted, fontSize = 13.sp)
                                Text(if (a.pestSuspected) S.found else S.safe, fontWeight = FontWeight.Bold, color = if (a.pestSuspected) OojooTheme.Red else OojooTheme.Ink, fontSize = 14.sp)
                            }
                            a.normalShot?.let { ns ->
                                HorizontalDivider(color = OojooTheme.Line)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(S.healthScore, color = OojooTheme.Muted, fontSize = 13.sp)
                                    Text("${ns.healthScore} / 100", fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            item { Text(S.waterHistory, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
            if (vm.waterings.isEmpty()) { item { Text(S.noWaterHistory, color = OojooTheme.Muted, fontSize = 13.sp) } }
            else {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp)) {
                            // Canvas 선 그래프 그리기
                            val reversed = vm.waterings.take(10).reversed()
                            if (reversed.size > 1) {
                                val maxVal = reversed.maxOf { it.amount_ml }.toFloat()
                                val minVal = 0f
                                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(120.dp).padding(vertical = 10.dp)) {
                                    val width = size.width
                                    val height = size.height
                                    val stepX = width / (reversed.size - 1)
                                    val path = Path()

                                    reversed.forEachIndexed { i, w ->
                                        val x = i * stepX
                                        val y = height - ((w.amount_ml - minVal) / (maxVal - minVal + 1f)) * height
                                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                        drawCircle(color = OojooTheme.Green, radius = 4.dp.toPx(), center = Offset(x, y))
                                    }
                                    drawPath(path = path, color = OojooTheme.Green, style = Stroke(width = 2.dp.toPx()))
                                }
                                HorizontalDivider(color = OojooTheme.Line, modifier = Modifier.padding(vertical = 8.dp))
                            }

                            vm.waterings.take(5).forEach { w ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("${w.amount_ml}ml", fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
                                        Text("${if (w.source == "auto") S.autoMode else S.manualMode} · ${w.created_at ?: ""}", color = OojooTheme.Muted, fontSize = 11.sp)
                                    }
                                    Text("×${"%.1f".format(w.weather_factor)}", color = OojooTheme.Muted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            item { Text(S.recentEvents, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
            if (vm.events.isEmpty()) { item { Text(S.noEvents, color = OojooTheme.Muted, fontSize = 13.sp) } }
            else {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp)) {
                            vm.events.take(20).forEachIndexed { index, e ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(OojooTheme.Green))
                                        if (index != vm.events.take(20).lastIndex) {
                                            Box(Modifier.width(2.dp).height(40.dp).background(OojooTheme.Line))
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.padding(bottom = 12.dp)) {
                                        Text(notiLabel(e.type, S), fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 14.sp)
                                        Text(e.created_at ?: "", color = OojooTheme.Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                vm.msg?.let { Text(it, fontSize = 13.sp, color = OojooTheme.Green) }
                OutlineButton(text = S.back, onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

fun notiLabel(t: String, S: com.oojoo.farm.master.data.AppStrings): String = when (t) {
    "harvest_ready" -> S.notiHarvestReady
    "pest_detected" -> S.notiPestDetected
    "auto_water" -> S.notiAutoWater
    "manual_water" -> S.notiManualWater
    "anomaly" -> S.notiAnomaly
    "capture" -> S.notiCapture
    else -> t
}
