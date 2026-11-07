package com.meowv.tune.playback

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.meowv.tune.R
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.QueueSelection
import com.meowv.tune.domain.RadioQueue
import com.meowv.tune.domain.RadioRepository
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.SettingsRepository
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import com.meowv.tune.domain.TuneSettings
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
    private val context: Context,
    private val player: Player,
    private val queue: RadioQueue,
    private val settings: SettingsRepository,
    private val network: StateFlow<NetworkState>,
    private val repository: RadioRepository,
    private val retryPolicy: PlaybackRetryPolicy,
    private val resolve: suspend (PlaybackIntent) -> ResolvedRadioStream,
    private val publish: (PlayerUiState) -> Unit
) : Player.Listener, RadioSessionPlayer.SessionCommands {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private val audioFocus = AudioFocusController(context, ::onAudioFocusEvent)
    private val routeMonitor = AudioRouteMonitor(context, ::onExternalOutputRemoved)
    private var request: Job? = null
    private var intent: PlaybackIntent? = null
    private var selection: QueueSelection? = null
    private var blocked: Command? = null
    private var playbackScope: ResolvedRadioScope? = null
    private var latestSettings = TuneSettings()
    private var committed = false
    private var recovering = false
    private var meteredApproved = false
    private var resumeAfterFocusGain = false
    private var sameRetries = 0
    private var alternateCount = 0
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
                    latestSettings = user
                    reactToNetworkChange(current, user)
                }
        }
    }

    fun play(station: Station) = submit {
        resetRetryBudget()
        run(Command.Play(station), allowMetered = false)
    }

    fun next() = submit {
        resetRetryBudget()
        run(Command.Next, allowMetered = false)
    }

    fun previous() = submit {
        resetRetryBudget()
        run(Command.Previous, allowMetered = false)
    }

    fun toggle() = submit { toggle(allowMetered = false) }

    override val canPrevious: Boolean get() = queue.canPrevious.value
    override val canNext: Boolean get() = queue.canNext.value

    override fun onSessionPlay() = submit { resume(allowMetered = false) }

    override fun onSessionPause() = submit { pause() }

    override fun onSessionNext() = next()

    override fun onSessionPrevious() = previous()

    override fun onSessionStop() = stop()

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

    fun cancelPending() {
        supersedeRequest()
        recovering = false
        blocked = null
        resetRetryBudget()
        if (state.phase == PlaybackPhase.RESOLVING || state.phase == PlaybackPhase.RETRYING) {
            update(state.copy(
                phase = if (player.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED,
                error = null,
                statusCode = null
            ))
        }
    }

    /**
     * 启动恢复：仅在用户开启自播、当前空闲且范围队列可用时重新解析并播放最后成功台。
     * 范围不匹配、元数据失效或策略拒绝时保持空闲，由电台页承担后续操作。
     */
    fun resumeLast(uuid: UUID) = submit {
        if (selection != null || state.phase != PlaybackPhase.IDLE) return@submit
        val user = settings.settings.first()
        if (!user.autoPlay) return@submit
        val activeScope = queue.activeScope.value ?: return@submit
        val station = try {
            repository.stationByUuid(uuid)
        } catch (_: TuneFailure) {
            null
        } ?: return@submit
        val country = activeScope.country
        if (country != null && station.countryCode != country) return@submit
        resetRetryBudget()
        run(Command.Resume(station), allowMetered = false)
    }

    fun stop() {
        supersedeRequest()
        audioFocus.abandon()
        player.stop()
        player.clearMediaItems()
        intent = null
        selection = null
        playbackScope = null
        blocked = null
        committed = false
        recovering = false
        meteredApproved = false
        resumeAfterFocusGain = false
        resetRetryBudget()
        update(PlayerUiState())
    }

    fun close() {
        supersedeRequest()
        audioFocus.abandon()
        routeMonitor.close()
        scope.cancel()
        player.removeListener(this)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val currentSelection = selection ?: return
        if (state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING, PlaybackPhase.ERROR)) return
        when {
            player.isPlaying -> {
                if (!committed) {
                    committed = queue.recordSuccessfulPlayback(currentSelection)
                    // 只有服务确认成功的台站才写回自播恢复点。
                    if (committed) scope.launch { settings.recordSuccessfulStation(currentSelection.station.uuid) }
                }
                val clearFocusError = if (state.error == TuneError.AUDIO_FOCUS_LOST) null else state.error
                update(state.copy(phase = PlaybackPhase.PLAYING, error = clearFocusError))
            }
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> {
                update(state.copy(phase = PlaybackPhase.PAUSED, error = TuneError.AUDIO_FOCUS_LOST))
            }
            player.playbackState == Player.STATE_ENDED -> scheduleRecovery(TuneFailure(TuneError.PLAYBACK))
            player.playbackState == Player.STATE_IDLE -> {
                if (player.playerError == null && player.mediaItemCount > 0) stop()
            }
            !player.playWhenReady -> update(state.copy(phase = PlaybackPhase.PAUSED))
            else -> update(state.copy(phase = PlaybackPhase.BUFFERING))
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (selection == null || state.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING)) return
        scheduleRecovery(classify(error))
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
                playbackScope = activeScope
                resolveAndPlay(next, PlaybackPhase.RESOLVING)
            }
            Command.Next -> {
                val activeScope = queue.activeScope.value ?: run {
                    fail(TuneError.PLAYBACK)
                    return
                }
                val next = try {
                    queue.next(activeScope, cursor = null)
                } catch (failure: TuneFailure) {
                    failWhileKeepingCurrent(failure)
                    return
                } ?: run {
                    fail(TuneError.PLAYBACK)
                    return
                }
                playbackScope = activeScope
                resolveAndPlay(next, PlaybackPhase.RESOLVING)
            }
            Command.Previous -> {
                // 系统媒体键不感知按钮启用状态，无可返回项时保持当前播放而不报错。
                val activeScope = queue.activeScope.value ?: return
                val next = queue.previous(activeScope) ?: return
                playbackScope = activeScope
                resolveAndPlay(next, PlaybackPhase.RESOLVING)
            }
            Command.Toggle -> toggle(allowMetered)
            is Command.Resume -> {
                val activeScope = queue.activeScope.value ?: return
                val next = queue.select(activeScope, command.station) ?: return
                playbackScope = activeScope
                resolveAndPlay(next, PlaybackPhase.RESOLVING)
            }
        }
    }

    private suspend fun toggle(allowMetered: Boolean) {
        when (state.phase) {
            PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING -> stop()
            // 失败态下播放键即重试，不受残留的 playWhenReady 影响。
            PlaybackPhase.ERROR -> resume(allowMetered)
            else -> if (player.playWhenReady) pause() else resume(allowMetered)
        }
    }

    private suspend fun pause() {
        if (player.playWhenReady) userPause()
    }

    private suspend fun resume(allowMetered: Boolean) {
        when (state.phase) {
            PlaybackPhase.IDLE -> return
            PlaybackPhase.RESOLVING, PlaybackPhase.RETRYING -> return
            PlaybackPhase.ERROR -> {
                val currentIntent = intent ?: return
                val currentSelection = selection ?: return
                if (!networkAllows(Command.Toggle, allowMetered)) return
                resetRetryBudget()
                resolveAndPlay(currentSelection, PlaybackPhase.RETRYING, currentIntent)
            }
            else -> {
                if (!networkAllows(Command.Toggle, allowMetered)) return
                if (!audioFocus.acquire()) {
                    fail(TuneError.AUDIO_FOCUS_LOST)
                    return
                }
                resumeAfterFocusGain = false
                update(state.copy(error = null, statusCode = null))
                player.play()
            }
        }
    }

    private suspend fun networkAllows(command: Command, allowMetered: Boolean): Boolean {
        val user = settings.settings.first()
        latestSettings = user
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
        audioFocus.abandon()
        resumeAfterFocusGain = false
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
                    if (id == generation) failWhileKeepingCurrent(failure)
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
        audioFocus.abandon()
        player.stop()
        player.clearMediaItems()
        val playbackIntent = existingIntent ?: PlaybackIntent(next.station)
        selection = next
        intent = playbackIntent
        blocked = null
        committed = false
        update(PlayerUiState(phase = phase, station = next.station, isLive = true))

        val stream = try {
            resolve(playbackIntent)
        } catch (failure: TuneFailure) {
            currentCoroutineContext().ensureActive()
            if (id == generation) recoverFromFailure(next, playbackIntent, failure)
            return
        }
        currentCoroutineContext().ensureActive()
        if (id != generation) return
        val item = MediaItem.Builder()
            .setMediaId(next.station.uuid.toString())
            .setUri(stream.url.toString())
            .setMediaMetadata(sessionMetadata(next.station))
        if (stream.isHls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (!audioFocus.acquire()) {
            fail(TuneError.AUDIO_FOCUS_LOST)
            return
        }
        update(state.copy(phase = PlaybackPhase.BUFFERING, error = null, statusCode = null))
        player.setMediaItem(item.build())
        player.prepare()
        player.play()
    }

    private fun scheduleRecovery(failure: TuneFailure) {
        val currentSelection = selection ?: return
        val currentIntent = intent ?: PlaybackIntent(currentSelection.station)
        if (recovering) return
        recovering = true
        val id = ++generation
        request?.cancel()
        request = scope.launch {
            commandMutex.withLock {
                try {
                    if (id == generation) recoverFromFailure(currentSelection, currentIntent, failure)
                } finally {
                    recovering = false
                }
            }
        }
    }

    private suspend fun recoverFromFailure(
        failed: QueueSelection,
        failedIntent: PlaybackIntent,
        failure: TuneFailure
    ) {
        val id = generation
        currentCoroutineContext().ensureActive()
        val decision = NetworkPolicy.evaluate(network.value, settings.settings.first(), meteredApproved)
        if (decision != NetworkDecision.ALLOWED) {
            request?.cancel()
            blocked = Command.Toggle
            meteredApproved = false
            resumeAfterFocusGain = false
            audioFocus.abandon()
            player.pause()
            update(state.copy(phase = PlaybackPhase.PAUSED, error = decision.error, statusCode = null))
            return
        }
        val canRetrySame = retryPolicy.isRetryable(failure) && retryPolicy.canRetrySame(++sameRetries)
        if (canRetrySame) {
            update(state.copy(
                phase = PlaybackPhase.RETRYING,
                station = failed.station,
                error = failure.kind,
                statusCode = failure.statusCode
            ))
            delay(retryPolicy.delayFor(sameRetries))
            currentCoroutineContext().ensureActive()
            if (id == generation) resolveAndPlay(failed, PlaybackPhase.RETRYING, failedIntent)
            return
        }

        if (retryPolicy.canTryAlternate(alternateCount)) {
            alternateCount++
            sameRetries = 0
            val activeScope = queue.activeScope.value ?: run {
                fail(failure.kind, failure.statusCode, failed.station)
                return
            }
            update(state.copy(
                phase = PlaybackPhase.RETRYING,
                station = failed.station,
                error = failure.kind,
                statusCode = failure.statusCode
            ))
            val alternative = try {
                queue.next(activeScope, cursor = failed.station.uuid)
            } catch (directoryFailure: TuneFailure) {
                fail(directoryFailure.kind, directoryFailure.statusCode, failed.station)
                return
            } ?: run {
                fail(failure.kind, failure.statusCode, failed.station)
                return
            }
            currentCoroutineContext().ensureActive()
            if (id == generation) resolveAndPlay(alternative, PlaybackPhase.RETRYING)
            return
        }
        fail(failure.kind, failure.statusCode, failed.station)
    }

    // 系统媒体界面只读取这里的元数据，不额外暴露列表、历史或详情。
    private fun sessionMetadata(station: Station): MediaMetadata = MediaMetadata.Builder()
        .setTitle(station.name.ifBlank { context.getString(R.string.station_unnamed) })
        .setArtist(station.countryCode?.displayName(Locale.getDefault()) ?: context.getString(R.string.app_name))
        .setIsPlayable(true)
        .apply { artworkUri(station.iconUrl)?.let { setArtworkUri(it) } }
        .build()

    private fun artworkUri(url: String?): Uri? = url
        ?.trim()
        ?.takeIf { it.startsWith("https://", ignoreCase = true) }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun classify(error: PlaybackException): TuneFailure {
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return when {
            http != null -> TuneFailure(TuneError.HTTP, http.responseCode)
            causes.any { it is SSLException } -> TuneFailure(TuneError.TLS)
            causes.any { it is SocketTimeoutException } ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> TuneFailure(TuneError.TIMEOUT)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> TuneFailure(TuneError.CONNECTION)
            error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                TuneFailure(TuneError.UNSUPPORTED_MEDIA)
            else -> TuneFailure(TuneError.PLAYBACK)
        }
    }

    private fun userPause() {
        resumeAfterFocusGain = false
        audioFocus.abandon()
        player.pause()
    }

    internal fun onAudioFocusEvent(event: AudioFocusEvent) {
        scope.launch {
            when (event) {
                AudioFocusEvent.GAIN -> {
                    player.volume = 1f
                    if (resumeAfterFocusGain && selection != null) {
                        resumeAfterFocusGain = false
                        update(state.copy(error = null, statusCode = null))
                        player.play()
                    } else if (state.error == TuneError.AUDIO_FOCUS_LOST) {
                        update(state.copy(error = null, statusCode = null))
                    }
                }
                AudioFocusEvent.DUCK -> player.volume = .2f
                AudioFocusEvent.TRANSIENT_LOSS -> {
                    resumeAfterFocusGain = player.isPlaying || player.playWhenReady
                    player.volume = 1f
                    player.pause()
                    update(state.copy(phase = PlaybackPhase.PAUSED, error = TuneError.AUDIO_FOCUS_LOST))
                }
                AudioFocusEvent.PERMANENT_LOSS -> {
                    resumeAfterFocusGain = false
                    player.volume = 1f
                    player.pause()
                    audioFocus.abandon()
                    update(state.copy(phase = PlaybackPhase.PAUSED, error = TuneError.AUDIO_FOCUS_LOST))
                }
                AudioFocusEvent.DENIED -> fail(TuneError.AUDIO_FOCUS_LOST)
            }
        }
    }

    internal fun onExternalOutputRemoved() {
        scope.launch {
            if (selection == null || !latestSettings.pauseOnDisconnect) return@launch
            if (!player.isPlaying && !player.playWhenReady) return@launch
            resumeAfterFocusGain = false
            audioFocus.abandon()
            player.volume = 1f
            player.pause()
            update(state.copy(phase = PlaybackPhase.PAUSED, error = TuneError.AUDIO_DISCONNECTED))
        }
    }

    private fun failWhileKeepingCurrent(failure: TuneFailure) {
        if (player.isPlaying) {
            update(state.copy(error = failure.kind, statusCode = failure.statusCode))
        } else {
            fail(failure.kind, failure.statusCode)
        }
    }

    private fun resetRetryBudget() {
        sameRetries = 0
        alternateCount = 0
    }

    private fun supersedeRequest() {
        generation++
        request?.cancel()
        request = null
    }

    private fun fail(kind: TuneError, status: Int? = null, station: Station? = state.station) {
        audioFocus.abandon()
        update(state.copy(phase = PlaybackPhase.ERROR, station = station, error = kind, statusCode = status))
    }

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
        data class Resume(val station: Station) : Command
    }
}
