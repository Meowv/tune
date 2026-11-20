package com.meowv.tune.music

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.first

internal class MusicSearchHistoryStore(private val preferences: DataStore<Preferences>) {
    suspend fun read(): List<String> = try {
        preferences.data.first()[Key].decodeValues()
    } catch (_: IOException) {
        emptyList()
    }

    suspend fun add(query: String) {
        val value = query.trim()
        if (value.length !in 1..100 || value.any(Char::isISOControl) ||
            value.contains("://") || value.startsWith("www.", ignoreCase = true)) return
        try {
            preferences.edit {
                it[Key] = (listOf(value) + it[Key].decodeValues().filterNot { old -> old == value })
                    .take(MaxEntries).joinToString(Separator)
            }
        } catch (_: IOException) {
            // 搜索本身不依赖历史写入成功。
        }
    }

    suspend fun clear() {
        try {
            preferences.edit { it.remove(Key) }
        } catch (_: IOException) {
            // 清空失败不影响当前搜索页。
        }
    }

    private fun String?.decodeValues(): List<String> = this.orEmpty()
        .split(Separator)
        .map(String::trim)
        .filter { it.length in 1..100 && it.none(Char::isISOControl) &&
            !it.contains("://") && !it.startsWith("www.", ignoreCase = true) }
        .distinct()
        .take(MaxEntries)

    private companion object {
        val Key = stringPreferencesKey("recent_queries")
        const val Separator = "\u001f"
        const val MaxEntries = 10
    }
}
