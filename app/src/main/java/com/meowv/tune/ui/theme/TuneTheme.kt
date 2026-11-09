package com.meowv.tune.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

object TuneColors {
    val Background = Color(0xFF0B1020)
    val Surface = Color(0xFF141B30)
    val Raised = Color(0xFF1B2540)
    val Primary = Color(0xFFA78BFA)
    val Accent = Color(0xFF8B5CF6)
    val Text = Color(0xFFF4F5FB)
    val Muted = Color(0xFFA7B0C5)
    val Border = Color(0xFF2A3550)
    val Divider = Color(0xFF202A40)
    val Selected = Color(0xFF252442)
    val Player = Color(0xFF192137)
    val Live = Color(0xFF36D6AB)
    val Error = Color(0xFFF87171)
}

private val Colors = darkColorScheme(
    primary = TuneColors.Primary,
    onPrimary = TuneColors.Background,
    secondary = TuneColors.Accent,
    background = TuneColors.Background,
    onBackground = TuneColors.Text,
    surface = TuneColors.Surface,
    onSurface = TuneColors.Text,
    surfaceVariant = TuneColors.Raised,
    onSurfaceVariant = TuneColors.Muted,
    outline = TuneColors.Border
)

@Composable
fun TuneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(),
        content = content
    )
}

object TuneSpacing {
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val Section = 24.dp
}

object TuneShapes {
    val Control = RoundedCornerShape(12.dp)
    val Station = RoundedCornerShape(18.dp)
    val Input = RoundedCornerShape(15.dp)
    val Navigation = RoundedCornerShape(16.dp)
    val Chip = RoundedCornerShape(18.dp)
    val Card = RoundedCornerShape(24.dp)
    val SettingsGroup = RoundedCornerShape(17.dp)
    val Dialog = RoundedCornerShape(20.dp)
}

val TuneTextStyle = TextStyle(fontFamily = FontFamily.SansSerif,
    platformStyle = PlatformTextStyle(includeFontPadding = false))
