package com.oojoo.farm.slave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.slave.R
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.service.FarmerEngine
import com.oojoo.farm.slave.service.FarmerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavController) {
    val ctx = LocalContext.current
    val english = LocalConfiguration.current.locales[0].language == "en"
    LaunchedEffect(Unit) {
        FarmerEngine.start(ctx)
        FarmerService.start(ctx)
    }

    val status by FarmerEngine.status.collectAsState()
    val logs by FarmerEngine.logs.collectAsState()
    val lastAnalysis by FarmerEngine.lastAnalysis.collectAsState()
    val plant by FarmerEngine.plant.collectAsState()
    val autoOn by FarmerEngine.autoOn.collectAsState()
    val weatherFactor by FarmerEngine.weatherFactor.collectAsState()
    val online by FarmerEngine.online.collectAsState()
    val captureRequested by FarmerEngine.captureRequested.collectAsState()
    val pendingLaser by FarmerEngine.pendingLaser.collectAsState()
    var headless by remember { mutableStateOf(Prefs.headless(ctx)) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dashboard_title), color = Color.White, fontWeight = FontWeight.Bold) }, actions = { TextButton(onClick = { nav.navigate("settings") }) { Text(stringResource(R.string.settings), color = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Teal)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 자율 관리 카드
            Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.device_ai), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
                    Text(stringResource(R.string.slave_status, (Prefs.slaveId(ctx) ?: "").take(8), stringResource(if (online) R.string.online else R.string.offline)), color = OojooTheme.Muted, fontSize = 13.sp)
                    plant?.let { Text(stringResource(R.string.plant_info, it.name, it.species ?: "?"), color = OojooTheme.Muted, fontSize = 13.sp) }
                        ?: Text(stringResource(R.string.plant_unlinked), color = OojooTheme.Red, fontSize = 13.sp)
                    Text(stringResource(R.string.status_format, localizeFarmerText(status, english)), color = OojooTheme.Ink, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.autonomous_management), Modifier.weight(1f), color = OojooTheme.Ink)
                        Switch(checked = autoOn, onCheckedChange = { FarmerEngine.toggleAuto() }, colors = SwitchDefaults.colors(checkedThumbColor = OojooTheme.Teal, checkedTrackColor = OojooTheme.TealLight))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.headless_mode), Modifier.weight(1f), color = OojooTheme.Ink, fontSize = 13.sp)
                        Switch(checked = headless, onCheckedChange = { headless = it; Prefs.setHeadless(ctx, it); FarmerService.start(ctx) }, colors = SwitchDefaults.colors(checkedThumbColor = OojooTheme.Teal, checkedTrackColor = OojooTheme.TealLight))
                    }
                    Text(stringResource(R.string.weather_factor, "%.2f".format(weatherFactor)), color = OojooTheme.Muted, fontSize = 13.sp)
                }
            }

            // 카메라 프리뷰 (프로토타입 .cam 스타일)
            Card(Modifier.fillMaxWidth().height(180.dp).shadow(OojooTheme.ShadowOffset, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)), shape = RoundedCornerShape(16.dp)) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(OojooTheme.CamGradient))) {
                    com.oojoo.farm.slave.vision.CameraPreview(
                        onAnalysisResult = { FarmerEngine.onAnalysis(it) },
                        captureRequested = captureRequested,
                        onCaptureDone = { FarmerEngine.onCaptureDone() }
                    )
                    // REC 표시
                    Row(Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFF5252)))
                        Text(stringResource(R.string.live), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (pendingLaser) {
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                    Text(stringResource(R.string.pest_approval), Modifier.padding(12.dp), color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            lastAnalysis?.let { a ->
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.recent_analysis), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
                        Text(stringResource(R.string.analysis_status, localizeFarmerText(a.healthStatus, english)), color = OojooTheme.Muted, fontSize = 13.sp)
                        Text(stringResource(R.string.green_brightness, "%.0f".format(a.greenness * 100), "%.0f".format(a.brightness * 100)), color = OojooTheme.Muted, fontSize = 13.sp)
                        Text(stringResource(R.string.water_need, stringResource(if (a.needWater) R.string.yes else R.string.no), "%.0f".format(a.confidence * 100)), color = OojooTheme.Muted, fontSize = 13.sp)
                        Text(stringResource(R.string.fruit_pest, "%.0f".format(a.fruitRipeness * 100), stringResource(if (a.pestSuspected) R.string.yes else R.string.no)), color = OojooTheme.Muted, fontSize = 13.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton(text = stringResource(R.string.manual_capture), onClick = { FarmerEngine.requestCapture() }, modifier = Modifier.weight(1f))
                GradientButton(text = stringResource(R.string.manual_water), onClick = { FarmerEngine.manualWater() }, modifier = Modifier.weight(1f))
            }

            Text(stringResource(R.string.event_log), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
            Card(Modifier.fillMaxWidth().heightIn(min = 120.dp).shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (logs.isEmpty()) Text(stringResource(R.string.no_logs), color = OojooTheme.Muted, fontSize = 13.sp)
                    else logs.forEach { Text(localizeFarmerText(it, english), color = OojooTheme.Ink, fontSize = 11.sp) }
                }
            }
        }
    }
}

