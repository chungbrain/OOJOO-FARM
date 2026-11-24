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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oojoo.farm.master.R
import androidx.navigation.NavController
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.Command
import com.oojoo.farm.master.model.CommandRequest
import com.oojoo.farm.master.model.PolicyRequest
import com.oojoo.farm.master.model.PolicyResponse
import com.oojoo.farm.master.model.RenameSlaveRequest
import com.oojoo.farm.master.model.Slave
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class FarmerListViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var slaves by mutableStateOf<List<Slave>>(emptyList())
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    var policyTarget by mutableStateOf<Slave?>(null)
    var policy by mutableStateOf<PolicyResponse?>(null)
    var policyLoading by mutableStateOf(false)
    var policySaving by mutableStateOf(false)
    var renameTarget by mutableStateOf<Slave?>(null)
    var renameSaving by mutableStateOf(false)
    var commandsTarget by mutableStateOf<Slave?>(null)
    var commands by mutableStateOf<List<Command>>(emptyList())
    var commandsLoading by mutableStateOf(false)
    fun refresh() { loading = true; viewModelScope.launch { try { slaves = FarmerIconStore.fetchSlaves(userId).slaves } catch (_: Exception) {}; loading = false } }
    fun pauseSlave(id: String) { viewModelScope.launch { try { api.sendCommand(CommandRequest(id, null, "pause")); msg = "⏸ 일시정지 지시!" } catch (e: Exception) { msg = e.message } } }
    fun resumeSlave(id: String) { viewModelScope.launch { try { api.sendCommand(CommandRequest(id, null, "resume")); msg = "▶ 재개 지시!" } catch (e: Exception) { msg = e.message } } }
    fun unpair(id: String) { viewModelScope.launch { try { api.unpair(id); msg = "연결 해제됨"; refresh() } catch (e: Exception) { msg = e.message } } }
    fun pestFan(id: String) { viewModelScope.launch { try { api.sendCommand(CommandRequest(id, null, "fan")); msg = "🌀 Fan 퇴치 지시!" } catch (e: Exception) { msg = e.message } } }
    fun pestLaser(id: String) { viewModelScope.launch { try { api.sendCommand(CommandRequest(id, null, "laser")); msg = "🔦 Laser 퇴치 승인!" } catch (e: Exception) { msg = e.message } } }

    fun openPolicy(s: Slave) {
        policyTarget = s
        policy = null
        policyLoading = true
        viewModelScope.launch {
            try { policy = api.policy(s.id) } catch (_: Exception) {}
            policyLoading = false
        }
    }
    fun closePolicy() { policyTarget = null; policy = null }
    fun savePolicy(waterAuto: Boolean, fanAuto: Boolean, laserApproval: Boolean, captureIntervalMin: Int, pestIntervalMin: Int, roiIntervalSec: Int) {
        val target = policyTarget ?: return
        policySaving = true
        viewModelScope.launch {
            try {
                api.setPolicy(target.id, PolicyRequest(
                    waterAuto = waterAuto,
                    fanAuto = fanAuto,
                    laserApproval = laserApproval,
                    captureInterval = captureIntervalMin,
                    pestInterval = pestIntervalMin,
                    roiInterval = roiIntervalSec
                ))
                msg = "정책이 저장되었습니다"
                closePolicy()
            } catch (e: Exception) { msg = e.message }
            policySaving = false
        }
    }

    fun openRename(s: Slave) { renameTarget = s }
    fun closeRename() { renameTarget = null }
    fun rename(name: String) {
        val target = renameTarget ?: return
        renameSaving = true
        viewModelScope.launch {
            try {
                api.renameSlave(target.id, RenameSlaveRequest(name.trim()))
                msg = "이름 변경됨"
                closeRename()
                refresh()
            } catch (e: Exception) { msg = e.message }
            renameSaving = false
        }
    }

    fun openCommands(s: Slave) {
        commandsTarget = s
        commands = emptyList()
        commandsLoading = true
        viewModelScope.launch {
            try { commands = api.commandHistory(s.id).commands } catch (_: Exception) {}
            commandsLoading = false
        }
    }
    fun closeCommands() { commandsTarget = null; commands = emptyList() }
    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmerListScreen(nav: NavController, vm: FarmerListViewModel = viewModel()) {
    val S = LocalAppStrings.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(S.farmerManage, color = Color.White, fontWeight = FontWeight.Black) }, actions = { Row { TextButton(onClick = { nav.navigate("family") }) { Text(stringResource(R.string.family_short), color = Color.White, fontWeight = FontWeight.Bold) }; TextButton(onClick = { nav.navigate("gallery") }) { Text(S.gallery, color = Color.White, fontWeight = FontWeight.Bold) }; TextButton(onClick = { nav.navigate("subscription") }) { Text(S.subscription, color = Color.White, fontWeight = FontWeight.Bold) } } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate("pairing") }, containerColor = OojooTheme.Green, contentColor = Color.White) { Icon(Icons.Default.Add, contentDescription = S.connectFarmer) } },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (vm.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp) } }
            if (vm.slaves.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🤖", fontSize = 56.sp); Spacer(Modifier.height(14.dp))
                            Text(S.noFarmersEmpty, color = OojooTheme.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(S.pairingTip, color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            items(vm.slaves) { s ->
                var showIconDialog by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 아이콘 눌러 선택 다이얼로그 — farmer별 아이콘 변경
                            Text(
                                farmerIconFor(s.id),
                                fontSize = 30.sp,
                                modifier = Modifier.clickable { showIconDialog = true }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 15.sp)
                                if (s.shared == 1) {
                                    val owner = s.owner_name?.ifBlank { null } ?: s.owner_email ?: stringResource(R.string.family_shared)
                                    Text("👥 $owner", color = OojooTheme.GreenDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                val dot = if (s.online == 1) S.online else S.offline
                                val bat = s.battery?.let { " · 🔋$it%" } ?: ""
                                Text("$dot$bat", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                s.last_seen?.let { Text("${S.lastComm}: $it", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                            }
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).border(2.dp, OojooTheme.Ink, RoundedCornerShape(50)).background(if (s.online == 1) OojooTheme.Green else Color.Gray))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlineButton(text = S.pause, onClick = { vm.pauseSlave(s.id) }, modifier = Modifier.weight(1f))
                            OutlineButton(text = S.resumeAction, onClick = { vm.resumeSlave(s.id) }, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlineButton(text = S.fanControl, onClick = { vm.pestFan(s.id) }, modifier = Modifier.weight(1f))
                            OutlineButton(text = S.laserControl, onClick = { vm.pestLaser(s.id) }, modifier = Modifier.weight(1f))
                        }
                        GradientButton(text = S.viewCamera, onClick = {
                            val encoded = android.net.Uri.encode(s.name)
                            nav.navigate("live_camera/${s.id}/$encoded")
                        }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { nav.navigate("report/${s.id}") }, modifier = Modifier.weight(1f)) { Text(S.reportBtn, color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                            TextButton(onClick = { vm.openCommands(s) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_commands), color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                        }
                        var showUnpairDialog by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.openPolicy(s) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.monitoring_settings), color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                            TextButton(onClick = { vm.openRename(s) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_rename), color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                            TextButton(onClick = { showUnpairDialog = true }, modifier = Modifier.weight(1f)) { Text("🗑️ ${S.delete}", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                        }
                        if (showUnpairDialog) {
                            AlertDialog(
                                onDismissRequest = { showUnpairDialog = false },
                                title = { Text(S.deleteFarmerTitle, fontWeight = FontWeight.Bold) },
                                text = { Text("'${s.name}'${S.deleteFarmerConfirm}") },
                                confirmButton = {
                                    TextButton(onClick = { vm.unpair(s.id); showUnpairDialog = false }) {
                                        Text(S.delete, color = OojooTheme.Red, fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showUnpairDialog = false }) { Text(S.cancel) }
                                }
                            )
                        }
                        if (showIconDialog) {
                            IconPickerDialog(target = s, onDismiss = { showIconDialog = false })
                        }
                    }
                }
            }
            item { vm.msg?.let { Text(localizeMasterMessage(it, S), fontSize = 13.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
            item { TextButton(onClick = { vm.refresh() }) { Text(S.refresh, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
        }
    }

    vm.policyTarget?.let { target ->
        MonitoringPolicyDialog(vm = vm, target = target)
    }
    vm.renameTarget?.let { target ->
        RenameDialog(vm = vm, target = target)
    }
    vm.commandsTarget?.let { target ->
        CommandsDialog(vm = vm, target = target)
    }
}

@Composable
private fun MonitoringPolicyDialog(vm: FarmerListViewModel, target: Slave) {
    val S = LocalAppStrings.current
    val p = vm.policy
    var waterAuto by remember(p) { mutableStateOf((p?.water_auto ?: 1) == 1) }
    var fanAuto by remember(p) { mutableStateOf((p?.fan_auto ?: 1) == 1) }
    var laserApproval by remember(p) { mutableStateOf((p?.laser_approval ?: 1) == 1) }
    var captureText by remember(p) { mutableStateOf(p?.capture_interval?.toString() ?: "60") }
    var pestText by remember(p) { mutableStateOf(p?.pest_interval?.toString() ?: "15") }
    var roiText by remember(p) { mutableStateOf(p?.roi_interval?.toString() ?: "20") }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line)
    AlertDialog(
        onDismissRequest = { if (!vm.policySaving) vm.closePolicy() },
        title = { Text(stringResource(R.string.monitoring_settings), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${farmerIconFor(target.id)} '${target.name}'", fontSize = 13.sp, color = OojooTheme.Muted, fontWeight = FontWeight.Bold)
                if (vm.policyLoading || p == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OojooTheme.Green) }
                } else {
                    SwitchRow(stringResource(R.string.policy_water_auto), waterAuto) { waterAuto = it }
                    SwitchRow(stringResource(R.string.policy_fan_auto), fanAuto) { fanAuto = it }
                    SwitchRow(stringResource(R.string.policy_laser_approval), laserApproval) { laserApproval = it }
                    OutlinedTextField(
                        value = captureText,
                        onValueChange = { v -> captureText = v.filter { it.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.policy_capture_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = OojooTheme.FieldShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.policy_capture_range), fontSize = 11.sp, color = OojooTheme.Muted)
                    OutlinedTextField(
                        value = pestText,
                        onValueChange = { v -> pestText = v.filter { it.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.policy_pest_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = OojooTheme.FieldShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.policy_pest_range), fontSize = 11.sp, color = OojooTheme.Muted)
                    OutlinedTextField(
                        value = roiText,
                        onValueChange = { v -> roiText = v.filter { it.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.roi_interval_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = OojooTheme.FieldShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.roi_interval_range), fontSize = 11.sp, color = OojooTheme.Muted)
                }
            }
        },
        confirmButton = {
            val cap = captureText.toIntOrNull()
            val pest = pestText.toIntOrNull()
            val roi = roiText.toIntOrNull()
            TextButton(
                onClick = {
                    if (cap != null && pest != null && roi != null) {
                        vm.savePolicy(waterAuto, fanAuto, laserApproval, cap, pest, roi)
                    }
                },
                enabled = !vm.policySaving && p != null && cap != null && pest != null && roi != null
            ) { Text(S.save, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = { vm.closePolicy() }, enabled = !vm.policySaving) { Text(S.cancel) }
        }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = OojooTheme.Green, checkedThumbColor = Color.White))
    }
}

@Composable
private fun RenameDialog(vm: FarmerListViewModel, target: Slave) {
    val S = LocalAppStrings.current
    var name by remember(target.id) { mutableStateOf(target.name) }
    AlertDialog(
        onDismissRequest = { if (!vm.renameSaving) vm.closeRename() },
        title = { Text(stringResource(R.string.rename_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v -> name = v.take(50) },
                    label = { Text(stringResource(R.string.rename_label)) },
                    placeholder = { Text(stringResource(R.string.rename_hint)) },
                    singleLine = true,
                    shape = OojooTheme.FieldShape,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.rename(name) }, enabled = !vm.renameSaving && name.isNotBlank()) {
                Text(S.save, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.closeRename() }, enabled = !vm.renameSaving) { Text(S.cancel) }
        }
    )
}

@Composable
private fun CommandsDialog(vm: FarmerListViewModel, target: Slave) {
    val S = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = { vm.closeCommands() },
        title = { Text(stringResource(R.string.commands_title), fontWeight = FontWeight.Bold) },
        text = {
            if (vm.commandsLoading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OojooTheme.Green) }
            } else if (vm.commands.isEmpty()) {
                Text(stringResource(R.string.commands_empty), fontSize = 13.sp, color = OojooTheme.Muted)
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.commands.forEach { c ->
                        val status = when (c.status) {
                            "queued" -> stringResource(R.string.status_queued)
                            "done" -> stringResource(R.string.status_done)
                            "cancelled" -> stringResource(R.string.status_cancelled)
                            else -> c.status
                        }
                        val color = when (c.status) {
                            "queued" -> OojooTheme.Orange
                            "done" -> OojooTheme.Teal
                            else -> OojooTheme.Muted
                        }
                        Row(Modifier.fillMaxWidth().clip(OojooTheme.PillShape).background(OojooTheme.GreenBg).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(c.action, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
                                val extra = if (c.action == "water") " \u00B7 ${c.amount_ml}ml" else ""
                                Text((c.created_at ?: "") + extra, fontSize = 11.sp, color = OojooTheme.Muted)
                            }
                            Text(status, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = color)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.closeCommands() }) { Text(S.close, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) }
        }
    )
}

/**
 * farmer 아이콘 선택 다이얼로그 — 팔레트 8종 중 하나를 눌러 선택.
 * 선택 즉시 FarmerIconStore(SnapshotState)에 반영되어 농장 씬 figure,
 * 식물카드 farmer pill, 사진첩 헤더 등 모든 화면이 갱신된다.
 * "자동 지정"을 누르면 선택을 해제해 ID 해시 기준 기본 아이콘으로 되돌린다.
 */
@Composable
private fun IconPickerDialog(target: Slave, onDismiss: () -> Unit) {
    val S = LocalAppStrings.current
    val ctx = LocalContext.current
    val current = FarmerIconStore.icon(ctx, target.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${S.iconChangeTitle} — '${target.name}'", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S.iconChangeHint, fontSize = 12.sp, color = OojooTheme.Muted)
                // 4열 아이콘 그리드 — 선택 항목은 녹색 강조
                val cols = 4
                val rows = (FarmerIconPalette.size + cols - 1) / cols
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0 until cols) {
                            val idx = row * cols + col
                            if (idx < FarmerIconPalette.size) {
                                val icon = FarmerIconPalette[idx]
                                val selected = (icon == current)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) OojooTheme.GreenBg else OojooTheme.Card,
                                    border = BorderStroke(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) OojooTheme.Green else OojooTheme.Line
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            FarmerIconStore.select(ctx, target.id, icon)
                                            onDismiss()
                                        }
                                ) {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                        Text(icon, fontSize = 26.sp)
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                FarmerIconStore.reset(ctx, target.id)
                onDismiss()
            }) { Text(S.iconAuto, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        }
    )
}
