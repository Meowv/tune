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
    // 列表播放键的未播放态：在次级灰里带一点紫，和纯文字灰区分开，又不抢当前台的品牌紫。
    val IconIdle = Color(0xFF9B94B6)
    val Border = Color(0xFF2A3550)
    // 常驻胶囊/分段控件的外描边比 Border 再弱一档，避免控件边缘比内容更抢眼。
    val BorderSoft = Color(0xFF232D46)
    val Divider = Color(0xFF202A40)
    val Selected = Color(0xFF252442)
    // 分段控件选中块：品牌紫 18% 叠在表面色上，比整块实色弱，但足够区分选中。
    val Segment = Color(0x2EA78BFA)
    // 导航是常驻控件，选中底色比列表/分段控件再弱一档，避免整屏出现多个同等强度的色块。
    val NavSelected = Color(0xFF2A2547)
    val Player = Color(0xFF192137)
    val Error = Color(0xFFF87171)

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
    val SettingsGroup = RoundedCornerShape(20.dp)
    val Player = RoundedCornerShape(24.dp)
    val Card = RoundedCornerShape(28.dp)
}

val TuneTextStyle = TextStyle(fontFamily = FontFamily.SansSerif,
    platformStyle = PlatformTextStyle(includeFontPadding = false))
