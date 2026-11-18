package com.meowv.tune.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val LocalLightPalette = staticCompositionLocalOf { false }

object TuneColors {
    val Background @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFF8F7FC) else Color(0xFF0B1020)
    val Surface @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFFFFFFF) else Color(0xFF141B30)
    val Raised @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFEFEDF7) else Color(0xFF1B2540)
    val Primary @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFF6142A5) else Color(0xFFA78BFA)
    val PrimaryLight @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFF51358F) else Color(0xFFD7B7FF)
    val Accent @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFF6E53B4) else Color(0xFF8B5CF6)
    val Text @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFF191B2A) else Color(0xFFF4F5FB)
    val Muted @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFF54596B) else Color(0xFFA7B0C5)
    // 列表播放键的未播放态：在次级灰里带一点紫，和纯文字灰区分开，又不抢当前台的品牌紫。
    val IconIdle @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFF625675) else Color(0xFF9B94B6)
    val Border @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFF797089) else Color(0xFF2A3550)
    // 常驻胶囊/分段控件的外描边比 Border 再弱一档，避免控件边缘比内容更抢眼。
    val BorderSoft @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFB5AEC4) else Color(0xFF232D46)
    val Divider @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFDDD8E8) else Color(0xFF202A40)
    val Selected @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFEEE8FB) else Color(0xFF252442)
    // 分段控件选中块：品牌紫 18% 叠在表面色上，比整块实色弱，但足够区分选中。
    val Segment @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0x2E6142A5) else Color(0x2EA78BFA)
    // 导航是常驻控件，选中底色比列表/分段控件再弱一档，避免整屏出现多个同等强度的色块。
    val NavSelected @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFE8E0F4) else Color(0xFF2A2547)
    val Player @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFEFEDF7) else Color(0xFF192137)
    val Error @Composable @ReadOnlyComposable get() =
        if (LocalLightPalette.current) Color(0xFFAB2738) else Color(0xFFF87171)

    /** 禁用态按 Material 的 38% 内容不透明度计算，不另造灰色。 */
    const val DisabledAlpha = .38f
}

/**
 * 图标与热区的统一尺寸。同一层级只用一个值；车机横屏整体放大一档，
 * 但不复制手机尺寸，避免车机上出现两套互不相干的比例。
 */
object TuneSizes {
    val Touch = 48.dp
    val TouchCar = 56.dp
    val IconXs = 16.dp
    val IconSm = 20.dp
    val IconMd = 22.dp
    val IconLg = 24.dp
    val IconXl = 26.dp
}

@Composable
fun TuneTheme(light: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLightPalette provides light) {
        val base = if (light) lightColorScheme() else darkColorScheme()
        MaterialTheme(
            colorScheme = base.copy(
                primary = TuneColors.Primary,
                onPrimary = if (light) Color.White else TuneColors.Background,
                secondary = TuneColors.Accent,
                background = TuneColors.Background,
                onBackground = TuneColors.Text,
                surface = TuneColors.Surface,
                onSurface = TuneColors.Text,
                surfaceVariant = TuneColors.Raised,
                onSurfaceVariant = TuneColors.Muted,
                primaryContainer = TuneColors.Selected,
                onPrimaryContainer = TuneColors.Text,
                outline = TuneColors.Border,
                outlineVariant = TuneColors.Divider,
                error = TuneColors.Error
            ),
            typography = Typography(),
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
}

/**
 * 圆角层级（由小到大）：图标底 < 列表行/控件 < 导航容器 < 设置容器 < 播放器 < 大卡片。
 * 子元素圆角始终小于父容器，避免出现「内圆角比外圆角更圆」的倒挂。
 */
object TuneShapes {
    val Control = RoundedCornerShape(12.dp)
    val Station = RoundedCornerShape(18.dp)
    val Navigation = RoundedCornerShape(16.dp)
    val Chip = RoundedCornerShape(18.dp)
    val Dialog = RoundedCornerShape(20.dp)
    val SettingsGroup = RoundedCornerShape(22.dp)
    val Player = RoundedCornerShape(24.dp)
    val Card = RoundedCornerShape(28.dp)
}

val TuneTextStyle = TextStyle(fontFamily = FontFamily.SansSerif,
    platformStyle = PlatformTextStyle(includeFontPadding = false))
