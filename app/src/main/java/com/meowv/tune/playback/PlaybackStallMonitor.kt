package com.meowv.tune.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class PlaybackProgress(val position: Long, val buffering: Boolean, val playWhenReady: Boolean)

/** 仅在需要检测时持有定时任务；每次暂停、换台或失败都会清除旧进度。 */
internal class PlaybackStallMonitor(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val progress: () -> PlaybackProgress,
    private val onStalled: () -> Unit,
    private val intervalMillis: Long = PlaybackStallDetector.CHECK_INTERVAL_MS
) {
    private var job: Job? = null
    private var lastPosition = -1L
    private var lastProgressAt: Long? = null

    fun sync(enabled: Boolean) {
        if (!enabled) {
            reset()
        } else if (job?.isActive != true) {
            job = scope.launch {
                while (true) {
                    delay(intervalMillis)
                    checkProgress()
                }
            }
        }
    }

    fun reset() {
        job?.cancel()
        job = null
        lastPosition = -1L
        lastProgressAt = null
    }

    private fun checkProgress() {
        val snapshot = progress()
        val now = nowMillis()
        val since = lastProgressAt
        if (snapshot.position != lastPosition || since == null) {
            lastPosition = snapshot.position
            lastProgressAt = now
            return
        }
        if (PlaybackStallDetector.isStalled(snapshot.buffering, snapshot.playWhenReady,
                snapshot.position, lastPosition, now - since)) {
            lastProgressAt = now
            onStalled()
        }
    }
}
