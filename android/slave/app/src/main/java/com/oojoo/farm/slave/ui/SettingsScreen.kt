package com.oojoo.farm.slave.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.hardware.Hardware
import com.oojoo.farm.slave.service.FarmerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    var region by remember { mutableStateOf(Prefs.region(ctx)) }
    var captureInterval by remember { mutableStateOf(Prefs.captureIntervalMinutes(ctx).toString()) }
    var autoWater by remember { mutableStateOf(Prefs.autoWater(ctx)) }
    var hwMsg by remember { mutableStateOf<String?>(null) }

    val blePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) { Hardware.useBle(ctx); hwMsg = "ESP32 BLE 스캔/연결 시도 중…" }
        else hwMsg = "블루투스 권한이 필요합니다"
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Farmer 설정", color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Teal)) }, containerColor = OojooTheme.Bg) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("재배 지역 (날씨 조회용)", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(region, { region = it }, "지역 (예: Seoul, Busan)")
            Text("캡처 주기 (분)", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(captureInterval, { captureInterval = it.filter { c -> c.isDigit() } }, "분 (1~360)")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("자율 관수", Modifier.weight(1f), color = OojooTheme.Ink)
                Switch(checked = autoWater, onCheckedChange = { autoWater = it }, colors = SwitchDefaults.colors(checkedThumbColor = OojooTheme.Teal, checkedTrackColor = OojooTheme.TealLight))
            }
            HorizontalDivider(color = OojooTheme.Line)
            Text("하드웨어 (ESP32)", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OutlineButton(text = "급수/Fan/Laser 하드웨어 BLE 연결", onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blePermLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                else blePermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }, modifier = Modifier.fillMaxWidth())
            hwMsg?.let { Text(it, color = OojooTheme.Muted, fontSize = 13.sp) }
            GradientButton(text = "저장", onClick = {
                Prefs.setRegion(ctx, region.trim())
                Prefs.setCaptureIntervalMinutes(ctx, captureInterval.toIntOrNull() ?: 60)
                Prefs.setAutoWater(ctx, autoWater)
                nav.navigateUp()
            }, modifier = Modifier.fillMaxWidth())

            HorizontalDivider(color = OojooTheme.Line)

            // 재페어링 섹션
            Text("마스터 연결", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
            val slaveId = Prefs.slaveId(ctx)
            if (slaveId != null) {
                Text("현재 연결된 Slave: ${slaveId.take(8)}…", color = OojooTheme.Muted, fontSize = 13.sp)
                Text("다른 마스터로 다시 페어링하려면 아래 버튼을 누르세요. 기존 연결이 해제됩니다.",
                    color = OojooTheme.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                var showReconfirm by remember { mutableStateOf(false) }
                GradientButton(
                    text = "🔄 다시 페어링하기",
                    onClick = { showReconfirm = true },
                    modifier = Modifier.fillMaxWidth()
                )
                if (showReconfirm) {
                    AlertDialog(
                        onDismissRequest = { showReconfirm = false },
                        title = { Text("재페어링", fontWeight = FontWeight.Bold) },
                        text = { Text("기존 마스터 연결을 해제하고 새로운 페어링 코드를 입력합니다.\n자율 관리 서비스도 중지됩니다.") },
                        confirmButton = {
                            TextButton(onClick = {
                                FarmerService.stop(ctx)
                                Prefs.clearSession(ctx)
                                showReconfirm = false
                                nav.navigate("pairing") { popUpTo("dashboard") { inclusive = true } }
                            }) { Text("재페어링", color = OojooTheme.Red, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showReconfirm = false }) { Text("취소") }
                        }
                    )
                }
            } else {
                Text("현재 페어링되어 있지 않습니다.", color = OojooTheme.Muted, fontSize = 13.sp)
                GradientButton(
                    text = "🔗 마스터 페어링하기",
                    onClick = { nav.navigate("pairing") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
