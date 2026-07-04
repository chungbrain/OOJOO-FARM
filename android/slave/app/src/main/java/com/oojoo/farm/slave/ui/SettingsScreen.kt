package com.oojoo.farm.slave.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    val blePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            Hardware.useBle(ctx)
            hwMsg = "ESP32 BLE 스캔/연결 시도 중…"
        } else {
            hwMsg = "블루투스 권한이 필요합니다"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Farmer 설정") }) }) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("재배 지역 (날씨 조회용)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = region,
                onValueChange = { region = it },
                label = { Text("지역 (예: Seoul, Busan)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("캡처 주기 (분)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = captureInterval,
                onValueChange = { captureInterval = it.filter { c -> c.isDigit() } },
                label = { Text("분 (1~360)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("자율 관수", Modifier.weight(1f))
                Switch(checked = autoWater, onCheckedChange = { autoWater = it })
            }

            HorizontalDivider()
            Text("하드웨어 (ESP32)", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        blePermLauncher.launch(arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ))
                    } else {
                        blePermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("급수/Fan/Laser 하드웨어 BLE 연결") }
            hwMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    Prefs.setRegion(ctx, region.trim())
                    Prefs.setCaptureIntervalMinutes(ctx, captureInterval.toIntOrNull() ?: 60)
                    Prefs.setAutoWater(ctx, autoWater)
                    nav.navigateUp()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("저장") }
            OutlinedButton(onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth()) { Text("뒤로") }
        }
    }
}
