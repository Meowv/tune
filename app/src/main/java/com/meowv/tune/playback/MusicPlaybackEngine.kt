package com.meowv.tune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.*
import com.meowv.tune.music.MusicFeedStore
import com.meowv.tune.music.MusicListeningHistory
import com.meowv.tune.music.MusicListeningTracker
import com.meowv.tune.music.MusicQueueManager
import com.meowv.tune.music.MusicSelection
import com.meowv.tune.music.MusicResumePoint
import com.meowv.tune.music.MusicResumeStore
import com.meowv.tune.music.findRememberedTrack
import com.meowv.tune.music.MusicNextPreparation
import com.meowv.tune.music.MusicRequestGate
import com.meowv.tune.music.musicRetryAfterSeconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicPlaybackEngine(
    context: Context,
    private val player: Player,
    private val repository: () -> MusicRepository?,
    private val network: StateFlow<NetworkState>,
    private val resumeStore: MusicResumeStore,
    private val feedStore: MusicFeedStore,
    private val listeningHistory: MusicListeningHistory? = null,
    private val publish: (PlayerUiState) -> Unit
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = MusicQueueManager(
        { repository() ?: throw MusicException(MusicFailure.NOT_CONFIGURED) }, feedStore = feedStore, listening = listeningHistory
    )
    private val listening = listeningHistory?.let { MusicListeningTracker(android.os.SystemClock::elapsedRealtime, started = it::started, emit = it::record) }
    private val focus = AudioFocusController(context, ::onAudioFocusEvent)
    private val route = AudioRouteMonitor(context, ::onExternalOutputRemoved)
    private val interruptions = MusicInterruptionState(android.os.SystemClock::elapsedRealtime)
    private val diagnostics = PlaybackDiagnostics.forDebug(PlaybackSource.MUSIC)
    private var active = false
    private var generation = 0L
    private var pending: Job? = null
    private var refill: Job? = null
    private var lyricsRequest: Job? = null
    private var refillSuppressed = false
    private var advancing = false
    private val nextPreparation = MusicNextPreparation(repository, scope, ::backgroundFailure)
    private var bufferTimeout: Job? = null
    private val stallMonitor = PlaybackStallMonitor(
        scope,
        android.os.SystemClock::elapsedRealtime,
        progress = {
            PlaybackProgress(player.currentPosition, player.playbackState == Player.STATE_BUFFERING, player.playWhenReady)
        },
        onStalled = {
            diagnostics?.stalled()
            fail(MusicFailure.SOURCE_TIMEOUT)
        }
    )
    private var loading = false
    private var desiredPlay = false
    private var current: MusicSelection? = null
    private var audio: PlayableAudio? = null
    private var itemId: String? = null
    private var endedItem: String? = null
    private var confirmed = false
    private var resourceRefreshed = false
    private var alternateAttempted = false
    private val requestGate = MusicRequestGate()
    private var failures = 0
    private var operationSequence = 0L
    private var resumePoint: MusicResumePoint? = null
    private var lastCheckpointAt = 0L
    private var state = PlayerUiState(source = PlaybackSource.MUSIC)
    val snapshot: PlayerUiState get() = state

    init {
        player.addListener(this)
        scope.launch {
            feedStore.snapshot.collect {
                if (!active || state.phase == PlaybackPhase.IDLE || !syncQueueFeed()) return@collect
                if (advancing) advanceQueue(PlaybackTrigger.AUTO_ADVANCE)
                else {
                    publishState(state)
                    maintainAhead()
                }
            }
        }
        scope.launch {
            network.collect { value ->
                if (!active) return@collect
                if (NetworkPolicy.evaluate(value) != NetworkDecision.ALLOWED) {
                    if (desiredPlay || interruptions.hasResumeIntent) pause(PauseReason.NETWORK)
                } else if (interruptions.networkAvailable() ||
                    resumePoint?.playOnEntry == true && state.phase == PlaybackPhase.PAUSED && state.error == null) {
                    resume(PlaybackTrigger.RECOVERY)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(500)
                if (active && itemMatches()) listening?.observe(player.isPlaying && player.playerError == null)
                if (active && !loading && itemMatches()) {
                    publishState(state)
                    if (player.isPlaying && android.os.SystemClock.elapsedRealtime() - lastCheckpointAt >= 3_000) {
                        checkpoint()
                    }
                    maintainAhead()
                }
            }
        }
    }

    fun setShuffle(enabled: Boolean) {
        if (queue.shuffle == enabled) return
        queue.shuffle = enabled
        nextPreparation.cancel()
    }

    fun activate() { active = true }
    fun deactivate() { if (active) stop(); active = false }

    suspend fun rememberedPlayback(): MusicResumePoint? =
        if (repository() == null) null else resumeStore.read()

    fun restore(point: MusicResumePoint, autoPlay: Boolean) {
        queue.reset()
        resumePoint = point
        // 「自动播放」关闭时只恢复上下文不发声；网络受限同样降级为暂停态。
        if (autoPlay && point.playOnEntry &&
            NetworkPolicy.evaluate(network.value) == NetworkDecision.ALLOWED
        ) {
            continueRemembered(point)
        } else {
            showRemembered(point)
        }
    }

    private fun continueRemembered(point: MusicResumePoint, trigger: PlaybackTrigger = PlaybackTrigger.RESUME) {
        if (!mayRequest(preserveCurrent = true)) return
        begin(trigger, saved = point) { queue.select(point.track) }
    }

    private fun showRemembered(point: MusicResumePoint, failure: MusicFailure? = null) {
        desiredPlay = false
        loading = false
        clearSupersededItem()
        resumePoint = point
        publishState(PlayerUiState(source = PlaybackSource.MUSIC, phase = PlaybackPhase.PAUSED,
            music = point.track, positionMs = point.positionMs, durationMs = point.track.durationMs,
            musicFailure = failure, error = failure?.let { TuneError.PLAYBACK }))
    }

    fun checkpoint() {
        if (!active || !confirmed || !itemMatches() || player.playbackState == Player.STATE_ENDED) return
        val track = current?.track ?: return
        if (state.music?.id != track.id) return
        val duration = player.duration.takeIf { it > 0 } ?: audio?.durationMs
        val position = player.currentPosition.coerceAtLeast(0)
        // 在结束事件来得及提交之前，尾部等于总时长的位置也不能当作未完成歌曲保存。
        if (duration != null && position >= duration) return
        resumeStore.save(MusicResumePoint(track, position, desiredPlay || interruptions.hasResumeIntent))
        lastCheckpointAt = android.os.SystemClock.elapsedRealtime()
    }

    fun play(track: MusicTrack? = null, resetSession: Boolean = true, seed: MusicQueueSeed? = null,
        trigger: PlaybackTrigger = PlaybackTrigger.PLAY) {
        if (!active || track != null && !track.canStartPlayback || !mayRequest()) return
        failures = 0
        resumePoint = null
        interruptions.reset()
        if (resetSession || !queue.canNext) queue.reset(seed, track?.id)
        syncQueueFeed()
        begin(trigger, explicit = track != null, advance = track == null) {
            if (track == null) queue.next() else queue.select(track)
        }
    }

    fun next() = advanceQueue(PlaybackTrigger.NEXT)

    private fun advanceQueue(trigger: PlaybackTrigger) {
        if (!active || !mayRequest()) return
        syncQueueFeed()
        if (trigger == PlaybackTrigger.NEXT && state.phase !in setOf(PlaybackPhase.IDLE, PlaybackPhase.ERROR)) state.music?.let {
            if (!loading && confirmed && itemMatches()) listening?.finish(earlySkip = true)
            queue.skip(it, affectRanking = !loading && confirmed && current?.track?.id == it.id &&
                state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED))
        }
        interruptions.reset()
        resumePoint = null
        begin(trigger, keepRefill = true, advance = true) { queue.next() }
    }

    fun previous() {
        if (!active || !queue.canPrevious || !mayRequest(preserveCurrent = true)) return
        interruptions.reset()
        begin(PlaybackTrigger.PREVIOUS, preserveCurrent = true, explicit = true) { queue.previous() }
    }

    private fun begin(
        trigger: PlaybackTrigger,
        preserveCurrent: Boolean = false,
        refreshProvider: String? = null,
        excludedProvider: String? = null,
        keepRefill: Boolean = false,
        saved: MusicResumePoint? = null,
        advance: Boolean = false,
        explicit: Boolean = false,
        select: suspend () -> MusicSelection?
    ) {
        val oldState = state
        val previousPosition = if (refreshProvider != null || excludedProvider != null)
            player.currentPosition.coerceAtLeast(0) else 0
        checkpoint()
        var refreshed = refreshProvider != null || excludedProvider != null
        val attemptedAlternate = excludedProvider != null || refreshProvider != null && alternateAttempted
        if (NetworkPolicy.evaluate(network.value) != NetworkDecision.ALLOWED) {
            diagnostics?.start(trigger)
            fail(MusicFailure.CONNECTION)
            return
        }
        val id = invalidate(keepRefill)
        diagnostics?.start(trigger)
        advancing = advance
        desiredPlay = true
        loading = true
        if (!preserveCurrent) {
            if (refreshProvider != null || excludedProvider != null) listening?.observe(false) else listening?.finish()
            itemId = null
            // 保留上一个会话条目，直到可用音源直接接替它。
            player.stop()
            focus.abandon()
        }
        publishState(state.copy(phase = PlaybackPhase.RESOLVING, musicFailure = null, error = null, operationFailure = null, canSeek = false))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var selected = false
            var prepared: Deferred<Result<PlayableAudio>>? = null
            try {
                val selectedTrack = withTimeout(8000) { select() }
                    ?: throw MusicException(MusicFailure.NO_PLAYABLE_MATCH)
                ensureActive()
                selected = true
                if (advance) prepared = nextPreparation.take(selectedTrack.track.id)
                if (refreshProvider == null && excludedProvider == null) listeningHistory?.attempted()
                if (!preserveCurrent) publishState(state.copy(music = selectedTrack.track))
                diagnostics?.resolving()
                val result = withTimeout(8000) {
                    var selection = selectedTrack
                    var retainedVersion: Long? = null
                    val source = repository() ?: throw MusicException(MusicFailure.NOT_CONFIGURED)
                    var playable = try {
                        val ahead = prepared?.await()?.getOrThrow()
                        ensureActive()
                        val cached = ahead?.takeIf { it.trackId == selection.track.id && nextPreparation.fresh(it) }
                        if (ahead != null && cached == null) refreshed = true
                        if (excludedProvider != null) source.resolveAlternate(selection.track.id, excludedProvider)
                        else cached ?: source.resolve(selection.track.id, refreshProvider ?: ahead?.providerId)
                    } catch (failure: MusicException) {
                        if (failure.failure !in MissingTrack || excludedProvider != null) throw failure
                        val feed = feedStore.restore()
                        val retained = feed.fallback.firstOrNull { it.id == selection.track.id }
                        val rememberedTrack = saved?.track
                            ?: retained
                            ?: throw failure
                        val fresh = source.findRememberedTrack(rememberedTrack)
                        ensureActive()
                        selection = selection.copy(track = fresh)
                        if (retained != null && retained != fresh) retainedVersion = feed.version
                        source.resolve(fresh.id)
                    }
                    ensureActive()
                    if (!nextPreparation.fresh(playable) && !refreshed) {
                        refreshed = true
                        playable = source.resolve(selection.track.id, playable.providerId)
                    }
                    if (playable.trackId != selection.track.id || playable.providerId == excludedProvider || !nextPreparation.fresh(playable) ||
                        !com.meowv.tune.music.MusicAudioUrlPolicy.allows(playable.url)) throw MusicException(MusicFailure.INVALID_RESPONSE)
                    retainedVersion?.let { feedStore.refreshFallback(it, selectedTrack.track.id, selection.track) }
                    selection to playable
                }
                ensureActive()
                if (!active || id != generation) return@launch
                if (advance && restartAdvanceIfFeedChanged()) return@launch
                loading = false
                resourceRefreshed = refreshed
                alternateAttempted = attemptedAlternate
                val (selection, playable) = result
                val duration = playable.durationMs ?: selection.track.durationMs
                val position = saved?.startPosition(playable.seekable, duration)
                    ?: previousPosition.takeIf { playable.seekable && duration != null && it in 0 until duration } ?: 0
                prepare(selection, playable, id, position, explicit, refreshProvider != null || excludedProvider != null,
                    automatic = !explicit && saved == null)
            } catch (failure: TimeoutCancellationException) {
                if (active && id == generation) {
                    if (advance && restartAdvanceIfFeedChanged()) return@launch
                    val error = MusicException(MusicFailure.SOURCE_TIMEOUT)
                    if (saved == null) loadFailed(error, preserveCurrent, oldState, selected) else restoreFailed(error, saved)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MusicException) {
                ensureActive()
                if (active && id == generation) {
                    if (advance && restartAdvanceIfFeedChanged()) return@launch
                    if (saved == null) loadFailed(failure, preserveCurrent, oldState, selected) else restoreFailed(failure, saved)
                }
            } finally {
                prepared?.cancel()
            }
        }
        // 先登记再启动，避免连续同步失败时外层任务覆盖最新的取消句柄。
        pending = job
        job.start()
    }

    private fun loadFailed(failure: MusicException, preserve: Boolean, old: PlayerUiState, selected: Boolean) {
        loading = false
        if (failure.failure == MusicFailure.RATE_LIMITED) requestGate.block(failure.retryAfterSeconds)
        val sourceBackoff = failure.retryAfterSeconds != null &&
            failure.failure in setOf(MusicFailure.SOURCE_UNAVAILABLE, MusicFailure.SOURCE_TIMEOUT)
        if (sourceBackoff) requestGate.block(failure.retryAfterSeconds, failure.failure)
        if (preserve && itemMatches() && player.playbackState != Player.STATE_ENDED) {
            diagnostics?.failed(failure.failure)
            if (player.isPlaying) diagnostics?.playing(completeAttempt = false)
            queue.invalidate()
            desiredPlay = player.playWhenReady
            publishState(old.copy(operationFailure = PlaybackOperationFailure(++operationSequence, TuneError.PLAYBACK)))
        } else if (!preserve && !sourceBackoff && selected && failure.failure in setOf(MusicFailure.TRACK_NOT_FOUND,
                MusicFailure.NO_PLAYABLE_MATCH, MusicFailure.SOURCE_UNAVAILABLE, MusicFailure.SOURCE_TIMEOUT,
                MusicFailure.PLAYBACK_UNAVAILABLE) && ++failures < 5 && queue.canNext) {
            diagnostics?.failed(failure.failure)
            advanceQueue(PlaybackTrigger.RECOVERY)
        } else {
            fail(failure.failure)
        }
    }

    private fun restoreFailed(failure: MusicException, point: MusicResumePoint) {
        diagnostics?.failed(failure.failure)
        if (failure.failure in MissingTrack) {
            resumeStore.save(null)
            resumePoint = null
            advanceQueue(PlaybackTrigger.RECOVERY)
        } else {
            backgroundFailure(failure)
            showRemembered(point, failure.failure)
        }
    }

    private fun prepare(selection: MusicSelection, playable: PlayableAudio, id: Long, positionMs: Long,
        explicit: Boolean, continuing: Boolean, automatic: Boolean) {
        diagnostics?.preparing()
        listening?.start(selection.track, explicit, continuing, automatic)
        lyricsRequest?.cancel()
        lyricsRequest = null
        current = selection; audio = playable; confirmed = false
        endedItem = null
        itemId = null
        publishState(PlayerUiState(source = PlaybackSource.MUSIC, phase = PlaybackPhase.BUFFERING, music = selection.track))
        if (!focus.acquire()) { fail(MusicFailure.PLAYBACK_UNAVAILABLE); return }
        val title = selection.track.title
        val artist = selection.track.artists.joinToString(" / ")
        val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .setDisplayTitle(title).setSubtitle(artist)
            .setAlbumTitle(selection.track.album).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsPlayable(true)
            .apply { selection.track.coverUrl?.takeIf { MediaUrlPolicy.parse(it)?.isHttps == true }?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val item = MediaItem.Builder().setMediaId("music:${selection.track.id}")
            .setUri(playable.url).setMimeType(playable.mimeType).setMediaMetadata(metadata).build()
        itemId = item.mediaId
        if (player.currentMediaItem == item) player.seekTo(positionMs) else player.setMediaItem(item, positionMs)
        player.prepare()
        player.play()
        watchBuffer(id)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (active && itemMatches()) listening?.observe(player.isPlaying && player.playerError == null)
        if (!active || loading || state.phase == PlaybackPhase.ERROR || !itemMatches()) return
        val error = player.playerError
        stallMonitor.sync(confirmed && desiredPlay && player.playWhenReady && error == null &&
            player.playbackState == Player.STATE_BUFFERING)
        when {
            error != null -> {
                val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val selection = current
                val provider = audio?.providerId
                if (http?.responseCode in setOf(404, 410) && desiredPlay && selection != null && provider != null) {
                    if (!resourceRefreshed) {
                        if (mayRequest()) {
                            diagnostics?.failed(TuneError.HTTP)
                            begin(PlaybackTrigger.RECOVERY, refreshProvider = provider) { queue.renew(selection) }
                        }
                        return
                    }
                    if (!alternateAttempted && selection.track.matches.any { it.providerId != provider }) {
                        if (mayRequest()) {
                            diagnostics?.failed(TuneError.HTTP)
                            begin(PlaybackTrigger.RECOVERY, excludedProvider = provider) { queue.renew(selection) }
                        }
                        return
                    }
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
                if (desiredPlay && reason == MusicFailure.PLAYBACK_UNAVAILABLE && ++failures < 5 && queue.canNext) {
                    diagnostics?.failed(reason)
                    advanceQueue(PlaybackTrigger.RECOVERY)
                } else fail(reason)
            }
            player.isPlaying -> {
                diagnostics?.playing()
                advancing = false
                bufferTimeout?.cancel()
                if (!confirmed) {
                    confirmed = current?.let(queue::recordSuccessfulPlayback) == true
                    if (confirmed) {
                        failures = 0
                        refillSuppressed = false
                        resumePoint = null
                        checkpoint()
                        loadLyrics()
                    }
                }
                publishState(state.copy(phase = PlaybackPhase.PLAYING, error = null, musicFailure = null))
                maintainAhead()
            }
            player.playbackState == Player.STATE_ENDED -> {
                if (endedItem != itemId) {
                    endedItem = itemId
                    listening?.finish(completed = true)
                    resumeStore.save(null)
                    resumePoint = null
                    if (desiredPlay) {
                        advanceQueue(PlaybackTrigger.AUTO_ADVANCE)
                        queue.clearSkipSignal()
                    }
                }
            }
            !player.playWhenReady -> publishState(state.copy(phase = PlaybackPhase.PAUSED))
            else -> {
                if (player.playbackState == Player.STATE_BUFFERING) diagnostics?.buffering()
                publishState(state.copy(phase = PlaybackPhase.BUFFERING))
            }
        }
    }

    fun toggle() {
        if (desiredPlay || loading) pause() else resume()
    }

    fun pause(reason: PauseReason = PauseReason.USER) {
        if (!active) return
        diagnostics?.reset(reason)
        val paused = interruptions.pause(state, reason, desiredPlay)
        invalidate()
        loading = false; desiredPlay = false
        if (reason != PauseReason.TRANSIENT_FOCUS) focus.abandon()
        player.pause(); player.volume = 1f
        clearSupersededItem()
        if (reason == PauseReason.USER) resumePoint?.let {
            resumePoint = it.copy(playOnEntry = false).also(resumeStore::save)
        }
        publishState(paused)
        checkpoint()
    }

    fun resume(trigger: PlaybackTrigger = PlaybackTrigger.RESUME) {
        if (!active || desiredPlay || loading) return
        interruptions.reset()
        if (NetworkPolicy.evaluate(network.value) != NetworkDecision.ALLOWED) {
            diagnostics?.failed(TuneError.NETWORK_OFFLINE)
            publishState(state.copy(error = TuneError.NETWORK_OFFLINE)); return
        }
        resumePoint?.let { continueRemembered(it, trigger); return }
        if (!itemMatches() || player.playbackState == Player.STATE_ENDED || state.phase == PlaybackPhase.ERROR) {
            play(state.music, resetSession = false, trigger = trigger)
        } else if (audio?.let { !nextPreparation.fresh(it) } == true) {
            val selection = current ?: return
            val provider = audio?.providerId ?: return
            if (mayRequest()) begin(trigger, refreshProvider = provider) { queue.renew(selection) }
        } else if (focus.acquire()) {
            diagnostics?.start(trigger)
            diagnostics?.preparing()
            if (!confirmed) current = current?.let(queue::renew)
            desiredPlay = true
            refillSuppressed = false
            publishState(state.copy(phase = PlaybackPhase.BUFFERING, error = null))
            player.play(); watchBuffer(generation)
        }
    }

    private fun loadLyrics() {
        val selection = current ?: return
        val track = selection.track
        val provider = audio?.providerId ?: return
        val owner = itemId ?: return
        lyricsRequest = scope.launch {
            val lines = try {
                repository()?.lyrics(track.id, provider).orEmpty()
            } catch (_: MusicException) {
                emptyList()
            }
            if (isActive && current === selection && owner == itemId && lines.isNotEmpty()) {
                publishState(state.copy(musicLyrics = lines))
            }
        }
    }

    fun seek(positionMs: Long) {
        if (active && state.canSeek && !loading) {
            val position = positionMs.coerceIn(0, state.durationMs ?: Long.MAX_VALUE)
            if ((state.durationMs ?: Long.MAX_VALUE) - position > 8000) nextPreparation.cancel()
            player.seekTo(position)
            publishState(state)
            checkpoint()
            maintainAhead()
        }
    }

    fun stop(forget: Boolean = true) {
        diagnostics?.reset()
        listening?.finish()
        if (forget) resumeStore.save(null)
        resumePoint = null
        invalidate()
        desiredPlay = false; loading = false
        interruptions.reset(); focus.abandon()
        lyricsRequest?.cancel()
        lyricsRequest = null
        itemId = null; current = null; audio = null
        player.stop(); player.clearMediaItems()
        publishState(PlayerUiState(source = PlaybackSource.MUSIC))
    }

    fun close() {
        if (active) {
            checkpoint()
            stop(forget = false)
            active = false
        }
        scope.cancel(); route.close(); focus.abandon(); player.removeListener(this)
    }

    private fun mayRequest(preserveCurrent: Boolean = false): Boolean = try { requestGate.check(); true } catch (failure: MusicException) {
        if (preserveCurrent && itemMatches() && player.playbackState != Player.STATE_ENDED) {
            diagnostics?.failed(failure.failure)
            if (player.isPlaying) diagnostics?.playing(completeAttempt = false)
            publishState(state.copy(operationFailure = PlaybackOperationFailure(++operationSequence, TuneError.PLAYBACK)))
        } else fail(failure.failure)
        false
    }

    private fun fail(failure: MusicFailure) {
        diagnostics?.failed(failure)
        listening?.finish()
        invalidate(); loading = false; desiredPlay = false
        interruptions.reset(); focus.abandon(); player.pause()
        clearSupersededItem()
        publishState(state.copy(phase = PlaybackPhase.ERROR, musicFailure = failure, canSeek = false))
    }

    private fun invalidate(keepRefill: Boolean = false): Long {
        generation++
        advancing = false
        pending?.cancel(); bufferTimeout?.cancel(); stallMonitor.reset(); queue.invalidate()
        if (!keepRefill) {
            nextPreparation.cancel()
            refill?.cancel()
        }
        return generation
    }

    private fun syncQueueFeed(): Boolean {
        if (!queue.syncFeed()) return false
        refill?.cancel()
        nextPreparation.cancel()
        refillSuppressed = false
        return true
    }

    private fun restartAdvanceIfFeedChanged(): Boolean {
        if (!syncQueueFeed()) return false
        advanceQueue(PlaybackTrigger.AUTO_ADVANCE)
        return true
    }

    private fun maintainAhead() {
        if (!active || loading || !desiredPlay || !confirmed || !itemMatches() || !player.isPlaying ||
            NetworkPolicy.evaluate(network.value) != NetworkDecision.ALLOWED) return
        try { requestGate.check() } catch (_: MusicException) { return }
        if (!refillSuppressed && (queue.needsReplenish || queue.needsFallbackPrefetch) && refill?.isActive != true) {
            val owner = queue.sessionVersion
            refillSuppressed = true
            refill = scope.launch {
                try {
                    withTimeout(8000) {
                        if (queue.needsReplenish) queue.replenish()
                        else queue.prefetchFallback()
                    }
                    ensureActive()
                    if (active && owner == queue.sessionVersion) {
                        refillSuppressed = true
                        publishState(state)
                    }
                } catch (_: TimeoutCancellationException) {
                    ensureActive()
                    if (active && owner == queue.sessionVersion) refillSuppressed = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: MusicException) {
                    ensureActive()
                    if (active && owner == queue.sessionVersion) {
                        refillSuppressed = true
                        backgroundFailure(failure)
                    }
                }
            }
        }
        try { requestGate.check() } catch (_: MusicException) { return }
        val duration = player.duration.takeIf { it > 0 } ?: audio?.durationMs ?: return
        if (duration - player.currentPosition in 1..8000) queue.nextTrack?.let(nextPreparation::prepare)
    }

    private fun backgroundFailure(failure: MusicException) {
        if (failure.failure == MusicFailure.RATE_LIMITED) requestGate.block(failure.retryAfterSeconds)
        else if (failure.retryAfterSeconds != null && failure.failure in
            setOf(MusicFailure.SOURCE_UNAVAILABLE, MusicFailure.SOURCE_TIMEOUT))
            requestGate.block(failure.retryAfterSeconds, failure.failure)
    }

    private fun itemMatches() = itemId != null && player.currentMediaItem?.mediaId == itemId

    private fun clearSupersededItem() {
        if (itemId == null && player.mediaItemCount > 0) player.clearMediaItems()
    }

    private fun publishState(value: PlayerUiState) {
        val matches = itemMatches()
        val duration = if (matches) player.duration.takeIf { it > 0 } ?: audio?.durationMs else null
        val remembered = resumePoint?.takeIf { it.track.id == value.music?.id }
        state = value.copy(canPrevious = queue.canPrevious, canNext = queue.canNext,
            durationMs = duration ?: remembered?.track?.durationMs,
            positionMs = if (matches) player.currentPosition.coerceAtLeast(0) else remembered?.let {
                it.track.durationMs?.let { duration -> it.startPosition(true, duration) } ?: it.positionMs
            },
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
                if (interruptions.focusGained()) resume(PlaybackTrigger.RECOVERY)
            }
            AudioFocusEvent.DUCK -> player.volume = .2f
            AudioFocusEvent.TRANSIENT_LOSS -> pause(PauseReason.TRANSIENT_FOCUS)
            AudioFocusEvent.PERMANENT_LOSS -> pause(PauseReason.PERMANENT_FOCUS)
            AudioFocusEvent.DENIED -> fail(MusicFailure.PLAYBACK_UNAVAILABLE)
        }
    }

    private companion object {
        val MissingTrack = setOf(MusicFailure.TRACK_NOT_FOUND, MusicFailure.NO_PLAYABLE_MATCH)
    }
}
