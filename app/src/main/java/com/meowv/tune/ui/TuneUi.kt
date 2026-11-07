package com.meowv.tune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.domain.RadioUiState
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.TuneError
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.radio.CountryOptions
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneTextStyle
import kotlin.math.min
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TuneApp(
    settings: TuneSettings,
    storageError: Boolean,
    radio: StateFlow<RadioUiState>,
    playback: StateFlow<PlayerUiState>,
    playbackConnected: StateFlow<Boolean>,
    onPlayback: (PlayerCommand) -> Unit,
    countries: CountryOptions,
    onLoadCountries: (Boolean) -> Unit,
    onRefreshRadio: () -> Unit,
    onLoadMore: () -> Unit,
    onSettingsChange: ((TuneSettings) -> TuneSettings) -> Unit,
    onClearDirectoryCache: () -> Unit,
    onShellChanged: (Boolean) -> Unit,
    onFirstLayout: () -> Unit
) {
    val connected by playbackConnected.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(TunePage.Radio) }
    var showScopePicker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showScopePicker) { if (showScopePicker) onLoadCountries(false) }
    val pageStates = rememberSaveableStateHolder()
    Surface(color = TuneColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowWidth = maxWidth
            val isCar = isCarWindow(windowWidth.value, maxHeight.value)
            LaunchedEffect(isCar) { onShellChanged(isCar) }
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(if (isCar) WindowInsets.displayCutout else WindowInsets.safeDrawing)
                    .onGloballyPositioned { onFirstLayout() }.testTag(if (isCar) "shell_car" else "shell_phone")
            ) {
                BrandHeader(isCar)
                if (storageError) {
                    Text(stringResource(R.string.settings_storage_error), color = TuneColors.Text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                Row(Modifier.weight(1f)) {
                    if (isCar) SideNavigation(page, windowWidth.value) { page = it }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        pageStates.SaveableStateProvider(page) {
                            when (page) {
                                TunePage.Radio -> RadioScreen(isCar, radio, onRefreshRadio, onLoadMore, if (connected) { station -> onPlayback(PlayerCommand.Play(station)) } else null) { showScopePicker = true }
                                TunePage.Music -> MusicScreen(isCar)
                                TunePage.Settings -> SettingsScreen(isCar, settings, onSettingsChange, onClearDirectoryCache)
                            }
                        }
                    }
                }
                GlobalMiniPlayer(isCar, playback, connected, onPlayback)
                if (!isCar) BottomNavigation(page) { page = it }
            }
            if (showScopePicker) {
                ScopePicker(isCar, maxHeight, settings.scope, countries, { onLoadCountries(true) },
                    onDismiss = { showScopePicker = false }) { selected ->
                    onSettingsChange { current -> current.copy(scope = selected) }
                    showScopePicker = false
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(isCar: Boolean) {
    Column(Modifier.testTag("brand_header")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (isCar) 79.dp else 74.dp)
                .background(if (isCar) TuneColors.Surface else TuneColors.Background)
                .padding(horizontal = if (isCar) 30.dp else 17.dp, vertical = if (isCar) 9.5.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCar) 10.dp else 7.dp)
        ) {
            BrandLogo(Modifier.size(if (isCar) 60.dp else 46.dp))
            Box(Modifier.weight(1f)) {
                val name = stringResource(R.string.brand_full)
                val base = TuneTextStyle.copy(fontSize = if (isCar) 30.sp else 20.sp, fontWeight = FontWeight.Bold, lineHeight = if (isCar) 38.sp else 26.sp,
                    letterSpacing = if (isCar) 1.2.sp else .8.sp)
                BasicText(
                    name,
                    modifier = Modifier.fillMaxWidth().testTag("brand_name").semantics { heading() },
                    style = base.copy(color = TuneColors.Text),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = base.fontSize, stepSize = .5.sp)
                )
            }
        }
        HorizontalDivider(color = TuneColors.Border, thickness = .5.dp)
    }
}

