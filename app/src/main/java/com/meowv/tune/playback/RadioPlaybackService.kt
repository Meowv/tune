package com.meowv.tune.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.meowv.tune.MainActivity
import com.meowv.tune.R
import com.meowv.tune.TuneApplication
import com.meowv.tune.domain.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class RadioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var publishedButtons: List<CommandButton> = emptyList()
    private var publishedCommands: Player.Commands? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val app = application as TuneApplication
        val transport = app.playbackCalls
        val dataSources = OkHttpDataSource.Factory(transport).setUserAgent("MeowvTune")
        val audio = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(audio, false)
            .build()
        var coordinator: PlaybackCoordinator? = null
        val engine = RadioPlayback(
            this,
            player,
            app.radioQueue,
            app.userSettings,
            app.networkState,
            app.radioRepository,
            app.playbackRetryPolicy,
            app::resolveStream,
            { state -> coordinator?.publishRadio(state) ?: app.playerController.publish(state) }
        )
        val music = MusicPlaybackEngine(this, player, { app.musicRepository }, app.networkState,
            { state -> coordinator?.publishMusic(state) })
        coordinator = PlaybackCoordinator(engine, music, app.playerController::publish)
        app.playerController.coordinator = coordinator
        app.playerController.engine = engine
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mediaSession = MediaSession.Builder(this, RadioSessionPlayer(player, coordinator))
            .setSessionActivity(activity)
            .setCallback(SessionCallback())
            .build()
        session = mediaSession
        setMediaNotificationProvider(
            object : DefaultMediaNotificationProvider(
                this,
                { DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.notification_channel_playback
            ) {
                override fun getMediaButtons(
                    session: MediaSession,
                    playerCommands: Player.Commands,
                    mediaButtonPreferences: ImmutableList<CommandButton>,
                    showPauseButton: Boolean
                ): ImmutableList<CommandButton> {
                    val buttons = super.getMediaButtons(session, playerCommands, mediaButtonPreferences, showPauseButton)
                    if (!applicationSourceIsMusic) return buttons
                    // Media3 的标准前后曲按钮不采用偏好中的 displayName，通知需在此同步文案。
                    return ImmutableList.copyOf(buttons.map { button ->
                        val label = when (button.playerCommand) {
                            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS -> R.string.music_previous
                            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT -> R.string.music_next
                            else -> null
                        }
                        if (label == null) button else CommandButton.Builder(button.icon)
                            .setPlayerCommand(button.playerCommand).setDisplayName(getString(label))
                            .setEnabled(button.isEnabled).setExtras(button.extras).setSlots(*button.slots.toArray()).build()
                    })
                }
            }.apply { setSmallIcon(R.drawable.ic_stat_tune) }
        )
        // 会话只在事件里转发底层 ExoPlayer 的命令集，这里在命令集变化后回填队列能力。
        mediaSession.player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) return
                publishedCommands = null
                publishCapabilities(mediaSession)
            }
        })
        scope.launch {
            app.playerController.state.map { listOf(it.source, it.canPrevious, it.canNext, it.canSeek) }
                .distinctUntilChanged().collect { publishCapabilities(mediaSession) }
        }
        scope.launch {
            combine(app.radioQueue.canPrevious, app.radioQueue.canNext) { previous, next -> previous to next }
                .distinctUntilChanged()
                .collect { publishCapabilities(mediaSession) }
        }
    }

    // 队列能力不会自动广播给媒体会话，按钮启用状态与可用命令需要显式推送。
    private fun publishCapabilities(mediaSession: MediaSession) {
        val buttons = mediaButtons()
        if (buttons != publishedButtons) {
            publishedButtons = buttons
            mediaSession.setMediaButtonPreferences(buttons)
        }
        val commands = mediaSession.player.availableCommands.buildUpon()
            .remove(Player.COMMAND_SET_MEDIA_ITEM)
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .build()
        if (commands != publishedCommands) {
            publishedCommands = commands
            mediaSession.connectedControllers.forEach { controller ->
                mediaSession.setAvailableCommands(controller, MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS, commands)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf { canConnect(controllerInfo) }

    override fun onDestroy() {
        scope.cancel()
        (application as TuneApplication).apply {
            sleepTimer.cancel()
            playerController.apply {
                coordinator?.close()
                coordinator = null
                engine = null
            }
        }
        session?.let {
            val player = it.player
            it.release()
            player.release()
        }
        session = null
        super.onDestroy()
    }

    // 系统媒体界面固定三个槽位，上一台/下一台按队列能力启用，避免布局随播放进度跳变。
    private fun mediaButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setDisplayName(getString(if (applicationSourceIsMusic) R.string.music_previous else R.string.player_previous))
            .setSlots(CommandButton.SLOT_BACK)
            .setEnabled(sessionPlayer.canPrevious)
            .build(),
        CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName(getString(R.string.action_play))
            .setSlots(CommandButton.SLOT_CENTRAL)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setDisplayName(getString(if (applicationSourceIsMusic) R.string.music_next else R.string.player_next))
            .setSlots(CommandButton.SLOT_FORWARD)
            .setEnabled(sessionPlayer.canNext)
            .build()
    )

    private val applicationSourceIsMusic: Boolean
        get() = (application as TuneApplication).playerController.coordinator?.activeSource == PlaybackSource.MUSIC

    private val sessionPlayer: RadioSessionPlayer
        get() = session?.player as RadioSessionPlayer

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!canConnect(controller)) {
                return MediaSession.ConnectionResult.reject()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .setMediaButtonPreferences(mediaButtons())
                .build()
        }
    }
    private companion object {
        const val CHANNEL_ID = "meowv_tune_playback"

        fun canConnect(controller: MediaSession.ControllerInfo): Boolean =
            controller.uid == Process.myUid() || controller.isTrusted
    }
}
