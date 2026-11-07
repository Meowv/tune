package com.meowv.tune.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.meowv.tune.R
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun RadioScreen(isCar: Boolean, scope: DemoScope, onScopeClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("page_radio")) {
        val wide = isCar && maxWidth >= 850.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
        LazyColumn(
            Modifier.fillMaxSize().testTag("station_list"),
            contentPadding = PaddingValues(start = if (isCar) 32.dp else 20.dp, end = if (isCar) 32.dp else 20.dp,
                top = if (isCar) 28.dp else 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCar) 6.dp else 4.dp)
        ) {
            item(key = "radio_header", contentType = "header") {
                Column {
                    if (wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioTitle(true, Modifier.weight(1f).padding(start = 8.dp))
                        ScopeSelector(scope, true, Modifier.width(317.dp), onScopeClick)
                    } else Column {
                        RadioTitle(isCar, Modifier.padding(start = if (isCar) 8.dp else 2.dp))
                        Spacer(Modifier.height(if (isCar) 18.dp else 30.dp))
                        ScopeSelector(scope, false, Modifier.fillMaxWidth(), onScopeClick)
                    }
                    Spacer(Modifier.height(if (wide) 24.dp else 12.dp))
                }
            }
            items(DemoStations, key = { it.id }, contentType = { "station" }) { station ->
                val playing = station.id == "CN" && scope in listOf(DemoScope.Auto, DemoScope.China, DemoScope.Global)
                StationRow(station, playing, isCar)
            }
        }
    }
}

@Composable
private fun RadioTitle(isCar: Boolean, modifier: Modifier) {
    Text(stringResource(R.string.menu_radio), modifier = modifier.semantics { heading() },
        color = TuneColors.Text, fontSize = if (isCar) 36.sp else 29.sp,
        lineHeight = if (isCar) 48.sp else 40.sp, fontWeight = FontWeight.Bold, style = TuneTextStyle)
}

@Composable
private fun ScopeSelector(scope: DemoScope, wide: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val name = stringResource(scope.label)
    val description = stringResource(R.string.scope_picker_description, name)
    Surface(
        onClick = onClick, color = TuneColors.Surface, shape = TuneShapes.Input,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) TuneColors.Primary else TuneColors.Border),
        modifier = modifier.onFocusChanged { focused = it.isFocused }.testTag("scope_selector")
            .semantics { contentDescription = description }
    ) {
        Row(
            Modifier.heightIn(min = if (wide) 67.dp else 54.dp)
                .padding(horizontal = if (wide) 24.dp else 14.dp, vertical = 5.dp).clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (wide) {
                Text(stringResource(R.string.scope_label), color = TuneColors.Muted, fontSize = 18.sp, style = TuneTextStyle)
                Spacer(Modifier.width(36.dp))
                Text(name, color = TuneColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), style = TuneTextStyle)
                TuneIcon(R.drawable.ic_ui_chevron_down, Modifier.size(24.dp))
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.scope_label), color = TuneColors.Muted, fontSize = 11.sp, style = TuneTextStyle)
                    Text(name, color = TuneColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = TuneTextStyle)
                }
                Row(Modifier.background(TuneColors.Raised, TuneShapes.Control).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.scope_change), color = TuneColors.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        style = TuneTextStyle)
                    TuneIcon(R.drawable.ic_ui_chevron_down, Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StationRow(station: DemoStation, playing: Boolean, isCar: Boolean) {
    val title = stringResource(station.name)
    val preview = stringResource(R.string.static_control)
    Surface(
        color = if (playing) TuneColors.Raised else TuneColors.Surface,
        shape = TuneShapes.Station,
        border = if (playing) BorderStroke(1.dp, TuneColors.Primary) else null,
        modifier = Modifier.fillMaxWidth().testTag("station_${station.id}")
            .semantics(mergeDescendants = true) { contentDescription = title + preview }
    ) {
        Row(
            Modifier.heightIn(min = if (isCar) 60.dp else 72.dp).padding(horizontal = if (isCar) 14.dp else 11.dp, vertical = if (isCar) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (isCar) 17.dp else 9.dp)
        ) {
            StationBadge(station, 46.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = TuneColors.Text, fontSize = if (isCar) 20.sp else 16.sp,
                    fontWeight = FontWeight.Bold, lineHeight = if (isCar) 26.sp else 20.sp, style = TuneTextStyle)
                if (playing) LiveStatus(isCar)
            }
            PreviewControl(if (playing) R.drawable.ic_radio_pause else R.drawable.ic_radio_play,
                stringResource(if (playing) R.string.player_demo_pause else R.string.preview_play),
                if (isCar) 56.dp else 48.dp, if (isCar) 44.dp else 34.dp, if (isCar) 26.dp else 22.dp,
                background = if (playing) TuneColors.Accent else Color(0xFF222D49),
                tint = if (playing) TuneColors.Text else TuneColors.Primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScopePicker(isCar: Boolean, windowHeight: Dp, scope: DemoScope, onDismiss: () -> Unit, onSelect: (DemoScope) -> Unit) {
    val height = (windowHeight - 48.dp).coerceAtMost(if (isCar) 490.dp else 520.dp)
    if (isCar) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val view = LocalView.current
            DisposableEffect(view) {
                val window = (view.parent as DialogWindowProvider).window
                WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.systemBars())
                onDispose { }
            }
            Surface(Modifier.widthIn(max = 460.dp).fillMaxWidth(.9f).height(height),
                shape = TuneShapes.Dialog, color = TuneColors.Surface) {
                ScopeOptions(scope, onDismiss, onSelect)
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = TuneColors.Surface, contentColor = TuneColors.Text,
            dragHandle = null
        ) {
            Box(Modifier.fillMaxWidth().heightIn(max = height)) { ScopeOptions(scope, onDismiss, onSelect) }
        }
    }
}

@Composable
private fun ScopeOptions(scope: DemoScope, onDismiss: () -> Unit, onSelect: (DemoScope) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp).testTag("scope_picker")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scope_label), color = TuneColors.Text, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, style = TuneTextStyle, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("scope_close")) {
                Icon(painter = androidx.compose.ui.res.painterResource(R.drawable.ic_ui_close),
                    contentDescription = stringResource(R.string.action_close), tint = TuneColors.Muted)
            }
        }
        LazyColumn(Modifier.weight(1f).focusGroup().testTag("scope_options"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(DemoScope.entries, key = { it.name }) { option ->
                if (option == DemoScope.China) Text(stringResource(R.string.scope_other_countries), color = TuneColors.Muted,
                    fontSize = 13.sp, style = TuneTextStyle, modifier = Modifier.padding(vertical = 12.dp).semantics { heading() })
                var focused by remember { mutableStateOf(false) }
                val label = stringResource(option.optionLabel)
                val shape = TuneShapes.Control
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (scope == option) TuneColors.Raised else TuneColors.Surface, shape)
                        .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .selectable(selected = scope == option, role = Role.RadioButton, onClick = { onSelect(option) })
                        .testTag("scope_${option.name}").semantics { contentDescription = label }
                        .padding(horizontal = TuneSpacing.Large, vertical = TuneSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = if (scope == option) TuneColors.Primary else TuneColors.Text, fontSize = 17.sp,
                        style = TuneTextStyle, modifier = Modifier.weight(1f).clearAndSetSemantics { })
                    if (scope == option) TuneIcon(R.drawable.ic_ui_check, Modifier.size(24.dp))
                }
            }
        }
    }
}
