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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.service.FarmerEngine
import com.oojoo.farm.slave.service.FarmerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavController) {
    val ctx = LocalContext.current
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
        topBar = { TopAppBar(title = { Text("Farmer 대시보드", color = Color.White, fontWeight = FontWeight.Bold) }, actions = { TextButton(onClick = { nav.navigate("settings") }) { Text("설정", color = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Teal)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 자율 관리 카드
            Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("온디바이스 AI 자율 관리", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
                    Text("Slave: ${(Prefs.slaveId(ctx) ?: "").take(8)}…  ${if (online) "● 온라인" else "○ 오프라인"}", color = OojooTheme.Muted, fontSize = 13.sp)
                    plant?.let { Text("식물: ${it.name} (${it.species ?: "?"})", color = OojooTheme.Muted, fontSize = 13.sp) }
                        ?: Text("식물 미연결 (마스터에서 등록 필요)", color = OojooTheme.Red, fontSize = 13.sp)
                    Text("상태: $status", color = OojooTheme.Ink, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("자율 관리", Modifier.weight(1f), color = OojooTheme.Ink)
                        Switch(checked = autoOn, onCheckedChange = { FarmerEngine.toggleAuto() }, colors = SwitchDefaults.colors(checkedThumbColor = OojooTheme.Teal, checkedTrackColor = OojooTheme.TealLight))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("헤드리스 모드 (화면 꺼도 동작)", Modifier.weight(1f), color = OojooTheme.Ink, fontSize = 13.sp)
                        Switch(checked = headless, onCheckedChange = { headless = it; Prefs.setHeadless(ctx, it); FarmerService.start(ctx) }, colors = SwitchDefaults.colors(checkedThumbColor = OojooTheme.Teal, checkedTrackColor = OojooTheme.TealLight))
                    }
                    Text("날씨 가중치: ${"%.2f".format(weatherFactor)}", color = OojooTheme.Muted, fontSize = 13.sp)
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
                        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (pendingLaser) {
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                    Text("🐛 해충 감지 — Laser 퇴치 마스터 승인 대기 중", Modifier.padding(12.dp), color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            lastAnalysis?.let { a ->
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("최근 분석", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
                        Text("상태: ${a.healthStatus}", color = OojooTheme.Muted, fontSize = 13.sp)
                        Text("녹색도: ${"%.0f".format(a.greenness * 100)}% / 밝기: ${"%.0f".format(a.brightness * 100)}%", color = OojooTheme.Muted, fontSize = 13.sp)
                        Text("수분 필요: ${if (a.needWater) "예" else "아니오"} (신뢰도 ${"%.0f".format(a.confidence * 100)}%)", color = OojooTheme.Muted, fontSize = 13.sp)
                        Text("열매 익음도: ${"%.0f".format(a.fruitRipeness * 100)}%  ·  해충 의심: ${if (a.pestSuspected) "예" else "아니오"}", color = OojooTheme.Muted, fontSize = 13.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton(text = "즉시 캡처", onClick = { FarmerEngine.requestCapture() }, modifier = Modifier.weight(1f))
                GradientButton(text = "수동 관수", onClick = { FarmerEngine.manualWater() }, modifier = Modifier.weight(1f))
            }

            Text("이벤트 로그", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
            Card(Modifier.fillMaxWidth().heightIn(min = 120.dp).shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (logs.isEmpty()) Text("로그 없음", color = OojooTheme.Muted, fontSize = 13.sp)
                    else logs.forEach { Text(it, color = OojooTheme.Ink, fontSize = 11.sp) }
                }
            }
        }
    }
}
