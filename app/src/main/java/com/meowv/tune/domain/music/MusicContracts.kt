package com.meowv.tune.domain.music

interface MusicRepository {
    suspend fun search(query: String, limit: Int = 20, cursor: String? = null): MusicPage
    suspend fun recommendations(limit: Int = 20, cursor: String? = null): MusicPage
    suspend fun resolve(trackId: String, preferredProviderId: String? = null): PlayableAudio
    suspend fun resolveAlternate(trackId: String, excludedProviderId: String): PlayableAudio =
        throw MusicException(MusicFailure.NO_PLAYABLE_MATCH)
}
