package com.meowv.tune.playback

import com.meowv.tune.domain.*
import com.meowv.tune.domain.music.MusicTrack

// 所有入口在服务主线程同步交接归属，耗时解析留给各引擎可取消的子任务。
internal class PlaybackCoordinator(
    private val radio: RadioPlayback,
    internal val music: MusicPlaybackEngine,
    private val publish: (PlayerUiState) -> Unit
) : RadioSessionPlayer.SessionCommands {
    var activeSource = PlaybackSource.RADIO
        private set

    fun publishRadio(state: PlayerUiState) { if (activeSource == PlaybackSource.RADIO) publish(state) }
    fun publishMusic(state: PlayerUiState) { if (activeSource == PlaybackSource.MUSIC) publish(state) }

    fun playRadio(station: Station) {
        if (activeSource != PlaybackSource.RADIO) {
            music.deactivate(); activeSource = PlaybackSource.RADIO; radio.activate()
        }
        radio.play(station)
    }

    fun playMusic(track: MusicTrack?) {
        if (activeSource != PlaybackSource.MUSIC) {
            radio.deactivate(); activeSource = PlaybackSource.MUSIC; music.activate()
        }
        music.play(track)
    }

    fun dispatch(command: PlayerCommand) {
        when (command) {
            is PlayerCommand.Play -> playRadio(command.station)
            is PlayerCommand.PlayMusic -> playMusic(command.track)
            is PlayerCommand.Seek -> if (activeSource == PlaybackSource.MUSIC) music.seek(command.positionMs)
            PlayerCommand.TogglePause -> if (activeSource == PlaybackSource.MUSIC) music.toggle() else radio.toggle()
            PlayerCommand.Next -> onSessionNext()
            PlayerCommand.Previous -> onSessionPrevious()
            PlayerCommand.Stop -> onSessionStop()
            PlayerCommand.CancelPending -> if (activeSource == PlaybackSource.RADIO) radio.cancelPending()
            PlayerCommand.CancelMusic -> if (activeSource == PlaybackSource.MUSIC) music.pause()
            is PlayerCommand.Resume -> {
                // 启动恢复仍遵循既有电台设置，已停止的音乐不能吞掉这个请求。
                if (activeSource == PlaybackSource.MUSIC && music.snapshot.phase == PlaybackPhase.IDLE) {
                    music.deactivate(); activeSource = PlaybackSource.RADIO; radio.activate()
                }
                if (activeSource == PlaybackSource.RADIO) radio.resumeLast(command.uuid)
            }
        }
    }

    override val canPrevious: Boolean get() = if (activeSource == PlaybackSource.MUSIC) music.snapshot.canPrevious else radio.canPrevious
    override val canNext: Boolean get() = if (activeSource == PlaybackSource.MUSIC) music.snapshot.canNext else radio.canNext
    override val canSeek: Boolean get() = activeSource == PlaybackSource.MUSIC && music.snapshot.canSeek
    override fun onSessionSeek(positionMs: Long) { if (activeSource == PlaybackSource.MUSIC) music.seek(positionMs) }
    override fun onSessionPlay() { if (activeSource == PlaybackSource.MUSIC) music.resume() else radio.onSessionPlay() }
    override fun onSessionPause() { if (activeSource == PlaybackSource.MUSIC) music.pause() else radio.onSessionPause() }
    override fun onSessionNext() { if (activeSource == PlaybackSource.MUSIC) music.next() else radio.next() }
    override fun onSessionPrevious() { if (activeSource == PlaybackSource.MUSIC) music.previous() else radio.previous() }
    override fun onSessionStop() { if (activeSource == PlaybackSource.MUSIC) music.stop() else radio.stop() }
    fun close() { music.close(); radio.close() }
}
