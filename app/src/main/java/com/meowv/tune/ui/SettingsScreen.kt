package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import com.meowv.tune.domain.LayoutMode
import com.meowv.tune.domain.RadioUiState
import com.meowv.tune.domain.TuneSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneTextStyle
import java.util.Locale

@Composable
internal fun SettingsScreen(
    isCar: Boolean,
    settings: TuneSettings,
    radio: RadioUiState,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onRefreshDirectory: () -> Unit,
    onClearDirectoryCache: () -> Unit
) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }
    LazyColumn(Modifier.fillMaxSize().testTag("page_settings"),
        contentPadding = PaddingValues(horizontal = if (isCar) 40.dp else 24.dp, vertical = if (isCar) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (isCar) 8.dp else 14.dp)) {
        item(key = "settings_title") {
            Text(stringResource(R.string.menu_settings), color = TuneColors.Text, fontSize = if (isCar) 28.sp else 27.sp,
                fontWeight = FontWeight.Bold, style = TuneTextStyle, modifier = Modifier.semantics { heading() })
        }
        item(key = "playback_settings") {
            SettingsGroup(R.string.settings_playback) {
                SettingRow(R.drawable.ic_ui_play, R.string.setting_autoplay, isCar, checked = settings.autoPlay) {
                    onSettingsChange { current -> current.copy(autoPlay = !current.autoPlay) }
                }
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_wifi, R.string.setting_wifi, isCar, checked = settings.wifiOnly) {
                    onSettingsChange { current -> current.copy(wifiOnly = !current.wifiOnly) }
                }
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_info, R.string.setting_data, isCar, checked = settings.dataWarning) {
                    onSettingsChange { current -> current.copy(dataWarning = !current.dataWarning) }
                }
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_volume, R.string.setting_disconnect, isCar, checked = settings.pauseOnDisconnect) {
                    onSettingsChange { current -> current.copy(pauseOnDisconnect = !current.pauseOnDisconnect) }
                }
            }
        }
        item(key = "appearance_settings") {
            SettingsGroup(R.string.settings_interface) {
                SettingRow(R.drawable.ic_ui_moon, R.string.setting_theme, isCar, value = R.string.theme_dark)
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_layout, R.string.setting_layout, isCar,
                    value = layoutLabel(settings.layout), onClick = { dialog = SettingsDialog.LAYOUT })
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_font, R.string.setting_large_text, isCar, checked = settings.largeText) {
                    onSettingsChange { current -> current.copy(largeText = !current.largeText) }
                }
            }
        }
        item(key = "about_settings") {
            SettingsGroup(R.string.settings_about) {
                SettingRow(R.drawable.ic_ui_refresh, R.string.setting_refresh, isCar, onClick = onRefreshDirectory)
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_close, R.string.setting_cache, isCar, onClick = onClearDirectoryCache)
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_info, R.string.setting_source, isCar, onClick = { dialog = SettingsDialog.SOURCE })
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_check, R.string.setting_licenses, isCar, onClick = { dialog = SettingsDialog.LICENSES })
                SettingsDivider()
                Text(stringResource(R.string.about_app_version, version), color = TuneColors.Muted, fontSize = 13.sp,
                    style = TuneTextStyle, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                Text(diagnostics(radio), color = TuneColors.Muted, fontSize = 12.sp,
                    style = TuneTextStyle, modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp)
                        .testTag("settings_diagnostics"))
            }
        }
    }
    when (dialog) {
        SettingsDialog.NONE -> Unit
        SettingsDialog.LAYOUT -> ChoiceDialog(
            title = R.string.setting_layout,
            options = LayoutMode.entries.map { it to layoutLabel(it) },
            selected = settings.layout,
            onDismiss = { dialog = SettingsDialog.NONE }
        ) { picked ->
            onSettingsChange { current -> current.copy(layout = picked) }
            dialog = SettingsDialog.NONE
        }
        SettingsDialog.SOURCE -> TextDialog(R.string.setting_source, R.string.about_source_body) { dialog = SettingsDialog.NONE }
        SettingsDialog.LICENSES -> TextDialog(R.string.setting_licenses, R.string.about_licenses_body) { dialog = SettingsDialog.NONE }
    }
}

