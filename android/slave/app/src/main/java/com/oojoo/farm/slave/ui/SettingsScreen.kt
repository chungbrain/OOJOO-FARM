package com.oojoo.farm.slave.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.slave.R
import com.oojoo.farm.slave.data.AppLocale
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.hardware.Hardware
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.network.ServerEndpointValidation
import com.oojoo.farm.slave.network.validateServerEndpoint
import com.oojoo.farm.slave.service.FarmerService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(Prefs.serverUrl(ctx)) }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    var serverError by remember { mutableStateOf(false) }
    var reconnecting by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf(Prefs.region(ctx)) }
    var captureInterval by remember { mutableStateOf(Prefs.captureIntervalMinutes(ctx).toString()) }
    var autoWater by remember { mutableStateOf(Prefs.autoWater(ctx)) }
    var selectedLanguage by remember { mutableStateOf(Prefs.language(ctx)) }
    var hwMsg by remember { mutableStateOf<String?>(null) }
    val bleConnectingText = stringResource(R.string.ble_connecting)
    val bluetoothPermissionText = stringResource(R.string.bluetooth_permission)

    val blePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) { Hardware.useBle(ctx); hwMsg = bleConnectingText }
        else hwMsg = bluetoothPermissionText
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.farmer_settings), color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Teal)) }, containerColor = OojooTheme.Bg) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.server_settings), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
            Text(stringResource(R.string.server_address_label), style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    serverMessage = null
                },
                placeholder = stringResource(R.string.server_url_hint),
            )
            GradientButton(
                text = stringResource(if (reconnecting) R.string.server_reconnecting else R.string.server_reconnect_action),
                onClick = {
                    when (val validation = validateServerEndpoint(serverUrl)) {
                        is ServerEndpointValidation.Invalid -> {
                            serverError = true
                            serverMessage = ctx.getString(R.string.server_invalid)
                        }
                        is ServerEndpointValidation.Valid -> scope.launch {
                            reconnecting = true
                            serverError = false
                            serverMessage = null
                            if (ApiClient.verifyBaseUrl(validation.normalizedUrl)) {
                                Prefs.setServerUrl(ctx, validation.normalizedUrl)
                                ApiClient.setBaseUrl(validation.normalizedUrl)
                                FarmerService.reconnect(ctx)
                                serverUrl = validation.normalizedUrl
                                serverMessage = ctx.getString(R.string.server_reconnect_success, validation.normalizedUrl)
                            } else {
                                serverError = true
                                serverMessage = ctx.getString(R.string.server_connection_failed)
                            }
                            reconnecting = false
                        }
                    }
                },
                enabled = !reconnecting,
                modifier = Modifier.fillMaxWidth(),
            )
            serverMessage?.let { message ->
                Text(
                    message,
                    color = if (serverError) OojooTheme.Red else OojooTheme.TealDark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                )
            }
            Text(stringResource(R.string.server_help), color = OojooTheme.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            HorizontalDivider(color = OojooTheme.Line)

            Text(stringResource(R.string.growing_region_label), style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(region, { region = it }, stringResource(R.string.growing_region_hint))
            Text(stringResource(R.string.capture_interval_label), style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(captureInterval, { captureInterval = it.filter { c -> c.isDigit() } }, stringResource(R.string.capture_interval_hint))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.auto_watering), Modifier.weight(1f), color = OojooTheme.Ink)
                Switch(checked = autoWater, onCheckedChange = { autoWater = it }, colors = SwitchDefaults.colors(checkedThumbColor = OojooTheme.Teal, checkedTrackColor = OojooTheme.TealLight))
            }
            HorizontalDivider(color = OojooTheme.Line)
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
            listOf(
                AppLocale.KOREAN to stringResource(R.string.language_korean),
                AppLocale.ENGLISH to stringResource(R.string.language_english)
            ).forEach { (code, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedLanguage == code,
                        onClick = {
                            selectedLanguage = code
                            AppLocale.setLanguage(ctx, code)
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = OojooTheme.Teal)
                    )
                    Text(label, color = OojooTheme.Ink)
                }
            }
            Text(stringResource(R.string.language_applied), color = OojooTheme.Muted, fontSize = 12.sp)
            HorizontalDivider(color = OojooTheme.Line)
            Text(stringResource(R.string.hardware_label), style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OutlineButton(text = stringResource(R.string.ble_connect), onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blePermLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                else blePermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }, modifier = Modifier.fillMaxWidth())
            hwMsg?.let { Text(it, color = OojooTheme.Muted, fontSize = 13.sp) }
            GradientButton(text = stringResource(R.string.save), onClick = {
                Prefs.setRegion(ctx, region.trim())
                Prefs.setCaptureIntervalMinutes(ctx, captureInterval.toIntOrNull() ?: 60)
                Prefs.setAutoWater(ctx, autoWater)
                nav.navigateUp()
            }, modifier = Modifier.fillMaxWidth())

            HorizontalDivider(color = OojooTheme.Line)

            // 재페어링 섹션
            Text(stringResource(R.string.master_connection), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
            val slaveId = Prefs.slaveId(ctx)
            if (slaveId != null) {
                Text(stringResource(R.string.current_slave, slaveId.take(8)), color = OojooTheme.Muted, fontSize = 13.sp)
                Text(stringResource(R.string.re_pairing_desc),
                    color = OojooTheme.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                var showReconfirm by remember { mutableStateOf(false) }
                GradientButton(
                    text = stringResource(R.string.re_pair),
                    onClick = { showReconfirm = true },
                    modifier = Modifier.fillMaxWidth()
                )
                if (showReconfirm) {
                    AlertDialog(
                        onDismissRequest = { showReconfirm = false },
                        title = { Text(stringResource(R.string.repairing_title), fontWeight = FontWeight.Bold) },
                        text = { Text(stringResource(R.string.repairing_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                FarmerService.stop(ctx)
                                Prefs.clearSession(ctx)
                                showReconfirm = false
                                nav.navigate("pairing") { popUpTo("dashboard") { inclusive = true } }
                            }) { Text(stringResource(R.string.repairing_title), color = OojooTheme.Red, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showReconfirm = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            } else {
                Text(stringResource(R.string.not_paired), color = OojooTheme.Muted, fontSize = 13.sp)
                GradientButton(
                    text = stringResource(R.string.pair_master),
                    onClick = { nav.navigate("pairing") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