@Composable
private fun SideNavigation(page: TunePage, windowWidth: Float, onPage: (TunePage) -> Unit) {
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
    val width = min((if (windowWidth >= 1000) 191f else 150f) * scale, windowWidth * .38f).dp
    Row(Modifier.width(width).fillMaxHeight().background(TuneColors.Surface).testTag("side_navigation")) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup()
                .padding(horizontal = 16.dp, vertical = 44.dp), verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            TunePage.entries.forEach { NavigationItem(it, it == page, true, Modifier.fillMaxWidth(), onPage) }
        }
        Box(Modifier.width(.5.dp).fillMaxHeight().background(TuneColors.Border))
    }
}

@Composable
private fun BottomNavigation(page: TunePage, onPage: (TunePage) -> Unit) {
    Column(Modifier.background(TuneColors.Surface).testTag("bottom_navigation")) {
        HorizontalDivider(color = TuneColors.Border, thickness = .5.dp)
        Row(Modifier.fillMaxWidth().focusGroup().padding(horizontal = 12.dp, vertical = 4.dp)) {
            TunePage.entries.forEach { NavigationItem(it, it == page, false, Modifier.weight(1f), onPage) }
        }
    }
}

@Composable
private fun NavigationItem(page: TunePage, selected: Boolean, isCar: Boolean, modifier: Modifier, onPage: (TunePage) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(page.label)
    val color = if (selected) TuneColors.Primary else TuneColors.Muted
    val shape = TuneShapes.Navigation
    val stacked = !isCar && LocalDensity.current.fontScale > 1.3f
    val resource = when (page) {
        TunePage.Radio -> R.drawable.ic_ui_radio
        TunePage.Music -> R.drawable.ic_ui_music
        TunePage.Settings -> R.drawable.ic_ui_settings
    }
    Box(
        modifier.heightIn(min = if (isCar) 69.dp else 48.dp).clip(shape)
            .background(if (selected) TuneColors.Raised else TuneColors.Surface)
            .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected, role = Role.Tab, onClick = { onPage(page) })
            .testTag("nav_${page.name.lowercase()}").semantics { contentDescription = label }
            .padding(horizontal = if (isCar) 12.dp else 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center
    ) {
        val content: @Composable () -> Unit = {
            TuneIcon(resource, Modifier.size(if (isCar) 30.dp else 24.dp), color)
            Spacer(if (stacked) Modifier.height(4.dp) else Modifier.width(12.dp))
            Text(label, color = color, fontSize = if (isCar) 23.sp else 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                style = TuneTextStyle, modifier = Modifier.clearAndSetSemantics { })
        }
        if (stacked) Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
        else Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun GlobalMiniPlayer(isCar: Boolean, playback: StateFlow<PlayerUiState>, connected: Boolean, onPlayback: (PlayerCommand) -> Unit) {
    val state by playback.collectAsStateWithLifecycle()
    val phase = state.phase
    val active = phase != PlaybackPhase.IDLE
    val pausing = phase == PlaybackPhase.PLAYING || phase == PlaybackPhase.BUFFERING
    val canToggle = connected && active && phase != PlaybackPhase.RESOLVING && phase != PlaybackPhase.RETRYING
    if (isCar) HorizontalDivider(color = TuneColors.Border, thickness = .5.dp)
    Box(Modifier.fillMaxWidth().testTag("global_player").background(if (isCar) TuneColors.Surface else TuneColors.Background)
        .padding(start = if (isCar) 0.dp else 9.dp, end = if (isCar) 0.dp else 9.dp,
            top = if (isCar) 0.dp else 8.dp, bottom = if (isCar) 0.dp else 10.dp)) {
        Surface(color = TuneColors.Surface, shape = RoundedCornerShape(if (isCar) 0.dp else 12.dp),
            border = if (isCar) null else androidx.compose.foundation.BorderStroke(1.dp, TuneColors.Border)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val playerWidth = maxWidth
                val stacked = !isCar && playerWidth < 382.dp && LocalDensity.current.fontScale > 1.3f
                val info: @Composable (Modifier) -> Unit = { modifier ->
                    Row(modifier, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (isCar) 17.dp else 8.dp)) {
                        StationBadge(state.station, if (isCar) 70.dp else 42.dp)
                        Column(Modifier.weight(1f).testTag("player_info"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.station?.name?.ifBlank { stringResource(R.string.station_unnamed) } ?: stringResource(R.string.player_idle), color = TuneColors.Text,
                                fontSize = if (isCar && playerWidth >= 1000.dp) 24.sp else 15.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TuneTextStyle)
                            if (active) Text(playbackLabel(state), color = TuneColors.Muted, fontSize = 12.sp)
                        }
                    }
                }
                val controls: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (isCar) 18.dp else 0.dp)) {
                        PreviewControl(R.drawable.ic_radio_previous, stringResource(R.string.player_previous),
                            if (isCar) 56.dp else 48.dp, 0.dp, if (isCar) 64.dp else 34.dp, tag = "player_previous")
                        PreviewControl(if (pausing) R.drawable.ic_radio_pause else R.drawable.ic_radio_play,
                            stringResource(if (pausing) R.string.action_pause else if (phase == PlaybackPhase.ERROR) R.string.directory_retry else R.string.action_play),
                            if (isCar) 72.dp else 48.dp, if (isCar) 72.dp else 44.dp,
                            if (isCar) 44.dp else 28.dp, background = TuneColors.Primary, tint = TuneColors.Background, tag = "player_pause", onClick = if (canToggle) ({ onPlayback(PlayerCommand.TogglePause) }) else null)
                        PreviewControl(R.drawable.ic_radio_next, stringResource(R.string.player_next),
                            if (isCar) 56.dp else 48.dp, 0.dp, if (isCar) 64.dp else 34.dp, tag = "player_next")
                        if (active && connected) PreviewControl(R.drawable.ic_ui_close, stringResource(R.string.action_stop),
                            if (isCar) 56.dp else 48.dp, 0.dp, 24.dp, tag = "player_stop",
                            onClick = { onPlayback(PlayerCommand.Stop) })
                    }
                }
                if (stacked) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(TuneSpacing.Small)) {
                    info(Modifier.fillMaxWidth())
                    Box(Modifier.align(Alignment.End)) { controls() }
                } else Row(
                    Modifier.fillMaxWidth().heightIn(min = if (isCar) 109.dp else 68.dp)
                        .padding(start = if (isCar) 29.dp else 8.dp,
                            end = if (isCar && playerWidth >= 1000.dp) 148.dp else 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small)
                ) {
                    info(Modifier.weight(1f))
                    controls()
                }
            }
        }
    }
}

