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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.CreatePlantRequest
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PlantRegistrationViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var name by mutableStateOf("")
    var species by mutableStateOf("")
    var plantedAt by mutableStateOf("")
    var selectedSlaveId by mutableStateOf<String?>(null)
    var stage by mutableStateOf("seedling")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)
    val stages = listOf("seedling" to "묘목", "vegetative" to "영양생장", "flowering" to "개화", "fruiting" to "결실")
    val popularSpecies = listOf("상추", "깻잎", "바질", "로즈마리", "방울토마토", "토마토", "대파", "딸기", "고추", "애호박", "호박", "고구마", "감자", "양파")

    init { loadSlaves() }
    fun loadSlaves() { viewModelScope.launch { try { slaves = api.slaves(userId).slaves } catch (_: Exception) {} } }

    fun register() {
        if (name.isBlank()) { error = "식물 이름을 입력하세요"; return }
        loading = true; error = null
        viewModelScope.launch {
            try {
                api.createPlant(CreatePlantRequest(userId, selectedSlaveId, name.trim(), species.trim().ifBlank { null }, plantedAt.trim().ifBlank { null }, stage))
                done = true
            } catch (e: Exception) { error = e.message ?: "등록 실패" }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantRegistrationScreen(nav: NavController, vm: PlantRegistrationViewModel = viewModel()) {
    var speciesExpanded by remember { mutableStateOf(false) }
    var stageExpanded by remember { mutableStateOf(false) }
    var slaveExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("식물 등록", color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
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
                        Text("선택된 작물", color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(vm.species.ifBlank { "작물 종류를 선택/입력하세요" }, color = OojooTheme.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
            }
            Text("식물 이름 *", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.name, { vm.name = it }, "예: 방울토마토")
            Text("작물 종류", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = speciesExpanded, onExpandedChange = { speciesExpanded = it }) {
                OutlinedTextField(
                    value = vm.species,
                    onValueChange = { vm.species = it; speciesExpanded = true },
                    placeholder = { Text("예: 토마토, 바질") },
                    shape = OojooTheme.FieldShape,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = speciesExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                val filtered = vm.popularSpecies.filter { it.contains(vm.species, ignoreCase = true) }
                if (filtered.isNotEmpty()) {
                    ExposedDropdownMenu(expanded = speciesExpanded, onDismissRequest = { speciesExpanded = false }) {
                        filtered.forEach { sp ->
                            DropdownMenuItem(
                                text = { Text("${plantEmojiFor(sp, null)}  $sp") },
                                onClick = { vm.species = sp; speciesExpanded = false }
                            )
                        }
                    }
                }
            }
            Text("식재일", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.plantedAt, { vm.plantedAt = it }, "YYYY-MM-DD")

            Text("담당 Farmer", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = slaveExpanded, onExpandedChange = { slaveExpanded = it }) {
                val s = vm.slaves.find { it.id == vm.selectedSlaveId }
                OutlinedTextField(value = s?.name ?: "선택 안함 (나중에 연결)", onValueChange = {}, readOnly = true, shape = OojooTheme.FieldShape, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = slaveExpanded) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line), modifier = Modifier.fillMaxWidth().menuAnchor())
                ExposedDropdownMenu(expanded = slaveExpanded, onDismissRequest = { slaveExpanded = false }) {
                    DropdownMenuItem(text = { Text("선택 안함") }, onClick = { vm.selectedSlaveId = null; slaveExpanded = false })
                    vm.slaves.forEach { sl -> DropdownMenuItem(text = { Text("${sl.name} ${if (sl.online == 1) "(온라인)" else "(오프라인)"}") }, onClick = { vm.selectedSlaveId = sl.id; slaveExpanded = false }) }
                }
            }

            Text("생장 단계", style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            ExposedDropdownMenuBox(expanded = stageExpanded, onExpandedChange = { stageExpanded = it }) {
                val lbl = vm.stages.find { it.first == vm.stage }?.second ?: vm.stage
                OutlinedTextField(value = lbl, onValueChange = {}, readOnly = true, shape = OojooTheme.FieldShape, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stageExpanded) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line), modifier = Modifier.fillMaxWidth().menuAnchor())
                ExposedDropdownMenu(expanded = stageExpanded, onDismissRequest = { stageExpanded = false }) {
                    vm.stages.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { vm.stage = v; stageExpanded = false }) }
                }
            }

            GradientButton(text = "식물 등록", onClick = { vm.register() }, enabled = !vm.loading && vm.name.isNotBlank(), modifier = Modifier.fillMaxWidth())
            if (vm.loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OojooTheme.Green) }
            if (vm.done) {
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape) {
                    Text("🌱 식물이 등록되었습니다!", Modifier.padding(16.dp), fontWeight = FontWeight.Bold, color = OojooTheme.Green)
                }
                GradientButton(text = "완료", onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth())
            }
            vm.error?.let { Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp) }
        }
    }
}
