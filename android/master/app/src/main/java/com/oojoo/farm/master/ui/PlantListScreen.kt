package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
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
import com.oojoo.farm.master.model.Plant
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PlantListViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var plants by mutableStateOf<List<Plant>>(emptyList())
    var loading by mutableStateOf(false)
    fun refresh() {
        loading = true
        viewModelScope.launch { try { plants = api.plants(userId).plants } catch (_: Exception) {}; loading = false }
    }
    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantListScreen(nav: NavController, vm: PlantListViewModel = viewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("내 식물", color = Color.White, fontWeight = FontWeight.Bold) }, actions = { TextButton(onClick = { nav.navigate("plant_register") }) { Text("+ 등록", color = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate("plant_register") }, containerColor = OojooTheme.Green, contentColor = Color.White) { Icon(Icons.Default.Add, contentDescription = "식물 등록") } },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green) } }
            if (vm.plants.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.CardElevation, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🌱", fontSize = 40.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("등록된 식물이 없습니다", color = OojooTheme.Ink, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("+ 버튼으로 식물을 등록하세요", color = OojooTheme.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            items(vm.plants) { p ->
                val stageK = mapOf("seedling" to "묘목", "vegetative" to "영양생장", "flowering" to "개화", "fruiting" to "결실")
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.CardElevation, OojooTheme.CardShape).clip(OojooTheme.CardShape).clickable { nav.navigate("plant_detail/${p.id}") }, shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
                            Text("${p.species ?: "?"} / 식재 ${p.planted_at ?: "?"}", color = OojooTheme.Muted, fontSize = 13.sp)
                            Text("Farmer: ${p.slave_id?.take(8) ?: "미연결"}", color = OojooTheme.Muted, fontSize = 13.sp)
                        }
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFFE8F5E9)) {
                            Text(stageK[p.stage] ?: p.stage ?: "?", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = OojooTheme.GreenDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
