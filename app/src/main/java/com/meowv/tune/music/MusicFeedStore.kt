package com.meowv.tune.music

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meowv.tune.domain.music.MusicPage
import com.meowv.tune.domain.music.MusicTrack
import com.meowv.tune.domain.music.canStartPlayback
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class MusicFeedSnapshot(
    val version: Long = 0,
    val complete: Boolean = false,
    val recommendations: List<MusicTrack> = emptyList(),
    val fallback: List<MusicTrack> = emptyList(),
    val rolling: Boolean = false,
    val nextCursor: String? = null,
    val discoveryTrackIds: Set<String> = emptySet()
) {
    val tracks: List<MusicTrack> = (recommendations + fallback).distinctBy { it.id }
}

internal class MusicFeedStore(private val preferences: DataStore<Preferences>? = null) {
    private val lock = Mutex()
    private val mutableSnapshot = MutableStateFlow(MusicFeedSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private var restored = false
    private var dirty = false

    suspend fun restore(): MusicFeedSnapshot = lock.withLock { restoreLocked() }

    suspend fun updateRecommendations(page: MusicPage): MusicFeedSnapshot = update { previous ->
        val tracks = page.items.filter { it.canStartPlayback }.distinctBy { it.id }
        if (page.partial && (previous.complete || previous.tracks.isNotEmpty() || tracks.isEmpty())) return@update previous
        val rolling = page.strategy == "rolling_candidates"
        val nextCursor = page.nextCursor.takeIf { rolling }
        val membershipChanged = !page.partial &&
            previous.recommendations.mapTo(HashSet()) { it.id } != tracks.mapTo(HashSet()) { it.id }
        val changed = previous.recommendations != tracks || previous.rolling != rolling || previous.nextCursor != nextCursor
        return@update previous.copy(
            version = if (changed) previous.version + 1 else previous.version,
            complete = !page.partial,
            recommendations = tracks,
            fallback = if (membershipChanged) emptyList() else previous.fallback,
            rolling = rolling,
            nextCursor = nextCursor,
            discoveryTrackIds = page.discoveryTrackIds + if (membershipChanged) emptySet() else
                previous.discoveryTrackIds.intersect(previous.fallback.map { it.id }.toSet())
        )
    }

    suspend fun appendFallback(version: Long, tracks: List<MusicTrack>, discoveryIds: Set<String> = emptySet()): MusicFeedSnapshot = update { previous ->
        if (version != previous.version) return@update previous
        val existing = previous.tracks.mapTo(HashSet()) { it.id }
        val added = tracks.filter { it.canStartPlayback && existing.add(it.id) }
        if (added.isEmpty()) return@update previous
        val fallback = (previous.fallback + added).takeLast(MaxFallbackTracks)
        val retainedIds = (previous.recommendations + fallback).mapTo(HashSet()) { it.id }
        previous.copy(
            fallback = fallback,
            discoveryTrackIds = previous.discoveryTrackIds.intersect(retainedIds) +
                discoveryIds.intersect(fallback.mapTo(HashSet()) { it.id })
        )
    }

    suspend fun refreshFallback(version: Long, originalId: String, track: MusicTrack) = update { previous ->
        if (version != previous.version) return@update previous
        previous.copy(fallback = previous.fallback.map { if (it.id == originalId) track else it }.distinctBy { it.id })
    }

    private suspend fun restoreLocked(): MusicFeedSnapshot {
        if (!restored) {
            val saved = try {
                val encoded = preferences?.data?.first()?.get(Key)
                withContext(Dispatchers.Default) { encoded?.let(MusicJson::decodeFeedSnapshot) }
            } catch (_: IOException) {
                return mutableSnapshot.value
            }
            if (saved != null) {
                mutableSnapshot.value = saved
                dirty = false
            }
            restored = true
        }
        return mutableSnapshot.value
    }

    private suspend fun update(transform: (MusicFeedSnapshot) -> MusicFeedSnapshot): MusicFeedSnapshot = lock.withLock {
        val previous = restoreLocked()
        val updated = transform(previous)
        if (updated != previous) dirty = true
        if (dirty && restored && preferences != null) {
            // 播放服务或页面离开时，已开始写入的版本和曲目仍作为一个整体提交。
            withContext(NonCancellable) {
                try {
                    val encoded = withContext(Dispatchers.Default) { MusicJson.encodeFeedSnapshot(updated) }
                preferences.edit { it[Key] = encoded }
                    dirty = false
                } catch (_: IOException) {
                    // 本地写入故障不影响补歌，之后的更新会再次尝试保存。
                }
                mutableSnapshot.value = updated
            }
        } else mutableSnapshot.value = updated
        updated
    }

    companion object {
        internal const val MaxFallbackTracks = 200
        private val Key = stringPreferencesKey("music_feed_snapshot")
    }
}
