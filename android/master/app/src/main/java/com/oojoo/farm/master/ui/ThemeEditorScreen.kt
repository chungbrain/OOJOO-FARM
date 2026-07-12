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
import com.oojoo.farm.master.data.Prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(nav: NavController, uiState: MutableState<OojooUiState>) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()
    
    // Local state for sliders so they drag smoothly
    var cornerRadius by remember { mutableStateOf(uiState.value.cornerRadius.toFloat()) }
    var shadowOffset by remember { mutableStateOf(uiState.value.shadowOffset.toFloat()) }
    var borderWidth by remember { mutableStateOf(uiState.value.borderWidth.toFloat()) }

    Scaffold(
        topBar = {
            CartoonAppBar(
                title = "UI 커스터마이징",
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
                Text("미리보기", fontWeight = FontWeight.Black, fontSize = 20.sp, color = OojooTheme.GreenDark)
                Spacer(Modifier.height(8.dp))
                Text("아래 슬라이더를 조절하면 즉시 이 카드의 둥글기, 그림자 크기, 테두리 두께가 변합니다!", color = OojooTheme.Ink, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                GradientButton(text = "적용 완료", onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth())
            }

            // Controls
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("UI 상세 설정", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OojooTheme.Ink)
                
                // Corner Radius Slider
                ControlSlider(
                    label = "모서리 둥글기 (Radius)",
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
                    label = "그림자 크기 (Shadow)",
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
                    label = "테두리 두께 (Border)",
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
                    text = "기본값으로 초기화",
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
