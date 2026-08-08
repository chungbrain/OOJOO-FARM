package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.model.CommandRequest
import com.oojoo.farm.master.model.VideoInfoResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private var sseJob: Job? = null

    private var videoHandled = false

    fun requestAndWatch(slaveId: String, slaveName: String, ctx: android.content.Context) {
        loading = true
        error = null
        status = "캡처 요청 전송 중…"
        videoInfo = null
        savedPath = null
        savedMsg = null
        videoHandled = false
        // 기존 SSE 연결 종료
        sseJob?.cancel()
        viewModelScope.launch {
            try {
                val cmdResp = api.sendCommand(CommandRequest(slaveId, null, "capture_video"))
                val commandId = cmdResp.commandId
                status = "캡처 요청 전송됨 — SSE로 응답 대기 중"
                // SSE로 영상 준비 완료 대기
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

    /** Master SSE 클라이언트 — 영상 준비 완료 이벤트를 실시간 수신. */
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
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                response.close()
            } catch (_: Exception) {}
        }
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCameraScreen(nav: NavController, slaveId: String, slaveName: String, vm: LiveCameraViewModel = viewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(slaveId) { vm.requestAndWatch(slaveId, slaveName, ctx) }
    val baseUrl = ApiClient.baseUrl.trimEnd('/')

    Scaffold(
        topBar = { TopAppBar(title = { Text("📹 $slaveName 카메라", color = Color.White, fontWeight = FontWeight.Black) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(vm.status, color = OojooTheme.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (vm.loading) { CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp) }
            vm.error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

            vm.videoInfo?.let { v ->
                val fullUrl = baseUrl + v.url
                Text("🎬 3초 영상 (촬영: ${v.created_at ?: "방금"})", color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                vm.savedMsg?.let { Text(it, color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton(text = "🔄 다시 촬영", onClick = { vm.requestAndWatch(slaveId, slaveName, ctx) }, modifier = Modifier.weight(1f))
                    OutlineButton(text = "📷 사진첩", onClick = { nav.navigate("gallery") }, modifier = Modifier.weight(1f))
                }
                OutlineButton(text = "뒤로", onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
