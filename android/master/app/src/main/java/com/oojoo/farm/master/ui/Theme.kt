package com.oojoo.farm.master.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

// 카툰 만화 디자인 토큰 — app-emulator_v2.html Cartoon Edition
object OojooTheme {
    // Master — Bright Cartoon Green
    val Green = Color(0xFF4CAF50)
    val GreenDark = Color(0xFF2E7D32)
    val GreenLight = Color(0xFFA5D6A7)
    val GreenBg = Color(0xFFE8F5E9)
    val Lime = Color(0xFFC6FF00)

    // 액센트 — Vibrant Cartoon Tones
    val Amber = Color(0xFFFFC107)
    val Orange = Color(0xFFFF6F00)
    val Red = Color(0xFFFF5252)
    val Blue = Color(0xFF42A5F5)
    val Sky = Color(0xFF4FC3F7)
    val Purple = Color(0xFFAB47BC)
    val Pink = Color(0xFFFF80AB)

    // 중성 — Warm Cartoon Ink
    val Ink = Color(0xFF2D3436)
    val Ink2 = Color(0xFF4A4A4A)
    val Muted = Color(0xFF7C7C7C)
    val Muted2 = Color(0xFFA0A0A0)
    val Line = Color(0xFFE0E0E0)
    val Line2 = Color(0xFFF0F0F0)
    val Bg = Color(0xFFFFF8E1)   // Warm cream background
    val Card = Color(0xFFFFFFFF)
    val Yellow = Color(0xFFFFFDE7) // Sticky note yellow

    // 그라디언트
    val WeatherGradient = listOf(Color(0xFF4FC3F7), Color(0xFF1976D2))
    val PrimaryGradient = listOf(Color(0xFF66BB6A), GreenDark)
    val SunGradient = listOf(Color(0xFFFFD54F), Color(0xFFFF9800))

    // 라운드 — Extra round for cartoon
    val CardShape = RoundedCornerShape(24.dp)
    val PillShape = RoundedCornerShape(50)
    val BtnShape = RoundedCornerShape(18.dp)
    val FieldShape = RoundedCornerShape(18.dp)
    val SmallShape = RoundedCornerShape(12.dp)

    // 카툰 보더 — Thick black outlines
    val Border = BorderStroke(2.dp, Ink)
    val BorderThin = BorderStroke(2.dp, Ink)

    // 카툰 그림자 — Hard offset (no blur)
    val ShadowOffset = 4.dp
    val ShadowOffsetSm = 2.dp
    val ShadowOffsetLg = 6.dp
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
    outline = OojooTheme.Ink,
    error = OojooTheme.Red
)

@Composable
fun OojooMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MasterColorScheme,
        typography = Typography(
            titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Black),
            titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Bold),
            headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 8.sp),
            labelMedium = Typography().labelMedium.copy(fontWeight = FontWeight.Bold),
            labelSmall = Typography().labelSmall.copy(fontWeight = FontWeight.Bold)
        ),
        content = content
    )
}

// 카툰 카드 — thick black border + hard offset shadow
@Composable
fun OojooCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .clip(OojooTheme.CardShape)
            .border(OojooTheme.BorderThin, OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card),
        onClick = onClick ?: {},
        enabled = onClick != null,
        border = OojooTheme.BorderThin
    ) { Column(Modifier.padding(16.dp), content = content) }
}

// 카툰 버튼 — 3D press effect with colored bottom shadow
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
        contentPadding = PaddingValues(vertical = 15.dp, horizontal = 18.dp),
        border = OojooTheme.BorderThin,
        modifier = modifier
            .shadow(OojooTheme.ShadowOffset, OojooTheme.BtnShape)
            .clip(OojooTheme.BtnShape)
            .border(OojooTheme.BorderThin, OojooTheme.BtnShape)
            .background(if (enabled) Brush.horizontalGradient(colors) else Brush.horizontalGradient(listOf(OojooTheme.Line, OojooTheme.Line)))
    ) { Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
}

// 카툰 아웃라인 버튼 — white bg, colored border + press shadow
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = OojooTheme.GreenDark,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = OojooTheme.BtnShape,
        border = OojooTheme.BorderThin,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = OojooTheme.Card, contentColor = color),
        contentPadding = PaddingValues(vertical = 15.dp, horizontal = 18.dp),
        modifier = modifier.shadow(OojooTheme.ShadowOffsetSm, OojooTheme.BtnShape)
    ) { Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
}

// 카툰 필드 — thick border + bottom shadow
@Composable
fun OojooField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = OojooTheme.Muted, fontWeight = FontWeight.Bold) },
        singleLine = singleLine,
        shape = OojooTheme.FieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = OojooTheme.Ink,
            unfocusedBorderColor = OojooTheme.Ink,
            focusedContainerColor = OojooTheme.Card,
            unfocusedContainerColor = OojooTheme.Card
        ),
        modifier = modifier.fillMaxWidth()
    )
}

// 카툰 칩 — pill with thick border
@Composable
fun OojooChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = OojooTheme.PillShape,
        color = if (selected) OojooTheme.Green else OojooTheme.Card,
        border = OojooTheme.BorderThin,
        modifier = modifier.clip(OojooTheme.PillShape).border(OojooTheme.BorderThin, OojooTheme.PillShape)
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (selected) Color.White else OojooTheme.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

fun Modifier.oojooShadow(): Modifier = this
    .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
    .border(OojooTheme.BorderThin, OojooTheme.CardShape)
