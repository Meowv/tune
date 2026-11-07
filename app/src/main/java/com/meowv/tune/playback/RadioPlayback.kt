package com.meowv.tune.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.meowv.tune.domain.*
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
internal class RadioPlayback(
    private val player: Player,
    private val resolve: suspend (PlaybackIntent) -> ResolvedRadioStream,
    private val publish: (PlayerUiState) -> Unit
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Job? = null
    private var intent: PlaybackIntent? = null
    private var state = PlayerUiState()

    init { player.addListener(this) }

    fun play(station: Station) = start(PlaybackIntent(station), PlaybackPhase.RESOLVING)

    private fun start(next: PlaybackIntent, phase: PlaybackPhase) {
        request?.cancel()
        update(PlayerUiState(phase = phase, station = next.station, isLive = true))
        intent = next
        player.stop()
        player.clearMediaItems()
        request = scope.launch {
            val stream = try {
                resolve(next)
            } catch (failure: TuneFailure) {
                ensureActive()
                fail(failure.kind, failure.statusCode)
                return@launch
            }
            ensureActive()
            val item = MediaItem.Builder().setMediaId(next.station.uuid.toString()).setUri(stream.url.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(next.station.name).build())
            if (stream.isHls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
            update(state.copy(phase = PlaybackPhase.BUFFERING))
            player.setMediaItem(item.build())
            player.prepare()
            player.play()
        }
    }

    fun toggle() {
        val current = intent ?: return
        when (state.phase) {
            PlaybackPhase.ERROR -> start(current, PlaybackPhase.RETRYING)
            PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING -> stop()
            else -> if (player.playWhenReady) player.pause() else player.play()
        }
    }

    fun stop() {
        request?.cancel()
        intent = null
        update(PlayerUiState())
        player.stop()
        player.clearMediaItems()
    }

    fun close() {
        scope.cancel()
        player.removeListener(this)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (intent == null || state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING, PlaybackPhase.ERROR)) return
        when {
            player.isPlaying -> update(state.copy(phase = PlaybackPhase.PLAYING))
            player.playbackState == Player.STATE_ENDED -> fail(TuneError.PLAYBACK)
            player.playbackState == Player.STATE_IDLE -> if (player.playerError == null && player.mediaItemCount > 0) stop()
            !player.playWhenReady -> update(state.copy(phase = PlaybackPhase.PAUSED))
            else -> update(state.copy(phase = PlaybackPhase.BUFFERING))
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (intent == null || state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING)) return
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val kind = when {
            http != null -> TuneError.HTTP
            causes.any { it is SSLException } -> TuneError.TLS
            causes.any { it is SocketTimeoutException } || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> TuneError.TIMEOUT
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> TuneError.CONNECTION
            error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> TuneError.UNSUPPORTED_MEDIA
            else -> TuneError.PLAYBACK
        }
        fail(kind, http?.responseCode)
    }

    private fun fail(kind: TuneError, status: Int? = null) =
        update(state.copy(phase = PlaybackPhase.ERROR, error = kind, statusCode = status))

    private fun update(value: PlayerUiState) {
        state = value
        publish(value)
    }
}
