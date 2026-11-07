package com.meowv.tune.ui

import androidx.compose.foundation.BorderStroke
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meowv.tune.R
import com.meowv.tune.domain.*
import com.meowv.tune.radio.CountryOptions
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneTextStyle
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun RadioScreen(isCar: Boolean, radio: StateFlow<RadioUiState>, onRefresh: () -> Unit, onLoadMore: () -> Unit, onPlay: ((Station) -> Unit)?, onScopeClick: () -> Unit) {
    val state by radio.collectAsStateWithLifecycle()
    key(state.scope, state.resolvedCountryCode) {
        RadioDirectory(isCar, state, onRefresh, onLoadMore, onPlay, onScopeClick)
    }
}

@Composable
private fun RadioDirectory(isCar: Boolean, state: RadioUiState, onRefresh: () -> Unit, onLoadMore: () -> Unit, onPlay: ((Station) -> Unit)?, onScopeClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val scopeName = scopeName(state, locale)
    val listState = rememberLazyListState()
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, state.stations.size, state.hasMore, state.isLoading, state.error) {
        if (state.hasMore && !state.isLoading && state.error == null) {
            snapshotFlow {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last > 0 && last >= state.stations.size - 3
            }.filter { it }.collect { loadMore() }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("page_radio")) {
        val wide = isCar && maxWidth >= 850.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
        LazyColumn(
            Modifier.fillMaxSize().testTag("station_list"),
            state = listState,
            contentPadding = PaddingValues(start = if (isCar) 32.dp else 20.dp, end = if (isCar) 32.dp else 20.dp,
                top = if (isCar) 28.dp else 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCar) 6.dp else 4.dp)
        ) {
            item(key = "radio_header", contentType = "header") {
                Column {
                    if (wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioTitle(true, state.isLoading, onRefresh, Modifier.weight(1f).padding(start = 8.dp))
                        ScopeSelector(scopeName, true, Modifier.width(317.dp), onScopeClick)
                    } else Column {
                        RadioTitle(isCar, state.isLoading, onRefresh, Modifier.padding(start = if (isCar) 8.dp else 2.dp))
                        Spacer(Modifier.height(if (isCar) 18.dp else 30.dp))
                        ScopeSelector(scopeName, false, Modifier.fillMaxWidth(), onScopeClick)
                    }
                    Spacer(Modifier.height(if (wide) 24.dp else 12.dp))
                }
            }
            if (state.cacheState != null) item(key = "directory_cache") {
                DirectoryFeedback(if (state.cacheState == DirectoryCacheState.STALE) R.string.directory_stale else R.string.directory_cached)
            }
            if (state.error != null) item(key = "directory_error") { DirectoryFeedback(R.string.directory_error, onRefresh) }
            items(state.stations, key = { it.uuid.toString() }, contentType = { "station" }) { station ->
                StationRow(station, isCar, onPlay)
            }
            when {
                state.isLoading -> item(key = "directory_loading") { DirectoryFeedback(R.string.directory_loading) }
                state.error == null && state.stations.isEmpty() -> item(key = "directory_empty") { DirectoryFeedback(R.string.directory_empty) }
            }
        }
    }
}

@Composable
private fun RadioTitle(isCar: Boolean, loading: Boolean, onRefresh: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.menu_radio), modifier = Modifier.weight(1f).semantics { heading() },
            color = TuneColors.Text, fontSize = if (isCar) 36.sp else 29.sp,
            lineHeight = if (isCar) 48.sp else 40.sp, fontWeight = FontWeight.Bold, style = TuneTextStyle)
        IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.testTag("directory_refresh")) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_ui_refresh), stringResource(R.string.directory_refresh))
        }
    }
}

