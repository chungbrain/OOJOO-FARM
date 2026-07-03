package com.oojoo.farm.slave.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.slave.data.Prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    var region by remember { mutableStateOf(Prefs.region(ctx)) }
    var captureInterval by remember { mutableStateOf(Prefs.captureIntervalMinutes(ctx).toString()) }
    var autoWater by remember { mutableStateOf(Prefs.autoWater(ctx)) }

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
