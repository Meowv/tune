package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.music.MusicDevicePhase
import com.meowv.tune.music.MusicDeviceState
import com.meowv.tune.R
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.ui.theme.TuneBrushes
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneMotion
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTypography

@Composable
internal fun SettingsScreen(
    isCar: Boolean,
    settings: TuneSettings,
    refreshing: Boolean,
    updateSubtitle: String,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onRefreshAll: () -> Unit,
    onClearCache: () -> Unit,
    onOpenUpdate: () -> Unit,
    musicDevice: MusicDeviceState? = null
) {
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }
    val playback: @Composable () -> Unit = {
        SettingsGroup(isCar, R.string.setting_group_playback) {
            SettingRow(
                R.drawable.ic_ui_autoplay, R.string.setting_autoplay, isCar,
                checked = settings.autoPlay,
                subtitle = stringResource(R.string.setting_autoplay_summary)
            ) {
                onSettingsChange { current -> current.copy(autoPlay = !current.autoPlay) }
            }
        }
    }
    val data: @Composable () -> Unit = {
        SettingsGroup(isCar, R.string.setting_group_data) {
            SettingRow(
                R.drawable.ic_ui_refresh, R.string.setting_refresh, isCar,
                enabled = !refreshing,
                trailing = if (refreshing) ({ InlineSpinner(isCar) }) else null,
                onClick = onRefreshAll
            )
            SettingsDivider(isCar)
            SettingRow(R.drawable.ic_ui_trash, R.string.setting_cache, isCar, onClick = onClearCache)
        }
    }
    val about: @Composable () -> Unit = {
        SettingsGroup(isCar, R.string.setting_group_about) {
            SettingRow(
                R.drawable.ic_ui_database, R.string.setting_source, isCar,
                trailing = { Chevron(isCar) }, onClick = { dialog = SettingsDialog.SOURCE }
            )
            SettingsDivider(isCar)
            SettingRow(
                R.drawable.ic_ui_scroll, R.string.setting_licenses, isCar,
                trailing = { Chevron(isCar) }, onClick = { dialog = SettingsDialog.LICENSES }
            )
            SettingsDivider(isCar)
            SettingRow(
                R.drawable.ic_ui_arrow_up_circle, R.string.setting_update, isCar,
                subtitle = updateSubtitle, trailing = { Chevron(isCar) }, onClick = onOpenUpdate
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = isCar && maxWidth >= 660.dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (wide) 2 else 1),
            modifier = Modifier.widthIn(max = if (wide) 1080.dp else 720.dp).fillMaxSize().testTag("page_settings"),
            contentPadding = PaddingValues(
                horizontal = if (isCar) TuneSpacing.Section else TuneSpacing.Large,
                vertical = TuneSpacing.Large
            ),
            horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Section),
            verticalArrangement = Arrangement.spacedBy(TuneSpacing.Large)
        ) {
            if (wide) {
                item(key = "settings_left_column") {
                    Column(verticalArrangement = Arrangement.spacedBy(TuneSpacing.Large)) {
                        playback()
                        data()
                        musicDevice?.let { MusicDeviceGroup(it, isCar) }
                    }
                }
            } else {
                item(key = "playback_settings") { playback() }
                item(key = "data_settings") { data() }
                if (musicDevice != null) item(key = "music_device") { MusicDeviceGroup(musicDevice, isCar) }
            }
            item(key = "about_settings") { about() }
        }
    }
    when (dialog) {
        SettingsDialog.NONE -> Unit
        SettingsDialog.SOURCE -> TextDialog(isCar, R.string.setting_source, R.string.about_source_body) { dialog = SettingsDialog.NONE }
        SettingsDialog.LICENSES -> TextDialog(isCar, R.string.setting_licenses, R.string.about_licenses_body) { dialog = SettingsDialog.NONE }
    }
}

@Composable
private fun MusicDeviceGroup(device: MusicDeviceState, isCar: Boolean) {
    val status = stringResource(when (device.phase) {
        MusicDevicePhase.CHECKING -> R.string.music_device_checking
        MusicDevicePhase.OFFLINE -> R.string.music_device_offline
        MusicDevicePhase.PENDING -> R.string.music_device_pending
        MusicDevicePhase.ACTIVE -> R.string.music_device_active
        MusicDevicePhase.REVOKED -> R.string.music_device_revoked
        MusicDevicePhase.UNAVAILABLE -> R.string.music_device_unavailable
    })
    SettingsGroup(isCar) {
        SettingRow(
            R.drawable.ic_menu_music, R.string.setting_music_device, isCar,
            subtitle = device.shortCode?.let { stringResource(R.string.music_device_summary, status, it) } ?: status
        )
    }
}

