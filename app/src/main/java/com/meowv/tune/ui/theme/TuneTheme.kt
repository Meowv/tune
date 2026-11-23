package com.meowv.tune.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.domain.ThemeMode

private const val DisabledContentAlpha = .38f

internal data class TunePalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val raised: Color,
    val primary: Color,
    val primaryLight: Color,
    val accent: Color,
    val text: Color,
    val muted: Color,
    val iconIdle: Color,
    val border: Color,
    val borderSoft: Color,
    val borderStrong: Color,
    val divider: Color,
    val selected: Color,
    val navSelected: Color,
    val player: Color,
    val error: Color,
    val onPrimary: Color,
    val inversePrimary: Color,
    val disabledContent: Color,
    val disabledContainer: Color,
    val focusOnAccent: Color,
    val optionSurface: Color,
    val pressedOverlay: Color,
    val focusOverlay: Color,
    val trackOff: Color,
    val scrim: Color,
    val shadowColor: Color,
    val phonePlayer: Brush,
    val carPlayer: Brush,
    val rail: Brush,
    val card: Brush,
    val dialog: Brush
)

internal val DarkTunePalette = TunePalette(
    isDark = true,
    background = Color(0xFF0B1020),
    surface = Color(0xFF151E33),
    raised = Color(0xFF202A43),
    primary = Color(0xFFB39AFF),
    primaryLight = Color(0xFFDDCEFF),
    accent = Color(0xFF9775FA),
    text = Color(0xFFF5F6FF),
    muted = Color(0xFFABB5CA),
    iconIdle = Color(0xFFB0BAD0),
    border = Color(0xFF34415E),
    borderSoft = Color(0xFF2A3550),
    borderStrong = Color(0xFF5A6890),
    divider = Color(0xFF253049),
    selected = Color(0xFF222844),
    navSelected = Color(0xFF272346),
    player = Color(0xFF182138),
    error = Color(0xFFF87171),
    onPrimary = Color(0xFF0B1020),
    inversePrimary = Color(0xFF5B3FA8),
    disabledContent = Color(0xFFABB5CA).copy(alpha = DisabledContentAlpha),
    disabledContainer = Color(0xFF202A43),
    focusOnAccent = Color(0xFF0B1020),
    optionSurface = Color(0xFF151E33),
    pressedOverlay = Color(0xFFB39AFF).copy(alpha = .23f),
    focusOverlay = Color(0xFFB39AFF).copy(alpha = .10f),
    trackOff = Color(0xFF202A43),
    scrim = Color(0xFF0B1020).copy(alpha = .72f),
    shadowColor = Color.Transparent,
    phonePlayer = Brush.linearGradient(listOf(Color(0xFF312E53), Color(0xFF1C253E))),
    carPlayer = Brush.horizontalGradient(listOf(Color(0xFF1D2742), Color(0xFF172037))),
    rail = Brush.verticalGradient(listOf(Color(0xFF19223B), Color(0xFF141E34))),
    card = Brush.verticalGradient(listOf(Color(0xFF202A43), Color(0xFF151E33))),
    dialog = Brush.verticalGradient(listOf(Color(0xFF202D4B), Color(0xFF18243C)))
)

internal val LightTunePalette = TunePalette(
    isDark = false,
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFF1F2F9),
    primary = Color(0xFF5B3FA8),
    // 浅色下 PrimaryLight 是「强调前景」，比 Primary 更深，保证在浅色选中底上可读。
    primaryLight = Color(0xFF4A2F8A),
    accent = Color(0xFF6D4AFF),
    text = Color(0xFF1B2133),
    muted = Color(0xFF5C6680),
    iconIdle = Color(0xFF5C6680),
    border = Color(0xFF818EA8),
    borderSoft = Color(0xFFBEC7D8),
    borderStrong = Color(0xFF818EA8),
    divider = Color(0xFFD2D9E6),
    selected = Color(0xFFE4DCF8),
    navSelected = Color(0xFFE4DCF8),
    player = Color(0xFFFFFFFF),
    error = Color(0xFFC5372F),
    onPrimary = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFDDCEFF),
    disabledContent = Color(0xFF8C96AC),
    disabledContainer = Color(0xFFEDF0F7),
    focusOnAccent = Color(0xFFFFFFFF),
    optionSurface = Color(0xFFF0F2F9),
    pressedOverlay = Color(0xFF5B3FA8).copy(alpha = .18f),
    focusOverlay = Color(0xFF5B3FA8).copy(alpha = .12f),
    trackOff = Color(0xFFBEC7D8),
    scrim = Color(0xFF1B2133).copy(alpha = .72f),
    shadowColor = Color(0x1A000000),
    phonePlayer = Brush.linearGradient(listOf(Color(0xFFF1EBFF), Color(0xFFE9EFFB))),
    carPlayer = Brush.horizontalGradient(listOf(Color(0xFFF4F1FD), Color(0xFFEAF0FA))),
    rail = Brush.verticalGradient(listOf(Color(0xFFF8F8FD), Color(0xFFF3F5FB))),
    card = Brush.verticalGradient(listOf(Color(0xFFF1F2F9), Color(0xFFFFFFFF))),
    dialog = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF4F5FB)))
)

