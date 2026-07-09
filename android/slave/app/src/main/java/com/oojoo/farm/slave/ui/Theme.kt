package com.oojoo.farm.slave.ui

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

object OojooTheme {
    val Teal = Color(0xFF26A69A)
    val TealDark = Color(0xFF00695C)
    val TealLight = Color(0xFF80CBC4)
    val TealBg = Color(0xFFE0F2F1)
    val Amber = Color(0xFFFFC107)
    val Red = Color(0xFFFF5252)
    val Ink = Color(0xFF2D3436)
    val Muted = Color(0xFF7C7C7C)
    val Muted2 = Color(0xFFA0A0A0)
    val Line = Color(0xFFE0E0E0)
    val Line2 = Color(0xFFF0F0F0)
    val Bg = Color(0xFFFFF8E1)
    val Card = Color(0xFFFFFFFF)
    val PrimaryGradient = listOf(Color(0xFF26A69A), Color(0xFF004D40))
    val CamGradient = listOf(Color(0xFF81C784), Color(0xFF2E7D32))
    val CardShape = RoundedCornerShape(24.dp)
    val BtnShape = RoundedCornerShape(18.dp)
    val FieldShape = RoundedCornerShape(18.dp)
    val PillShape = RoundedCornerShape(50)
    val Border = BorderStroke(2.dp, Ink)
    val ShadowOffset = 4.dp
    val ShadowOffsetSm = 2.dp
}

private val SlaveColorScheme = lightColorScheme(
    primary = OojooTheme.Teal,
    onPrimary = Color.White,
    primaryContainer = OojooTheme.TealBg,
    onPrimaryContainer = OojooTheme.TealDark,
    background = OojooTheme.Bg,
    onBackground = OojooTheme.Ink,
    surface = OojooTheme.Card,
    onSurface = OojooTheme.Ink,
    outline = OojooTheme.Ink,
    error = OojooTheme.Red
)

@Composable
fun OojooSlaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlaveColorScheme,
        typography = Typography(
            titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Black),
            titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Bold)
        ),
        content = content
    )
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: List<Color> = OojooTheme.PrimaryGradient) {
    Button(
        onClick = onClick, enabled = enabled, shape = OojooTheme.BtnShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White, disabledContainerColor = OojooTheme.Line, disabledContentColor = OojooTheme.Muted),
        contentPadding = PaddingValues(vertical = 15.dp, horizontal = 18.dp),
        border = OojooTheme.Border,
        modifier = modifier.shadow(OojooTheme.ShadowOffset, OojooTheme.BtnShape).clip(OojooTheme.BtnShape).border(2.dp, OojooTheme.Ink, OojooTheme.BtnShape).background(if (enabled) Brush.horizontalGradient(colors) else Brush.horizontalGradient(listOf(OojooTheme.Line, OojooTheme.Line)))
    ) { Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = OojooTheme.TealDark, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = OojooTheme.BtnShape, border = OojooTheme.Border, colors = ButtonDefaults.outlinedButtonColors(containerColor = OojooTheme.Card, contentColor = color), contentPadding = PaddingValues(vertical = 15.dp, horizontal = 18.dp), modifier = modifier.shadow(OojooTheme.ShadowOffsetSm, OojooTheme.BtnShape)) { Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
}

@Composable
fun OojooField(value: String, onValueChange: (String) -> Unit, placeholder: String, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, placeholder = { Text(placeholder, color = OojooTheme.Muted, fontWeight = FontWeight.Bold) }, singleLine = singleLine,
        shape = OojooTheme.FieldShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Ink, unfocusedBorderColor = OojooTheme.Ink, focusedContainerColor = OojooTheme.Card, unfocusedContainerColor = OojooTheme.Card),
        modifier = Modifier.fillMaxWidth()
    )
}
