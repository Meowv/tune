package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.meowv.tune.R
import com.meowv.tune.domain.ThemeMode
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.music.MusicDevicePhase
import com.meowv.tune.music.MusicDeviceState
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
        SettingsGroup {
            SettingRow(
                R.drawable.ic_ui_autoplay, R.string.setting_autoplay, isCar,
                checked = settings.autoPlay,
                subtitle = stringResource(R.string.setting_autoplay_summary)
            ) {
                onSettingsChange { current -> current.copy(autoPlay = !current.autoPlay) }
            }
        }
    }
    val appearance: @Composable () -> Unit = {
        SettingsGroup {
            SettingRow(
                R.drawable.ic_ui_theme, R.string.setting_theme, isCar,
                subtitle = stringResource(settings.themeMode.label()),
                trailing = { Chevron(isCar) }, onClick = { dialog = SettingsDialog.THEME }
            )
        }
    }
    val data: @Composable () -> Unit = {
        SettingsGroup {
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
        SettingsGroup {
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
                        appearance()
                        data()
                        musicDevice?.let { MusicDeviceGroup(it, isCar) }
                    }
                }
            } else {
                item(key = "playback_settings") { playback() }
                item(key = "appearance_settings") { appearance() }
                item(key = "data_settings") { data() }
                if (musicDevice != null) item(key = "music_device") { MusicDeviceGroup(musicDevice, isCar) }
            }
            item(key = "about_settings") { about() }
        }
    }
    when (dialog) {
        SettingsDialog.NONE -> Unit
        SettingsDialog.THEME -> ThemeDialog(
            isCar, settings.themeMode,
            onDismiss = { dialog = SettingsDialog.NONE }
        ) { mode ->
            onSettingsChange { current -> current.copy(themeMode = mode) }
            dialog = SettingsDialog.NONE
        }
        SettingsDialog.SOURCE -> SettingsDetailsDialog(
            isCar, R.string.setting_source, R.drawable.ic_ui_database,
            onDismiss = { dialog = SettingsDialog.NONE }
        ) {
            DetailEntry(isCar, R.string.about_source_radio, R.string.about_source_radio_body)
            DetailEntry(isCar, R.string.about_source_music, R.string.about_source_music_body)
            DetailEntry(isCar, R.string.about_source_local, R.string.about_source_local_body)
        }
        SettingsDialog.LICENSES -> SettingsDetailsDialog(
            isCar, R.string.setting_licenses, R.drawable.ic_ui_scroll,
            onDismiss = { dialog = SettingsDialog.NONE }
        ) {
            DetailEntry(isCar, R.string.about_license_mit, R.string.brand_full)
            DetailEntry(isCar, R.string.about_license_apache, R.string.about_license_apache_libraries)
            DetailEntry(isCar, R.string.about_license_bsd, R.string.about_license_bsd_libraries)
        }
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
    SettingsGroup {
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
        trackColor = TuneColors.TrackOff,
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

@StringRes
private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.FOLLOW_SYSTEM -> R.string.theme_follow_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Composable
private fun ThemeDialog(
    isCar: Boolean,
    selected: ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (ThemeMode) -> Unit
) {
    SettingsDetailsDialog(
        isCar, R.string.setting_theme, R.drawable.ic_ui_theme, onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TuneSpacing.Small)) {
            ThemeMode.entries.forEach { mode ->
                ThemeOption(isCar, mode, selected == mode) { onSelect(mode) }
            }
        }
    }
}

@Composable
private fun ThemeOption(isCar: Boolean, mode: ThemeMode, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = TuneShapes.Control
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (isCar) 56.dp else 52.dp)
            .clip(shape)
            .background(if (selected) TuneColors.Selected else TuneColors.OptionSurface)
            .border(
                if (focused || selected) 2.dp else 1.dp,
                if (focused || selected) TuneColors.Primary else TuneColors.BorderStrong,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("theme_${mode.name}")
            .padding(horizontal = TuneSpacing.Large, vertical = TuneSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium)
    ) {
        Text(
            stringResource(mode.label()),
            color = if (selected) TuneColors.PrimaryLight else TuneColors.Text,
            style = TuneTypography.rowTitle(isCar),
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            TuneIcon(R.drawable.ic_ui_check, Modifier.size(TuneSizes.IconSm), TuneColors.Primary)
        }
    }
}