internal val LocalTunePalette = compositionLocalOf { DarkTunePalette }
internal val LocalTuneIsDark = compositionLocalOf { true }

object TuneColors {
    val Background: Color @Composable get() = LocalTunePalette.current.background
    val Surface: Color @Composable get() = LocalTunePalette.current.surface
    val Raised: Color @Composable get() = LocalTunePalette.current.raised
    val Primary: Color @Composable get() = LocalTunePalette.current.primary
    val PrimaryLight: Color @Composable get() = LocalTunePalette.current.primaryLight
    val Accent: Color @Composable get() = LocalTunePalette.current.accent
    val Text: Color @Composable get() = LocalTunePalette.current.text
    val Muted: Color @Composable get() = LocalTunePalette.current.muted
    val IconIdle: Color @Composable get() = LocalTunePalette.current.iconIdle
    val Border: Color @Composable get() = LocalTunePalette.current.border
    val BorderSoft: Color @Composable get() = LocalTunePalette.current.borderSoft
    val BorderStrong: Color @Composable get() = LocalTunePalette.current.borderStrong
    val Divider: Color @Composable get() = LocalTunePalette.current.divider
    val Selected: Color @Composable get() = LocalTunePalette.current.selected
    val Segment: Color @Composable get() = LocalTunePalette.current.primary.copy(alpha = .18f)
    val NavSelected: Color @Composable get() = LocalTunePalette.current.navSelected
    val Player: Color @Composable get() = LocalTunePalette.current.player
    val Error: Color @Composable get() = LocalTunePalette.current.error
    val OnPrimary: Color @Composable get() = LocalTunePalette.current.onPrimary
    val DisabledContent: Color @Composable get() = LocalTunePalette.current.disabledContent
    val DisabledContainer: Color @Composable get() = LocalTunePalette.current.disabledContainer
    val FocusOnAccent: Color @Composable get() = LocalTunePalette.current.focusOnAccent
    val OptionSurface: Color @Composable get() = LocalTunePalette.current.optionSurface
    val PressedOverlay: Color @Composable get() = LocalTunePalette.current.pressedOverlay
    val FocusOverlay: Color @Composable get() = LocalTunePalette.current.focusOverlay
    val TrackOff: Color @Composable get() = LocalTunePalette.current.trackOff
    val Scrim: Color @Composable get() = LocalTunePalette.current.scrim
    val Shadow: Color @Composable get() = LocalTunePalette.current.shadowColor

    /** 深色沿用 Material 38% 规则；浅色用 DisabledContent 实色，常量只用于叠加透明度的场景。 */
    const val DisabledAlpha = DisabledContentAlpha
}

internal object TuneBrushes {
    val PhonePlayer: Brush @Composable get() = LocalTunePalette.current.phonePlayer
    val CarPlayer: Brush @Composable get() = LocalTunePalette.current.carPlayer
    val Rail: Brush @Composable get() = LocalTunePalette.current.rail
    val Card: Brush @Composable get() = LocalTunePalette.current.card
    val Dialog: Brush @Composable get() = LocalTunePalette.current.dialog
}

internal object TuneMotion {
    const val Press = 100
    const val ColorChange = 180
    const val Navigation = 260
}

/**
 * 图标与热区的统一尺寸。同一层级只用一个值；车机横屏整体放大一档，
 * 但不复制手机尺寸，避免车机上出现两套互不相干的比例。
 */
object TuneSizes {
    val Touch = 48.dp
    val TouchCar = 56.dp
    val IconTiny = 18.dp
    val IconXs = 16.dp
    val IconSm = 20.dp
    val IconMd = 22.dp
    val IconLg = 24.dp
    val IconXl = 26.dp
    val IconHero = 30.dp
}

