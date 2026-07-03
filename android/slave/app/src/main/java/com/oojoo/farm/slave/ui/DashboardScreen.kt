package com.oojoo.farm.slave.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.model.*
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.vision.AnalysisResult
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FarmerViewModel(app: Application) : AndroidViewModel(app) {
    private val api = ApiClient.api
    private val ctx = app
    val slaveId = Prefs.slaveId(app) ?: ""
    val userId = Prefs.userId(app) ?: ""

    var autoOn by mutableStateOf(Prefs.autoWater(app))
    var plant by mutableStateOf<Plant?>(null)
    var lastAnalysis by mutableStateOf<AnalysisResult?>(null)
    var lastWater by mutableStateOf<String?>(null)
    var lastCapture by mutableStateOf<String?>(null)
    var logs by mutableStateOf<List<String>>(emptyList())
    var status by mutableStateOf("자율 관리 대기 중")
    var weatherFactor by mutableStateOf(Prefs.weatherFactor(app))
    var captureRequested by mutableStateOf(false)
    private var loop: Job? = null
    private var weatherLoop: Job? = null
    private var commandLoop: Job? = null

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    init {
        loadPlant()
        startLoops()
    }

    private fun loadPlant() {
        viewModelScope.launch {
            try {
                val resp = api.slavePlants(slaveId)
                plant = resp.plants.firstOrNull()
                if (plant != null) addLog("[${now()}] 식물 연결: ${plant!!.name}")
            } catch (e: Exception) {
                addLog("[${now()}] 식물 조회 실패: ${e.message}")
            }
        }
    }

    private fun startLoops() {
        loop?.cancel()
        weatherLoop?.cancel()
        commandLoop?.cancel()

        loop = viewModelScope.launch {
            while (isActive) {
                if (autoOn) tick()
                delay(30_000L)
            }
        }

        weatherLoop = viewModelScope.launch {
            while (isActive) {
                fetchWeather()
                delay(30 * 60 * 1000L)
            }
        }

        commandLoop = viewModelScope.launch {
            while (isActive) {
                pollCommands()
                delay(10_000L)
            }
        }
    }

    fun onAnalysisResult(result: AnalysisResult) {
        lastAnalysis = result
        lastCapture = now()
        if (result.healthStatus.contains("실패").not() && result.healthStatus.contains("불가").not()) {
            addLog("[${now()}] 분석: ${result.healthStatus} (녹색:${"%.0f".format(result.greenness * 100)}%)")
        }
    }

    private suspend fun tick() {
        status = "관찰 중…"
        try { api.heartbeat(HeartbeatRequest(slaveId)) } catch (_: Exception) {}

        val result = lastAnalysis
        if (result != null && result.needWater && autoOn) {
            addLog("[${now()}] AI 판정: 수분 부족 의심 → 자동 관수")
            doWater("auto", result.confidence)
        } else if (result != null) {
            status = "관찰 완료: ${result.healthStatus}"
        } else {
            status = "관찰 대기 (카메라 분석 대기)"
        }
    }

    private suspend fun fetchWeather() {
        try {
            val region = Prefs.region(ctx)
            val resp = api.weather(region)
            weatherFactor = resp.weatherFactor
            Prefs.setWeatherFactor(ctx, resp.weatherFactor)
            addLog("[${now()}] 날씨 업데이트: ${region} ${resp.temp?.let { "${it.toInt()}°C" } ?: "?"} (가중치:${"%.2f".format(resp.weatherFactor)})")
        } catch (e: Exception) {
            addLog("[${now()}] 날씨 조회 실패 (캐시 사용): ${e.message}")
        }
    }

    private suspend fun pollCommands() {
        try {
            val resp = api.pendingCommands(slaveId)
            for (cmd in resp.commands) {
                when (cmd.action) {
                    "water" -> {
                        addLog("[${now()}] 마스터 지시: 관수 ${cmd.amount_ml}ml")
                        doWater("manual", 1.0, cmd.amount_ml, cmd.plant_id)
                        api.commandDone(cmd.id)
                    }
                    "pause" -> {
                        autoOn = false
                        Prefs.setAutoWater(ctx, false)
                        addLog("[${now()}] 마스터 지시: 자율 관리 일시정지")
                        api.commandDone(cmd.id)
                    }
                    "resume" -> {
                        autoOn = true
                        Prefs.setAutoWater(ctx, true)
                        addLog("[${now()}] 마스터 지시: 자율 관리 재개")
                        api.commandDone(cmd.id)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun doWater(source: String, confidence: Double = 1.0, amountOverride: Int? = null, plantIdOverride: String? = null) {
        viewModelScope.launch {
            val baseAmount = amountOverride ?: 300
            val amount = if (source == "auto") (baseAmount * weatherFactor).toInt().coerceIn(50, 2000) else baseAmount
            val pid = plantIdOverride ?: plant?.id
            try {
                api.wateringLog(WateringLogRequest(slaveId, pid, amount, source, weatherFactor))
                api.reportEvent(EventRequest(
                    slaveId, pid,
                    if (source == "auto") "auto_water" else "manual_water",
                    mapOf("amount" to "$amount", "weatherFactor" to "${"%.2f".format(weatherFactor)}", "confidence" to "${"%.2f".format(confidence)}")
                ))
                lastWater = now()
                addLog("[$lastWater] $source 관수 ${amount}ml (날씨가중치:${"%.2f".format(weatherFactor)})")
                status = "$source 관수 실행 (${amount}ml)"
            } catch (e: Exception) {
                addLog("[${now()}] 관수 실패: ${e.message}")
            }
        }
    }

    fun toggleAuto() {
        autoOn = !autoOn
        Prefs.setAutoWater(ctx, autoOn)
        status = if (autoOn) "자율 관리 동작 중" else "자율 관리 일시정지"
    }

    fun requestCapture() {
        captureRequested = true
    }

    fun onCaptureDone() {
        captureRequested = false
    }

    private fun addLog(s: String) { logs = (listOf(s) + logs).take(20) }

    override fun onCleared() {
        loop?.cancel()
        weatherLoop?.cancel()
        commandLoop?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavController, vm: FarmerViewModel = viewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Farmer 대시보드") },
                actions = { TextButton(onClick = { nav.navigate("settings") }) { Text("설정") } }
            )
        }
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("온디바이스 AI 자율 관리", fontWeight = FontWeight.Bold)
                    Text("Slave: ${vm.slaveId.take(8)}…", style = MaterialTheme.typography.bodySmall)
                    vm.plant?.let { Text("식물: ${it.name} (${it.species ?: "?"})", style = MaterialTheme.typography.bodySmall) }
                        ?: Text("식물 미연결 (마스터에서 등록 필요)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text("상태: ${vm.status}", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("자율 관리", Modifier.weight(1f))
                        Switch(checked = vm.autoOn, onCheckedChange = { vm.toggleAuto() })
                    }
                    Text("날씨 가중치: ${"%.2f".format(vm.weatherFactor)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth().height(180.dp)) {
                Box(Modifier.fillMaxSize()) {
                    com.oojoo.farm.slave.vision.CameraPreview(
                        onAnalysisResult = { vm.onAnalysisResult(it) },
                        captureRequested = vm.captureRequested,
                        onCaptureDone = { vm.onCaptureDone() }
                    )
                }
            }

            vm.lastAnalysis?.let { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("최근 분석", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text("상태: ${a.healthStatus}", style = MaterialTheme.typography.bodySmall)
                        Text("녹색도: ${"%.0f".format(a.greenness * 100)}% / 밝기: ${"%.0f".format(a.brightness * 100)}%", style = MaterialTheme.typography.bodySmall)
                        Text("수분 필요: ${if (a.needWater) "예" else "아니오"} (신뢰도 ${"%.0f".format(a.confidence * 100)}%)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.requestCapture() }, modifier = Modifier.weight(1f)) { Text("즉시 캡처") }
                Button(onClick = { vm.doWater("manual") }, modifier = Modifier.weight(1f)) { Text("수동 관수") }
            }

            Text("이벤트 로그", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (vm.logs.isEmpty()) Text("로그 없음", style = MaterialTheme.typography.bodySmall)
                    vm.logs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