/** 与列表/播放器同款的品牌紫加载环，尺寸随车机放大一档。 */
@Composable
private fun InlineSpinner(isCar: Boolean) {
    if (!rememberMotionEnabled()) {
        TuneIcon(
            R.drawable.ic_ui_refresh,
            Modifier.size(if (isCar) 20.dp else 18.dp).progressSemantics(),
            TuneColors.Primary
        )
        return
    }
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
private fun TextDialog(isCar: Boolean, @StringRes title: Int, @StringRes body: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(title), color = TuneColors.Text, style = TuneTypography.pageTitle(isCar),
                modifier = Modifier.semantics { heading() }
            )
        },
        text = {
            Text(
                stringResource(body), color = TuneColors.Muted,
                style = TuneTypography.rowTitle(isCar).copy(fontWeight = FontWeight.Normal),
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)) {
                Text(stringResource(R.string.action_close))
            }
        },
        shape = TuneShapes.Dialog,
        containerColor = TuneColors.Raised,
        textContentColor = TuneColors.Text
    )
}

@Composable
private fun SettingsGroup(
    isCar: Boolean,
    @StringRes title: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(TuneSpacing.Small)) {
        if (title != null) {
            Text(
                stringResource(title), color = TuneColors.Muted,
                style = TuneTypography.meta(isCar).copy(fontWeight = FontWeight.SemiBold, letterSpacing = .5.sp),
                modifier = Modifier.padding(horizontal = TuneSpacing.Hairline).semantics { heading() }
            )
        }
        Surface(
            color = TuneColors.Surface,
            shape = RoundedCornerShape(if (isCar) 19.dp else 18.dp),
            border = BorderStroke(1.dp, TuneColors.Border.copy(alpha = .5f))
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(TuneBrushes.Card)
                    .padding(vertical = TuneSpacing.Hairline),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsDivider(isCar: Boolean) {
    HorizontalDivider(
        Modifier.padding(start = if (isCar) 68.dp else 65.dp, end = TuneSpacing.Large),
        color = TuneColors.Border.copy(alpha = .38f),
        thickness = .5.dp
    )
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
    onClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    // 行反馈铺满菜单项，由外层卡片统一裁切圆角，避免出现内缩的独立色块。
    val shape = RectangleShape
    val background by animateColorAsState(
        if (focused && enabled) TuneColors.Selected else Color.Transparent,
        animationSpec = tween(if (rememberMotionEnabled()) TuneMotion.ColorChange else 0), label = "setting_row_background"
    )
    val contentAlpha = if (enabled) 1f else .55f
    val rowModifier = Modifier.fillMaxWidth().heightIn(min = if (isCar) 68.dp else 64.dp)
        .clip(shape)
        .background(background)
        .then(if (focused && enabled) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .testTag("setting_$label")
    val action = if (onClick == null) Modifier else if (checked != null) {
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
        rowModifier.then(action).padding(horizontal = TuneSpacing.Large, vertical = TuneSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium)
    ) {
        Box(
            Modifier.size(if (isCar) 40.dp else 37.dp)
                .background(TuneColors.Primary.copy(alpha = if (isCar) .13f else .09f), TuneShapes.Control),
            contentAlignment = Alignment.Center
        ) {
            TuneIcon(resource, Modifier.size(TuneSizes.IconSm), TuneColors.PrimaryLight.copy(alpha = contentAlpha))
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(label), color = TuneColors.Text.copy(alpha = contentAlpha),
                style = TuneTypography.rowTitle(isCar).copy(fontWeight = FontWeight.SemiBold)
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle, color = TuneColors.Muted.copy(alpha = contentAlpha),
                    style = TuneTypography.secondary(isCar)
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

/** 点击由整行统一处理，Switch 只展示状态，避免重复触发。 */
@Composable
private fun ToggleSwitch(checked: Boolean) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 3.dp,
        animationSpec = tween(if (rememberMotionEnabled()) TuneMotion.ColorChange else 0),
        label = "setting_switch_thumb"
    )
    val track = if (checked) Brush.linearGradient(listOf(TuneColors.Primary, TuneColors.Accent))
    else Brush.linearGradient(listOf(TuneColors.Raised, TuneColors.Player))
    Box(
        Modifier.size(width = 45.dp, height = 26.dp)
            .background(track, CircleShape)
            .border(1.dp, if (checked) TuneColors.Primary else TuneColors.Border, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.offset(x = thumbOffset).size(19.dp)
                .background(if (checked) TuneColors.Text else TuneColors.Muted, CircleShape)
        )
    }
}
