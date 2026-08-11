package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.model.ReportResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class ReportViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var report by mutableStateOf<ReportResponse?>(null)
    var loading by mutableStateOf(false)
    fun load(slaveId: String) { loading = true; viewModelScope.launch { try { report = api.report(slaveId) } catch (_: Exception) {}; loading = false } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(nav: NavController, slaveId: String, vm: ReportViewModel = viewModel()) {
    val S = LocalAppStrings.current
    LaunchedEffect(slaveId) { vm.load(slaveId) }
    Scaffold(topBar = { TopAppBar(title = { Text(S.farmerReportTitle, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
        val r = vm.report
        if (r == null) { Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = OojooTheme.Green) }; return@Scaffold }
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(S.waterSummary, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
                    stat(S.totalWaterCount, "${r.watering.count}${S.timesUnit}")
                    HorizontalDivider(color = OojooTheme.Line)
                    stat(S.totalWaterAmount, "${r.watering.totalMl} ml")
                    HorizontalDivider(color = OojooTheme.Line)
                    stat(S.autoVsManual, "${r.watering.autoCount} / ${r.watering.manualCount}")
                    r.lastWatering?.let { HorizontalDivider(color = OojooTheme.Line); stat(S.lastWater, "${it.created_at ?: "-"} (${it.amount_ml}ml)") }
                }
            }
            Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(S.eventSummary, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
                    stat(S.harvestReadyReport, "${r.harvestReady}${S.timesUnit}")
                    HorizontalDivider(color = OojooTheme.Line)
                    stat(S.pestDetectedReport, "${r.pestDetected}${S.timesUnit}")
                    HorizontalDivider(color = OojooTheme.Line)
                    stat(S.anomaliesReport, "${r.anomalies}${S.timesUnit}")
                }
            }
            Text(S.reportNotice, color = OojooTheme.Muted.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = OojooTheme.Ink, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 14.sp)
    }
}
