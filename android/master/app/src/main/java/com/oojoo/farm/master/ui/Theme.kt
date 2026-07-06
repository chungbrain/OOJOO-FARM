package com.oojoo.farm.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 프로토타입 app-emulator.html 의 디자인 토큰
object OojooTheme {
    // Master 그린
    val Green = Color(0xFF2E7D32)
    val GreenDark = Color(0xFF1B5E20)
    val GreenLight = Color(0xFFA5D6A7)
    val GreenBg = Color(0xFFF1F8E9)

    // 액센트
    val Amber = Color(0xFFFFA000)
    val Red = Color(0xFFE53935)
    val Blue = Color(0xFF1E88E5)
    val Sky = Color(0xFF4FC3F7)

    // 중성
    val Ink = Color(0xFF1F2937)
    val Muted = Color(0xFF6B7280)
    val Line = Color(0xFFE5E7EB)
    val Bg = Color(0xFFF3F4F6)
    val Card = Color(0xFFFFFFFF)

    // 날씨 그라디언트
    val WeatherGradient = listOf(Color(0xFF4FC3F7), Color(0xFF1E88E5))
    val PrimaryGradient = listOf(Green, GreenDark)

    // 라운드
    val CardShape = RoundedCornerShape(16.dp)
    val PillShape = RoundedCornerShape(50)
    val BtnShape = RoundedCornerShape(13.dp)
    val FieldShape = RoundedCornerShape(12.dp)

    // 그림자
    val CardElevation = 6.dp
}

private val MasterColorScheme = lightColorScheme(
    primary = OojooTheme.Green,
    onPrimary = Color.White,
    primaryContainer = OojooTheme.GreenBg,
    onPrimaryContainer = OojooTheme.GreenDark,
    secondary = OojooTheme.GreenLight,
    background = OojooTheme.Bg,
    onBackground = OojooTheme.Ink,
    surface = OojooTheme.Card,
    onSurface = OojooTheme.Ink,
    surfaceVariant = OojooTheme.Bg,
    outline = OojooTheme.Line,
    error = OojooTheme.Red
)

@Composable
fun OojooMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MasterColorScheme,
        typography = Typography(
            titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Bold),
            headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 8.sp),
            labelMedium = Typography().labelMedium.copy(fontWeight = FontWeight.Bold),
            labelSmall = Typography().labelSmall.copy(fontWeight = FontWeight.Bold)
        ),
        content = content
    )
}

// 공용 컴포저블 — 프로토타입 .card .btn .weather 스타일
@Composable
fun OojooCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = Modifier
        .shadow(OojooTheme.CardElevation, OojooTheme.CardShape)
        .clip(OojooTheme.CardShape)
        .background(OojooTheme.Card)
    Card(
        modifier = if (onClick != null) (modifier then base).clip(OojooTheme.CardShape) else modifier then base,
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card),
        onClick = onClick ?: {},
        enabled = onClick != null
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: List<Color> = OojooTheme.PrimaryGradient
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = OojooTheme.BtnShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = OojooTheme.Line,
            disabledContentColor = OojooTheme.Muted
        ),
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp),
        modifier = modifier
            .clip(OojooTheme.BtnShape)
            .background(if (enabled) Brush.horizontalGradient(colors) else Brush.horizontalGradient(listOf(OojooTheme.Line, OojooTheme.Line)))
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OojooTheme.Green,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = OojooTheme.BtnShape,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp),
        modifier = modifier
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

fun Modifier.oojooShadow(): Modifier = this.shadow(OojooTheme.CardElevation, OojooTheme.CardShape)