private fun tuneColorScheme(palette: TunePalette, isDark: Boolean) = if (isDark) {
    darkColorScheme(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        inversePrimary = palette.inversePrimary,
        primaryContainer = palette.selected,
        onPrimaryContainer = palette.text,
        secondary = palette.accent,
        onSecondary = palette.onPrimary,
        secondaryContainer = palette.selected,
        onSecondaryContainer = palette.text,
        tertiary = palette.primaryLight,
        onTertiary = palette.onPrimary,
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceVariant = palette.raised,
        onSurfaceVariant = palette.muted,
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = palette.background,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.raised,
        surfaceContainerHighest = palette.raised,
        inverseSurface = palette.text,
        inverseOnSurface = palette.background,
        outline = palette.border,
        outlineVariant = palette.divider,
        error = palette.error,
        onError = palette.background,
        errorContainer = palette.selected,
        onErrorContainer = palette.text,
        scrim = palette.background.copy(alpha = .72f)
    )
} else {
    lightColorScheme(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        inversePrimary = palette.inversePrimary,
        primaryContainer = palette.selected,
        onPrimaryContainer = palette.text,
        secondary = palette.accent,
        onSecondary = palette.onPrimary,
        secondaryContainer = palette.selected,
        onSecondaryContainer = palette.text,
        tertiary = palette.primaryLight,
        onTertiary = palette.onPrimary,
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceVariant = palette.raised,
        onSurfaceVariant = palette.muted,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = palette.background,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.raised,
        surfaceContainerHighest = palette.raised,
        inverseSurface = palette.text,
        inverseOnSurface = Color.White,
        outline = palette.border,
        outlineVariant = palette.divider,
        error = palette.error,
        onError = Color.White,
        errorContainer = palette.selected,
        onErrorContainer = palette.text,
        scrim = palette.text.copy(alpha = .72f)
    )
}

@Composable
fun TuneTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (isDark) DarkTunePalette else LightTunePalette
    CompositionLocalProvider(
        LocalTunePalette provides palette,
        LocalTuneIsDark provides isDark
    ) {
        MaterialTheme(
            colorScheme = tuneColorScheme(palette, isDark),
            typography = TuneTypography.Material,
            content = content
        )
    }
}

object TuneSpacing {
    val Hairline = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val Section = 24.dp
    val Wide = 32.dp
}

/**
 * 圆角层级（由小到大）：图标底 < 列表行/控件 < 导航容器 < 设置容器 < 播放器 < 大卡片。
 * 子元素圆角始终小于父容器，避免出现「内圆角比外圆角更圆」的倒挂。
 */
object TuneShapes {
    val Control = RoundedCornerShape(12.dp)
    val Station = RoundedCornerShape(13.dp)
    val Navigation = RoundedCornerShape(16.dp)
    val Chip = RoundedCornerShape(15.dp)
    val Dialog = RoundedCornerShape(24.dp)
    val SettingsGroup = RoundedCornerShape(19.dp)
    val Player = RoundedCornerShape(24.dp)
    val Card = RoundedCornerShape(28.dp)
}

val TuneTextStyle = TextStyle(fontFamily = FontFamily.SansSerif,
    platformStyle = PlatformTextStyle(includeFontPadding = false))

internal object TuneTypography {
    fun pageTitle(isCar: Boolean) = title(if (isCar) 24.sp else 20.sp)
    fun playbackTitle(isCar: Boolean) = title(if (isCar) 26.sp else 22.sp)
    fun rowTitle(isCar: Boolean) = text(if (isCar) 16.sp else 14.sp, FontWeight.Medium)
    fun secondary(isCar: Boolean) = text(if (isCar) 13.sp else 12.sp)
    fun meta(isCar: Boolean) = text(if (isCar) 12.sp else 11.sp)
    fun control(isCar: Boolean) = text(if (isCar) 14.sp else 13.sp, FontWeight.Medium)

    val Material = Typography(
        titleLarge = pageTitle(false),
        titleMedium = rowTitle(false),
        titleSmall = control(false),
        bodyLarge = text(16.sp),
        bodyMedium = text(14.sp),
        bodySmall = secondary(false),
        labelLarge = control(false),
        labelMedium = secondary(false),
        labelSmall = meta(false)
    )

    private fun title(size: TextUnit) = text(size, FontWeight.SemiBold)

    private fun text(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
        TuneTextStyle.copy(fontSize = size, lineHeight = size * 1.4f, fontWeight = weight)
}
