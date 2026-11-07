package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun SettingsScreen(isCar: Boolean) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    LazyColumn(Modifier.fillMaxSize().testTag("page_settings"),
        contentPadding = PaddingValues(horizontal = if (isCar) 40.dp else 24.dp, vertical = if (isCar) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (isCar) 8.dp else 14.dp)) {
        item(key = "settings_title") {
            Text(stringResource(R.string.menu_settings), color = TuneColors.Text, fontSize = if (isCar) 28.sp else 27.sp,
                fontWeight = FontWeight.Bold, style = TuneTextStyle, modifier = Modifier.semantics { heading() })
        }
        item(key = "playback_settings") {
            SettingsGroup(R.string.settings_playback) {
                SettingRow(R.drawable.ic_ui_play, R.string.setting_autoplay, isCar, checked = false)
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_wifi, R.string.setting_wifi, isCar, checked = false)
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_info, R.string.setting_data, isCar, checked = true)
            }
        }
        item(key = "appearance_settings") {
            SettingsGroup(R.string.settings_interface) {
                SettingRow(R.drawable.ic_ui_moon, R.string.setting_theme, isCar, value = R.string.theme_dark)
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_layout, R.string.setting_layout, isCar, value = R.string.layout_auto)
            }
        }
        item(key = "about_settings") {
            SettingsGroup(R.string.settings_about) {
                SettingRow(R.drawable.ic_ui_refresh, R.string.setting_cache, isCar)
                SettingsDivider()
                Text(stringResource(R.string.about_app_version, version), color = TuneColors.Muted, fontSize = 13.sp,
                    style = TuneTextStyle, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
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
private fun SettingRow(@DrawableRes resource: Int, @StringRes label: Int, isCar: Boolean, checked: Boolean? = null, @StringRes value: Int? = null) {
    val title = stringResource(label)
    val rowModifier = Modifier.fillMaxWidth().heightIn(min = if (isCar) 56.dp else 52.dp)
        .padding(vertical = if (isCar) 4.dp else 2.dp).testTag("setting_$label")
    val semantics = if (checked != null) {
        val state = stringResource(if (checked) R.string.preview_toggle_on else R.string.preview_toggle_off)
        Modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            contentDescription = title
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            stateDescription = state
            disabled()
        }
    } else Modifier.semantics(mergeDescendants = true) { }
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
            TuneIcon(R.drawable.ic_ui_chevron_right, Modifier.size(16.dp, 20.dp), TuneColors.Muted)
        }
    }
}