@Composable
private fun playbackLabel(state: PlayerUiState): String = when (state.phase) {
    PlaybackPhase.IDLE -> stringResource(R.string.player_idle)
    PlaybackPhase.RESOLVING -> stringResource(R.string.playback_resolving)
    PlaybackPhase.RETRYING -> stringResource(R.string.playback_retrying)
    PlaybackPhase.BUFFERING -> stringResource(R.string.playback_buffering)
    PlaybackPhase.PLAYING -> stringResource(R.string.playback_live)
    PlaybackPhase.PAUSED -> stringResource(R.string.playback_paused)
    PlaybackPhase.ERROR -> when (state.error) {
        TuneError.HTTP, TuneError.SERVER -> state.statusCode?.let { stringResource(R.string.playback_http, it) }
            ?: stringResource(R.string.playback_unavailable)
        TuneError.TIMEOUT -> stringResource(R.string.playback_timeout)
        TuneError.TLS -> stringResource(R.string.playback_tls)
        TuneError.INVALID_RESPONSE -> stringResource(R.string.playback_no_source)
        TuneError.CONNECTION -> stringResource(R.string.playback_connection)
        TuneError.UNSUPPORTED_MEDIA -> stringResource(R.string.playback_format)
        else -> stringResource(R.string.playback_unavailable)
    }
}
