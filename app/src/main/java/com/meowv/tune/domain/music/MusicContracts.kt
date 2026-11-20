package com.meowv.tune.domain.music

import java.time.Instant

interface MusicRepository {
    suspend fun search(query: String, limit: Int = 20, cursor: String? = null): MusicPage
    suspend fun recommendations(limit: Int = 20, cursor: String? = null): MusicPage

    suspend fun candidates(limit: Int = 30, cursor: String? = null): MusicPage =
        MusicPage(emptyList(), strategy = "rolling_warming")
    suspend fun radioRecommendations(): List<MusicTrack> = emptyList()

    // 个性化扩展点：将来可依据最近播放曲目召回不同查询词，队列侧无需改动。
    fun fallbackSearchQueries(context: MusicTrack?): List<String> = listOf(fallbackQuery(Instant.now().epochSecond))

    suspend fun lyrics(trackId: String, providerId: String): List<MusicLyricLine> = emptyList()
    suspend fun resolve(trackId: String, preferredProviderId: String? = null): PlayableAudio
    suspend fun resolveAlternate(trackId: String, excludedProviderId: String): PlayableAudio =
        throw MusicException(MusicFailure.NO_PLAYABLE_MATCH)
}


// 与 API MusicCatalog.FallbackQuery 保持顺序和小时桶一致。
private val fallbackStyles = listOf("华语流行", "摇滚", "民谣", "电子", "说唱", "轻音乐", "ACG", "爵士")

internal fun fallbackQuery(epochSeconds: Long): String =
    fallbackStyles[Math.floorMod(Math.floorDiv(epochSeconds, 3600L), fallbackStyles.size.toLong()).toInt()]
