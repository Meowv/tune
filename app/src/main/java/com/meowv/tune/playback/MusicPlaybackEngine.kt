package com.meowv.tune.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.*
import com.meowv.tune.music.MusicQueueManager
import com.meowv.tune.music.MusicSelection
import com.meowv.tune.music.MusicRequestGate
import com.meowv.tune.music.musicRetryAfterSeconds
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicPlaybackEngine(
    context: Context,
    private val player: Player,
    private val repository: () -> MusicRepository?,
    private val network: StateFlow<NetworkState>,
    private val publish: (PlayerUiState) -> Unit
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = MusicQueueManager({ repository() ?: throw MusicException(MusicFailure.NOT_CONFIGURED) })
    private val focus = AudioFocusController(context, ::onAudioFocusEvent)
    private val route = AudioRouteMonitor(context, ::onExternalOutputRemoved)
    private val interruptions = MusicInterruptionState(android.os.SystemClock::elapsedRealtime)
    private var active = false
    private var generation = 0L
    private var pending: Job? = null
    private var refill: Job? = null
    private var bufferTimeout: Job? = null
    private var loading = false
    private var desiredPlay = false
    private var current: MusicSelection? = null
    private var audio: PlayableAudio? = null
    private var itemId: String? = null
    private var endedItem: String? = null
    private var confirmed = false
    private var resourceRefreshed = false
    private val requestGate = MusicRequestGate()
    private var failures = 0
    private var state = PlayerUiState(source = PlaybackSource.MUSIC)
    val snapshot: PlayerUiState get() = state

    init {
        player.addListener(this)
        scope.launch {
            network.collect { value ->
                if (!active) return@collect
                if (NetworkPolicy.evaluate(value) != NetworkDecision.ALLOWED) {
                    if (desiredPlay || interruptions.hasResumeIntent) pause(PauseReason.NETWORK)
                } else if (interruptions.networkAvailable()) resume()
            }
        }
        scope.launch {
            while (isActive) {
                delay(500)
                if (active && !loading && itemMatches()) publishState(state)
            }
        }
    }

    fun activate() { active = true }
    fun deactivate() { if (active) stop(); active = false }

    fun play(track: MusicTrack? = null, resetSession: Boolean = true) {
        if (!active || !mayRequest()) return
        failures = 0
        interruptions.reset()
        if (resetSession || !queue.canNext) queue.reset()
        begin { if (track == null) queue.next() else queue.select(track) }
    }

    fun next() {
        if (!active || !mayRequest()) return
        interruptions.reset()
        begin { queue.next() }
    }

    fun previous() {
        if (!active || !queue.canPrevious || !mayRequest()) return
        interruptions.reset()
        begin(preserveCurrent = true) { queue.previous() }
    }

    private fun begin(preserveCurrent: Boolean = false, refreshProvider: String? = null, select: suspend () -> MusicSelection?) {
        val oldState = state
        resourceRefreshed = refreshProvider != null
        if (NetworkPolicy.evaluate(network.value) != NetworkDecision.ALLOWED) {
            fail(MusicFailure.CONNECTION)
            return
        }
        val id = invalidate()
        desiredPlay = true
        loading = true
        if (!preserveCurrent) {
            itemId = null
            player.stop(); player.clearMediaItems()
            focus.abandon()
        }
        publishState(state.copy(phase = PlaybackPhase.RESOLVING, musicFailure = null, error = null, operationFailure = null, canSeek = false))
        pending = scope.launch {
            var selected = false
            try {
                val result = withTimeout(8000) {
                    val selection = select() ?: throw MusicException(MusicFailure.NO_PLAYABLE_MATCH)
                    ensureActive()
                    selected = true
                    if (!preserveCurrent) publishState(state.copy(music = selection.track))
                    val source = repository() ?: throw MusicException(MusicFailure.NOT_CONFIGURED)
                    var playable = source.resolve(selection.track.id, refreshProvider)
                    ensureActive()
                    if (playable.expiresAt?.isAfter(Instant.now()) == false && !resourceRefreshed) {
                        resourceRefreshed = true
                        playable = source.resolve(selection.track.id, playable.providerId)
                    }
                    if (playable.trackId != selection.track.id || playable.expiresAt?.isAfter(Instant.now()) == false ||
                        !com.meowv.tune.music.MusicAudioUrlPolicy.allows(playable.url)) throw MusicException(MusicFailure.INVALID_RESPONSE)
                    selection to playable
                }
                ensureActive()
                if (!active || id != generation) return@launch
                loading = false
                prepare(result.first, result.second, id)
            } catch (failure: TimeoutCancellationException) {
                if (active && id == generation) loadFailed(MusicException(MusicFailure.SOURCE_TIMEOUT), preserveCurrent, oldState, selected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MusicException) {
                ensureActive()
                if (active && id == generation) loadFailed(failure, preserveCurrent, oldState, selected)
            }
        }
    }

    private fun loadFailed(failure: MusicException, preserve: Boolean, old: PlayerUiState, selected: Boolean) {
        loading = false
        if (failure.failure == MusicFailure.RATE_LIMITED) requestGate.block(failure.retryAfterSeconds)
        if (preserve && itemMatches() && player.playbackState != Player.STATE_ENDED) {
            queue.invalidate()
            desiredPlay = player.playWhenReady
            publishState(old.copy(operationFailure = PlaybackOperationFailure(generation, TuneError.PLAYBACK)))
        } else if (!preserve && selected && failure.failure in setOf(MusicFailure.TRACK_NOT_FOUND,
                MusicFailure.NO_PLAYABLE_MATCH, MusicFailure.SOURCE_UNAVAILABLE, MusicFailure.SOURCE_TIMEOUT,
                MusicFailure.PLAYBACK_UNAVAILABLE) && ++failures < 5 && queue.canNext) {
            next()
        } else {
            fail(failure.failure)
        }
    }

    private fun prepare(selection: MusicSelection, playable: PlayableAudio, id: Long) {
        current = selection; audio = playable; confirmed = false
        endedItem = null
        itemId = "music:$id:${selection.token}"
        publishState(PlayerUiState(source = PlaybackSource.MUSIC, phase = PlaybackPhase.BUFFERING, music = selection.track))
        if (!focus.acquire()) { fail(MusicFailure.PLAYBACK_UNAVAILABLE); return }
        val metadata = MediaMetadata.Builder().setTitle(selection.track.title)
            .setArtist(selection.track.artists.joinToString(" / ")).setAlbumTitle(selection.track.album).setIsPlayable(true).build()
        val item = MediaItem.Builder().setMediaId(itemId!!).setUri(playable.url).setMediaMetadata(metadata)
        playable.mimeType?.let(item::setMimeType)
        player.setMediaItem(item.build()); player.prepare(); player.play()
        watchBuffer(id)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!active || loading || state.phase == PlaybackPhase.ERROR || !itemMatches()) return
        val error = player.playerError
        when {
            error != null -> {
                val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val selection = current
                val provider = audio?.providerId
                if (http?.responseCode == 404 && desiredPlay && !resourceRefreshed && selection != null && provider != null) {
                    // 失效资源只刷新当前来源一次，继续失败后才进入既有跳歌预算。
                    begin(refreshProvider = provider) { queue.renew(selection) }
                    return
                }
                val reason = when (http?.responseCode) {
                    401 -> MusicFailure.UNAUTHORIZED
                    403 -> MusicFailure.FORBIDDEN
                    429 -> MusicFailure.RATE_LIMITED
                    else -> MusicFailure.PLAYBACK_UNAVAILABLE
                }
                if (reason == MusicFailure.RATE_LIMITED) {
                    val retry = musicRetryAfterSeconds(http?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull())
                    requestGate.block(retry)
                }
                // 解码/连接失败可跳过候选；权限和限流必须停止，不跨源绕过。
                if (desiredPlay && reason == MusicFailure.PLAYBACK_UNAVAILABLE && ++failures < 5 && queue.canNext) next()
                else fail(reason)
            }
            player.isPlaying -> {
                bufferTimeout?.cancel()
                if (!confirmed) {
                    confirmed = current?.let(queue::recordSuccessfulPlayback) == true
                    if (confirmed) {
                        failures = 0
                        refill = scope.launch {
                            try {
                                queue.replenish()
                                ensureActive()
                                if (active) publishState(state)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: MusicException) { /* 下一次真正切歌时再呈现候选获取错误。 */ }
                        }
                    }
                }
                publishState(state.copy(phase = PlaybackPhase.PLAYING, error = null, musicFailure = null))
            }
            player.playbackState == Player.STATE_ENDED -> {
                if (desiredPlay && endedItem != itemId) { endedItem = itemId; next() }
            }
            !player.playWhenReady -> publishState(state.copy(phase = PlaybackPhase.PAUSED))
            else -> publishState(state.copy(phase = PlaybackPhase.BUFFERING))
        }
    }

    fun toggle() {
        if (desiredPlay || loading) pause() else resume()
    }

    fun pause(reason: PauseReason = PauseReason.USER) {
        if (!active) return
        val paused = interruptions.pause(state, reason, desiredPlay)
        invalidate()
        loading = false; desiredPlay = false
        if (reason != PauseReason.TRANSIENT_FOCUS) focus.abandon()
        player.pause(); player.volume = 1f
        publishState(paused)
    }

    fun resume() {
        if (!active || desiredPlay || loading) return
        interruptions.reset()
        if (NetworkPolicy.evaluate(network.value) != NetworkDecision.ALLOWED) {
            publishState(state.copy(error = TuneError.NETWORK_OFFLINE)); return
        }
        if (!itemMatches() || player.playbackState == Player.STATE_ENDED || state.phase == PlaybackPhase.ERROR ||
            audio?.expiresAt?.isAfter(Instant.now()) == false) {
            play(state.music, resetSession = false)
        } else if (focus.acquire()) {
            if (!confirmed) current = current?.let(queue::renew)
            desiredPlay = true
            publishState(state.copy(phase = PlaybackPhase.BUFFERING, error = null))
            player.play(); watchBuffer(generation)
        }
    }

    fun seek(positionMs: Long) {
        if (active && state.canSeek && !loading) {
            player.seekTo(positionMs.coerceIn(0, state.durationMs ?: Long.MAX_VALUE))
            publishState(state)
        }
    }

    fun stop() {
        invalidate()
        desiredPlay = false; loading = false
        interruptions.reset(); focus.abandon()
        itemId = null; current = null; audio = null
        player.stop(); player.clearMediaItems()
        publishState(PlayerUiState(source = PlaybackSource.MUSIC))
    }

    fun close() {
        if (active) deactivate()
        scope.cancel(); route.close(); focus.abandon(); player.removeListener(this)
    }

    private fun mayRequest(): Boolean = try { requestGate.check(); true } catch (_: MusicException) {
        fail(MusicFailure.RATE_LIMITED)
        false
    }

    private fun fail(failure: MusicFailure) {
        invalidate(); loading = false; desiredPlay = false
        interruptions.reset(); focus.abandon(); player.pause()
        publishState(state.copy(phase = PlaybackPhase.ERROR, musicFailure = failure, canSeek = false))
    }

    private fun invalidate(): Long {
        generation++
        pending?.cancel(); refill?.cancel(); bufferTimeout?.cancel(); queue.invalidate()
        return generation
    }

    private fun itemMatches() = itemId != null && player.currentMediaItem?.mediaId == itemId

    private fun publishState(value: PlayerUiState) {
        val duration = if (itemMatches()) player.duration.takeIf { it > 0 } else null
        state = value.copy(canPrevious = queue.canPrevious, canNext = queue.canNext,
            durationMs = duration ?: audio?.durationMs,
            positionMs = if (itemMatches()) player.currentPosition.coerceAtLeast(0) else null,
            canSeek = !loading && value.phase != PlaybackPhase.ERROR && itemMatches() && audio?.seekable == true &&
                player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        if (active) publish(state)
    }

    private fun watchBuffer(id: Long) {
        bufferTimeout?.cancel()
        bufferTimeout = scope.launch {
            delay(8000)
            if (active && id == generation && desiredPlay && !player.isPlaying) fail(MusicFailure.SOURCE_TIMEOUT)
        }
    }

    internal fun onExternalOutputRemoved() { if (active) pause(PauseReason.OUTPUT) }

    internal fun onAudioFocusEvent(event: AudioFocusEvent) {
        if (!active) return
        when (event) {
            AudioFocusEvent.GAIN -> {
                player.volume = 1f
                if (NetworkPolicy.evaluate(network.value) != NetworkDecision.ALLOWED && interruptions.hasResumeIntent) pause(PauseReason.NETWORK)
                if (interruptions.focusGained()) resume()
            }
            AudioFocusEvent.DUCK -> player.volume = .2f
            AudioFocusEvent.TRANSIENT_LOSS -> pause(PauseReason.TRANSIENT_FOCUS)
            AudioFocusEvent.PERMANENT_LOSS -> pause(PauseReason.PERMANENT_FOCUS)
            AudioFocusEvent.DENIED -> fail(MusicFailure.PLAYBACK_UNAVAILABLE)
        }
    }
}
