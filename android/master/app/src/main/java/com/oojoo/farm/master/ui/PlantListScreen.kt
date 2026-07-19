package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    fun refresh() { loading = true; viewModelScope.launch { try { plants = api.plants(userId).plants } catch (_: Exception) {}; loading = false } }
    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantListScreen(nav: NavController, vm: PlantListViewModel = viewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🌱 내 식물", color = Color.White, fontWeight = FontWeight.Black) },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = Color.White)
                    }
                    TextButton(onClick = { nav.navigate("plant_register") }) {
                        Text("＋ 등록", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate("plant_register") }, containerColor = OojooTheme.Green, contentColor = Color.White) { Icon(Icons.Default.Add, contentDescription = "식물 등록") } },
        containerColor = OojooTheme.Bg
    ) { p ->
        if (vm.loading && vm.plants.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp)
            }
            return@Scaffold
        }
        if (vm.plants.isEmpty()) {
            // Farmer 페이지와 동일하게 상단부터 배치 (LazyColumn 첫 item)
            LazyColumn(
                Modifier.fillMaxSize().padding(p).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Card(
                        Modifier.fillMaxWidth()
                            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
                            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape),
                        shape = OojooTheme.CardShape,
                        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
                    ) {
                        Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🌱", fontSize = 56.sp); Spacer(Modifier.height(14.dp))
                            Text("등록된 식물이 없어요!", color = OojooTheme.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("＋ 버튼으로 등록해요!", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item {
                    TextButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text("🔄 새로고침", color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
                    }
                }
            }
            return@Scaffold
        }
        // 2열 카드 그리드 — 한 화면에 많은 식물이 빠르게 보이도록
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(p).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(vm.plants, key = { it.id }) { plant ->
                PlantGridCard(plant) { nav.navigate("plant_detail/${plant.id}") }
            }
            // 하단 새로고침 버튼 (전체 너비)
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                TextButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                    Text("🔄 새로고침", color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PlantGridCard(plant: Plant, onClick: () -> Unit) {
    val stageK = mapOf("seedling" to "묘목", "vegetative" to "영양생장", "flowering" to "개화", "fruiting" to "결실")
    val emoji = plantEmojiFor(plant.species, plant.stage)

    Card(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape)
            .clip(OojooTheme.CardShape)
            .clickable { onClick() },
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 큰 이모지 (작물 종류별)
            Text(emoji, fontSize = 48.sp, textAlign = TextAlign.Center)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    plant.name,
                    fontWeight = FontWeight.ExtraBold,
                    color = OojooTheme.Ink,
                    fontSize = 15.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    plant.species ?: "작물 미지정",
                    color = OojooTheme.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }

            Surface(
                shape = OojooTheme.PillShape,
                color = OojooTheme.GreenBg,
                border = BorderStroke(1.5.dp, OojooTheme.Ink)
            ) {
                Text(
                    stageK[plant.stage] ?: plant.stage ?: "?",
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = OojooTheme.GreenDark,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
