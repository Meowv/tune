package com.meowv.tune.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TuneColors {
    val Background = Color(0xFF0B1020)
    val Surface = Color(0xFF151E33)
    val Raised = Color(0xFF202A43)
    val Primary = Color(0xFFB39AFF)
    val PrimaryLight = Color(0xFFDDCEFF)
    val Accent = Color(0xFF9775FA)
    val Text = Color(0xFFF5F6FF)
    val Muted = Color(0xFFABB5CA)
    val IconIdle = Color(0xFFB0BAD0)
    val Border = Color(0xFF34415E)
    val BorderSoft = Color(0xFF2A3550)
    val Divider = Color(0xFF253049)
    val Selected = Color(0xFF222844)
    val Segment = Primary.copy(alpha = .18f)
    val NavSelected = Color(0xFF272346)
    val Player = Color(0xFF182138)
    val Error = Color(0xFFF87171)

    /** 禁用态按 Material 的 38% 内容不透明度计算，不另造灰色。 */
    const val DisabledAlpha = .38f
}

internal object TuneBrushes {
    val PhonePlayer = Brush.linearGradient(listOf(Color(0xFF312E53), Color(0xFF1C253E)))
    val CarPlayer = Brush.horizontalGradient(listOf(Color(0xFF1D2742), Color(0xFF172037)))
    val Rail = Brush.verticalGradient(listOf(Color(0xFF19223B), Color(0xFF141E34)))
    val Card = Brush.verticalGradient(listOf(TuneColors.Raised, TuneColors.Surface))
    val Dialog = Brush.verticalGradient(listOf(Color(0xFF202D4B), Color(0xFF18243C)))
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
    primaryContainer = TuneColors.Selected,
    onPrimaryContainer = TuneColors.Text,
    outline = TuneColors.Border,
    outlineVariant = TuneColors.Divider,
    error = TuneColors.Error
)

@Composable
fun TuneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = TuneTypography.Material, content = content)
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
