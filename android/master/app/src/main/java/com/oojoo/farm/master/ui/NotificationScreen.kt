package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.Notification
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

class NotificationViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var items by mutableStateOf<List<Notification>>(emptyList())
    var loading by mutableStateOf(false)
    fun refresh() { loading = true; viewModelScope.launch { try { items = api.notifications(Session.userId).notifications } catch (_: Exception) {}; loading = false } }
    init { refresh() }
}

private fun notiColor(t: String, payload: String?): Color = when (t) {
    "harvest_ready" -> OojooTheme.Amber
    "pest_detected" -> OojooTheme.Red
    "auto_water" -> OojooTheme.Blue
    "manual_water" -> OojooTheme.Blue
    "anomaly" -> OojooTheme.Red
    // F3 — 건강상태 변화: 악화(poor/very_poor)는 주황, 개선은 청록
    "health_change" -> notiHealthColor(payload)
    else -> Color(0xFF9CA3AF)
}

/** health_change payload 에서 악화/개선 판별 — to 등급이 poor/very_poor 면 경고색. */
private fun notiHealthColor(payload: String?): Color {
    return try {
        val p = payload?.let { JSONObject(it) }
        val to = p?.optString("to") ?: ""
        if (to == "poor" || to == "very_poor") OojooTheme.Orange else OojooTheme.Teal
    } catch (_: Exception) {
        OojooTheme.Teal
    }
}

/** health_change 상세 문구 — "건강 → 나쁨 (해충: 심각발생)" 형태. */
private fun notiHealthDetail(payload: String?): String? {
    return try {
        val p = payload?.let { JSONObject(it) } ?: return null
        val from = p.optString("fromKo", "")
        val to = p.optString("toKo", "")
        if (from.isBlank() || to.isBlank()) return null
        val pest = p.optString("pestLevelKo", "").takeIf { it.isNotBlank() }?.let { " · 🐛 $it" } ?: ""
        "$from → $to$pest"
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(nav: NavController, vm: NotificationViewModel = viewModel()) {
    val S = LocalAppStrings.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(S.notificationCenter, color = Color.White, fontWeight = FontWeight.Black) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp) } }
            if (vm.items.isEmpty() && !vm.loading) {
                item { Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) { Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("📭", fontSize = 56.sp); Spacer(Modifier.height(14.dp)); Text(S.noNotifications, color = OojooTheme.Muted, fontWeight = FontWeight.Bold) } } }
            }
            items(vm.items) { n ->
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffsetSm, RoundedCornerShape(18.dp)).border(2.dp, OojooTheme.Ink, RoundedCornerShape(18.dp)), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = notiColor(n.type, n.payload))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(notiLabel(n.type, S), fontWeight = FontWeight.ExtraBold, color = OojooTheme.Ink, fontSize = 15.sp)
                        // F3 — health_change 전이 상세 (예: "건강 → 나쁨 · 🐛 심각발생")
                        if (n.type == "health_change") {
                            notiHealthDetail(n.payload)?.let {
                                Text(it, color = OojooTheme.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        val target = listOfNotNull(n.plant_name, n.slave_name).joinToString(" · ")
                        if (target.isNotBlank()) Text(target, color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        n.created_at?.let { Text(it, color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            item { TextButton(onClick = { vm.refresh() }) { Text(S.refresh, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
        }
    }
}
