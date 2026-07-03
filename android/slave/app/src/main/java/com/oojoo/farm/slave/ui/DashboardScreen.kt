package com.oojoo.farm.slave.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.model.EventRequest
import com.oojoo.farm.slave.model.HeartbeatRequest
import com.oojoo.farm.slave.model.WateringLogRequest
import com.oojoo.farm.slave.network.ApiClient
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FarmerViewModel(app: Application) : AndroidViewModel(app) {
    private val api = ApiClient.api
    val slaveId = Prefs.slaveId(app) ?: ""
    val userId = Prefs.userId(app) ?: ""

    var autoOn by mutableStateOf(true)
    var lastWater by mutableStateOf<String?>(null)
    var logs by mutableStateOf<List<String>>(emptyList())
    var status by mutableStateOf("자율 관리 대기 중")
    private var loop: Job? = null

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    init { startLoop() }

    fun startLoop() {
        loop?.cancel()
        loop = viewModelScope.launch {
            while (isActive) {
                if (autoOn) tick()
                delay(30_000L) // 30초 주기 (Phase1 테스트용)
            }
        }
    }

    fun toggleAuto() {
        autoOn = !autoOn
        status = if (autoOn) "자율 관리 동작 중" else "자율 관리 일시정지"
    }

    private suspend fun tick() {
        status = "관찰 중…"
        // 하트비트
        try { api.heartbeat(HeartbeatRequest(slaveId)) } catch (_: Exception) {}
        // 온디바이스 AI 판정(가짜): 수분 부족 확률
        val needWater = (0..1).random() == 1
        if (needWater) {
            doWater("auto")
        } else {
            addLog("[${now()}] 관찰: 수분 양호")
            status = "관찰 완료: 수분 양호"
        }
    }

    fun doWater(source: String) {
        viewModelScope.launch {
            val amount = 300
            try {
                api.wateringLog(WateringLogRequest(slaveId, null, amount, source, 1.0))
                api.reportEvent(EventRequest(slaveId, null, if (source == "auto") "auto_water" else "manual_water", mapOf("amount" to "$amount")))
                lastWater = now()
                addLog("[$lastWater] $source 관수 ${amount}ml")
                status = "$source 관수 실행"
            } catch (e: Exception) {
                addLog("[${now()}] 오류: ${e.message}")
            }
        }
    }

    private fun addLog(s: String) { logs = (listOf(s) + logs).take(10) }

    override fun onCleared() { loop?.cancel() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavController, vm: FarmerViewModel = viewModel()) {
    Scaffold(topBar = { TopAppBar(title = { Text("Farmer 대시보드") }) }) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("온디바이스 AI 자율 관리", fontWeight = FontWeight.Bold)
                    Text("Slave ID: ${vm.slaveId}", style = MaterialTheme.typography.bodySmall)
                    Text("상태: ${vm.status}", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("자율 관리", Modifier.weight(1f))
                        Switch(checked = vm.autoOn, onCheckedChange = { vm.toggleAuto() })
                    }
                    vm.lastWater?.let { Text("마지막 관수: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Button(
                onClick = { vm.doWater("manual") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("수동 관수 즉시 실행") }
            Text("이벤트 로그", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (vm.logs.isEmpty()) Text("로그 없음", style = MaterialTheme.typography.bodySmall)
                    vm.logs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
