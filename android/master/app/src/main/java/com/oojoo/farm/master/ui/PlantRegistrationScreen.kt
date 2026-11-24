package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.CreatePlantRequest
import com.oojoo.farm.master.model.Plant
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PlantRegistrationViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var plants by mutableStateOf<List<Plant>>(emptyList())
    var name by mutableStateOf("")
    var species by mutableStateOf("")
    var plantedAt by mutableStateOf("")
    var selectedSlaveId by mutableStateOf<String?>(null)
    var stage by mutableStateOf("seedling")
    // 농장 배치 위치 — null이면 자동 배치 규칙에 맡김 (선택기에서 기본 추천 제공)
    var farmCell by mutableStateOf<FarmCell?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)
    val popularSpecies = listOf("상추", "깻잎", "바질", "로즈마리", "허브", "선인장", "방울토마토", "토마토", "대파", "딸기", "고추", "애호박", "호박", "고구마", "감자", "양파")

    init { loadSlaves() }
    fun loadSlaves() { viewModelScope.launch { try { slaves = FarmerIconStore.fetchSlaves(userId).slaves } catch (_: Exception) {} } }

    /** 기존 식물 점유 현황 로드 + 농장 위치 기본 추천(가장 좋은 빈 칸). */
    fun loadFarmLayout(ctx: android.content.Context) {
        viewModelScope.launch {
            try { plants = api.plants(userId).plants } catch (_: Exception) {}
            if (farmCell == null) {
                farmCell = pickEmptyFarmCell(computeFarmOccupancy(ctx, plants).values.toSet())
            }
        }
    }

    fun register(ctx: android.content.Context, onSuccess: () -> Unit = {}) {
        if (name.isBlank()) { error = "식물 이름을 입력하세요"; return }
        loading = true; error = null
        viewModelScope.launch {
            try {
                val resp = api.createPlant(CreatePlantRequest(userId, selectedSlaveId, name.trim(), species.trim().ifBlank { null }, plantedAt.trim().ifBlank { null }, stage))
                // 선택한 농장 위치 저장 — 홈 농장 씬이 이 위치에 식물을 배치한다
                farmCell?.let { c -> Prefs.setFarmPlantCell(ctx, resp.plantId, c.row, c.col) }
                done = true
                kotlinx.coroutines.delay(800)  // 등록 완료 메시지를 잠깐 보여줌
                onSuccess()
            } catch (e: Exception) { error = e.message ?: "등록 실패" }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantRegistrationScreen(nav: NavController, vm: PlantRegistrationViewModel = viewModel()) {
    val S = LocalAppStrings.current
    // 농장 위치 선택기용 — 기존 식물 점유 현황 + 빈 칸 추천
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadFarmLayout(ctx) }
    val speciesLabels = if (S.isEnglish) mapOf(
        "상추" to "Lettuce", "깻잎" to "Perilla", "바질" to "Basil", "로즈마리" to "Rosemary",
        "허브" to "Herb", "선인장" to "Cactus",
        "방울토마토" to "Cherry tomato", "토마토" to "Tomato", "대파" to "Green onion",
        "딸기" to "Strawberry", "고추" to "Chili pepper", "애호박" to "Zucchini",
        "호박" to "Pumpkin", "고구마" to "Sweet potato", "감자" to "Potato", "양파" to "Onion"
    ) else emptyMap()
    val stages = listOf(
        "seedling" to S.growthStageSeedling,
        "vegetative" to S.growthStageVegetative,
        "flowering" to S.growthStageFlowering,
        "fruiting" to S.growthStageFruiting
    )
    var speciesExpanded by remember { mutableStateOf(false) }
    var stageExpanded by remember { mutableStateOf(false) }
    var slaveExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(S.plantRegister, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 선택한 작물 종류의 이모지 미리보기
            Card(
                Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape),
                shape = OojooTheme.CardShape,
                colors = CardDefaults.cardColors(containerColor = OojooTheme.GreenBg)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(plantEmojiFor(vm.species.ifBlank { null }, vm.stage), fontSize = 40.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(S.selectedCrop, color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(vm.species.ifBlank { S.cropInputPrompt }.let { speciesLabels[it] ?: it }, color = OojooTheme.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
            }
            Text(S.plantNameRequired, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.name, { vm.name = it }, S.plantNamePh)
            Text(S.species, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = speciesExpanded, onExpandedChange = { speciesExpanded = it }) {
                OutlinedTextField(
                    value = speciesLabels[vm.species] ?: vm.species,
                    onValueChange = { vm.species = it; speciesExpanded = true },
                    placeholder = { Text(S.speciesPh) },
                    shape = OojooTheme.FieldShape,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = speciesExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                val filtered = vm.popularSpecies.filter {
                    it.contains(vm.species, ignoreCase = true) || speciesLabels[it]?.contains(vm.species, ignoreCase = true) == true
                }
                if (filtered.isNotEmpty()) {
                    ExposedDropdownMenu(expanded = speciesExpanded, onDismissRequest = { speciesExpanded = false }) {
                        filtered.forEach { sp ->
                            DropdownMenuItem(
                                text = { Text("${plantEmojiFor(sp, null)}  ${speciesLabels[sp] ?: sp}") },
                                onClick = { vm.species = sp; speciesExpanded = false }
                            )
                        }
                    }
                }
            }
            Text(S.plantedDateOptional, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.plantedAt, { vm.plantedAt = it }, S.plantedDatePh)

            Text(S.selectFarmer, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = slaveExpanded, onExpandedChange = { slaveExpanded = it }) {
                val s = vm.slaves.find { it.id == vm.selectedSlaveId }
                OutlinedTextField(value = s?.name ?: S.selectLater, onValueChange = {}, readOnly = true, shape = OojooTheme.FieldShape, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = slaveExpanded) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line), modifier = Modifier.fillMaxWidth().menuAnchor())
                ExposedDropdownMenu(expanded = slaveExpanded, onDismissRequest = { slaveExpanded = false }) {
                    DropdownMenuItem(text = { Text(S.selectNone) }, onClick = { vm.selectedSlaveId = null; slaveExpanded = false })
                    vm.slaves.forEach { sl -> DropdownMenuItem(text = { Text("${sl.name} ${if (sl.online == 1) S.farmerOnlineBadge else S.farmerOfflineBadge}") }, onClick = { vm.selectedSlaveId = sl.id; slaveExpanded = false }) }
                }
            }

            Text(S.growthStage, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = stageExpanded, onExpandedChange = { stageExpanded = it }) {
                val lbl = stages.find { it.first == vm.stage }?.second ?: vm.stage
                OutlinedTextField(value = lbl, onValueChange = {}, readOnly = true, shape = OojooTheme.FieldShape, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stageExpanded) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line), modifier = Modifier.fillMaxWidth().menuAnchor())
                ExposedDropdownMenu(expanded = stageExpanded, onDismissRequest = { stageExpanded = false }) {
                    stages.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { vm.stage = v; stageExpanded = false }) }
                }
            }

            // 농장 위치 — 3x4 구역 중 배치할 칸 선택 (기본: 가장 좋은 빈 칸 추천)
            FarmCellPicker(
                occupied = computeFarmOccupancy(ctx, vm.plants),
                emojis = vm.plants.associate { it.id to plantEmojiFor(it.species, it.stage) },
                selected = vm.farmCell,
                onSelect = { vm.farmCell = it },
                movingEmoji = plantEmojiFor(vm.species.ifBlank { null }, vm.stage)
            )

            GradientButton(text = S.plantRegister, onClick = { vm.register(ctx) { nav.navigateUp() } }, enabled = !vm.loading && vm.name.isNotBlank(), modifier = Modifier.fillMaxWidth())
            if (vm.loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OojooTheme.Green) }
            if (vm.done) {
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape) {
                    Text(S.registerSuccess, Modifier.padding(16.dp), fontWeight = FontWeight.Bold, color = OojooTheme.Green)
                }
                GradientButton(text = S.done, onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth())
            }
            vm.error?.let { Text("⚠️ ${localizeMasterMessage(it, S)}", color = OojooTheme.Red, fontSize = 13.sp) }
        }
    }
}
