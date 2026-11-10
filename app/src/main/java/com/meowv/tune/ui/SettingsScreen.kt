package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun SettingsScreen(
    isCar: Boolean,
    settings: TuneSettings,
    refreshing: Boolean,
    updateSubtitle: String,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onRefreshDirectory: () -> Unit,
    onClearDirectoryCache: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }
    LazyColumn(Modifier.fillMaxSize().testTag("page_settings"),
        contentPadding = PaddingValues(horizontal = if (isCar) 40.dp else 24.dp, vertical = if (isCar) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (isCar) 8.dp else 12.dp)) {
        item(key = "playback_settings") {
            SettingsGroup {
                SettingRow(R.drawable.ic_ui_autoplay, R.string.setting_autoplay, isCar, checked = settings.autoPlay) {
                    onSettingsChange { current -> current.copy(autoPlay = !current.autoPlay) }
                }
            }
        }
        item(key = "about_settings") {
            SettingsGroup {
                // 刷新期间行内转圈并禁用，重复点击不会叠加请求。
                SettingRow(
                    R.drawable.ic_ui_refresh, R.string.setting_refresh, isCar,
                    enabled = !refreshing,
                    trailing = if (refreshing) ({ InlineSpinner(isCar) }) else null,
                    onClick = onRefreshDirectory
                )
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_trash, R.string.setting_cache, isCar, onClick = onClearDirectoryCache)
                SettingsDivider()
                SettingRow(
                    R.drawable.ic_ui_database, R.string.setting_source, isCar,
                    trailing = { Chevron(isCar) },
                    onClick = { dialog = SettingsDialog.SOURCE }
                )
                SettingsDivider()
                SettingRow(
                    R.drawable.ic_ui_scroll, R.string.setting_licenses, isCar,
                    trailing = { Chevron(isCar) },
                    onClick = { dialog = SettingsDialog.LICENSES }
                )
                SettingsDivider()
                // 版本与更新：副标题跟随检查状态，入口只在设置页，不新增主菜单。
                SettingRow(
                    R.drawable.ic_ui_arrow_up_circle, R.string.setting_update, isCar,
                    subtitle = updateSubtitle,
                    trailing = { Chevron(isCar) },
                    onClick = onOpenUpdate
                )
            }
        }
    }
    when (dialog) {
        SettingsDialog.NONE -> Unit
        SettingsDialog.SOURCE -> TextDialog(R.string.setting_source, R.string.about_source_body) { dialog = SettingsDialog.NONE }
        SettingsDialog.LICENSES -> TextDialog(R.string.setting_licenses, R.string.about_licenses_body) { dialog = SettingsDialog.NONE }
    }
}

/** 与列表/播放器同款的品牌紫加载环，尺寸随车机放大一档。 */
@Composable
private fun InlineSpinner(isCar: Boolean) {
    CircularProgressIndicator(
        Modifier.size(if (isCar) 20.dp else 18.dp),
        color = TuneColors.Primary,
        strokeWidth = 2.dp,
        trackColor = TuneColors.Primary.copy(alpha = .22f),
        strokeCap = StrokeCap.Round
    )
}

/** 进入详情弹窗的行用轻量右箭头提示「还有内容」，点击区域仍是整行。 */
@Composable
private fun Chevron(isCar: Boolean) {
    TuneIcon(
        R.drawable.ic_chevron_right,
        Modifier.size(if (isCar) 20.dp else 18.dp),
        TuneColors.Muted
    )
}

private enum class SettingsDialog { NONE, SOURCE, LICENSES }

@Composable
private fun TextDialog(@StringRes title: Int, @StringRes body: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title), color = TuneColors.Text) },
        text = { Text(stringResource(body), color = TuneColors.Muted, fontSize = 14.sp, style = TuneTextStyle) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        containerColor = TuneColors.Surface,
        textContentColor = TuneColors.Text
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = TuneColors.Surface, shape = TuneShapes.SettingsGroup) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 4.dp), color = TuneColors.Border, thickness = .5.dp)
}

@Composable
private fun SettingRow(
    @DrawableRes resource: Int,
    @StringRes label: Int,
    isCar: Boolean,
    checked: Boolean? = null,
    enabled: Boolean = true,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = TuneShapes.Control
    // 与电台列表一致：按住时整行圆角高亮，焦点态补描边，波纹裁进圆角内。
    val background by animateColorAsState(
        when {
            pressed -> TuneColors.Primary.copy(alpha = .12f)
            focused -> TuneColors.Selected
            else -> Color.Transparent
        },
        animationSpec = tween(180), label = "setting_row_background"
    )
    val iconBackground by animateColorAsState(
        if (pressed || focused) TuneColors.Primary.copy(alpha = .16f) else TuneColors.Primary.copy(alpha = .10f),
        animationSpec = tween(180), label = "setting_row_icon"
    )
    val contentAlpha = if (enabled) 1f else .55f
    val rowModifier = Modifier.fillMaxWidth().heightIn(min = if (isCar) 56.dp else 52.dp)
        .padding(vertical = TuneSpacing.Hairline)
        .clip(shape)
        .background(background)
        .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .testTag("setting_$label")
    val action = if (checked != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = interaction,
            indication = ripple(color = TuneColors.Primary),
            role = Role.Switch
        ) { onClick() }
    } else {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = ripple(color = TuneColors.Primary),
            role = Role.Button,
            onClick = onClick
        )
    }
    Row(
        rowModifier.then(action),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium)
    ) {
        Box(Modifier.size(40.dp).background(iconBackground, TuneShapes.Control), contentAlignment = Alignment.Center) {
            TuneIcon(resource, Modifier.size(TuneSizes.IconLg), TuneColors.Primary.copy(alpha = contentAlpha))
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(label), color = TuneColors.Text.copy(alpha = contentAlpha),
                fontSize = if (isCar) 17.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                style = TuneTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle, color = TuneColors.Muted.copy(alpha = contentAlpha),
                    fontSize = if (isCar) 14.sp else 12.sp, style = TuneTextStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (checked != null) {
            ToggleSwitch(checked)
        } else {
            trailing?.invoke()
        }
    }
}

/** 自绘开关：轨道与滑块都做颜色/位移过渡，点按后立即有反馈。 */
@Composable
private fun ToggleSwitch(checked: Boolean) {
    val trackColor by animateColorAsState(
        if (checked) TuneColors.Accent else TuneColors.Border,
        animationSpec = tween(180), label = "switch_track"
    )
    val thumbColor by animateColorAsState(
        if (checked) TuneColors.Text else TuneColors.Muted,
        animationSpec = tween(180), label = "switch_thumb"
    )
    val thumbOffset by animateDpAsState(
        if (checked) 25.dp else 5.dp,
        animationSpec = tween(180), label = "switch_thumb_offset"
    )
    Box(Modifier.size(56.dp, 48.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(50.dp, 28.dp).background(trackColor, CircleShape)) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = thumbOffset)
                    .size(20.dp).background(thumbColor, CircleShape)
            )
        }
    }
}
