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

internal const val DialogDimAmount = .46f

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
    val card: Brush
)

internal val DarkTunePalette = TunePalette(
    isDark = true,
    background = Color(0xFF0D1221),
    surface = Color(0xFF151C2E),
    raised = Color(0xFF1D263B),
    primary = Color(0xFFB9A3FA),
    primaryLight = Color(0xFFE0D5FF),
    accent = Color(0xFF9474E8),
    text = Color(0xFFF5F6FF),
    muted = Color(0xFFABB5CA),
    iconIdle = Color(0xFFB0BAD0),
    // BorderSoft=装饰/禁用/轨道，Border=容器，BorderStrong=可交互控件。
    border = Color(0xFF344059),
    borderSoft = Color(0xFF2A3550),
    borderStrong = Color(0xFF5A6890),
    divider = Color(0xFF242E42),
    selected = Color(0xFF24263C),
    navSelected = Color(0xFF30284C),
    player = Color(0xFF182138),
    error = Color(0xFFF87171),
    onPrimary = Color(0xFF0D1221),
    inversePrimary = Color(0xFF6448B1),
    disabledContent = Color(0xFFABB5CA).copy(alpha = DisabledContentAlpha),
    disabledContainer = Color(0xFF1D263B),
    focusOnAccent = Color(0xFF0D1221),
    optionSurface = Color(0xFF151C2E),
    pressedOverlay = Color(0xFFB9A3FA).copy(alpha = .23f),
    focusOverlay = Color(0xFFB9A3FA).copy(alpha = .10f),
    trackOff = Color(0xFF1D263B),
    scrim = Color(0xFF0D1221).copy(alpha = DialogDimAmount),
    shadowColor = Color.Transparent,
    phonePlayer = Brush.linearGradient(listOf(Color(0xFF312E53), Color(0xFF1C253E))),
    carPlayer = Brush.horizontalGradient(listOf(Color(0xFF1D2742), Color(0xFF172037))),
    card = Brush.verticalGradient(listOf(Color(0xFF1D263B), Color(0xFF151C2E)))
)

internal val LightTunePalette = TunePalette(
    isDark = false,
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFEDF0F7),
    primary = Color(0xFF6448B1),
    // 浅色下 PrimaryLight 是「强调前景」，比 Primary 更深，保证在浅色选中底上可读。
    primaryLight = Color(0xFF50358D),
    accent = Color(0xFF8665D7),
    text = Color(0xFF1B2133),
    muted = Color(0xFF5C6680),
    iconIdle = Color(0xFF5C6680),
    // BorderSoft=装饰/禁用/轨道，Border=容器，BorderStrong=可交互控件。
    border = Color(0xFFDCE1EB),
    borderSoft = Color(0xFFE2E5EE),
    borderStrong = Color(0xFF818EA8),
    divider = Color(0xFFE2E5EE),
    selected = Color(0xFFEDE7FA),
    navSelected = Color(0xFFE8DFF9),
    player = Color(0xFFFFFFFF),
    error = Color(0xFFB82F29),
    onPrimary = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFE0D5FF),
    disabledContent = Color(0xFF8C96AC),
    disabledContainer = Color(0xFFEDF0F7),
    focusOnAccent = Color(0xFFFFFFFF),
    optionSurface = Color(0xFFF0F2F9),
    pressedOverlay = Color(0xFF6448B1).copy(alpha = .18f),
    focusOverlay = Color(0xFF6448B1).copy(alpha = .12f),
    trackOff = Color(0xFFDCE1EB),
    scrim = Color(0xFF1B2133).copy(alpha = DialogDimAmount),
    shadowColor = Color(0x1A000000),
    phonePlayer = Brush.linearGradient(listOf(Color(0xFFF1EBFF), Color(0xFFE9EFFB))),
    carPlayer = Brush.horizontalGradient(listOf(Color(0xFFF4F1FD), Color(0xFFEAF0FA))),
    card = Brush.verticalGradient(listOf(Color(0xFFEDF0F7), Color(0xFFFFFFFF)))
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
    /** 容器边界：分组卡、内容卡、弹窗。 */
    val Border: Color @Composable get() = LocalTunePalette.current.border
    /** 装饰/禁用/进度轨道：drag handle、分隔线、禁用开关轮廓。 */
    val BorderSoft: Color @Composable get() = LocalTunePalette.current.borderSoft
    /** 可交互控件边界：输入框、选项、按钮、焦点前描边。 */
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
    val Card: Brush @Composable get() = LocalTunePalette.current.card
}

internal object TuneMotion {
    const val Press = 100
    const val ColorChange = 180
    const val Navigation = 220
    const val Card = 220
    const val Artwork = 180
    const val Lyrics = 200
    const val Fullscreen = 280
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
        secondary = palette.primary,
        onSecondary = palette.onPrimary,
        secondaryContainer = palette.selected,
        onSecondaryContainer = palette.text,
        tertiary = palette.primaryLight,
        onTertiary = palette.onPrimary,
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceTint = palette.primary,
        surfaceDim = palette.background,
        surfaceBright = palette.raised,
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
        scrim = palette.background.copy(alpha = DialogDimAmount)
    )
} else {
    lightColorScheme(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        inversePrimary = palette.inversePrimary,
        primaryContainer = palette.selected,
        onPrimaryContainer = palette.text,
        secondary = palette.primary,
        onSecondary = palette.onPrimary,
        secondaryContainer = palette.selected,
        onSecondaryContainer = palette.text,
        tertiary = palette.primaryLight,
        onTertiary = palette.onPrimary,
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceTint = palette.primary,
        surfaceDim = palette.background,
        surfaceBright = palette.raised,
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
        scrim = palette.text.copy(alpha = DialogDimAmount)
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
    val Panel = 20.dp
    val Section = 24.dp
    val Wide = 32.dp
}

/**
 * 小状态、控件、普通容器和媒体卡片共用同一组圆角规格。
 */
object TuneShapes {
    val Status = RoundedCornerShape(8.dp)
    val Control = RoundedCornerShape(12.dp)
    val Station = RoundedCornerShape(12.dp)
    val Navigation = RoundedCornerShape(16.dp)
    val Chip = RoundedCornerShape(12.dp)
    val Dialog = RoundedCornerShape(24.dp)
    val SettingsGroup = RoundedCornerShape(18.dp)
    val Player = RoundedCornerShape(24.dp)
    val Card = RoundedCornerShape(24.dp)
}

val TuneTextStyle = TextStyle(fontFamily = FontFamily.SansSerif,
    platformStyle = PlatformTextStyle(includeFontPadding = false))

internal object TuneTypography {
    fun pageTitle(isCar: Boolean) = title(if (isCar) 24.sp else 20.sp)
    fun playbackTitle(isCar: Boolean) = title(if (isCar) 26.sp else 23.sp)
    fun rowTitle(isCar: Boolean) = text(if (isCar) 16.sp else 15.sp, FontWeight.Medium)
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