@Composable
private fun ScopeSelector(name: String, wide: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
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
private fun StationRow(station: Station, isCar: Boolean, onPlay: ((Station) -> Unit)?) {
    val title = station.name.ifBlank { stringResource(R.string.station_unnamed) }
    Surface(
        onClick = { onPlay?.invoke(station) },
        enabled = onPlay != null,
        color = TuneColors.Surface,
        shape = TuneShapes.Station,
        modifier = Modifier.fillMaxWidth().testTag("station_${station.uuid}")
    ) {
        Row(
            Modifier.heightIn(min = if (isCar) 60.dp else 72.dp).padding(horizontal = if (isCar) 14.dp else 11.dp, vertical = if (isCar) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (isCar) 17.dp else 9.dp)
        ) {
            StationBadge(station, 46.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = TuneColors.Text, fontSize = if (isCar) 20.sp else 16.sp,
                    fontWeight = FontWeight.Bold, lineHeight = if (isCar) 26.sp else 20.sp, style = TuneTextStyle,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            PreviewControl(R.drawable.ic_radio_play, stringResource(R.string.action_play),
                if (isCar) 56.dp else 48.dp, if (isCar) 44.dp else 34.dp, if (isCar) 26.dp else 22.dp,
                background = Color(0xFF222D49), tint = TuneColors.Primary,
                onClick = onPlay?.let { play -> { play(station) } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScopePicker(isCar: Boolean, windowHeight: Dp, scope: RadioScope, countries: CountryOptions,
    onRetry: () -> Unit, onDismiss: () -> Unit, onSelect: (RadioScope) -> Unit) {
    val height = (windowHeight - 48.dp).coerceIn(0.dp, if (isCar) 490.dp else 520.dp)
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
                ScopeOptions(scope, countries, onRetry, onDismiss, onSelect)
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = TuneColors.Surface, contentColor = TuneColors.Text,
            dragHandle = null
        ) {
            Box(Modifier.fillMaxWidth().heightIn(max = height)) { ScopeOptions(scope, countries, onRetry, onDismiss, onSelect) }
        }
    }
}

@Composable
private fun ScopeOptions(scope: RadioScope, countries: CountryOptions, onRetry: () -> Unit, onDismiss: () -> Unit, onSelect: (RadioScope) -> Unit) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val sorted = remember(countries.countries, locale) {
        val collator = Collator.getInstance(locale)
        countries.countries.map { it.code to it.displayName(locale) }.sortedWith { a, b -> collator.compare(a.second, b.second) }
    }
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
            item(key = "AUTO") { ScopeOption("Auto", stringResource(R.string.layout_auto), scope.mode == ScopeMode.AUTO) { onSelect(RadioScope()) } }
            item(key = "GLOBAL") { ScopeOption("Global", stringResource(R.string.scope_global), scope.mode == ScopeMode.GLOBAL) { onSelect(RadioScope(ScopeMode.GLOBAL)) } }
            if (countries.isLoading) item { DirectoryFeedback(R.string.directory_loading) }
            if (countries.failed) item { DirectoryFeedback(R.string.directory_error, onRetry) }
            if (countries.stale) item { DirectoryFeedback(R.string.directory_stale) }
            if (sorted.isEmpty() && !countries.isLoading && !countries.failed) item { DirectoryFeedback(R.string.scope_empty, onRetry) }
            items(sorted, key = { it.first.value }) { (code, label) ->
                val option = RadioScope(ScopeMode.COUNTRY, code)
                ScopeOption(code.value, label, scope == option) { onSelect(option) }
            }
        }
    }
}

@Composable
private fun ScopeOption(tag: String, label: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = TuneShapes.Control
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (selected) TuneColors.Raised else TuneColors.Surface, shape)
            .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("scope_$tag").semantics { contentDescription = label }
            .padding(horizontal = TuneSpacing.Large, vertical = TuneSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (selected) TuneColors.Primary else TuneColors.Text, fontSize = 17.sp,
            style = TuneTextStyle, modifier = Modifier.weight(1f).clearAndSetSemantics { })
        if (selected) TuneIcon(R.drawable.ic_ui_check, Modifier.size(24.dp))
    }
}

@Composable
private fun DirectoryFeedback(@StringRes message: Int, onRetry: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(message), color = TuneColors.Muted, modifier = Modifier.weight(1f), style = TuneTextStyle)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.directory_retry)) }
    }
}

@Composable
private fun scopeName(state: RadioUiState, locale: Locale): String = when (state.scope.mode) {
    ScopeMode.AUTO -> state.resolvedCountryCode?.let { stringResource(R.string.scope_auto_country, it.displayName(locale)) }
        ?: stringResource(if (state.isLoading || state.error != null) R.string.layout_auto else R.string.scope_global)
    ScopeMode.COUNTRY -> requireNotNull(state.scope.countryCode).displayName(locale)
    ScopeMode.GLOBAL -> stringResource(R.string.scope_global)
}