private enum class SettingsDialog { NONE, LAYOUT, SOURCE, LICENSES }

@StringRes
private fun layoutLabel(layout: LayoutMode): Int = when (layout) {
    LayoutMode.AUTO -> R.string.layout_auto
    LayoutMode.PHONE -> R.string.layout_phone
    LayoutMode.CAR -> R.string.layout_car
}

// 诊断只显示范围、目录状态与错误类别，不含地址、台站或凭据。
private fun diagnostics(radio: RadioUiState): String {
    val scope = radio.resolvedCountryCode?.displayName(Locale.getDefault()) ?: "不限区域"
    val cache = when (radio.cacheState) {
        com.meowv.tune.domain.DirectoryCacheState.FRESH -> "缓存"
        com.meowv.tune.domain.DirectoryCacheState.STALE -> "缓存已过期"
        null -> "实时"
    }
    val error = radio.error?.let { " · 错误 $it" } ?: ""
    return "范围 $scope · 目录 $cache$error"
}

@Composable
private fun ChoiceDialog(
    @StringRes title: Int,
    options: List<Pair<LayoutMode, Int>>,
    selected: LayoutMode,
    onDismiss: () -> Unit,
    onPick: (LayoutMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title), color = TuneColors.Text) },
        text = {
            Column {
                options.forEach { (mode, label) ->
                    val checked = mode == selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(checked, role = Role.RadioButton, onClick = { onPick(mode) })
                            .testTag("layout_option_${mode.name.lowercase()}")
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(18.dp).background(
                                if (checked) TuneColors.Accent else TuneColors.Border, CircleShape
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label), color = TuneColors.Text, fontSize = 16.sp, style = TuneTextStyle)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        containerColor = TuneColors.Surface,
        textContentColor = TuneColors.Text
    )
}

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
private fun SettingsGroup(@StringRes label: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(label), color = TuneColors.Primary, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
            style = TuneTextStyle, modifier = Modifier.semantics { heading() })
        Surface(color = TuneColors.Surface, shape = TuneShapes.SettingsGroup) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 4.dp), color = TuneColors.Border, thickness = .5.dp)
}

@Composable
private fun SettingRow(@DrawableRes resource: Int, @StringRes label: Int, isCar: Boolean, checked: Boolean? = null, @StringRes value: Int? = null, onClick: (() -> Unit)? = null) {
    val title = stringResource(label)
    val rowModifier = Modifier.fillMaxWidth().heightIn(min = if (isCar) 56.dp else 52.dp)
        .padding(vertical = if (isCar) 4.dp else 2.dp).testTag("setting_$label")
    val semantics = if (checked != null && onClick != null) {
        Modifier.toggleable(checked, role = Role.Switch, onValueChange = { onClick() })
    } else if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
    else Modifier.semantics(mergeDescendants = true) { }
    Row(rowModifier.then(semantics), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium)) {
        Box(Modifier.size(38.dp).background(TuneColors.Raised, TuneShapes.Control), contentAlignment = Alignment.Center) {
            TuneIcon(resource, Modifier.size(22.dp))
        }
        Text(title, color = TuneColors.Text, fontSize = if (isCar) 17.sp else 15.sp, fontWeight = FontWeight.Bold,
            style = TuneTextStyle, modifier = Modifier.weight(1f))
        if (checked != null) {
            Box(Modifier.size(56.dp, 48.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(50.dp, 28.dp).background(if (checked) TuneColors.Accent else TuneColors.Border, CircleShape)) {
                    Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 5.dp)
                        .size(20.dp).background(if (checked) TuneColors.Text else TuneColors.Muted, CircleShape))
                }
            }
        } else if (value != null) {
            Text(stringResource(value), color = TuneColors.Muted, fontSize = if (isCar) 14.sp else 13.sp, style = TuneTextStyle)
            // 无点击入口的项不显示箭头，避免把固定值伪装成可切换。
            if (onClick != null) TuneIcon(R.drawable.ic_ui_chevron_right, Modifier.size(16.dp, 20.dp), TuneColors.Muted)
        }
    }
}
