package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.oojoo.farm.master.data.AppStrings
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.model.CommandRequest
import com.oojoo.farm.master.model.VideoInfoResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import android.os.Environment
import android.widget.VideoView
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveCameraViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var videoInfo by mutableStateOf<VideoInfoResponse?>(null)
    var status by mutableStateOf("요청 대기")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var savedPath by mutableStateOf<String?>(null)
    var savedMsg by mutableStateOf<String?>(null)

    // 사진 즉시 촬영 모드 (capture_photo)
    var photoMode by mutableStateOf(false)
    var photoUrl by mutableStateOf<String?>(null)
    var photoTakenAt by mutableStateOf<String?>(null)

    // 실시간 보기 — farmer에게 live_start 를 보내 3초 프레임 push를 켜고,
    // 같은 주기(3초)로 백엔드의 최신 프레임을 받아 화면을 갱신한다 (3초 라이브).
    var liveFrame by mutableStateOf<android.graphics.Bitmap?>(null)
    var liveFrameAt by mutableStateOf(0L)
    var liveActive by mutableStateOf(false)
    var liveError by mutableStateOf<String?>(null)
    var liveLoading by mutableStateOf(false)
    private var liveJob: Job? = null
    private var liveSlaveId: String? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun startLive(slaveId: String) {
        stopLiveLoop()
        liveSlaveId = slaveId
        liveLoading = true
        liveError = null
        liveFrame = null
        liveFrameAt = 0L
        liveActive = false
        status = "실시간 스트림 준비 중…"
        liveJob = viewModelScope.launch {
            // farmer에게 라이브 push 시작 지시 (SSE/폴링으로 전달)
            try {
                api.sendCommand(CommandRequest(slaveId, null, "live_start"))
            } catch (e: Exception) {
                liveLoading = false
                liveError = e.message
                status = "실시간 보기 불가"
                return@launch
            }
            // 3초 폴링 — 최신 프레임으로 화면 refresh (라이브화)
            var misses = 0
            while (isActive) {
                val fetchedAt = System.currentTimeMillis()
                val frame = fetchLiveFrame(slaveId)
                if (frame != null) {
                    misses = 0
                    liveFrame = frame
                    liveFrameAt = fetchedAt
                    liveActive = true
                    liveError = null
                    liveLoading = false
                    status = "실시간 스트리밍 중…"
                } else {
                    misses++
                    // 첫 프레임이 15초(5회) 안에 안 오면 farmer 미응답 안내,
                    // 스트리밍 중 끊기면(3회 연속) 신호 끊김 표시 (마지막 화면 유지)
                    if (liveFrame == null && misses >= 5) {
                        liveLoading = false
                        status = "실시간 신호 없음 — Farmer가 오프라인이거나 카메라가 꺼져 있습니다"
                    } else if (liveFrame != null && misses >= 3) {
                        liveActive = false
                        status = "실시간 신호 없음 — Farmer가 오프라인이거나 카메라가 꺼져 있습니다"
                    }
                }
                delay(3000)
            }
        }
    }

    fun stopLive() {
        stopLiveLoop()
        val sid = liveSlaveId
        liveSlaveId = null
        if (liveFrame != null) status = "실시간 보기 종료"
        liveFrame = null
        liveFrameAt = 0L
        liveActive = false
        liveLoading = false
        if (sid != null) {
            // farmer의 push 루프 종료 지시 (best-effort)
            viewModelScope.launch {
                try { api.sendCommand(CommandRequest(sid, null, "live_stop")) } catch (_: Exception) {}
            }
        }
    }

    private fun stopLiveLoop() {
        liveJob?.cancel()
        liveJob = null
    }

    /** 백엔드에서 farmer의 최신 라이브 프레임(JPEG)을 받아 Bitmap으로 디코딩. 실패/없음이면 null. */
    private suspend fun fetchLiveFrame(slaveId: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val url = "${ApiClient.baseUrl.trimEnd('/')}/api/live/$slaveId/frame"
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (_: Exception) { null }
        }

    private var sseJob: Job? = null

    private var videoHandled = false

    fun requestAndWatch(slaveId: String, slaveName: String, ctx: android.content.Context) {
        request(slaveId, slaveName, ctx, action = "capture_video")
    }

    fun requestPhoto(slaveId: String, slaveName: String, ctx: android.content.Context) {
        request(slaveId, slaveName, ctx, action = "capture_photo")
    }

    private fun request(slaveId: String, slaveName: String, ctx: android.content.Context, action: String) {
        // 라이브 스트림이 켜져 있으면 종료 — 촬영 결과가 같은 슬롯에 표시되므로
        // farmer의 push 루프도 끊고 화면을 촬영 흐름으로 전환한다.
        stopLive()
        photoMode = action == "capture_photo"
        loading = true
        error = null
        status = if (photoMode) "사진 요청 전송 중…" else "캡처 요청 전송 중…"
        videoInfo = null
        savedPath = null
        savedMsg = null
        photoUrl = null
        photoTakenAt = null
        videoHandled = false
        // 기존 SSE 연결 종료
        sseJob?.cancel()
        viewModelScope.launch {
            try {
                val cmdResp = api.sendCommand(CommandRequest(slaveId, null, action))
                val commandId = cmdResp.commandId
                status = if (photoMode) "사진 요청 전송됨 — 응답 대기 중" else "캡처 요청 전송됨 — SSE로 응답 대기 중"
                // SSE로 영상/사진 준비 완료 대기
                startMasterSSE(slaveId, commandId, slaveName, ctx)
                // 시간 초과 감시 (40초)
                launch {
                    var waited = 0
                    while (waited < 40 && loading && !videoHandled) {
                        delay(1000)
                        waited += 1
                    }
                    if (loading && !videoHandled) {
                        status = "시간 초과 — Slave가 오프라인이거나 카메라 미준비일 수 있습니다"
                        loading = false
                        sseJob?.cancel()
                    }
                }
            } catch (e: Exception) {
                error = e.message
                status = "요청 실패"
                loading = false
            }
        }
    }

    /** Master SSE 클라이언트 — 영상/사진 준비 완료 이벤트를 실시간 수신. */
    private fun startMasterSSE(slaveId: String, commandId: String, slaveName: String, ctx: android.content.Context) {
        sseJob = viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = ApiClient.baseUrl.trimEnd('/')
            val url = "$baseUrl/api/commands/sse/master/$slaveId"
            try {
                val client = OkHttpClient.Builder()
                    .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@launch
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.removePrefix("data: ").trim()
                        if (jsonStr.isNotEmpty()) {
                            try {
                                val json = JSONObject(jsonStr)
                                if (json.optString("type") == "video_ready") {
                                    val cmdId = json.optString("commandId", "")
                                    if (cmdId == commandId) {
                                        val v = VideoInfoResponse(
                                            videoId = json.getString("videoId"),
                                            slaveId = json.getString("slaveId"),
                                            commandId = json.optString("commandId"),
                                            url = json.getString("url"),
                                            mime = json.optString("mime"),
                                            size = json.optInt("size"),
                                            created_at = json.optString("created_at")
                                        )
                                        withContext(Dispatchers.Main) {
                                            onVideoReady(v, slaveName, ctx)
                                        }
                                        response.close()
                                        return@launch
                                    }
                                } else if (json.optString("type") == "photo_ready") {
                                    val cmdId = json.optString("commandId", "")
                                    if (cmdId == commandId) {
                                        val url = json.getString("url")
                                        val takenAt = json.optString("taken_at", "")
                                        withContext(Dispatchers.Main) {
                                            onPhotoReady(url, takenAt)
                                        }
                                        response.close()
                                        return@launch
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                response.close()
            } catch (_: Exception) {}
        }
    }

    /** 사진 수신 처리 — 요청 매칭된 사진만 표시. */
    private fun onPhotoReady(url: String, takenAt: String) {
        if (videoHandled) return
        videoHandled = true
        photoUrl = url
        photoTakenAt = takenAt.takeIf { it.isNotBlank() }
        status = "사진 수신 완료!"
        loading = false
        sseJob?.cancel()
    }

    /** 영상 수신 처리 — SSE와 폴링 중 먼저 도착한 쪽만 처리 (중복 저장 방지). */
    private fun onVideoReady(v: VideoInfoResponse, slaveName: String, ctx: android.content.Context) {
        if (videoHandled) return
        videoHandled = true
        videoInfo = v
        status = "영상 수신 완료!"
        loading = false
        sseJob?.cancel()
        viewModelScope.launch { saveToDevice(v, slaveName, ctx) }
    }

    private suspend fun saveToDevice(v: VideoInfoResponse, slaveName: String, ctx: android.content.Context) {
        try {
            val baseUrl = ApiClient.baseUrl.trimEnd('/')
            val fullUrl = baseUrl + v.url
            savedMsg = "기기에 저장 중…"
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "OOJOO_FARM").apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outFile = File(dir, "camera_${timestamp}.mp4")
                val conn = URL(fullUrl).openConnection() as HttpURLConnection
                conn.connect()
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                outFile
            }
            savedPath = file.absolutePath
            val createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            Prefs.addGalleryItem(ctx, file.absolutePath, slaveName, createdAt)
            savedMsg = "✅ 기기에 저장됨!"
        } catch (e: Exception) {
            savedMsg = "저장 실패: ${e.message}"
        }
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        liveJob?.cancel()
        // 화면 이탈 시 라이브 push가 켜져 있으면 farmer에게 종료 지시 (best-effort —
        // viewModelScope는 이미 취소되므로 별도 IO 스코프로 전송).
        val sid = liveSlaveId
        if (sid != null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { api.sendCommand(CommandRequest(sid, null, "live_stop")) } catch (_: Exception) {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCameraScreen(nav: NavController, slaveId: String, slaveName: String, vm: LiveCameraViewModel = viewModel()) {
    val ctx = LocalContext.current
    val S = LocalAppStrings.current
    LaunchedEffect(slaveId) { vm.requestAndWatch(slaveId, slaveName, ctx) }
    val baseUrl = ApiClient.baseUrl.trimEnd('/')

    Scaffold(
        topBar = { TopAppBar(title = { Text("${S.liveCameraPrefix}$slaveName${S.liveCameraSuffix}", color = Color.White, fontWeight = FontWeight.Black) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(mapStatus(vm.status, S), color = OojooTheme.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (vm.loading) { CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp) }
            vm.error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

            // 라이브 연결 진행/에러 표시 — 결과 미디어는 아래 슬롯에 유지된다
            if (vm.liveLoading) {
                Text(mapStatus(vm.status, S), color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 2.dp)
            }
            vm.liveError?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

            // 미디어 표시 슬롯 — 3초 영상·사진·실시간 스트림이 같은 위치를 공유한다.
            // 실시간 보기 중이면 라이브 프레임이 3초 영상이 보이던 바로 그 자리를 차지하고,
            // 종료하면 직전 결과(3초 영상/사진)로 되돌아온다.
            when {
                vm.liveFrame != null -> {
                    Card(Modifier.fillMaxWidth().height(280.dp).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.Image(
                                bitmap = vm.liveFrame!!.asImageBitmap(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // LIVE 배지 — 신호 끊기면 회색으로 전환 (안내 문장은 상단 상태줄)
                            Text(
                                S.liveBadge,
                                color = if (vm.liveActive) Color(0xFFFF3B30) else Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), OojooTheme.CardShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(S.liveRefreshNote, color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(text = S.liveStop, onClick = { vm.stopLive() }, modifier = Modifier.weight(1f))
                        OutlineButton(text = S.photoCapture, onClick = { vm.requestPhoto(slaveId, slaveName, ctx) }, modifier = Modifier.weight(1f))
                    }
                }

                vm.videoInfo != null -> {
                    val v = vm.videoInfo!!
                    val fullUrl = baseUrl + v.url
                    Text("${S.videoCapturedPrefix}${v.created_at ?: S.justNow}${S.videoCapturedSuffix}", color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Card(Modifier.fillMaxWidth().height(280.dp).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                        AndroidView(
                            factory = { context ->
                                VideoView(context).apply {
                                    setVideoURI(Uri.parse(fullUrl))
                                    setOnPreparedListener { it.isLooping = true; start() }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    vm.savedMsg?.let { Text(mapSavedMsg(it, S), color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(text = S.recapture, onClick = { vm.requestAndWatch(slaveId, slaveName, ctx) }, modifier = Modifier.weight(1f))
                        OutlineButton(text = S.photoCapture, onClick = { vm.requestPhoto(slaveId, slaveName, ctx) }, modifier = Modifier.weight(1f))
                        OutlineButton(text = S.gallery, onClick = { nav.navigate("gallery") }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(text = S.liveViewBtn, onClick = { vm.startLive(slaveId) }, modifier = Modifier.weight(1f))
                        OutlineButton(text = S.back, onClick = { nav.navigateUp() }, modifier = Modifier.weight(1f))
                    }
                }

                vm.photoUrl != null -> {
                    val fullUrl = baseUrl + vm.photoUrl!!
                    Text("${S.photoCapturedPrefix}${vm.photoTakenAt ?: S.justNow}${S.photoCapturedSuffix}", color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Card(Modifier.fillMaxWidth().height(280.dp).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                        coil.compose.AsyncImage(
                            model = fullUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(text = S.recapture, onClick = { vm.requestPhoto(slaveId, slaveName, ctx) }, modifier = Modifier.weight(1f))
                        OutlineButton(text = S.videoCaptureBtn, onClick = { vm.requestAndWatch(slaveId, slaveName, ctx) }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(text = S.liveViewBtn, onClick = { vm.startLive(slaveId) }, modifier = Modifier.weight(1f))
                        OutlineButton(text = S.back, onClick = { nav.navigateUp() }, modifier = Modifier.weight(1f))
                    }
                }

                else -> {
                    // 아직 결과 없음 — 사진 촬영/실시간 보기로 전환 가능
                    if (!vm.loading && !vm.liveLoading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GradientButton(text = S.liveViewBtn, onClick = { vm.startLive(slaveId) }, modifier = Modifier.weight(1f))
                            OutlineButton(text = S.photoCapture, onClick = { vm.requestPhoto(slaveId, slaveName, ctx) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun mapStatus(s: String, S: AppStrings): String = when (s) {
    "요청 대기" -> S.statusWaiting
    "캡처 요청 전송 중…" -> S.captureSending
    "사진 요청 전송 중…" -> S.photoSending
    "캡처 요청 전송됨 — SSE로 응답 대기 중" -> S.captureSentWaiting
    "사진 요청 전송됨 — 응답 대기 중" -> S.photoSentWaiting
    "시간 초과 — Slave가 오프라인이거나 카메라 미준비일 수 있습니다" -> S.captureTimeout
    "영상 수신 완료!" -> S.captureDone
    "사진 수신 완료!" -> S.photoDone
    "요청 실패" -> S.captureFailed
    "실시간 스트림 준비 중…" -> S.liveFetching
    "실시간 스트리밍 중…" -> S.liveStreaming
    "실시간 보기 종료" -> S.liveStopped
    "실시간 보기 불가" -> S.liveUnavailable
    "실시간 신호 없음 — Farmer가 오프라인이거나 카메라가 꺼져 있습니다" -> S.liveNoSignal
    else -> s
}

private fun mapSavedMsg(m: String, S: AppStrings): String = when {
    m.startsWith("기기에 저장 중") -> S.savingToDevice
    m.startsWith("✅") -> S.saveCompleteMsg
    m.startsWith("저장 실패") -> S.saveFailedPrefix + m.removePrefix("저장 실패: ")
    else -> m
}
