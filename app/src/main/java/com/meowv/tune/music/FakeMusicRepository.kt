package com.meowv.tune.music

import com.meowv.tune.domain.music.*

// 无默认媒体地址；调用方显式注入测试资源，不能在正式包里假装存在可播服务。
internal class FakeMusicRepository(
    private val tracks: List<MusicTrack>,
    private val audio: Map<String, PlayableAudio> = emptyMap()
) : MusicRepository {
    override suspend fun search(query: String, limit: Int, cursor: String?): MusicPage {
        if (query.trim().length !in 1..100 || query.any(Char::isISOControl)) throw MusicException(MusicFailure.INVALID_REQUEST)
        return page(tracks.filter { (it.title + " " + it.artists.joinToString(" ")).contains(query.trim(), ignoreCase = true) }, limit, cursor)
    }
    override suspend fun recommendations(limit: Int, cursor: String?) = page(tracks, limit, cursor).copy(strategy = "local_test_catalog")
    override suspend fun resolve(trackId: String, preferredProviderId: String?): PlayableAudio {
        if (tracks.none { it.id == trackId }) throw MusicException(MusicFailure.TRACK_NOT_FOUND)
        val playable = audio[trackId] ?: throw MusicException(MusicFailure.SOURCE_UNAVAILABLE)
        if (preferredProviderId != null && preferredProviderId != playable.providerId) throw MusicException(MusicFailure.FORBIDDEN)
        return playable
    }
    private fun page(items: List<MusicTrack>, limit: Int, cursor: String?): MusicPage {
        val offset = cursor?.removePrefix("fake:")?.toIntOrNull() ?: if (cursor == null) 0 else throw MusicException(MusicFailure.INVALID_REQUEST)
        if (limit !in 1..50 || offset < 0 || offset > items.size) throw MusicException(MusicFailure.INVALID_REQUEST)
        val values = items.drop(offset).take(limit)
        return MusicPage(values, if (offset + values.size < items.size) "fake:${offset + values.size}" else null)
    }
}
