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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.AppLocale
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.network.ApiClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(nav: NavController, uiState: MutableState<OojooUiState>) {
    val ctx = LocalContext.current
    val S = LocalAppStrings.current
    val scrollState = rememberScrollState()
    var cornerRadius by remember { mutableStateOf(uiState.value.cornerRadius.toFloat()) }
    var shadowOffset by remember { mutableStateOf(uiState.value.shadowOffset.toFloat()) }
    var borderWidth by remember { mutableStateOf(uiState.value.borderWidth.toFloat()) }
    var selectedLang by remember { mutableStateOf(Prefs.language(ctx)) }
    var serverUrl by remember { mutableStateOf(Prefs.serverUrl(ctx)) }
    var serverMsg by remember { mutableStateOf<String?>(null) }

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
                    AppLocale.SYSTEM to S.languageSystem,
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

                // === 서버 설정 ===
                Spacer(Modifier.height(20.dp))
                Text(S.serverSection, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OojooTheme.Ink)
                OojooField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = "http://10.0.2.2:4000/"
                )
                Spacer(Modifier.height(8.dp))
                GradientButton(
                    text = S.serverApply,
                    onClick = {
                        val normalized = serverUrl.trim().ifBlank { Prefs.serverUrl(ctx) }
                        Prefs.setServerUrl(ctx, normalized)
                        ApiClient.setBaseUrl(normalized)
                        serverMsg = S.serverApplied + normalized + S.serverAppliedSuffix
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                serverMsg?.let {
                    Text(it, fontSize = 12.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
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
