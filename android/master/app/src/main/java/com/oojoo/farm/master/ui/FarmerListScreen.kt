package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    fun refresh() {
        loading = true
        viewModelScope.launch {
            try { slaves = api.slaves(userId).slaves } catch (_: Exception) {}
            loading = false
        }
    }

    fun pauseSlave(slaveId: String) {
        viewModelScope.launch {
            try { api.sendCommand(CommandRequest(slaveId, null, "pause")); msg = "일시정지 지시 전송" }
            catch (e: Exception) { msg = e.message }
        }
    }

    fun resumeSlave(slaveId: String) {
        viewModelScope.launch {
            try { api.sendCommand(CommandRequest(slaveId, null, "resume")); msg = "재개 지시 전송" }
            catch (e: Exception) { msg = e.message }
        }
    }

    fun unpair(slaveId: String) {
        viewModelScope.launch {
            try { api.unpair(slaveId); msg = "연결 해제됨"; refresh() }
            catch (e: Exception) { msg = e.message }
        }
    }

    fun pestFan(slaveId: String) {
        viewModelScope.launch {
            try { api.sendCommand(CommandRequest(slaveId, null, "fan")); msg = "Fan 퇴치 지시 전송" }
            catch (e: Exception) { msg = e.message }
        }
    }

    fun pestLaser(slaveId: String) {
        viewModelScope.launch {
            try { api.sendCommand(CommandRequest(slaveId, null, "laser")); msg = "Laser 퇴치 승인·지시 전송" }
            catch (e: Exception) { msg = e.message }
        }
    }

    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmerListScreen(nav: NavController, vm: FarmerListViewModel = viewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Farmer 관리") }, actions = { TextButton(onClick = { nav.navigate("subscription") }) { Text("⭐ 구독") } }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("pairing") }) {
                Icon(Icons.Default.Add, contentDescription = "Farmer 연결")
            }
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (vm.loading) item { CircularProgressIndicator() }
            if (vm.slaves.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("연결된 Farmer가 없습니다", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("+ 버튼으로 Farmer를 페어링하세요", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(vm.slaves) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.Bold)
                                Text(if (s.online == 1) "온라인" else "오프라인", style = MaterialTheme.typography.bodySmall)
                                s.last_seen?.let { Text("마지막 통신: $it", style = MaterialTheme.typography.bodySmall) }
                                s.battery?.let { Text("배터리: $it%", style = MaterialTheme.typography.bodySmall) }
                            }
                            AssistChip(onClick = {}, label = { Text(if (s.online == 1) "●" else "○") })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.pauseSlave(s.id) }, modifier = Modifier.weight(1f)) { Text("일시정지") }
                            OutlinedButton(onClick = { vm.resumeSlave(s.id) }, modifier = Modifier.weight(1f)) { Text("재개") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.pestFan(s.id) }, modifier = Modifier.weight(1f)) { Text("Fan 퇴치") }
                            OutlinedButton(onClick = { vm.pestLaser(s.id) }, modifier = Modifier.weight(1f)) { Text("Laser 승인") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { nav.navigate("report/${s.id}") }) { Text("📊 리포트") }
                            TextButton(onClick = { vm.unpair(s.id) }) { Text("연결 해제", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item { vm.msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
            item { TextButton(onClick = { vm.refresh() }) { Text("새로고침") } }
        }
    }
}
