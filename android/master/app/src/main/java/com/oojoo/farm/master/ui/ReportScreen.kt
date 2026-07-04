package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.master.model.ReportResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class ReportViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var report by mutableStateOf<ReportResponse?>(null)
    var loading by mutableStateOf(false)
    fun load(slaveId: String) {
        loading = true
        viewModelScope.launch { try { report = api.report(slaveId) } catch (_: Exception) {}; loading = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(nav: NavController, slaveId: String, vm: ReportViewModel = viewModel()) {
    LaunchedEffect(slaveId) { vm.load(slaveId) }
    Scaffold(topBar = { TopAppBar(title = { Text("Farmer 리포트 (7일)") }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹") } }) }) { p ->
        val r = vm.report
        if (r == null) { Box(Modifier.fillMaxSize().padding(p), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("관수 요약", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    stat("총 관수 횟수", "${r.watering.count}회")
                    stat("총 급수량", "${r.watering.totalMl} ml")
                    stat("자율 / 수동", "${r.watering.autoCount} / ${r.watering.manualCount}")
                    r.lastWatering?.let { stat("마지막 관수", "${it.created_at ?: "-"} (${it.amount_ml}ml)") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("이벤트 요약", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    stat("🍅 수확 적기 감지", "${r.harvestReady}회")
                    stat("🐛 해충 감지", "${r.pestDetected}회")
                    stat("⚠️ 이상 징후", "${r.anomalies}회")
                }
            }
            Text("최근 7일 기준 집계입니다.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}
