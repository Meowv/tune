package com.meowv.tune.music

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meowv.tune.domain.music.MusicTrack
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class MusicResumePoint(val track: MusicTrack, val positionMs: Long, val playOnEntry: Boolean) {
    fun startPosition(seekable: Boolean, durationMs: Long?): Long =
        positionMs.takeIf { seekable && durationMs != null && it in 0 until durationMs } ?: 0
}

// 从主线程提交，交给应用级写入者。界面和服务关闭不会取消已经提交的写入。
internal class MusicResumeStore(private val store: DataStore<Preferences>) {
    private val updates = Channel<MusicResumePoint?>(Channel.CONFLATED)
    private var saved: MusicResumePoint? = null
    private var hasSaved = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launchWriter()
    }

    private fun CoroutineScope.launchWriter() = launch {
        for (point in updates) {
            try {
                store.edit { prefs ->
                    if (point == null) prefs.remove(Key)
                    else prefs[Key] = MusicJson.encodeResumePoint(point)
                }
            } catch (_: IOException) {
                // 存储故障不打断播放，后续检查点仍可提交。
            }
        }
    }

    fun save(point: MusicResumePoint?) {
        saved = point
        hasSaved = true
        updates.trySend(point)
    }

    suspend fun read(): MusicResumePoint? {
        if (hasSaved) return saved
        val point = try {
            (store.data.first().asMap()[Key] as? String)?.let(MusicJson::decodeResumePoint)
        } catch (_: IOException) {
            null
        }
        return if (hasSaved) saved else point
    }

    private companion object { val Key = stringPreferencesKey("last_music_position") }
}
