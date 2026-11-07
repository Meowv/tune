package com.meowv.tune.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.QueueSelection
import com.meowv.tune.domain.RadioQueue
import com.meowv.tune.domain.SettingsRepository
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.domain.TuneSettings
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(UnstableApi::class)
internal class RadioPlayback(
    private val player: Player,
    private val queue: RadioQueue,
    private val settings: SettingsRepository,
    private val network: StateFlow<NetworkState>,
    private val resolve: suspend (PlaybackIntent) -> ResolvedRadioStream,
    private val publish: (PlayerUiState) -> Unit
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private var request: Job? = null
    private var intent: PlaybackIntent? = null
    private var selection: QueueSelection? = null
    private var blocked: Command? = null
    private var committed = false
    private var meteredApproved = false
    private var generation = 0L
    private var state = PlayerUiState()

    init {
        player.addListener(this)
        scope.launch {
            combine(queue.canPrevious, queue.canNext) { previous, next -> previous to next }
                .collect { (previous, next) ->
                    publish(decorate(state.copy(canPrevious = previous, canNext = next)))
                }
        }
        scope.launch {
            combine(network, settings.settings) { current, user -> current to user }
                .distinctUntilChanged()
                .collect { (current, user) ->
                    reactToNetworkChange(current, user)
                }
        }
    }

    fun play(station: Station) = submit { run(Command.Play(station), allowMetered = false) }

    fun next() = submit { run(Command.Next, allowMetered = false) }

    fun previous() = submit { run(Command.Previous, allowMetered = false) }

    fun toggle() = submit { toggle(allowMetered = false) }

    fun allowMeteredRetry() = submit {
        val pending = blocked ?: return@submit
        run(pending, allowMetered = true)
    }

    fun dismissNetworkPrompt() {
        blocked = null
        if (state.error == TuneError.MOBILE_CONFIRMATION) {
            if (selection == null) update(PlayerUiState()) else update(state.copy(error = null, statusCode = null))
        }
    }

    fun stop() {
        supersedeRequest()
        player.stop()
        player.clearMediaItems()
        intent = null
        selection = null
        blocked = null
        committed = false
        meteredApproved = false
        update(PlayerUiState())
    }

    fun close() {
        supersedeRequest()
        scope.cancel()
        player.removeListener(this)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val currentSelection = selection ?: return
        if (state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING, PlaybackPhase.ERROR)) return
        when {
            player.isPlaying -> {
                if (!committed) committed = queue.recordSuccessfulPlayback(currentSelection)
                update(state.copy(phase = PlaybackPhase.PLAYING))
            }
            player.playbackState == Player.STATE_ENDED -> fail(TuneError.PLAYBACK)
            player.playbackState == Player.STATE_IDLE -> {
                if (player.playerError == null && player.mediaItemCount > 0) stop()
            }
            !player.playWhenReady -> update(state.copy(phase = PlaybackPhase.PAUSED))
            else -> update(state.copy(phase = PlaybackPhase.BUFFERING))
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (selection == null || state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING)) return
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val kind = when {
            http != null -> TuneError.HTTP
            causes.any { it is SSLException } -> TuneError.TLS
            causes.any { it is SocketTimeoutException } ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> TuneError.TIMEOUT
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> TuneError.CONNECTION
            error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                TuneError.UNSUPPORTED_MEDIA
            else -> TuneError.PLAYBACK
        }
        fail(kind, http?.responseCode)
    }

    private suspend fun run(command: Command, allowMetered: Boolean) {
        if (!networkAllows(command, allowMetered)) return
        when (command) {
            is Command.Play -> {
                val activeScope = queue.activeScope.value ?: run {
                    fail(TuneError.PLAYBACK, station = command.station)
                    return
                }
                val next = queue.select(activeScope, command.station.uuid) ?: run {
                    fail(TuneError.PLAYBACK, station = command.station)
                    return
                }
                resolveAndPlay(next, PlaybackPhase.RESOLVING)
            }
            Command.Next -> {
                val activeScope = queue.activeScope.value ?: run {
                    fail(TuneError.PLAYBACK)
                    return
                }
                val next = queue.next(activeScope, cursor = null) ?: run {
                    fail(TuneError.PLAYBACK)
                    return
                }
                resolveAndPlay(next, PlaybackPhase.RESOLVING)
            }
            Command.Previous -> {
                val activeScope = queue.activeScope.value ?: run {
                    fail(TuneError.PLAYBACK)
                    return
                }
                val next = queue.previous(activeScope) ?: run {
                    fail(TuneError.PLAYBACK)
                    return
                }
                resolveAndPlay(next, PlaybackPhase.RESOLVING)
            }
            Command.Toggle -> toggle(allowMetered)
        }
    }

    private suspend fun toggle(allowMetered: Boolean) {
        when (state.phase) {
            PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING -> stop()
            PlaybackPhase.ERROR -> {
                val currentIntent = intent ?: return
                val currentSelection = selection ?: return
                if (!networkAllows(Command.Toggle, allowMetered)) return
                resolveAndPlay(currentSelection, PlaybackPhase.RETRYING, currentIntent)
            }
            else -> {
                if (player.playWhenReady) {
                    player.pause()
                } else {
                    if (!networkAllows(Command.Toggle, allowMetered)) return
                    player.play()
                }
            }
        }
    }

    private suspend fun networkAllows(command: Command, allowMetered: Boolean): Boolean {
        val user = settings.settings.first()
        val decision = NetworkPolicy.evaluate(network.value, user, allowMetered)
        if (decision == NetworkDecision.ALLOWED) {
            blocked = null
            meteredApproved = allowMetered && network.value.metered
            if (isNetworkError(state.error)) update(state.copy(error = null, statusCode = null))
            return true
        }
        blocked = command
        val error = decision.error
        val phase = if (selection == null) PlaybackPhase.ERROR else state.phase
        update(state.copy(phase = phase, error = error, statusCode = null))
        return false
    }

    private suspend fun reactToNetworkChange(current: NetworkState, user: TuneSettings) {
        if (selection == null) return
        val decision = NetworkPolicy.evaluate(current, user, meteredApproved)
        if (decision == NetworkDecision.ALLOWED) {
            if (current.type == NetworkType.WIFI || current.type == NetworkType.ETHERNET) meteredApproved = false
            if (isNetworkError(state.error)) update(state.copy(error = null, statusCode = null))
            return
        }
        request?.cancel()
        player.pause()
        blocked = Command.Toggle
        meteredApproved = false
        update(state.copy(phase = PlaybackPhase.PAUSED, error = decision.error, statusCode = null))
    }

    private fun submit(block: suspend () -> Unit) {
        val id = ++generation
        request?.cancel()
        request = scope.launch {
            commandMutex.withLock {
                if (id != generation) return@withLock
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: TuneFailure) {
                    ensureActive()
                    if (id == generation) fail(failure.kind, failure.statusCode)
                }
            }
        }
    }

    private suspend fun resolveAndPlay(
        next: QueueSelection,
        phase: PlaybackPhase,
        existingIntent: PlaybackIntent? = null
    ) {
        val id = generation
        player.stop()
        player.clearMediaItems()
        val playbackIntent = existingIntent ?: PlaybackIntent(next.station)
        selection = next
        intent = playbackIntent
        blocked = null
        committed = false
        update(PlayerUiState(phase = phase, station = next.station, isLive = true))

        val stream = resolve(playbackIntent)
        currentCoroutineContext().ensureActive()
        if (id != generation) return
        val item = MediaItem.Builder()
            .setMediaId(next.station.uuid.toString())
            .setUri(stream.url.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(next.station.name).build())
        if (stream.isHls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        update(state.copy(phase = PlaybackPhase.BUFFERING, error = null, statusCode = null))
        player.setMediaItem(item.build())
        player.prepare()
        player.play()
    }

    private fun supersedeRequest() {
        generation++
        request?.cancel()
        request = null
    }

    private fun fail(kind: TuneError, status: Int? = null, station: Station? = state.station) =
        update(state.copy(phase = PlaybackPhase.ERROR, station = station, error = kind, statusCode = status))

    private fun update(value: PlayerUiState) {
        state = value
        publish(decorate(value))
    }

    private fun decorate(value: PlayerUiState) = value.copy(
        canPrevious = queue.canPrevious.value,
        canNext = queue.canNext.value
    )

    private val NetworkDecision.error: TuneError
        get() = when (this) {
            NetworkDecision.ALLOWED -> error("Allowed decisions have no error")
            NetworkDecision.OFFLINE -> TuneError.NETWORK_OFFLINE
            NetworkDecision.WIFI_ONLY -> TuneError.WIFI_ONLY
            NetworkDecision.CONFIRM_METERED -> TuneError.MOBILE_CONFIRMATION
        }

    private fun isNetworkError(error: TuneError?) =
        error in setOf(TuneError.NETWORK_OFFLINE, TuneError.WIFI_ONLY, TuneError.MOBILE_CONFIRMATION)

    private sealed interface Command {
        data class Play(val station: Station) : Command
        data object Next : Command
        data object Previous : Command
        data object Toggle : Command
    }
}
