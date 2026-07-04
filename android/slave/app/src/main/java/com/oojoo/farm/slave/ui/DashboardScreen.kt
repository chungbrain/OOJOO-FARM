package com.oojoo.farm.slave.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.service.FarmerEngine
import com.oojoo.farm.slave.service.FarmerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavController) {
    val ctx = LocalContext.current

    // 엔진 + 헤드리스 서비스 보장 (자율 루프의 단일 실행 주체)
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
        topBar = {
            TopAppBar(
                title = { Text("Farmer 대시보드") },
                actions = { TextButton(onClick = { nav.navigate("settings") }) { Text("설정") } }
            )
        }
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("온디바이스 AI 자율 관리", fontWeight = FontWeight.Bold)
                    Text("Slave: ${(Prefs.slaveId(ctx) ?: "").take(8)}…  ${if (online) "● 온라인" else "○ 오프라인"}", style = MaterialTheme.typography.bodySmall)
                    plant?.let { Text("식물: ${it.name} (${it.species ?: "?"})", style = MaterialTheme.typography.bodySmall) }
                        ?: Text("식물 미연결 (마스터에서 등록 필요)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text("상태: $status", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("자율 관리", Modifier.weight(1f))
                        Switch(checked = autoOn, onCheckedChange = { FarmerEngine.toggleAuto() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("헤드리스 모드 (화면 꺼도 동작)", Modifier.weight(1f))
                        Switch(checked = headless, onCheckedChange = {
                            headless = it
                            Prefs.setHeadless(ctx, it)
                            FarmerService.start(ctx)
                        })
                    }
                    Text("날씨 가중치: ${"%.2f".format(weatherFactor)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth().height(180.dp)) {
                Box(Modifier.fillMaxSize()) {
                    com.oojoo.farm.slave.vision.CameraPreview(
                        onAnalysisResult = { FarmerEngine.onAnalysis(it) },
                        captureRequested = captureRequested,
                        onCaptureDone = { FarmerEngine.onCaptureDone() }
                    )
                }
            }

            if (pendingLaser) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("🐛 해충 감지 — Laser 퇴치 마스터 승인 대기 중", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            lastAnalysis?.let { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("최근 분석", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text("상태: ${a.healthStatus}", style = MaterialTheme.typography.bodySmall)
                        Text("녹색도: ${"%.0f".format(a.greenness * 100)}% / 밝기: ${"%.0f".format(a.brightness * 100)}%", style = MaterialTheme.typography.bodySmall)
                        Text("수분 필요: ${if (a.needWater) "예" else "아니오"} (신뢰도 ${"%.0f".format(a.confidence * 100)}%)", style = MaterialTheme.typography.bodySmall)
                        Text("열매 익음도: ${"%.0f".format(a.fruitRipeness * 100)}%  ·  해충 의심: ${if (a.pestSuspected) "예" else "아니오"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { FarmerEngine.requestCapture() }, modifier = Modifier.weight(1f)) { Text("즉시 캡처") }
                Button(onClick = { FarmerEngine.manualWater() }, modifier = Modifier.weight(1f)) { Text("수동 관수") }
            }

            Text("이벤트 로그", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (logs.isEmpty()) Text("로그 없음", style = MaterialTheme.typography.bodySmall)
                    logs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
