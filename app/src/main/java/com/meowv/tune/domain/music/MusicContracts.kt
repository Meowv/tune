package com.meowv.tune.domain.music

interface MusicRepository {
    suspend fun search(query: String, limit: Int = 20, cursor: String? = null): MusicPage
    suspend fun recommendations(limit: Int = 20, cursor: String? = null): MusicPage
    suspend fun radioRecommendations(): List<MusicTrack> = emptyList()

    // 个性化扩展点：将来可依据最近播放曲目召回不同查询词，队列侧无需改动。
    fun fallbackSearchQueries(context: MusicTrack?): List<String> = listOf("华语流行")

    suspend fun lyrics(trackId: String, providerId: String): List<MusicLyricLine> = emptyList()
    suspend fun resolve(trackId: String, preferredProviderId: String? = null): PlayableAudio
    suspend fun resolveAlternate(trackId: String, excludedProviderId: String): PlayableAudio =
        throw MusicException(MusicFailure.NO_PLAYABLE_MATCH)
}