private enum class SettingsDialog { NONE, THEME, SOURCE, LICENSES }

@Composable
private fun RememberDialogDim() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(.72f)
        onDispose { }
    }
}

@Composable
private fun SettingsDetailsDialog(
    isCar: Boolean,
    @StringRes title: Int,
    @DrawableRes icon: Int,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TuneIcon(icon, Modifier.size(TuneSizes.IconLg), TuneColors.PrimaryLight)
                Text(
                    stringResource(title), color = TuneColors.Text, style = TuneTypography.pageTitle(isCar),
                    modifier = Modifier.semantics { heading() }
                )
            }
        },
        text = {
            RememberDialogDim()
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TuneSpacing.Large),
                content = content
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
private fun DetailEntry(isCar: Boolean, @StringRes title: Int, @StringRes body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline)) {
        Text(
            stringResource(title), color = TuneColors.Text,
            style = TuneTypography.rowTitle(isCar).copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.semantics { heading() }
        )
        Text(
            stringResource(body), color = TuneColors.Muted,
            style = TuneTypography.rowTitle(isCar).copy(fontWeight = FontWeight.Normal)
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = TuneColors.Surface,
        shape = TuneShapes.SettingsGroup,
        border = BorderStroke(1.dp, TuneColors.BorderStrong)
    ) {
        Column(Modifier.fillMaxWidth().background(TuneBrushes.Card), content = content)
    }
}

@Composable
private fun SettingsDivider(isCar: Boolean) {
    HorizontalDivider(
        Modifier.padding(start = if (isCar) 68.dp else 65.dp, end = TuneSpacing.Large),
        color = TuneColors.Divider,
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
    val labelColor = if (enabled) TuneColors.Text else TuneColors.DisabledContent
    val subtitleColor = if (enabled) TuneColors.Muted else TuneColors.DisabledContent
    val iconColor = if (enabled) TuneColors.PrimaryLight else TuneColors.DisabledContent
    val rowModifier = Modifier.fillMaxWidth().heightIn(min = if (isCar) 68.dp else 64.dp)
        .controlFeedback(interaction, RectangleShape, enabled && onClick != null)
        .testTag("setting_$label")
    val action = if (onClick == null) Modifier else if (checked != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            role = Role.Switch
        ) { onClick() }
    } else {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
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
                .background(
                    TuneColors.Primary.copy(alpha = if (!enabled) .04f else if (isCar) .13f else .09f),
                    TuneShapes.Control
                ),
            contentAlignment = Alignment.Center
        ) {
            TuneIcon(resource, Modifier.size(TuneSizes.IconSm), iconColor)
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(label), color = labelColor,
                style = TuneTypography.rowTitle(isCar).copy(fontWeight = FontWeight.SemiBold)
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle, color = subtitleColor,
                    style = TuneTypography.secondary(isCar)
                )
            }
        }
        val rowTrailing = trailing
        if (checked != null) {
            ToggleSwitch(checked, enabled)
        } else if (rowTrailing != null) {
            rowTrailing()
        }
    }
}

/** 点击由整行统一处理，Switch 只展示状态，避免重复触发。 */
@Composable
private fun ToggleSwitch(checked: Boolean, enabled: Boolean = true) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 3.dp,
        animationSpec = tween(if (rememberMotionEnabled()) TuneMotion.ColorChange else 0),
        label = "setting_switch_thumb"
    )
    val track = when {
        !enabled -> Brush.linearGradient(listOf(TuneColors.DisabledContainer, TuneColors.DisabledContainer))
        checked -> Brush.linearGradient(listOf(TuneColors.Primary, TuneColors.Accent))
        else -> Brush.linearGradient(listOf(TuneColors.TrackOff, TuneColors.TrackOff))
    }
    val outline = when {
        !enabled -> TuneColors.BorderSoft
        checked -> TuneColors.Primary
        else -> TuneColors.BorderStrong
    }
    val thumb = when {
        !enabled -> TuneColors.DisabledContent
        checked -> TuneColors.OnPrimary
        else -> TuneColors.Text
    }
    Box(
        Modifier.size(width = 45.dp, height = 26.dp)
            .background(track, CircleShape)
            .border(1.dp, outline, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.offset(x = thumbOffset).size(19.dp)
                .background(thumb, CircleShape)
        )
    }
}
