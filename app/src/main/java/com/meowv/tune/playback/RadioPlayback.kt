package com.meowv.tune.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.QueueSelection
import com.meowv.tune.domain.RadioQueue
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(UnstableApi::class)
internal class RadioPlayback(
    private val player: Player,
    private val queue: RadioQueue,
    private val resolve: suspend (PlaybackIntent) -> ResolvedRadioStream,
    private val publish: (PlayerUiState) -> Unit
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private var request: Job? = null
    private var intent: PlaybackIntent? = null
    private var selection: QueueSelection? = null
    private var committed = false
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
    }

    fun play(station: Station) = submit {
        val activeScope = queue.activeScope.value ?: run {
            fail(TuneError.PLAYBACK, station = station)
            return@submit
        }
        val next = queue.select(activeScope, station.uuid) ?: run {
            fail(TuneError.PLAYBACK, station = station)
            return@submit
        }
        resolveAndPlay(next, PlaybackPhase.RESOLVING)
    }

    fun next() = submit {
        val activeScope = queue.activeScope.value ?: run {
            fail(TuneError.PLAYBACK)
            return@submit
        }
        val next = queue.next(activeScope, cursor = null) ?: run {
            fail(TuneError.PLAYBACK)
            return@submit
        }
        resolveAndPlay(next, PlaybackPhase.RESOLVING)
    }

    fun previous() = submit {
        val activeScope = queue.activeScope.value ?: run {
            fail(TuneError.PLAYBACK)
            return@submit
        }
        val next = queue.previous(activeScope) ?: run {
            fail(TuneError.PLAYBACK)
            return@submit
        }
        resolveAndPlay(next, PlaybackPhase.RESOLVING)
    }

    fun toggle() {
        val currentIntent = intent ?: return
        val currentSelection = selection ?: return
        when (state.phase) {
            PlaybackPhase.ERROR -> submit {
                resolveAndPlay(currentSelection, PlaybackPhase.RETRYING, currentIntent)
            }
            PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING -> stop()
            else -> if (player.playWhenReady) player.pause() else player.play()
        }
    }

    fun stop() {
        supersedeRequest()
        player.stop()
        player.clearMediaItems()
        intent = null
        selection = null
        committed = false
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
}
