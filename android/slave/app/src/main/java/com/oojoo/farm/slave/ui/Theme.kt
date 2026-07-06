package com.oojoo.farm.slave.ui

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

object OojooTheme {
    val Teal = Color(0xFF00897B)
    val TealDark = Color(0xFF00695C)
    val TealLight = Color(0xFF80CBC4)
    val TealBg = Color(0xFFE0F2F1)
    val Amber = Color(0xFFFFA000)
    val Red = Color(0xFFE53935)
    val Ink = Color(0xFF1F2937)
    val Muted = Color(0xFF6B7280)
    val Line = Color(0xFFE5E7EB)
    val Bg = Color(0xFFE0F2F1)
    val Card = Color(0xFFFFFFFF)
    val PrimaryGradient = listOf(Teal, TealDark)
    val CamGradient = listOf(Color(0xFF66BB6A), Color(0xFF2E7D32))
    val CardShape = RoundedCornerShape(16.dp)
    val BtnShape = RoundedCornerShape(13.dp)
    val FieldShape = RoundedCornerShape(12.dp)
    val CardElevation = 6.dp
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
    outline = OojooTheme.Line,
    error = OojooTheme.Red
)

@Composable
fun OojooSlaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlaveColorScheme,
        typography = Typography(
            titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Bold)
        ),
        content = content
    )
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
        onClick = onClick, enabled = enabled, shape = OojooTheme.BtnShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White, disabledContainerColor = OojooTheme.Line, disabledContentColor = OojooTheme.Muted),
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp),
        modifier = modifier.clip(OojooTheme.BtnShape).background(if (enabled) Brush.horizontalGradient(colors) else Brush.horizontalGradient(listOf(OojooTheme.Line, OojooTheme.Line)))
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = OojooTheme.Teal, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = OojooTheme.BtnShape, border = androidx.compose.foundation.BorderStroke(1.5.dp, color), colors = ButtonDefaults.outlinedButtonColors(contentColor = color), contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp), modifier = modifier) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun OojooField(value: String, onValueChange: (String) -> Unit, placeholder: String, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, placeholder = { Text(placeholder, color = OojooTheme.Muted) }, singleLine = singleLine,
        shape = OojooTheme.FieldShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Teal, unfocusedBorderColor = OojooTheme.Line, focusedContainerColor = OojooTheme.Card, unfocusedContainerColor = OojooTheme.Card),
        modifier = Modifier.fillMaxWidth()
    )
}
