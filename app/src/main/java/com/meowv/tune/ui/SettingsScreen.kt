package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun SettingsScreen(
    isCar: Boolean,
    settings: TuneSettings,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onRefreshDirectory: () -> Unit,
    onClearDirectoryCache: () -> Unit
) {
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }
    LazyColumn(Modifier.fillMaxSize().testTag("page_settings"),
        contentPadding = PaddingValues(horizontal = if (isCar) 40.dp else 24.dp, vertical = if (isCar) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (isCar) 8.dp else 14.dp)) {
        item(key = "playback_settings") {
            SettingsGroup {
                SettingRow(R.drawable.ic_ui_play, R.string.setting_autoplay, isCar, checked = settings.autoPlay) {
                    onSettingsChange { current -> current.copy(autoPlay = !current.autoPlay) }
                }
            }
        }
        item(key = "about_settings") {
            SettingsGroup {
                SettingRow(R.drawable.ic_ui_refresh, R.string.setting_refresh, isCar, onClick = onRefreshDirectory)
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_close, R.string.setting_cache, isCar, onClick = onClearDirectoryCache)
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_info, R.string.setting_source, isCar, onClick = { dialog = SettingsDialog.SOURCE })
                SettingsDivider()
                SettingRow(R.drawable.ic_ui_check, R.string.setting_licenses, isCar, onClick = { dialog = SettingsDialog.LICENSES })
            }
        }
    }
    when (dialog) {
        SettingsDialog.NONE -> Unit
        SettingsDialog.SOURCE -> TextDialog(R.string.setting_source, R.string.about_source_body) { dialog = SettingsDialog.NONE }
        SettingsDialog.LICENSES -> TextDialog(R.string.setting_licenses, R.string.about_licenses_body) { dialog = SettingsDialog.NONE }
    }
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
        if (pressed || focused) TuneColors.Primary.copy(alpha = .18f) else TuneColors.Raised,
        animationSpec = tween(180), label = "setting_row_icon"
    )
    val rowModifier = Modifier.fillMaxWidth().heightIn(min = if (isCar) 56.dp else 52.dp)
        .padding(vertical = if (isCar) 4.dp else 2.dp)
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
        Box(Modifier.size(38.dp).background(iconBackground, TuneShapes.Control), contentAlignment = Alignment.Center) {
            TuneIcon(resource, Modifier.size(22.dp))
        }
        Text(stringResource(label), color = TuneColors.Text, fontSize = if (isCar) 17.sp else 15.sp, fontWeight = FontWeight.Bold,
            style = TuneTextStyle, modifier = Modifier.weight(1f))
        if (checked != null) {
            Box(Modifier.size(56.dp, 48.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(50.dp, 28.dp).background(if (checked) TuneColors.Accent else TuneColors.Border, CircleShape)) {
                    Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 5.dp)
                        .size(20.dp).background(if (checked) TuneColors.Text else TuneColors.Muted, CircleShape))
                }
            }
        }
    }
}
