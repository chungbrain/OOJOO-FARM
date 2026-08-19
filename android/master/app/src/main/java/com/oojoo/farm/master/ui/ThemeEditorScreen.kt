package com.oojoo.farm.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.AppLocale
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.network.ApiClient
import com.oojoo.farm.master.network.ServerEndpointValidation
import com.oojoo.farm.master.network.validateServerEndpoint
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(nav: NavController, uiState: MutableState<OojooUiState>) {
    val ctx = LocalContext.current
    val S = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var cornerRadius by remember { mutableStateOf(uiState.value.cornerRadius.toFloat()) }
    var shadowOffset by remember { mutableStateOf(uiState.value.shadowOffset.toFloat()) }
    var borderWidth by remember { mutableStateOf(uiState.value.borderWidth.toFloat()) }
    var selectedLang by remember { mutableStateOf(Prefs.language(ctx)) }
    var serverUrl by remember { mutableStateOf(Prefs.serverUrl(ctx)) }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    var serverError by remember { mutableStateOf(false) }
    var reconnecting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CartoonAppBar(
                title = S.uiCustomize,
                onBack = { nav.popBackStack() }
            )
        },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(p)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Live Preview Card
            OojooCard(modifier = Modifier.fillMaxWidth()) {
                Text(S.preview, fontWeight = FontWeight.Black, fontSize = 20.sp, color = OojooTheme.GreenDark)
                Spacer(Modifier.height(8.dp))
                Text(S.previewDesc, color = OojooTheme.Ink, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                GradientButton(text = S.applyComplete, onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth())
            }

            // Controls
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(S.uiDetailSettings, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OojooTheme.Ink)

                // Corner Radius Slider
                ControlSlider(
                    label = S.cornerRadiusLabel,
                    value = cornerRadius,
                    range = 0f..50f,
                    onValueChange = {
                        cornerRadius = it
                        uiState.value = uiState.value.copy(cornerRadius = it.toInt())
                        Prefs.setCornerRadius(ctx, it.toInt())
                    }
                )

                // Shadow Offset Slider
                ControlSlider(
                    label = S.shadowLabel,
                    value = shadowOffset,
                    range = 0f..16f,
                    onValueChange = {
                        shadowOffset = it
                        uiState.value = uiState.value.copy(shadowOffset = it.toInt())
                        Prefs.setShadowOffset(ctx, it.toInt())
                    }
                )

                // Border Width Slider
                ControlSlider(
                    label = S.borderWidthLabel,
                    value = borderWidth,
                    range = 0f..8f,
                    onValueChange = {
                        borderWidth = it
                        uiState.value = uiState.value.copy(borderWidth = it.toInt())
                        Prefs.setBorderWidth(ctx, it.toInt())
                    }
                )
                
                Spacer(Modifier.height(20.dp))
                
                OutlineButton(
                    text = S.reset,
                    onClick = {
                        cornerRadius = 24f
                        shadowOffset = 4f
                        borderWidth = 2f
                        uiState.value = OojooUiState()
                        Prefs.setCornerRadius(ctx, 24)
                        Prefs.setShadowOffset(ctx, 4)
                        Prefs.setBorderWidth(ctx, 2)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = OojooTheme.Red
                )

                // === 언어 설정 ===
                Spacer(Modifier.height(20.dp))
                Text(S.language, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OojooTheme.Ink)
                val langOptions = listOf(
                    AppLocale.KOREAN to S.languageKorean,
                    AppLocale.ENGLISH to S.languageEnglish
                )
                langOptions.forEach { (code, label) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedLang == code,
                            onClick = {
                                selectedLang = code
                                AppLocale.setLanguage(ctx, code)
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = OojooTheme.Green)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 15.sp, color = OojooTheme.Ink)
                    }
                }
                Text(
                    S.restartNotice,
                    fontSize = 12.sp, color = OojooTheme.Muted
                )

                HorizontalDivider(color = OojooTheme.Line)
                Text(stringResource(R.string.server_settings), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OojooTheme.Ink)
                Text(stringResource(R.string.server_address_label), fontSize = 13.sp, color = OojooTheme.Muted)
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
                        color = if (serverError) OojooTheme.Red else OojooTheme.GreenDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 18.sp,
                    )
                }
Text(stringResource(R.string.server_help), color = OojooTheme.Muted, fontSize = 12.sp, lineHeight = 18.sp)

                // === 계정 / 로그아웃 ===
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = OojooTheme.Line)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.account_label), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OojooTheme.Ink)
                OutlineButton(
                    text = stringResource(R.string.family_open),
                    onClick = { nav.navigate("family") },
                    modifier = Modifier.fillMaxWidth()
                )
                val currentEmail = Prefs.email(ctx)
                if (!currentEmail.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.auth_logged_in_as, currentEmail),
                        fontSize = 13.sp, color = OojooTheme.Muted, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlineButton(
                        text = stringResource(R.string.auth_logout),
                        onClick = {
                            Prefs.clearAccount(ctx)
                            Session.clear()
                            nav.navigate("auth") { popUpTo(0) { inclusive = true } }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = OojooTheme.Red
                    )
                }
            }
        }
    }
}

@Composable
fun ControlSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Bold, color = OojooTheme.Ink, fontSize = 14.sp)
            Text("${value.toInt()}", fontWeight = FontWeight.Black, color = OojooTheme.GreenDark)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = OojooTheme.Green,
                activeTrackColor = OojooTheme.GreenLight,
                inactiveTrackColor = OojooTheme.Line
            )
        )
    }
}
