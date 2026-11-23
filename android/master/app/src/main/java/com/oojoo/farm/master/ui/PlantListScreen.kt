package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.Plant
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.model.UpdatePlantRequest
import com.oojoo.farm.master.model.AttachVideoRequest
import com.oojoo.farm.master.model.CommandRequest
import com.oojoo.farm.master.model.CreatePostRequest
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PlantListViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var plants by mutableStateOf<List<Plant>>(emptyList())
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var analyses by mutableStateOf<Map<String, com.oojoo.farm.master.model.AnalysisResponse>>(emptyMap())
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)

    fun refresh() {
        loading = true; msg = null
        viewModelScope.launch {
            try {
                val newPlants = api.plants(userId).plants
                plants = newPlants
                slaves = try { api.slaves(userId).slaves } catch (_: Exception) { emptyList() }

                // Fetch latest analysis for each assigned plant
                val analysisMap = mutableMapOf<String, com.oojoo.farm.master.model.AnalysisResponse>()
                for (p in newPlants) {
                    if (p.slave_id != null) {
                        try {
                            val analysis = api.latestAnalysis(p.id)
                            analysisMap[p.id] = analysis
                        } catch (_: Exception) {}
                    }
                }
                analyses = analysisMap
            } catch (_: Exception) {}
            loading = false
        }
    }

    fun assignSlave(plant: Plant, slaveId: String?) {
        viewModelScope.launch {
            try {
                api.updatePlant(plant.id, UpdatePlantRequest(slaveId = slaveId, roi = plant.roi))
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

    /**
     * 식물카드 나눔/판매 — 이웃(커뮤니티) 탭에 plant_id 연결 게시물을
     * 즉시 생성한다. 성장스토리 동영상:
     *  - 이미 만든 growth 클립이 있으면 바로 첨부
     *  - 없으면 Farmer 배정 + 사진이 있으면 generate_growth_clip 을 발주하고
     *    준비되면(최대 90초 폴링) 자동 첨부 — 사용자는 대기하지 않는다
     *  - 둘 다 안 되면 동영상 없는 카드 (성장 스토리 텍스트로 대체)
     */
    fun sharePlant(plant: Plant, type: String, quantity: String, price: String) {
        viewModelScope.launch {
            try {
                var videoId: String? = null
                try { videoId = api.plantVideos(plant.id, "growth").videos.firstOrNull()?.videoId } catch (_: Exception) {}
                val photoCount = try { api.plantPhotos(plant.id).photos.size } catch (_: Exception) { 0 }
                val farmer = slaves.firstOrNull { it.id == plant.slave_id }

                val created = api.communityCreate(
                    CreatePostRequest(
                        userId = userId,
                        type = type,
                        title = plant.name,
                        crop = plant.species,
                        quantity = quantity.trim().ifBlank { null },
                        price = if (type == "sell") price.trim().toIntOrNull() else null,
                        region = Session.region,
                        description = buildGrowthStory(plant, photoCount, farmer?.name),
                        image = plantEmojiFor(plant.species, plant.stage),
                        plantId = plant.id,
                        videoId = videoId
                    )
                )
                msg = if (type == "share") "나눔 카드를 이웃에 올렸어요" else "판매 카드를 이웃에 올렸어요"

                if (videoId == null && plant.slave_id != null && photoCount > 0) {
                    watchShareVideoJob?.cancel()
                    watchShareVideoJob = viewModelScope.launch {
                        try {
                            val cmd = api.sendCommand(CommandRequest(plant.slave_id!!, plant.id, "generate_growth_clip"))
                            var waited = 0
                            while (waited < 90) {
                                kotlinx.coroutines.delay(4_000)
                                waited += 4
                                try {
                                    val v = api.videoByCommand(cmd.commandId)
                                    api.communityAttachVideo(created.postId, AttachVideoRequest(v.videoId))
                                    msg = "성장 스토리 동영상이 카드에 첨부되었어요"
                                    return@launch
                                } catch (_: Exception) {}
                            }
                            // 타임아웃 — 동영상 없는 카드로 남김 (fallback)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                msg = e.message ?: "게시물 등록 실패"
            }
        }
    }

    private var watchShareVideoJob: kotlinx.coroutines.Job? = null

    /** 성장스토리 설명 자동 구성 — 심은 날 · 생육 일수 · 사진 수 · 담당 Farmer. */
    private fun buildGrowthStory(plant: Plant, photoCount: Int, farmerName: String?): String {
        val parts = mutableListOf(plant.name + (plant.species?.let { " ($it)" } ?: ""))
        plant.planted_at?.let { plantedAt ->
            parts.add("심은 날 $plantedAt")
            try {
                val planted = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(plantedAt)
                if (planted != null) {
                    val days = ((System.currentTimeMillis() - planted.time) / (24L * 3600 * 1000)).toInt()
                    if (days > 0) parts.add("생육 ${days}일차")
                }
            } catch (_: Exception) {}
        }
        if (photoCount > 0) parts.add("성장 사진 ${photoCount}장")
        farmerName?.let { parts.add("Farmer: $it") }
        return parts.joinToString(" · ")
    }

    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantListScreen(nav: NavController, vm: PlantListViewModel = viewModel()) {
    val S = LocalAppStrings.current
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
                title = { Text(S.myPlants, color = Color.White, fontWeight = FontWeight.Black) },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = S.refresh, tint = Color.White)
                    }
                    TextButton(onClick = { nav.navigate("plant_register") }) {
                        Text("＋ ${S.register}", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate("plant_register") }, containerColor = OojooTheme.Green, contentColor = Color.White) { Icon(Icons.Default.Add, contentDescription = S.plantRegister) } },
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
                            Text(S.noPlantsEmpty, color = OojooTheme.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("＋ ${S.register}!", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item {
                    TextButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text(S.refresh, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
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
                // 카드 폭이 제한(max 180dp)되므로, 넓은 화면에서는 셀 안에 가운데 정렬
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PlantGridCard(
                        plant = plant,
                        slaves = vm.slaves,
                        latestAnalysis = vm.analyses[plant.id],
                        onClick = { nav.navigate("plant_detail/${plant.id}") },
                        onAssignSlave = { slaveId -> vm.assignSlave(plant, slaveId) },
                        onDelete = { vm.deletePlant(plant) },
                        onSharePlant = { type, qty, price -> vm.sharePlant(plant, type, qty, price) }
                    )
                }
            }
            item(span = { GridItemSpan(2) }) {
                Column {
                    vm.msg?.let {
                        Text(localizeMasterMessage(it, S), fontSize = 13.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    TextButton(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text(S.refresh, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
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
    latestAnalysis: com.oojoo.farm.master.model.AnalysisResponse?,
    onClick: () -> Unit,
    onAssignSlave: (String?) -> Unit,
    onDelete: () -> Unit,
    onSharePlant: (type: String, quantity: String, price: String) -> Unit
) {
    val S = LocalAppStrings.current
    val stageK = mapOf(
        "seedling" to S.growthStageSeedling,
        "vegetative" to S.growthStageVegetative,
        "flowering" to S.growthStageFlowering,
        "fruiting" to S.growthStageFruiting
    )
    val emoji = plantEmojiFor(plant.species, plant.stage)
    var showAssignDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var shareType by remember { mutableStateOf<String?>(null) }

    Card(
        Modifier
            // 화면이 커져도 카드가 비율적으로 확대되지 않게 최대 폭 제한.
            // (수정 전: fillMaxWidth만 써서 셀 폭=화면 폭에 비례해 커졌음)
            // 순서 주의 — widthIn이 fillMaxWidth 바깥에 와야 상한이 적용된다.
            .widthIn(max = 180.dp)
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
                    plant.species ?: S.speciesUnspecified,
                    color = OojooTheme.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                if (plant.shared == 1) {
                    val owner = plant.owner_name?.ifBlank { null } ?: plant.owner_email
                    Text(
                        if (owner.isNullOrBlank()) stringResource(R.string.family_shared) else stringResource(R.string.family_shared_with, owner),
                        color = OojooTheme.GreenDark,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
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

            // Farmer 배정 상태 / 건강 상태 표시
            val assignedSlave = slaves.find { it.id == plant.slave_id }
            if (assignedSlave != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = OojooTheme.PillShape,
                        color = farmerColorFor(assignedSlave.id),
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
                    if (latestAnalysis?.analysis != null) {
                        Spacer(Modifier.height(4.dp))
                        val health = latestAnalysis.analysis.healthStatus
                        val color = if (health.contains("건강") || health.contains("양호")) OojooTheme.Teal else OojooTheme.Orange
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = color.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, color)
                        ) {
                            Text(
                                health,
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = color,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = OojooTheme.PillShape,
                    color = OojooTheme.Yellow,
                    border = BorderStroke(1.5.dp, OojooTheme.Ink),
                    modifier = Modifier.clickable { showAssignDialog = true }
                ) {
                    Text(
                        S.assignFarmer,
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = OojooTheme.Ink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // 나눔/판매 — 이웃(커뮤니티) 탭에 카드 즉시 생성, 성장스토리 동영상은 준비되면 첨부
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = OojooTheme.PillShape,
                    color = OojooTheme.GreenBg,
                    border = BorderStroke(1.dp, OojooTheme.Ink),
                    modifier = Modifier.weight(1f).clickable { shareType = "share" }
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "🤝 ${S.share}",
                            color = OojooTheme.GreenDark,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Surface(
                    shape = OojooTheme.PillShape,
                    color = OojooTheme.Yellow,
                    border = BorderStroke(1.dp, OojooTheme.Ink),
                    modifier = Modifier.weight(1f).clickable { shareType = "sell" }
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "🏷️ ${S.sell}",
                            color = OojooTheme.Ink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
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
            title = { Text(S.deletePlantTitle, fontWeight = FontWeight.Bold) },
            text = { Text("'${plant.name}'${S.deletePlantConfirm}") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text(S.delete, color = OojooTheme.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(S.cancel) }
            }
        )
    }

    if (shareType != null) {
        SharePlantDialog(
            plant = plant,
            type = shareType!!,
            onDismiss = { shareType = null },
            onSubmit = { qty, price ->
                onSharePlant(shareType!!, qty, price)
                shareType = null
            }
        )
    }
}

/**
 * 식물카드 나눔/판매 다이얼로그 — 제출 시 이웃(커뮤니티) 탭에
 * plant_id 연결 게시물이 즉시 생성된다 (성장스토리 동영상은
 * ViewModel 에서 준비되면 자동 첨부).
 */
@Composable
private fun SharePlantDialog(
    plant: Plant,
    type: String,
    onDismiss: () -> Unit,
    onSubmit: (quantity: String, price: String) -> Unit
) {
    val S = LocalAppStrings.current
    var quantity by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                (if (type == "share") S.plantShareDialogTitle else S.plantSellDialogTitle).format(plant.name),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    plant.name + (plant.species?.let { " ($it)" } ?: ""),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = OojooTheme.Ink
                )
                Text(S.plantShareDialogHint, fontSize = 12.sp, color = OojooTheme.Muted)
                OojooField(quantity, { quantity = it }, S.quantityPh)
                if (type == "sell") {
                    OojooField(price, { price = it.filter { c -> c.isDigit() } }, S.priceWon)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(quantity, price) }) { Text(S.postButton, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        }
    )
}

@Composable
private fun AssignFarmerDialog(
    plant: Plant,
    slaves: List<Slave>,
    onDismiss: () -> Unit,
    onAssign: (String?) -> Unit
) {
    val S = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.assignFarmerTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("${plant.name}${S.selectFarmerPrompt}", fontSize = 13.sp, color = OojooTheme.Muted)
                Spacer(Modifier.height(12.dp))
                if (slaves.isEmpty()) {
                    Text(S.noSlaves,
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(22.dp)
                                                .clip(RoundedCornerShape(50))
                                                .border(1.5.dp, OojooTheme.Ink, RoundedCornerShape(50))
                                                .background(farmerColorFor(slave.id))
                                        )
                                        Text("🤖", fontSize = 20.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(slave.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
                                        Text(if (slave.online == 1) S.online else S.offline,
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
                                    Text(S.unassign, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Red)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(S.close) } }
    )
}
