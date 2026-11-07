package com.meowv.tune.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * 把系统媒体命令（通知、锁屏、蓝牙耳机、车机方向盘）统一转成播放服务命令。
 *
 * 会话本身只持有一条直播流，原生 ExoPlayer 的 next/previous 没有队列语义。
 * 该代理保留全部播放状态读取，只改写命令入口和可用能力，使系统侧与 App 内按钮
 * 走同一套范围队列规则。
 *
 * 上一台/下一台在系统侧恒定声明可用：单条直播流没有播放列表，ExoPlayer 的
 * 可用能力会在换台过程中反复变化，媒体会话缓存随之抖动并可能拒收命令。
 * 真正的边界由范围队列与通知按钮的启用状态表达，无可返回项时命令为空操作。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RadioSessionPlayer(
    player: Player,
    private val commands: SessionCommands
) : ForwardingPlayer(player) {

    interface SessionCommands {
        val canPrevious: Boolean
        val canNext: Boolean
        fun onSessionPlay()
        fun onSessionPause()
        fun onSessionNext()
        fun onSessionPrevious()
        fun onSessionStop()
    }

    val canPrevious: Boolean get() = commands.canPrevious
    val canNext: Boolean get() = commands.canNext

    private val listeners = HashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val proxy = RadioSessionListener(listener, ::getAvailableCommands)
        listeners[listener] = proxy
        super.addListener(proxy)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listeners.remove(listener) ?: listener)
    }

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .removeAll(
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH
        )
        .build()

    override fun play() = commands.onSessionPlay()

    override fun pause() = commands.onSessionPause()

    override fun stop() = commands.onSessionStop()

    override fun seekToNext() = commands.onSessionNext()

    override fun seekToNextMediaItem() = commands.onSessionNext()

    override fun seekToNextWindow() = commands.onSessionNext()

    override fun seekToPrevious() = commands.onSessionPrevious()

    override fun seekToPreviousMediaItem() = commands.onSessionPrevious()

    override fun seekToPreviousWindow() = commands.onSessionPrevious()
}
