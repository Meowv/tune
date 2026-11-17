package com.meowv.tune.music

import com.meowv.tune.domain.music.*
import java.time.Instant
import kotlinx.coroutines.*

// 只持有一首的短时解析结果；不取出候选、不写历史，也不下载音频。
internal class MusicNextPreparation(
    private val repository: () -> MusicRepository?,
    private val scope: CoroutineScope,
    private val onFailure: (MusicException) -> Unit,
    private val now: () -> Instant = Instant::now
) {
    private class Entry(val id: String) {
        lateinit var result: Deferred<Result<PlayableAudio>>
        var failureUntil: Instant? = null
    }
    private var entry: Entry? = null

    fun prepare(track: MusicTrack) {
        if (entry?.let { it.id == track.id && !it.result.isCancelled } == true) return
        cancel()
        val value = Entry(track.id)
        value.result = scope.async(start = CoroutineStart.LAZY) {
            try {
                val audio = withTimeout(8000) {
                    (repository() ?: throw MusicException(MusicFailure.NOT_CONFIGURED)).resolve(track.id)
                }
                ensureActive()
                if (audio.trackId != track.id || !MusicAudioUrlPolicy.allows(audio.url)) throw MusicException(MusicFailure.INVALID_RESPONSE)
                Result.success(audio)
            } catch (_: TimeoutCancellationException) {
                val failure = MusicException(MusicFailure.SOURCE_TIMEOUT)
                ensureActive(); onFailure(failure); Result.failure(failure)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MusicException) {
                ensureActive()
                failure.retryAfterSeconds?.let { value.failureUntil = now().plusSeconds(it.coerceIn(1, 86400)) }
                onFailure(failure)
                Result.failure(failure)
            }
        }
        entry = value
        value.result.start()
    }

    fun take(trackId: String?): Deferred<Result<PlayableAudio>>? {
        val value = entry
        entry = null
        if (value != null && value.id == trackId && !value.result.isCancelled && value.failureUntil?.isAfter(now()) != false) return value.result
        value?.result?.cancel()
        return null
    }

    fun fresh(audio: PlayableAudio): Boolean = audio.expiresAt?.isAfter(now().plusSeconds(5)) != false

    fun cancel() { entry?.result?.cancel(); entry = null }
}
