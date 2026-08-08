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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.Plant
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.model.UpdatePlantRequest
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PlantListViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var plants by mutableStateOf<List<Plant>>(emptyList())
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)

    fun refresh() {
        loading = true; msg = null
        viewModelScope.launch {
            try {
                plants = api.plants(userId).plants
                slaves = try { api.slaves(userId).slaves } catch (_: Exception) { emptyList() }
            } catch (_: Exception) {}
            loading = false
        }
    }

    fun assignSlave(plant: Plant, slaveId: String?) {
        viewModelScope.launch {
            try {
                api.updatePlant(plant.id, UpdatePlantRequest(slaveId = slaveId))
                msg = if (slaveId == null) "Farmer 배정 해제" else "Farmer 배정 완료"
                refresh()
            } catch (e: Exception) {
                msg = e.message ?: "배정 실패"
            }
        }
    }

    fun deletePlant(plant: Plant) {
        viewModelScope.launch {
            try {
                api.deletePlant(plant.id)
                msg = "🗑️ '${plant.name}' 삭제됨"
                refresh()
            } catch (e: Exception) {
                msg = e.message ?: "삭제 실패"
            }
        }
    }

    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantListScreen(nav: NavController, vm: PlantListViewModel = viewModel()) {
    // 화면 재진입(등록/삭제 후 복귀) 시 자동 새로고침
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { vm.refresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(p).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(vm.plants, key = { it.id }) { plant ->
                PlantGridCard(
                    plant = plant,
                    slaves = vm.slaves,
                    onClick = { nav.navigate("plant_detail/${plant.id}") },
                    onAssignSlave = { slaveId -> vm.assignSlave(plant, slaveId) },
                    onDelete = { vm.deletePlant(plant) }
                )
            }
            item(span = { GridItemSpan(2) }) {
                Column {
                    vm.msg?.let {
                        Text(it, fontSize = 13.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    TextButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text("🔄 새로고침", color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlantGridCard(
    plant: Plant,
    slaves: List<Slave>,
    onClick: () -> Unit,
    onAssignSlave: (String?) -> Unit,
    onDelete: () -> Unit
) {
    val stageK = mapOf("seedling" to "묘목", "vegetative" to "영양생장", "flowering" to "개화", "fruiting" to "결실")
    val emoji = plantEmojiFor(plant.species, plant.stage)
    var showAssignDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

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
        Box(Modifier.fillMaxSize()) {
            // 삭제 버튼 (우상단)
            Surface(
                shape = CircleShape,
                color = OojooTheme.Red.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, OojooTheme.Red.copy(alpha = 0.5f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clickable { showDeleteDialog = true }
            ) {
                Text("🗑️", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 1.dp))
            }
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
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

            // Farmer 배정 상태 / 버튼
            val assignedSlave = slaves.find { it.id == plant.slave_id }
            if (assignedSlave != null) {
                Surface(
                    shape = OojooTheme.PillShape,
                    color = OojooTheme.Green,
                    border = BorderStroke(1.5.dp, OojooTheme.Ink),
                    modifier = Modifier.clickable { showAssignDialog = true }
                ) {
                    Text(
                        "🤖 ${assignedSlave.name}",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            } else {
                Surface(
                    shape = OojooTheme.PillShape,
                    color = OojooTheme.Yellow,
                    border = BorderStroke(1.5.dp, OojooTheme.Ink),
                    modifier = Modifier.clickable { showAssignDialog = true }
                ) {
                    Text(
                        "＋ Farmer 배정",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = OojooTheme.Ink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
        }
    }

    if (showAssignDialog) {
        AssignFarmerDialog(
            plant = plant,
            slaves = slaves,
            onDismiss = { showAssignDialog = false },
            onAssign = { slaveId ->
                onAssignSlave(slaveId)
                showAssignDialog = false
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("🗑️ 식물 삭제", fontWeight = FontWeight.Bold) },
            text = { Text("'${plant.name}'을(를) 삭제합니다.\n관련 이벤트/관수 기록도 함께 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("삭제", color = OojooTheme.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun AssignFarmerDialog(
    plant: Plant,
    slaves: List<Slave>,
    onDismiss: () -> Unit,
    onAssign: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🤖 Farmer 배정", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("${plant.name}에 배정할 Farmer를 선택하세요", fontSize = 13.sp, color = OojooTheme.Muted)
                Spacer(Modifier.height(12.dp))
                if (slaves.isEmpty()) {
                    Text("연결된 Farmer가 없습니다.\nFarmer 페이지에서 먼저 페어링하세요.",
                        fontSize = 13.sp, color = OojooTheme.Muted)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(slaves) { slave ->
                            val isAssigned = slave.id == plant.slave_id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAssigned) OojooTheme.GreenBg else OojooTheme.Card,
                                border = BorderStroke(1.dp, OojooTheme.Line),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssign(slave.id) }
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🤖", fontSize = 20.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(slave.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
                                        Text(if (slave.online == 1) "🟢 온라인" else "⚪ 오프라인",
                                            fontSize = 11.sp, color = OojooTheme.Muted)
                                    }
                                    if (isAssigned) Text("✓", color = OojooTheme.Green, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = OojooTheme.Card,
                                border = BorderStroke(1.dp, OojooTheme.Line),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssign(null) }
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("❌", fontSize = 20.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("배정 해제", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Red)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}
