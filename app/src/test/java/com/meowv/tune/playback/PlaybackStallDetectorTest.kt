package com.meowv.tune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallDetectorTest {
    @Test
    fun stalledOnlyWhenBufferingWithoutProgress() {
        // 缓冲中且位置未推进，超过阈值判定卡死。
        assertTrue(PlaybackStallDetector.isStalled(true, true, 1000, 1000, 25_000, 25_000))
        // 位置在推进说明仍在缓冲数据，不算卡死。
        assertFalse(PlaybackStallDetector.isStalled(true, true, 2000, 1000, 60_000, 25_000))
        // 未在缓冲（正常播放或暂停）不判定卡死。
        assertFalse(PlaybackStallDetector.isStalled(false, true, 1000, 1000, 60_000, 25_000))
        // 用户暂停后 playWhenReady=false，不应触发恢复。
        assertFalse(PlaybackStallDetector.isStalled(true, false, 1000, 1000, 60_000, 25_000))
        // 未达阈值不判定。
        assertFalse(PlaybackStallDetector.isStalled(true, true, 1000, 1000, 24_999, 25_000))
    }
}
