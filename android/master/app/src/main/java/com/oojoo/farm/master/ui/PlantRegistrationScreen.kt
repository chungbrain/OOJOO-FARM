package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.master.model.CreatePlantRequest
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class PlantRegistrationViewModel : ViewModel() {
    private val api = ApiClient.api
    val userId = "u1"

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

    init { loadSlaves() }

    fun loadSlaves() {
        viewModelScope.launch {
            try { slaves = api.slaves(userId).slaves } catch (_: Exception) {}
        }
    }

    fun register() {
        if (name.isBlank()) { error = "식물 이름을 입력하세요"; return }
        loading = true
        error = null
        viewModelScope.launch {
            try {
                api.createPlant(CreatePlantRequest(
                    userId = userId,
                    slaveId = selectedSlaveId,
                    name = name.trim(),
                    species = species.trim().ifBlank { null },
                    plantedAt = plantedAt.trim().ifBlank { null },
                    stage = stage
                ))
                done = true
            } catch (e: Exception) {
                error = e.message ?: "등록 실패"
            }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantRegistrationScreen(nav: NavController, vm: PlantRegistrationViewModel = viewModel()) {
    var stageExpanded by remember { mutableStateOf(false) }
    var slaveExpanded by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("식물 등록") }) }) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = vm.name,
                onValueChange = { vm.name = it },
                label = { Text("식물 이름 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.species,
                onValueChange = { vm.species = it },
                label = { Text("작물 종류 (예: 토마토, 바질)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.plantedAt,
                onValueChange = { vm.plantedAt = it },
                label = { Text("식재일 (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Farmer 선택", style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(
                expanded = slaveExpanded,
                onExpandedChange = { slaveExpanded = it }
            ) {
                val selectedSlave = vm.slaves.find { it.id == vm.selectedSlaveId }
                OutlinedTextField(
                    value = selectedSlave?.name ?: "선택 안함 (나중에 연결)",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("담당 Farmer") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = slaveExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = slaveExpanded, onDismissRequest = { slaveExpanded = false }) {
                    DropdownMenuItem(text = { Text("선택 안함") }, onClick = { vm.selectedSlaveId = null; slaveExpanded = false })
                    vm.slaves.forEach { s ->
                        DropdownMenuItem(text = { Text("${s.name} ${if (s.online == 1) "(온라인)" else "(오프라인)"}") }, onClick = { vm.selectedSlaveId = s.id; slaveExpanded = false })
                    }
                }
            }

            Text("생장 단계", style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(
                expanded = stageExpanded,
                onExpandedChange = { stageExpanded = it }
            ) {
                val stageLabel = vm.stages.find { it.first == vm.stage }?.second ?: vm.stage
                OutlinedTextField(
                    value = stageLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("단계") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stageExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = stageExpanded, onDismissRequest = { stageExpanded = false }) {
                    vm.stages.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { vm.stage = value; stageExpanded = false })
                    }
                }
            }

            Button(
                onClick = { vm.register() },
                enabled = !vm.loading && vm.name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("식물 등록")
            }

            if (vm.done) {
                Card(Modifier.fillMaxWidth()) {
                    Text("식물이 등록되었습니다!", Modifier.padding(16.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth()) { Text("완료") }
            }
            vm.error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { nav.navigateUp() }, modifier = Modifier.fillMaxWidth()) { Text("뒤로") }
        }
    }
}
