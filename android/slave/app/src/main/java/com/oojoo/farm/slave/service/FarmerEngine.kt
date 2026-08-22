package com.oojoo.farm.slave.service

import android.content.Context
import android.os.BatteryManager
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.hardware.Hardware
import com.oojoo.farm.slave.model.*
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.album.PlantAlbum
import com.oojoo.farm.slave.vision.AnalysisResult
import com.oojoo.farm.slave.vision.CameraHolder
import com.oojoo.farm.slave.vision.GrowthClipRenderer
import com.oojoo.farm.slave.vision.PlantAnalyzer
import com.oojoo.farm.slave.vision.PlantHealthNet
import com.oojoo.farm.slave.vision.RoiStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 슬레이브 자율 관리의 단일 실행 주체(singleton).
 *
 * 관찰/자동관수·명령 폴링·날씨 갱신·하트비트 루프를 여기에서만 돌린다.
 * UI(DashboardScreen)와 백그라운드(FarmerService)가 동일 인스턴스를 공유하므로
 * 화면이 꺼져도(헤드리스) 서비스가 엔진을 살려 두어 자율 관리가 지속된다.
 *
 * 중복 실행 방지: start() 는 idempotent. 카메라 분석 결과는 onAnalysis() 로 주입한다.
 */
object FarmerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loops: MutableList<Job> = mutableListOf()
    @Volatile private var started = false

    private lateinit var appCtx: Context

    private val _status = MutableStateFlow("자율 관리 대기 중")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _lastAnalysis = MutableStateFlow<AnalysisResult?>(null)
    val lastAnalysis: StateFlow<AnalysisResult?> = _lastAnalysis.asStateFlow()

    private val _plant = MutableStateFlow<Plant?>(null)
    val plant: StateFlow<Plant?> = _plant.asStateFlow()
    private val _plants = MutableStateFlow<List<Plant>>(emptyList())
    val plants: StateFlow<List<Plant>> = _plants.asStateFlow()

    private val _autoOn = MutableStateFlow(true)
    val autoOn: StateFlow<Boolean> = _autoOn.asStateFlow()

    private val _weatherFactor = MutableStateFlow(1.0)
    val weatherFactor: StateFlow<Double> = _weatherFactor.asStateFlow()

    private val _lastWater = MutableStateFlow<String?>(null)
    val lastWater: StateFlow<String?> = _lastWater.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    // 카메라 즉시 캡처 요청 플래그 (UI 가 관찰)
    private val _captureRequested = MutableStateFlow(false)
    val captureRequested: StateFlow<Boolean> = _captureRequested.asStateFlow()

    // ROI 기반 다중 식물 모니터링 결과 (plantId -> 최신 진단)
    data class RoiStatus(
        val plantId: String,
        val plantName: String,
        val healthStatus: String,
        val greenness: Double,
        val needWater: Boolean,
        val pestSuspected: Boolean,
        val modelId: String?,
        val updatedAt: String
    )

    private val _roiStatuses = MutableStateFlow<List<RoiStatus>>(emptyList())
    val roiStatuses: StateFlow<List<RoiStatus>> = _roiStatuses.asStateFlow()

    // ROI 모니터링에서 물 필요/해충 감지 시 각 식물 알림 debounce
    private val roiLastNotify = mutableMapOf<String, Long>()

    // 자율 실행 정책 (원격 정책 동기화)
    private val _fanAuto = MutableStateFlow(true)
    val fanAuto: StateFlow<Boolean> = _fanAuto.asStateFlow()
    private val _laserApproval = MutableStateFlow(true) // true=마스터 승인 필요
    val laserApproval: StateFlow<Boolean> = _laserApproval.asStateFlow()
    private val _pendingLaser = MutableStateFlow(false)  // 해충 감지 후 Laser 승인 대기
    val pendingLaser: StateFlow<Boolean> = _pendingLaser.asStateFlow()

    // 알림 debounce (밀리초)
    private var lastHarvestNotify = 0L
    private var lastPestNotify = 0L
    private val HARVEST_COOLDOWN = 60 * 60 * 1000L // 1시간
    private val PEST_COOLDOWN = 15 * 60 * 1000L    // 15분

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun slaveId() = Prefs.slaveId(appCtx) ?: ""

    @Volatile private var sseJob: Job? = null
    @Volatile private var sseConnected = false

    /** SSE 클라이언트 — 백엔드에서 명령을 실시간으로 수신. */
    private fun startSSE() {
        sseJob?.cancel()
        sseJob = scope.launch {
            val baseUrl = ApiClient.baseUrl.trimEnd('/')
            val sid = slaveId()
            val sessionKey = Prefs.sessionKey(appCtx) ?: ""
            val url = "$baseUrl/api/commands/sse/slave/$sid"

            while (isActive) {
                try {
                    val client = OkHttpClient.Builder()
                        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url(url)
                        .header("x-session-key", sessionKey)
                        .build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        sseConnected = false
                        addLog("[${now()}] SSE 연결 실패 (${response.code}) — 5초 후 재시도")
                        delay(5000)
                        continue
                    }
                    sseConnected = true
                    addLog("[${now()}] SSE 연결됨 — 실시간 명령 대기")
                    val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                    var line: String?
                    while (isActive) {
                        line = reader.readLine()
                        if (line == null) break
                        if (line.startsWith("data: ")) {
                            val jsonStr = line.removePrefix("data: ").trim()
                            if (jsonStr.isNotEmpty()) {
                                try {
                                    val json = JSONObject(jsonStr)
                                    if (json.optString("type") == "command") {
                                        scope.launch { handleSSECommand(json.getJSONObject("command")) }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    response.close()
                    if (isActive) { delay(3000) }
                } catch (_: Exception) {
                    sseConnected = false
                    if (isActive) { delay(5000) }
                }
            }
        }
    }

    /** SSE로 수신한 명령 처리. */
    private suspend fun handleSSECommand(cmd: JSONObject) {
        try {
            val id = cmd.getString("id")
            val action = cmd.getString("action")
            val amountMl = cmd.optInt("amount_ml", 300)
            val plantId = cmd.optString("plant_id").takeIf { it.isNotBlank() && it != "null" }
            when (action) {
                "water" -> {
                    addLog("[${now()}] 마스터 지시(SSE): 관수 ${amountMl}ml")
                    doWater("manual", 1.0, amountMl, plantId)
                    ApiClient.api.commandDone(id)
                }
                "pause" -> {
                    _autoOn.value = false; Prefs.setAutoWater(appCtx, false)
                    addLog("[${now()}] 마스터 지시(SSE): 일시정지")
                    ApiClient.api.commandDone(id)
                }
                "resume" -> {
                    _autoOn.value = true; Prefs.setAutoWater(appCtx, true)
                    addLog("[${now()}] 마스터 지시(SSE): 재개")
                    ApiClient.api.commandDone(id)
                }
                "fan" -> {
                    Hardware.controller.fan(10_000L)
                    addLog("[${now()}] 마스터 지시(SSE): Fan 퇴치")
                    postEventSafe("pest_control", mapOf("response" to "fan_manual"))
                    ApiClient.api.commandDone(id)
                }
                "laser" -> {
                    Hardware.controller.laserPulse(500L)
                    _pendingLaser.value = false
                    addLog("[${now()}] 마스터 승인(SSE): Laser 퇴치")
                    postEventSafe("pest_control", mapOf("response" to "laser_approved"))
                    ApiClient.api.commandDone(id)
                }
                "capture_photo" -> {
                    addLog("[${now()}] 마스터 요청(SSE): 즉시 사진 촬영")
                    captureAndUploadPhotoNow(id, plantId)
                    ApiClient.api.commandDone(id)
                }
                "capture_video" -> {
                    addLog("[${now()}] 마스터 요청(SSE): 3초 영상 캡처")
                    captureAndUploadVideo(id)
                    ApiClient.api.commandDone(id)
                }
                "generate_growth_clip" -> {
                    addLog("[${now()}] 마스터 요청(SSE): 성장 숏클립 생성")
                    generateAndUploadClip(id, plantId)
                    ApiClient.api.commandDone(id)
                }
            }
        } catch (_: Exception) {}
    }

    fun start(context: Context) {
        appCtx = context.applicationContext
        PlantHealthNet.init(appCtx)
        ApiClient.setBaseUrl(Prefs.serverUrl(appCtx))
        ApiClient.setSessionKey(Prefs.sessionKey(appCtx))
        _autoOn.value = Prefs.autoWater(appCtx)
        _weatherFactor.value = Prefs.weatherFactor(appCtx)
        if (started) return
        started = true

        loadPlant()
        syncPolicy()

        loops.add(scope.launch {
            while (isActive) {
                // 하트비트는 자율 정지 상태여도 항상 전송(온라인 표시 유지)
                try {
                    ApiClient.api.heartbeat(HeartbeatRequest(slaveId(), batteryLevel()))
                    _online.value = true
                    flushQueue() // 재연결 시 오프라인 누적 이벤트 동기화
                } catch (_: Exception) { _online.value = false }
                if (!sseConnected) pollCommands() // SSE 미연결 시 폴링 fallback
                if (_autoOn.value) tick()
                maybeCaptureGrowthPhoto()
                delay(30_000L)
            }
        })
        loops.add(scope.launch {
            while (isActive) {
                fetchWeather()
                delay(30 * 60 * 1000L)
            }
        })
        // SSE: 실시간 명령 수신 (폴링 대체)
        startSSE()
        // ROI 다중 식물 모니터링 루프
        loops.add(scope.launch { roiMonitorLoop() })
        addLog("[${now()}] 엔진 시작")
    }

    fun stop() {
        loops.forEach { it.cancel() }
        loops.clear()
        sseJob?.cancel()
        sseJob = null
        started = false
        _online.value = false
    }

    fun isRunning() = started

    fun onAnalysis(result: AnalysisResult) {
        _lastAnalysis.value = result
        if (!result.healthStatus.contains("실패") && !result.healthStatus.contains("불가")) {
            addLog("[${now()}] 분석: ${result.healthStatus} (녹색:${"%.0f".format(result.greenness * 100)}%)")
        }
    }

    fun toggleAuto() {
        val next = !_autoOn.value
        _autoOn.value = next
        Prefs.setAutoWater(appCtx, next)
        _status.value = if (next) "자율 관리 동작 중" else "자율 관리 일시정지"
    }

    fun requestCapture() { _captureRequested.value = true }
    fun onCaptureDone() { _captureRequested.value = false }

    fun manualWater() = doWater("manual")

    private fun batteryLevel(): Int? = try {
        val bm = appCtx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (_: Exception) { null }

    private fun loadPlant() {
        scope.launch {
            try {
                val resp = ApiClient.api.slavePlants(slaveId())
                _plants.value = resp.plants
                _plant.value = resp.plants.firstOrNull()
                val chosen = _plant.value
                PlantHealthNet.setSpecies(chosen?.species ?: chosen?.name)
                chosen?.let {
                    val mid = PlantHealthNet.speciesId
                    addLog("[${now()}] 식물 연결: ${it.name} (${resp.plants.size}그루) 모델:${mid ?: "heuristic"}")
                }
            } catch (e: Exception) {
                addLog("[${now()}] 식물 조회 실패: ${e.message}")
            }
        }
    }

    private fun syncPolicy() {
        scope.launch {
            try {
                val p = ApiClient.api.policy(slaveId())
                _autoOn.value = p.water_auto == 1
                _fanAuto.value = p.fan_auto == 1
                _laserApproval.value = p.laser_approval == 1
                Prefs.setAutoWater(appCtx, _autoOn.value)
                Prefs.setCaptureIntervalMinutes(appCtx, p.capture_interval)
                p.region?.takeIf { it.isNotBlank() }?.let { Prefs.setRegion(appCtx, it) }
                addLog("[${now()}] 원격 정책 동기화 (자동관수:${if (_autoOn.value) "ON" else "OFF"}, Fan:${if (_fanAuto.value) "자동" else "수동"}, Laser:${if (_laserApproval.value) "승인필요" else "자동"})")
            } catch (_: Exception) { /* 정책 없으면 로컬 기본값 유지 */ }
        }
    }

    private suspend fun tick() {
        _status.value = "관찰 중…"
        val result = _lastAnalysis.value
        if (result != null && result.needWater && _autoOn.value) {
            addLog("[${now()}] AI 판정: 수분 부족 의심 → 자동 관수")
            doWater("auto", result.confidence)
        } else if (result != null) {
            _status.value = "관찰 완료: ${result.healthStatus}"
        } else {
            _status.value = "관찰 대기 (카메라 분석 대기)"
        }

        // 수확/해충 자율 파이프라인
        if (result != null) {
            val nowMs = System.currentTimeMillis()
            if (result.fruitRipeness >= 0.55 && nowMs - lastHarvestNotify > HARVEST_COOLDOWN) {
                lastHarvestNotify = nowMs
                addLog("[${now()}] 🍅 수확 적기 감지 (익음도 ${"%.0f".format(result.fruitRipeness * 100)}%) → 마스터 알림")
                postEventSafe("harvest_ready", mapOf("ripeness" to "%.2f".format(result.fruitRipeness)))
            }
            if (result.pestSuspected && nowMs - lastPestNotify > PEST_COOLDOWN) {
                lastPestNotify = nowMs
                handlePest()
            }
        }
    }

    // 해충 대응: Fan 자동(정책) / Laser 는 승인 필요 시 대기, 아니면 자율 실행
    private fun handlePest() {
        if (_fanAuto.value) {
            Hardware.controller.fan(10_000L)
            addLog("[${now()}] 🐛 해충 감지 → Fan 자동 퇴치(10초)")
            postEventSafe("pest_detected", mapOf("response" to "fan"))
        }
        if (_laserApproval.value) {
            _pendingLaser.value = true
            addLog("[${now()}] 🐛 해충 감지 → Laser 마스터 승인 요청")
            postEventSafe("pest_detected", mapOf("response" to "laser_pending"))
        } else {
            Hardware.controller.laserPulse(500L)
            addLog("[${now()}] 🐛 해충 감지 → Laser 자율 퇴치")
            postEventSafe("pest_detected", mapOf("response" to "laser_auto"))
        }
    }

    private suspend fun fetchWeather() {
        try {
            val region = Prefs.region(appCtx)
            val resp = ApiClient.api.weather(region)
            _weatherFactor.value = resp.weatherFactor
            Prefs.setWeatherFactor(appCtx, resp.weatherFactor)
            addLog("[${now()}] 날씨 업데이트: $region ${resp.temp?.let { "${it.toInt()}°C" } ?: "?"} (가중치:${"%.2f".format(resp.weatherFactor)})")
        } catch (e: Exception) {
            addLog("[${now()}] 날씨 조회 실패 (캐시 사용): ${e.message}")
        }
    }

    private suspend fun pollCommands() {
        try {
            val resp = ApiClient.api.pendingCommands(slaveId())
            for (cmd in resp.commands) {
                when (cmd.action) {
                    "water" -> {
                        addLog("[${now()}] 마스터 지시: 관수 ${cmd.amount_ml}ml")
                        doWater("manual", 1.0, cmd.amount_ml, cmd.plant_id)
                        ApiClient.api.commandDone(cmd.id)
                    }
                    "pause" -> {
                        _autoOn.value = false
                        Prefs.setAutoWater(appCtx, false)
                        addLog("[${now()}] 마스터 지시: 자율 관리 일시정지")
                        ApiClient.api.commandDone(cmd.id)
                    }
                    "resume" -> {
                        _autoOn.value = true
                        Prefs.setAutoWater(appCtx, true)
                        addLog("[${now()}] 마스터 지시: 자율 관리 재개")
                        ApiClient.api.commandDone(cmd.id)
                    }
                    "fan" -> {
                        Hardware.controller.fan(10_000L)
                        addLog("[${now()}] 마스터 지시: Fan 퇴치 실행")
                        postEventSafe("pest_control", mapOf("response" to "fan_manual"))
                        ApiClient.api.commandDone(cmd.id)
                    }
                    "laser" -> {
                        Hardware.controller.laserPulse(500L)
                        _pendingLaser.value = false
                        addLog("[${now()}] 마스터 승인: Laser 퇴치 실행")
                        postEventSafe("pest_control", mapOf("response" to "laser_approved"))
                        ApiClient.api.commandDone(cmd.id)
                    }
                    "capture_photo" -> {
                        addLog("[${now()}] 마스터 요청: 즉시 사진 촬영")
                        captureAndUploadPhotoNow(cmd.id, cmd.plant_id)
                        ApiClient.api.commandDone(cmd.id)
                    }
                    "capture_video" -> {
                        addLog("[${now()}] 마스터 요청: 3초 영상 캡처")
                        captureAndUploadVideo(cmd.id)
                        ApiClient.api.commandDone(cmd.id)
                    }
                    "generate_growth_clip" -> {
                        addLog("[${now()}] 마스터 요청: 성장 숏클립 생성")
                        generateAndUploadClip(cmd.id, cmd.plant_id)
                        ApiClient.api.commandDone(cmd.id)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun doWater(source: String, confidence: Double = 1.0, amountOverride: Int? = null, plantIdOverride: String? = null) {
        scope.launch {
            val baseAmount = amountOverride ?: 300
            val factor = _weatherFactor.value
            val amount = if (source == "auto") (baseAmount * factor).toInt().coerceIn(50, 2000) else baseAmount
            val pid = plantIdOverride ?: _plant.value?.id

            // 실제 하드웨어(급수 밸브) 제어 — 공급량에 비례한 개방시간 + Fail-safe 자동 폐쇄.
            // 대략 20ms/ml, 최소 0.5초, 최대 60초(Fail-safe).
            val valveMs = (amount * 20L).coerceIn(500L, 60_000L)
            try {
                Hardware.controller.openValve(valveMs)
                addLog("[${now()}] 급수 밸브 개방 ${valveMs}ms (하드웨어:${if (Hardware.controller.connected.value) "연결" else "시뮬"})")
            } catch (e: Exception) {
                addLog("[${now()}] 밸브 제어 실패: ${e.message}")
            }

            _lastWater.value = now()
            _status.value = "$source 관수 실행 (${amount}ml)"
            addLog("[${_lastWater.value}] $source 관수 ${amount}ml (날씨가중치:${"%.2f".format(factor)})")
            // 백엔드 보고 (오프라인이면 큐에 적재 후 재연결 시 동기화)
            wateringLogSafe(pid, amount, source, factor)
            postEventSafe(
                if (source == "auto") "auto_water" else "manual_water",
                mapOf("amount" to "$amount", "weatherFactor" to "%.2f".format(factor), "confidence" to "%.2f".format(confidence)),
                pid
            )
        }
    }

    private var lastPhotoAttempt = 0L

    /** ROI 편집 화면에서 저장 후 호출 — 즉시 재로드. */
    fun onRoisChanged() {
        scope.launch { runRoiMonitoring() }
    }

    /**
     * ROI 기반 다중 식물 모니터링 루프.
     * 설정된 각 ROI를 프레임에서 잘라 해당 식물의 모델로 주기적으로 진단한다.
     * ROI가 없으면 기존 단일 식물 흐름(_lastAnalysis)을 그대로 사용한다.
     */
    private suspend fun roiMonitorLoop() {
        while (coroutineContext.isActive) {
            delay(20_000L) // 20초 주기
            try {
                runRoiMonitoring()
            } catch (_: Exception) {}
        }
    }

    private suspend fun runRoiMonitoring() {
        val rois = RoiStore.list(appCtx)
        if (rois.isEmpty()) {
            if (_roiStatuses.value.isNotEmpty()) _roiStatuses.value = emptyList()
            return
        }
        if (!awaitCameraReady(1500)) return

        val bitmap = suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
            CameraHolder.captureFrame { b -> if (cont.isActive) cont.resume(b) }
        } ?: return

        try {
            val newStatuses = mutableListOf<RoiStatus>()
            for (roi in rois) {
                if (!roi.isValid()) continue
                val result = PlantAnalyzer.analyzeRoi(bitmap, roi)
                val status = RoiStatus(
                    plantId = roi.plantId,
                    plantName = roi.plantName,
                    healthStatus = result.healthStatus,
                    greenness = result.greenness,
                    needWater = result.needWater,
                    pestSuspected = result.pestSuspected,
                    modelId = result.modelId,
                    updatedAt = now()
                )
                newStatuses += status

                // ROI별 이벤트 알림 (식물당 15분 debounce)
                val nowMs = System.currentTimeMillis()
                val lastN = roiLastNotify[roi.plantId] ?: 0L
                if (nowMs - lastN > 15 * 60 * 1000L) {
                    if (result.needWater) {
                        roiLastNotify[roi.plantId] = nowMs
                        addLog("[${now()}] ROI 진단 [${roi.plantName}]: ${result.healthStatus} → 물 부족")
                        postEventSafe("water_low", mapOf("health" to result.healthStatus), roi.plantId)
                    }
                    if (result.pestSuspected) {
                        roiLastNotify[roi.plantId] = nowMs
                        addLog("[${now()}] ROI 진단 [${roi.plantName}]: 해충 의심")
                        postEventSafe("pest_detected", mapOf("health" to result.healthStatus), roi.plantId)
                    }
                }
            }
            _roiStatuses.value = newStatuses
        } finally {
            bitmap.recycle()
        }
    }

    /** 카메라 준비 대기 (바인딩 진행 중일 수 있어 잠깐 재시도). */
    private suspend fun awaitCameraReady(timeoutMs: Long = 5000): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            if (CameraHolder.ready) return true
            delay(150)
        }
        return CameraHolder.ready
    }

    /**
     * 마스터의 즉시 사진 요청(capture_photo) 처리.
     * 지체 없이 촬영 → 분석 → 업로드. 백그라운드에서도 CameraHost가
     * 카메라를 유지하므로 앱이 내려가 있어도 동작한다.
     */
    private fun captureAndUploadPhotoNow(commandId: String, plantIdHint: String?) {
        scope.launch {
            if (!awaitCameraReady()) {
                addLog("[${now()}] 즉시 사진 실패: 카메라 미준비")
                return@launch
            }
            val plant = _plants.value.firstOrNull { it.id == plantIdHint } ?: _plant.value
            val pid = plant?.id ?: plantIdHint
            if (pid.isNullOrBlank()) {
                addLog("[${now()}] 즉시 사진 실패: 식물 미지정")
                return@launch
            }
            val file = suspendCancellableCoroutine<File?> { cont ->
                CameraHolder.captureStill(appCtx) { f -> if (cont.isActive) cont.resume(f) }
            }
            if (file == null || !file.exists()) {
                addLog("[${now()}] 즉시 사진 촬영 실패")
                return@launch
            }
            // 촬영 즉시 분석 결과 반영 (마스터 대시보드 상태 갱신)
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val result = PlantAnalyzer.analyzeBitmap(bitmap)
                    _lastAnalysis.value = result
                    addLog("[${now()}] 즉시 분석: ${result.healthStatus} (녹색:${"%.0f".format(result.greenness * 100)}%)")
                }
            } catch (_: Exception) {}

            val takenAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val location = Prefs.region(appCtx)
            try {
                PlantAlbum.add(appCtx, pid, file, takenAt, location)
                val reqFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("photo", file.name, reqFile)
                ApiClient.api.uploadPhoto(
                    slaveId(),
                    part,
                    pid.toRequestBody("text/plain".toMediaTypeOrNull()),
                    takenAt.toRequestBody("text/plain".toMediaTypeOrNull()),
                    location.toRequestBody("text/plain".toMediaTypeOrNull()),
                    commandId.takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaTypeOrNull())
                )
                addLog("[${now()}] 즉시 사진 전송 완료 (${file.length() / 1024}KB)")
            } catch (e: Exception) {
                addLog("[${now()}] 즉시 사진 업로드 실패: ${e.message}")
            } finally {
                file.delete()
            }
        }
    }

    private suspend fun maybeCaptureGrowthPhoto() {
        if (!CameraHolder.ready || CameraHolder.isRecording()) return
        val plants = _plants.value.ifEmpty { listOfNotNull(_plant.value) }
        if (plants.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPhotoAttempt < 60_000L) return
        val last = Prefs.lastPhotoAt(appCtx)
        val analysis = _lastAnalysis.value
        val lastG = Prefs.lastPhotoGreenness(appCtx)
        val changed = analysis != null && lastG >= 0f && kotlin.math.abs(analysis.greenness - lastG) >= 0.12
        val dueDaily = last == 0L || nowMs - last >= 20L * 60 * 60 * 1000
        val dueChange = changed && nowMs - last >= 4L * 60 * 60 * 1000
        if (!dueDaily && !dueChange) return
        lastPhotoAttempt = nowMs
        captureAndArchivePhoto(plants)
    }

    private suspend fun captureAndArchivePhoto(plants: List<Plant>) {
        if (!CameraHolder.ready) {
            addLog("[${now()}] 성장 사진 건너뜀: 카메라 미준비")
            return
        }
        val file = suspendCancellableCoroutine<File?> { cont ->
            CameraHolder.captureStill(appCtx) { f -> if (cont.isActive) cont.resume(f) }
        }
        if (file == null || !file.exists()) {
            addLog("[${now()}] 성장 사진 촬영 실패")
            return
        }
        val takenAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val location = Prefs.region(appCtx)
        try {
            for (p in plants) {
                PlantAlbum.add(appCtx, p.id, file, takenAt, location)
                val reqFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("photo", file.name, reqFile)
                ApiClient.api.uploadPhoto(
                    slaveId(),
                    part,
                    p.id.toRequestBody("text/plain".toMediaTypeOrNull()),
                    takenAt.toRequestBody("text/plain".toMediaTypeOrNull()),
                    location.toRequestBody("text/plain".toMediaTypeOrNull())
                )
            }
            Prefs.setLastPhotoAt(appCtx, System.currentTimeMillis())
            _lastAnalysis.value?.let { Prefs.setLastPhotoGreenness(appCtx, it.greenness.toFloat()) }
            addLog("[${now()}] 성장 사진 저장·전송 (${plants.size}그루, ${file.length() / 1024}KB)")
        } catch (e: Exception) {
            addLog("[${now()}] 성장 사진 업로드 실패: ${e.message}")
        } finally {
            file.delete()
        }
    }

    private suspend fun generateAndUploadClip(commandId: String, plantIdHint: String?) {
        val plant = _plants.value.firstOrNull { it.id == plantIdHint } ?: _plant.value
        val plantId = plant?.id ?: plantIdHint
        if (plantId.isNullOrBlank()) {
            reportClipFailed(commandId, null, "plant not assigned")
            return
        }
        try {
            val photos = ensureAlbum(plantId)
            if (photos.isEmpty()) {
                reportClipFailed(commandId, plantId, "no photos yet")
                addLog("[${now()}] 숏클립 실패: 누적 사진 없음")
                return
            }
            addLog("[${now()}] 숏클립 렌더 시작 (사진 ${photos.size}장 + BGM)")
            val out = File(appCtx.cacheDir, "growth_${System.currentTimeMillis()}.mp4")
            val rendered = withContext(Dispatchers.Default) {
                GrowthClipRenderer.render(photos, plant?.name ?: "식물", out)
            }
            val reqFile = rendered.file.asRequestBody("video/mp4".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("video", rendered.file.name, reqFile)
            ApiClient.api.uploadVideo(
                slaveId(),
                part,
                commandId.toRequestBody("text/plain".toMediaTypeOrNull()),
                plantId.toRequestBody("text/plain".toMediaTypeOrNull()),
                "growth".toRequestBody("text/plain".toMediaTypeOrNull()),
                rendered.photoCount.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            )
            addLog("[${now()}] 숏클립 업로드 완료 (${rendered.file.length() / 1024}KB)")
            rendered.file.delete()
        } catch (e: Exception) {
            addLog("[${now()}] 숏클립 실패: ${e.message}")
            reportClipFailed(commandId, plantId, e.message ?: "render failed")
        }
    }

    private suspend fun ensureAlbum(plantId: String): List<com.oojoo.farm.slave.album.AlbumEntry> {
        val local = PlantAlbum.list(appCtx, plantId)
        if (local.isNotEmpty()) return local
        return try {
            val remote = ApiClient.api.plantPhotos(plantId).photos
            val client = OkHttpClient()
            for (p in remote) {
                val full = ApiClient.baseUrl.trimEnd('/') + p.url
                val resp = client.newCall(Request.Builder().url(full).build()).execute()
                if (!resp.isSuccessful) { resp.close(); continue }
                val dest = File(appCtx.cacheDir, "dl_${p.id}.jpg")
                dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                resp.close()
                PlantAlbum.add(appCtx, plantId, dest, p.taken_at ?: "", p.location ?: Prefs.region(appCtx))
                dest.delete()
            }
            PlantAlbum.list(appCtx, plantId)
        } catch (_: Exception) {
            local
        }
    }

    private suspend fun reportClipFailed(commandId: String, plantId: String?, error: String) {
        try {
            ApiClient.api.clipFailed(slaveId(), ClipFailedRequest(commandId, plantId, error))
        } catch (_: Exception) {}
    }

    // ---------- 3초 비디오 캡처 + 업로드 ----------
    // CameraPreview 가 바인딩한 VideoCapture 를 CameraHolder 를 통해 사용.
    // 별도 바인딩 불필요 — CameraPreview 의 LaunchedEffect 가 통합 바인딩함.
    private fun captureAndUploadVideo(commandId: String) {
        scope.launch {
            if (!awaitCameraReady()) {
                addLog("[${now()}] 영상 캡처 실패: 카메라 미준비")
                return@launch
            }
            addLog("[${now()}] 3초 영상 캡처 시작…")
            // 3초 캡처 (블로킹 콜백을 await로 변환)
            val file = suspendCancellableCoroutine<File?> { cont ->
                CameraHolder.capture3s(appCtx) { f -> if (cont.isActive) cont.resume(f) }
            }
            if (file == null || !file.exists()) {
                addLog("[${now()}] 영상 캡처 실패: 파일 없음")
                return@launch
            }
            addLog("[${now()}] 영상 캡처 완료 (${file.length() / 1024}KB) → 업로드")
            try {
                val reqFile = file.asRequestBody("video/mp4".toMediaTypeOrNull())
                val cId = commandId.toRequestBody("text/plain".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("video", file.name, reqFile)
                val resp = ApiClient.api.uploadVideo(slaveId(), part, cId)
                addLog("[${now()}] 영상 업로드 완료 (id:${resp.videoId.take(8)})")
            } catch (e: Exception) {
                addLog("[${now()}] 영상 업로드 실패: ${e.message}")
            } finally {
                file.delete()
            }
        }
    }

    // ---------- 오프라인 큐 (실패 시 로컬 적재, 재연결 시 flush) ----------
    private suspend fun wateringLogSafe(pid: String?, amount: Int, source: String, factor: Double) {
        try {
            ApiClient.api.wateringLog(WateringLogRequest(slaveId(), pid, amount, source, factor))
        } catch (_: Exception) {
            enqueue(JSONObject().apply {
                put("kind", "watering"); put("plantId", pid ?: JSONObject.NULL)
                put("amountMl", amount); put("source", source); put("weatherFactor", factor)
            })
        }
    }

    private fun postEventSafe(type: String, payload: Map<String, String>, pid: String? = _plant.value?.id) {
        scope.launch {
            try {
                ApiClient.api.reportEvent(EventRequest(slaveId(), pid, type, payload))
            } catch (_: Exception) {
                enqueue(JSONObject().apply {
                    put("kind", "event"); put("plantId", pid ?: JSONObject.NULL)
                    put("type", type); put("payload", JSONObject(payload as Map<*, *>))
                })
            }
        }
    }

    private fun enqueue(obj: JSONObject) {
        val arr = try { JSONArray(Prefs.offlineQueue(appCtx)) } catch (_: Exception) { JSONArray() }
        arr.put(obj)
        Prefs.setOfflineQueue(appCtx, arr.toString())
    }

    private suspend fun flushQueue() {
        val arr = try { JSONArray(Prefs.offlineQueue(appCtx)) } catch (_: Exception) { JSONArray() }
        if (arr.length() == 0) return
        val remaining = JSONArray()
        var sent = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pid = if (o.isNull("plantId")) null else o.optString("plantId")
            try {
                if (o.getString("kind") == "watering") {
                    ApiClient.api.wateringLog(WateringLogRequest(slaveId(), pid, o.getInt("amountMl"), o.getString("source"), o.getDouble("weatherFactor")))
                } else {
                    val pj = o.optJSONObject("payload") ?: JSONObject()
                    val map = mutableMapOf<String, String>()
                    for (k in pj.keys()) map[k] = pj.getString(k)
                    ApiClient.api.reportEvent(EventRequest(slaveId(), pid, o.getString("type"), map))
                }
                sent++
            } catch (_: Exception) {
                remaining.put(o)
            }
        }
        Prefs.setOfflineQueue(appCtx, remaining.toString())
        if (sent > 0) addLog("[${now()}] 오프라인 이벤트 ${sent}건 동기화 완료")
    }

    private fun addLog(s: String) {
        _logs.value = (listOf(s) + _logs.value).take(30)
    }
}
