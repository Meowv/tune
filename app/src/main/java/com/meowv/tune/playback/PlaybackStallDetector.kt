package com.meowv.tune.playback

/**
 * 直播流卡死检测。
 *
 * 部分源在连接保持但不再发送数据时，ExoPlayer 会长期停在 BUFFERING 且不抛错误；
 * 这里用播放位置是否推进判断卡死，交由既有有界恢复预算处理。
 */
internal object PlaybackStallDetector {
    const val CHECK_INTERVAL_MS = 5_000L
    const val TIMEOUT_MS = 25_000L

    fun isStalled(
        buffering: Boolean,
        playWhenReady: Boolean,
        position: Long,
        lastPosition: Long,
        sinceProgressMs: Long,
        timeoutMs: Long = TIMEOUT_MS
    ): Boolean {
        if (!buffering || !playWhenReady) return false
        if (position != lastPosition) return false
        return sinceProgressMs >= timeoutMs
    }
}
