package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
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
import com.oojoo.farm.master.model.Plant
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PlantListViewModel : ViewModel() {
    private val api = ApiClient.api
    val userId = "u1"
    var plants by mutableStateOf<List<Plant>>(emptyList())
    var loading by mutableStateOf(false)

    fun refresh() {
        loading = true
        viewModelScope.launch {
            try { plants = api.plants(userId).plants } catch (_: Exception) {}
            loading = false
        }
    }

    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantListScreen(nav: NavController, vm: PlantListViewModel = viewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("내 식물") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("plant_register") }) {
                Icon(Icons.Default.Add, contentDescription = "식물 등록")
            }
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (vm.loading) {
                item { CircularProgressIndicator() }
            }
            if (vm.plants.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("등록된 식물이 없습니다", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("+ 버튼으로 식물을 등록하세요", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(vm.plants) { p ->
                Card(
                    Modifier.fillMaxWidth().clickable { nav.navigate("plant_detail/${p.id}") }
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text("${p.species ?: "?"} / 식재: ${p.planted_at ?: "?"}", style = MaterialTheme.typography.bodySmall)
                            Text("Farmer: ${p.slave_id?.take(8) ?: "미연결"}", style = MaterialTheme.typography.bodySmall)
                        }
                        val stageLabels = mapOf("seedling" to "묘목", "vegetative" to "영양", "flowering" to "개화", "fruiting" to "결실")
                        AssistChip(onClick = {}, label = { Text(stageLabels[p.stage] ?: p.stage ?: "?") })
                    }
                }
            }
        }
    }
}
