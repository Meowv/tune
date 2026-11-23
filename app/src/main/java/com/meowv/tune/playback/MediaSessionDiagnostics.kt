package com.meowv.tune.playback

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.meowv.tune.domain.PlaybackSource

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MediaSessionDiagnostics(private val source: () -> PlaybackSource) : Player.Listener {
    private var previousSnapshot: String? = null

    fun log(event: String, detail: String = "") {
        Log.d(TAG, "event=$event source=${source()} $detail")
    }

    fun controller(event: String, controller: MediaSession.ControllerInfo) {
        log(event, "package=${controller.packageName} uid=${controller.uid} version=${controller.controllerVersion} trusted=${controller.isTrusted}")
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val changed = buildList {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) add("item")
            if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) add("metadata")
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) add("timeline")
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED)) add("state")
        }
        if (changed.isEmpty()) return
        val metadata = player.mediaMetadata
        val state = when (player.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "unknown"
        }
        // 仅记录展示字段和逻辑 ID，避免序列化包含音源地址的 MediaItem。
        val snapshot = "id=${player.currentMediaItem?.mediaId} title=${metadata.title} artist=${metadata.artist} " +
            "state=$state playing=${player.isPlaying} playWhenReady=${player.playWhenReady} items=${player.mediaItemCount}"
        if (snapshot == previousSnapshot) return
        previousSnapshot = snapshot
        log(changed.joinToString("+"), snapshot)
    }

    private companion object {
        const val TAG = "MeowvTuneMediaSession"
    }
}
