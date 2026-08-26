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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.VideoView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class PlantDetailViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var plant by mutableStateOf<Plant?>(null)
    var waterings by mutableStateOf<List<Watering>>(emptyList())
    var events by mutableStateOf<List<FarmEvent>>(emptyList())
    var latestAnalysis by mutableStateOf<AnalysisResponse?>(null)
    var weather by mutableStateOf<WeatherResponse?>(null)
    var photos by mutableStateOf<List<PlantPhoto>>(emptyList())
    var growthClip by mutableStateOf<VideoInfoResponse?>(null)
    var clipMaking by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var editing by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var editName by mutableStateOf("")
    var editSpecies by mutableStateOf("")
    var editPlantedAt by mutableStateOf("")
    var editStage by mutableStateOf("seedling")
    var editSlaveId by mutableStateOf<String?>(null)
    private var sseJob: Job? = null
    val popularSpecies = listOf("상추", "깻잎", "바질", "로즈마리", "허브", "선인장", "방울토마토", "토마토", "대파", "딸기", "고추", "애호박", "호박", "고구마", "감자", "양파")

    fun load(plantId: String) {
        loading = true
        viewModelScope.launch {
            try {
                plant = api.plant(plantId)
                plant?.let { p ->
                    try { waterings = api.waterings(p.id).waterings } catch (_: Exception) {}
                    if (p.slave_id != null) try { events = api.events(p.slave_id).events } catch (_: Exception) {}
                    try { latestAnalysis = api.latestAnalysis(p.id) } catch (_: Exception) {}
                    try { photos = api.plantPhotos(p.id).photos } catch (_: Exception) {}
                    try { growthClip = api.plantVideos(p.id, "growth").videos.firstOrNull() } catch (_: Exception) {}
                }
                try { slaves = api.slaves(Session.userId).slaves } catch (_: Exception) {}
                try { weather = api.weather(Session.region) } catch (_: Exception) {}
            } catch (e: Exception) { msg = e.message }
            loading = false
        }
    }

    fun startEdit() {
        val p = plant ?: return
        editName = p.name
        editSpecies = p.species ?: ""
        editPlantedAt = p.planted_at ?: ""
        editStage = p.stage ?: "seedling"
        editSlaveId = p.slave_id
        editing = true
    }

    fun saveEdit() {
        val p = plant ?: return
        if (editName.isBlank()) { msg = "식물 이름을 입력하세요"; return }
        saving = true
        viewModelScope.launch {
            try {
                api.updatePlant(
                    p.id,
                    UpdatePlantRequest(
                        slaveId = editSlaveId,
                        name = editName.trim(),
                        species = editSpecies.trim().ifBlank { null },
                        plantedAt = editPlantedAt.trim().ifBlank { null },
                        stage = editStage
                    )
                )
                editing = false
                msg = "수정되었습니다"
                load(p.id)
            } catch (e: Exception) { msg = e.message }
            saving = false
        }
    }

    fun requestGrowthClip(ctx: android.content.Context) {
        val p = plant ?: return
        val sId = p.slave_id ?: run { msg = ctx.getString(R.string.growth_clip_no_farmer); return }
        if (photos.isEmpty()) {
            msg = ctx.getString(R.string.growth_clip_need_photos)
            return
        }
        clipMaking = true
        msg = ctx.getString(R.string.growth_clip_waiting)
        sseJob?.cancel()
        viewModelScope.launch {
            try {
                val cmd = api.sendCommand(CommandRequest(sId, p.id, "generate_growth_clip"))
                startClipSse(sId, cmd.commandId, p.id, ctx)
                var waited = 0
                var got = false
                while (waited < 90 && clipMaking && !got) {
                    delay(1000)
                    waited += 1
                    if (waited % 4 == 0) {
                        try {
                            onClipReady(api.videoByCommand(cmd.commandId), ctx)
                            got = true
                        } catch (_: Exception) {}
                    }
                }
                if (!got && clipMaking) {
                    clipMaking = false
                    msg = ctx.getString(R.string.growth_clip_timeout)
                    sseJob?.cancel()
                }
            } catch (e: Exception) {
                clipMaking = false
                msg = e.message
            }
        }
    }

    private fun startClipSse(slaveId: String, commandId: String, plantId: String, ctx: android.content.Context) {
        sseJob = viewModelScope.launch(Dispatchers.IO) {
            val url = ApiClient.baseUrl.trimEnd('/') + "/api/commands/sse/master/$slaveId"
            try {
                val client = OkHttpClient.Builder()
                    .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (!response.isSuccessful) return@launch
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data: ")) continue
                    val jsonStr = line.removePrefix("data: ").trim()
                    if (jsonStr.isEmpty()) continue
                    try {
                        val json = JSONObject(jsonStr)
                        val type = json.optString("type")
                        if (type == "growth_clip_ready" || (type == "video_ready" && json.optString("kind") == "growth")) {
                            if (json.optString("commandId") == commandId || json.optString("plantId") == plantId) {
                                val v = VideoInfoResponse(
                                    videoId = json.getString("videoId"),
                                    slaveId = json.getString("slaveId"),
                                    commandId = json.optString("commandId"),
                                    plantId = json.optString("plantId"),
                                    kind = json.optString("kind"),
                                    url = json.getString("url"),
                                    mime = json.optString("mime"),
                                    size = json.optInt("size")
                                )
                                withContext(Dispatchers.Main) { onClipReady(v, ctx) }
                                response.close()
                                return@launch
                            }
                        }
                        if (type == "growth_clip_failed" && json.optString("commandId") == commandId) {
                            withContext(Dispatchers.Main) {
                                clipMaking = false
                                msg = ctx.getString(R.string.growth_clip_failed)
                            }
                            response.close()
                            return@launch
                        }
                    } catch (_: Exception) {}
                }
                response.close()
            } catch (_: Exception) {}
        }
    }

    private fun onClipReady(v: VideoInfoResponse, ctx: android.content.Context) {
        if (!clipMaking && growthClip?.videoId == v.videoId) return
        growthClip = v
        clipMaking = false
        msg = ctx.getString(R.string.growth_clip_done)
        sseJob?.cancel()
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
                if (vm.editing && pl != null) {
                    PlantEditCard(vm)
                } else if (pl != null) {
                    val stageK = mapOf(
                        "seedling" to S.growthStageSeedling,
                        "vegetative" to S.growthStageVegetative,
                        "flowering" to S.growthStageFlowering,
                        "fruiting" to S.growthStageFruiting
                    )
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(pl.name, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = OojooTheme.Ink, modifier = Modifier.weight(1f))
                                Surface(
                                    shape = CircleShape,
                                    color = OojooTheme.GreenBg,
                                    border = BorderStroke(1.dp, OojooTheme.Ink),
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clickable { vm.startEdit() }
                                ) {
                                    Text("✏️", fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            Text("${S.typeLabel}: ${pl.species ?: S.unknown}", color = OojooTheme.Muted, fontSize = 14.sp)
                            Text("${S.datePlantedLabel}: ${pl.planted_at ?: S.unknown}", color = OojooTheme.Muted, fontSize = 13.sp)
                            Text("${S.stageLabel}: ${stageK[pl.stage] ?: pl.stage ?: S.unknown}", color = OojooTheme.Muted, fontSize = 13.sp)
                            Text("${S.farmerLabel}: ${vm.slaves.find { it.id == pl.slave_id }?.name ?: pl.slave_id?.take(8) ?: S.unconnected}", color = OojooTheme.Muted, fontSize = 13.sp)
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
                val ctx = LocalContext.current
                Text(stringResource(R.string.growth_album_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.growth_album_hint), color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (vm.photos.isEmpty()) {
                    Text(stringResource(R.string.growth_album_empty), color = OojooTheme.Muted, fontSize = 13.sp)
                } else {
                    val rows = vm.photos.chunked(3)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { photo ->
                                    Column(Modifier.weight(1f)) {
                                        AsyncImage(
                                            model = ApiClient.baseUrl.trimEnd('/') + photo.url,
                                            contentDescription = photo.taken_at,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(0.75f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(1.5.dp, OojooTheme.Ink, RoundedCornerShape(12.dp))
                                        )
                                        Text(
                                            listOfNotNull(photo.taken_at, photo.location).joinToString(" · "),
                                            color = OojooTheme.Muted,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2
                                        )
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                GradientButton(
                    text = stringResource(if (vm.clipMaking) R.string.growth_clip_making else R.string.growth_clip_action),
                    onClick = { vm.requestGrowthClip(ctx) },
                    enabled = !vm.clipMaking && vm.plant?.slave_id != null,
                    modifier = Modifier.fillMaxWidth()
                )
                vm.growthClip?.let { clip ->
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.growth_clip_ready_label), fontWeight = FontWeight.Bold, color = OojooTheme.GreenDark)
                    val playUrl = ApiClient.baseUrl.trimEnd('/') + clip.url
                    AndroidView(
                        factory = { c ->
                            VideoView(c).apply {
                                setVideoPath(playUrl)
                                setOnPreparedListener { it.isLooping = true; start() }
                            }
                        },
                        update = { view ->
                            if (view.tag != playUrl) {
                                view.tag = playUrl
                                view.setVideoPath(playUrl)
                                view.start()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(2.dp, OojooTheme.Ink, RoundedCornerShape(16.dp))
                    )
                }
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
                        Text(stringResource(R.string.humidity_summary, w.humidity?.toInt()?.toString() ?: "?", "%.2f".format(w.weatherFactor)), color = Color.White.copy(alpha = 0.95f), fontSize = 12.sp)
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
                                Text(localizeMasterMessage(a.healthStatus, S), fontWeight = FontWeight.Bold, color = color, fontSize = 14.sp)
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
                            HorizontalDivider(color = OojooTheme.Line)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(S.aiModelLabel, color = OojooTheme.Muted, fontSize = 13.sp)
                                Text(a.modelId ?: S.heuristicModel, fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 14.sp)
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
                vm.msg?.let { Text(localizeMasterMessage(it, S), fontSize = 13.sp, color = OojooTheme.Green) }
                OutlineButton(text = S.back, onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlantEditCard(vm: PlantDetailViewModel) {
    val S = LocalAppStrings.current
    val speciesLabels = if (S.isEnglish) mapOf(
        "상추" to "Lettuce", "깻잎" to "Perilla", "바질" to "Basil", "로즈마리" to "Rosemary",
        "허브" to "Herb", "선인장" to "Cactus",
        "방울토마토" to "Cherry tomato", "토마토" to "Tomato", "대파" to "Green onion",
        "딸기" to "Strawberry", "고추" to "Chili pepper", "애호박" to "Zucchini",
        "호박" to "Pumpkin", "고구마" to "Sweet potato", "감자" to "Potato", "양파" to "Onion"
    ) else emptyMap()
    val stages = listOf(
        "seedling" to S.growthStageSeedling,
        "vegetative" to S.growthStageVegetative,
        "flowering" to S.growthStageFlowering,
        "fruiting" to S.growthStageFruiting
    )
    var speciesExpanded by remember { mutableStateOf(false) }
    var stageExpanded by remember { mutableStateOf(false) }
    var slaveExpanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(S.plantEditTitle, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = OojooTheme.Ink)
                Text(plantEmojiFor(vm.editSpecies.ifBlank { null }, vm.editStage), fontSize = 24.sp)
            }
            Text(S.plantNameRequired, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.editName, { vm.editName = it }, S.plantNamePh)
            Text(S.species, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = speciesExpanded, onExpandedChange = { speciesExpanded = it }) {
                OutlinedTextField(
                    value = speciesLabels[vm.editSpecies] ?: vm.editSpecies,
                    onValueChange = { vm.editSpecies = it; speciesExpanded = true },
                    placeholder = { Text(S.speciesPh) },
                    shape = OojooTheme.FieldShape,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = speciesExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                val filtered = vm.popularSpecies.filter {
                    it.contains(vm.editSpecies, ignoreCase = true) || speciesLabels[it]?.contains(vm.editSpecies, ignoreCase = true) == true
                }
                if (filtered.isNotEmpty()) {
                    ExposedDropdownMenu(expanded = speciesExpanded, onDismissRequest = { speciesExpanded = false }) {
                        filtered.forEach { sp ->
                            DropdownMenuItem(
                                text = { Text("${plantEmojiFor(sp, null)}  ${speciesLabels[sp] ?: sp}") },
                                onClick = { vm.editSpecies = sp; speciesExpanded = false }
                            )
                        }
                    }
                }
            }
            Text(S.plantedDateOptional, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.editPlantedAt, { vm.editPlantedAt = it }, S.plantedDatePh)
            Text(S.selectFarmer, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = slaveExpanded, onExpandedChange = { slaveExpanded = it }) {
                val s = vm.slaves.find { it.id == vm.editSlaveId }
                OutlinedTextField(value = s?.name ?: S.selectLater, onValueChange = {}, readOnly = true, shape = OojooTheme.FieldShape, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = slaveExpanded) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line), modifier = Modifier.fillMaxWidth().menuAnchor())
                ExposedDropdownMenu(expanded = slaveExpanded, onDismissRequest = { slaveExpanded = false }) {
                    DropdownMenuItem(text = { Text(S.selectNone) }, onClick = { vm.editSlaveId = null; slaveExpanded = false })
                    vm.slaves.forEach { sl -> DropdownMenuItem(text = { Text("${sl.name} ${if (sl.online == 1) S.farmerOnlineBadge else S.farmerOfflineBadge}") }, onClick = { vm.editSlaveId = sl.id; slaveExpanded = false }) }
                }
            }
            Text(S.growthStage, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = stageExpanded, onExpandedChange = { stageExpanded = it }) {
                val lbl = stages.find { it.first == vm.editStage }?.second ?: vm.editStage
                OutlinedTextField(value = lbl, onValueChange = {}, readOnly = true, shape = OojooTheme.FieldShape, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stageExpanded) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line), modifier = Modifier.fillMaxWidth().menuAnchor())
                ExposedDropdownMenu(expanded = stageExpanded, onDismissRequest = { stageExpanded = false }) {
                    stages.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { vm.editStage = v; stageExpanded = false }) }
                }
            }
            if (vm.saving) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OojooTheme.Green) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton(text = S.save, onClick = { vm.saveEdit() }, enabled = !vm.saving && vm.editName.isNotBlank(), modifier = Modifier.weight(1f))
                OutlineButton(text = S.cancel, onClick = { vm.editing = false }, modifier = Modifier.weight(1f), enabled = !vm.saving)
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
