package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.Notification
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class NotificationViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var items by mutableStateOf<List<Notification>>(emptyList())
    var loading by mutableStateOf(false)

    fun refresh() {
        loading = true
        viewModelScope.launch {
            try { items = api.notifications(Session.userId).notifications } catch (_: Exception) {}
            loading = false
        }
    }

    init { refresh() }
}

private fun label(type: String): String = when (type) {
    "harvest_ready" -> "🍅 수확 적기"
    "pest_detected" -> "🐛 해충 감지"
    "auto_water" -> "💧 자율 관수"
    "manual_water" -> "💧 수동 관수"
    "anomaly" -> "⚠️ 이상 징후"
    "capture" -> "📷 캡처"
    else -> type
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(nav: NavController, vm: NotificationViewModel = viewModel()) {
    Scaffold(topBar = { TopAppBar(title = { Text("알림 센터") }) }) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (vm.loading) item { CircularProgressIndicator() }
            if (vm.items.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text("알림이 없습니다", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            items(vm.items) { n ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label(n.type), fontWeight = FontWeight.Bold)
                        val target = listOfNotNull(n.plant_name, n.slave_name).joinToString(" · ")
                        if (target.isNotBlank()) Text(target, style = MaterialTheme.typography.bodySmall)
                        n.payload?.takeIf { it.isNotBlank() && it != "{}" }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        n.created_at?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            item { TextButton(onClick = { vm.refresh() }) { Text("새로고침") } }
        }
    }
}
