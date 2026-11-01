package com.temporizador.ciclico.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF0B0D0F)
    val Card = Color(0xFF1A1D21)
    val CardHigh = Color(0xFF262A2F)
    val Outline = Color(0xFF353A40)
    val Turquoise = Color(0xFF1FC8B8)
    val TurquoiseDark = Color(0xFF0E5F58)
    val OnTurquoise = Color(0xFF00201D)
    val Text = Color(0xFFF2F4F5)
    val TextSecondary = Color(0xFF9AA3AB)
    val Red = Color(0xFFEF5350)
    val Amber = Color(0xFFFFB74D)
    val Green = Color(0xFF66BB6A)
}

private val colors = darkColorScheme(
    primary = Palette.Turquoise,
    onPrimary = Palette.OnTurquoise,
    primaryContainer = Palette.TurquoiseDark,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.Turquoise,
    onSecondary = Palette.OnTurquoise,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Card,
    onSurface = Palette.Text,
    surfaceVariant = Palette.CardHigh,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainer = Palette.Card,
    surfaceContainerHigh = Palette.CardHigh,
    surfaceContainerHighest = Palette.CardHigh,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Red,
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, fontSize = 64.sp, letterSpacing = 0.sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, fontSize = 48.sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.5.sp),
)

/** Estilo de dígitos de cronómetro: ancho fijo para que no "bailen" al cambiar. */
val ClockStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
