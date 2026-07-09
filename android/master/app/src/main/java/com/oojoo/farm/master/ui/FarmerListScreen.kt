package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.CommandRequest
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class FarmerListViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    fun refresh() { loading = true; viewModelScope.launch { try { slaves = api.slaves(userId).slaves } catch (_: Exception) {}; loading = false } }
    fun pauseSlave(id: String) { viewModelScope.launch { try { api.sendCommand(CommandRequest(id, null, "pause")); msg = "⏸ 일시정지 지시!" } catch (e: Exception) { msg = e.message } } }
    fun resumeSlave(id: String) { viewModelScope.launch { try { api.sendCommand(CommandRequest(id, null, "resume")); msg = "▶ 재개 지시!" } catch (e: Exception) { msg = e.message } } }
    fun unpair(id: String) { viewModelScope.launch { try { api.unpair(id); msg = "연결 해제됨"; refresh() } catch (e: Exception) { msg = e.message } } }
    fun pestFan(id: String) { viewModelScope.launch { try { api.sendCommand(CommandRequest(id, null, "fan")); msg = "🌀 Fan 퇴치 지시!" } catch (e: Exception) { msg = e.message } } }
    fun pestLaser(id: String) { viewModelScope.launch { try { api.sendCommand(CommandRequest(id, null, "laser")); msg = "🔦 Laser 퇴치 승인!" } catch (e: Exception) { msg = e.message } } }
    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmerListScreen(nav: NavController, vm: FarmerListViewModel = viewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("🤖 Farmer 관리", color = Color.White, fontWeight = FontWeight.Black) }, actions = { TextButton(onClick = { nav.navigate("subscription") }) { Text("⭐ 구독", color = Color.White, fontWeight = FontWeight.Bold) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate("pairing") }, containerColor = OojooTheme.Green, contentColor = Color.White) { Icon(Icons.Default.Add, contentDescription = "Farmer 연결") } },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (vm.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp) } }
            if (vm.slaves.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🤖", fontSize = 56.sp); Spacer(Modifier.height(14.dp))
                            Text("연결된 Farmer가 없어요!", color = OojooTheme.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("＋ 버튼으로 페어링해요!", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            items(vm.slaves) { s ->
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(50)).border(2.dp, OojooTheme.Ink, RoundedCornerShape(50)).background(OojooTheme.GreenBg), contentAlignment = Alignment.Center) { Text("🤖", fontSize = 26.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 15.sp)
                                val dot = if (s.online == 1) "🟢 온라인" else "⚪ 오프라인"
                                val bat = s.battery?.let { " · 🔋$it%" } ?: ""
                                Text("$dot$bat", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                s.last_seen?.let { Text("마지막 통신: $it", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                            }
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).border(2.dp, OojooTheme.Ink, RoundedCornerShape(50)).background(if (s.online == 1) OojooTheme.Green else Color.Gray))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlineButton(text = "⏸ 정지", onClick = { vm.pauseSlave(s.id) }, modifier = Modifier.weight(1f))
                            OutlineButton(text = "▶ 재개", onClick = { vm.resumeSlave(s.id) }, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlineButton(text = "🌀 Fan", onClick = { vm.pestFan(s.id) }, modifier = Modifier.weight(1f))
                            OutlineButton(text = "🔦 Laser", onClick = { vm.pestLaser(s.id) }, modifier = Modifier.weight(1f))
                        }
                        GradientButton(text = "📹 카메라 보기 (3초)", onClick = {
                            val encoded = android.net.Uri.encode(s.name)
                            nav.navigate("live_camera/${s.id}/$encoded")
                        }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { nav.navigate("report/${s.id}") }) { Text("📊 리포트", color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                            TextButton(onClick = { vm.unpair(s.id) }) { Text("연결 해제", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                }
            }
            item { vm.msg?.let { Text(it, fontSize = 13.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
            item { TextButton(onClick = { vm.refresh() }) { Text("🔄 새로고침", color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
        }
    }
}
