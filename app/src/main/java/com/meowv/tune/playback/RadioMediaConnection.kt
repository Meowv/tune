package com.meowv.tune.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.PlayerController
import com.meowv.tune.domain.PlayerUiState
import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class RadioMediaConnection(context: Context) : PlayerController {
    private val app = context.applicationContext
    private val token = SessionToken(app, ComponentName(app, RadioPlaybackService::class.java))
    private var leases = 0
    private var pending: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val connectedState = MutableStateFlow(false)
    val connected = connectedState.asStateFlow()
    private val playback = MutableStateFlow(PlayerUiState())
    override val state = playback.asStateFlow()

    internal var engine: RadioPlayback? = null
        set(value) {
            field = value
            if (value == null) playback.value = PlayerUiState()
        }

    internal fun publish(value: PlayerUiState) { playback.value = value }

    @MainThread
    fun acquire(): AutoCloseable {
        leases++
        if (pending == null) connect()
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                if (--leases == 0) release()
            }
        }
    }

    private fun connect() {
        val future = MediaController.Builder(app, token).setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                if (this@RadioMediaConnection.controller !== controller) return
                release()
                if (leases > 0) connect()
            }
        }).buildAsync()
        pending = future
        future.addListener({
            if (pending === future) {
                val media = runCatching { future.get() }.getOrNull()
                if (media?.isConnected == true) {
                    controller = media
                    connectedState.value = true
                } else release()
            }
        }, app.mainExecutor)
    }

    private fun release() {
        controller = null
        val future = pending
        pending = null
        connectedState.value = false
        future?.let(MediaController::releaseFuture)
    }

    override suspend fun dispatch(command: PlayerCommand) = withContext(Dispatchers.Main.immediate) {
        val media = controller ?: throw TuneFailure(TuneError.PLAYBACK)
        when (command) {
            is PlayerCommand.Play -> requireEngine().play(command.station)
            PlayerCommand.TogglePause -> requireEngine().toggle()
            PlayerCommand.Next -> if (media.hasNextMediaItem()) media.seekToNextMediaItem() else Unit
            PlayerCommand.Previous -> if (media.hasPreviousMediaItem()) media.seekToPreviousMediaItem() else Unit
            PlayerCommand.Stop -> requireEngine().stop()
        }
    }

    private fun requireEngine() = engine ?: throw TuneFailure(TuneError.PLAYBACK)
}
