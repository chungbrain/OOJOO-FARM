package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.oojoo.farm.master.model.CommandRequest
import com.oojoo.farm.master.model.VideoInfoResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.net.Uri
import android.widget.VideoView

class LiveCameraViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var videoInfo by mutableStateOf<VideoInfoResponse?>(null)
    var status by mutableStateOf("요청 대기")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun requestAndPoll(slaveId: String) {
        loading = true
        error = null
        status = "캡처 요청 전송 중…"
        videoInfo = null
        viewModelScope.launch {
            try {
                // 1) capture_video 명령 전송 → commandId 수신
                val cmdResp = api.sendCommand(CommandRequest(slaveId, null, "capture_video"))
                val commandId = cmdResp.commandId
                status = "캡처 요청 전송됨 (명령 $commandId) — Slave 응답 대기 (최대 40초)"
                // 2) Slave가 캡처+업로드할 때까지 commandId 로 폴링 (최대 40초)
                //    Slave 폴링 주기 10초 + 3초 캡처 + 업로드 시간 고려
                var waited = 0
                while (waited < 40) {
                    delay(2000)
                    waited += 2
                    try {
                        val v = api.videoByCommand(commandId)
                        videoInfo = v
                        status = "영상 수신 완료 (${waited}초)"
                        loading = false
                        return@launch
                    } catch (_: Exception) { /* 아직 업로드 안 됨 */ }
                }
                status = "시간 초과 — Slave가 오프라인이거나 카메라 미준비일 수 있습니다"
                loading = false
            } catch (e: Exception) {
                error = e.message
                status = "요청 실패"
                loading = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCameraScreen(nav: NavController, slaveId: String, slaveName: String, vm: LiveCameraViewModel = viewModel()) {
    LaunchedEffect(slaveId) { vm.requestAndPoll(slaveId) }
    val baseUrl = ApiClient.baseUrl.trimEnd('/')

    Scaffold(
        topBar = { TopAppBar(title = { Text("📹 $slaveName 카메라", color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(vm.status, color = OojooTheme.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (vm.loading) { CircularProgressIndicator(color = OojooTheme.Green) }
            vm.error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp) }

            vm.videoInfo?.let { v ->
                val fullUrl = baseUrl + v.url
                Text("🎬 3초 영상 (촬영: ${v.created_at ?: "방금"})", color = OojooTheme.Ink, fontSize = 13.sp)
                Card(Modifier.fillMaxWidth().height(280.dp), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton(text = "다시 촬영", onClick = { vm.requestAndPoll(slaveId) }, modifier = Modifier.weight(1f))
                    OutlineButton(text = "뒤로", onClick = { nav.navigateUp() }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
