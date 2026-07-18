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
        }
    }
}