private fun localizeFarmerText(text: String, english: Boolean): String {
    if (!english) return text
    val replacements = listOf(
        "자율 관리 대기 중" to "Autonomous management idle",
        "자율 관리 동작 중" to "Autonomous management running",
        "자율 관리 일시정지" to "Autonomous management paused",
        "분석 불가" to "Analysis unavailable",
        "너무 어두움 (카메라 위치 확인 필요)" to "Too dark (check camera position)",
        "건강 (녹색 충분)" to "Healthy (sufficient greenness)",
        "물 부족" to "Water shortage",
        "물 과다" to "Overwatering",
        "햇빛 부족" to "Low sunlight",
        "해충 발생" to "Pest detected",
        "온도 더움" to "Too hot",
        "건강" to "Healthy",
        "보통 (녹색 약간 부족)" to "Fair (slightly low greenness)",
        "주의 (황변 의심)" to "Caution (possible yellowing)",
        "이상 (색상 심각)" to "Abnormal (severe color issue)",
        "관찰 중…" to "Observing…",
        "관찰 완료" to "Observation complete",
        "관찰 대기" to "Waiting to observe",
        "카메라 분석 대기" to "waiting for camera analysis",
        "엔진 시작" to "Engine started",
        "연결 실패" to "connection failed",
        "초 후 재시도" to "s; retrying",
        "연결됨" to "connected",
        "실시간 명령 대기" to "waiting for real-time commands",
        "마스터 지시" to "Master command",
        "마스터 승인" to "Master approval",
        "마스터 요청" to "Master request",
        "식물 연결" to "Plant linked",
        "식물 조회 실패" to "Failed to load plant",
        "원격 정책 동기화" to "Remote policy synchronized",
        "자동관수" to "automatic watering",
        "일시정지" to "pause",
        "재개" to "resume",
        "퇴치" to "control",
        "승인필요" to "approval required",
        "AI 판정" to "AI decision",
        "수분 부족 의심" to "possible water shortage",
        "수확 적기 감지" to "harvest readiness detected",
        "마스터 알림" to "Master notified",
        "해충 감지" to "pest detected",
        "승인 요청" to "approval requested",
        "자율 퇴치" to "automatic control",
        "날씨 업데이트" to "Weather updated",
        "날씨 조회 실패" to "Weather lookup failed",
        "캐시 사용" to "using cache",
        "가중치" to "factor",
        "급수 밸브 개방" to "Water valve opened",
        "밸브 제어 실패" to "Valve control failed",
        "관수 실행" to "watering performed",
        "관수" to "watering",
        "영상 캡처 실패" to "Video capture failed",
        "카메라 미준비" to "camera not ready",
        "대시보드 화면이 켜져 있어야 함" to "Dashboard must be open",
        "영상 캡처 시작" to "Video capture started",
        "파일 없음" to "file missing",
        "영상 캡처 완료" to "Video capture complete",
        "영상 업로드 완료" to "Video upload complete",
        "영상 업로드 실패" to "Video upload failed",
        "업로드" to "upload",
        "오프라인 이벤트" to "Offline events",
        "건 동기화 완료" to " synchronized"
    )
    return replacements.fold(text) { result, (source, target) -> result.replace(source, target) }
}
