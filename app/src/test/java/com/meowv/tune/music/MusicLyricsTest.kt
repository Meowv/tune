package com.meowv.tune.music

import com.meowv.tune.domain.music.MusicLyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicLyricsTest {
    @Test
    fun multipleTimestampsAndOffsetFollowPlaybackPosition() {
        val lines = MusicLyrics.parse("""
            [ti:标题]
            [00:05.25][01:01.125]同一行
            [00:10]下一行
            [offset:-250]
        """.trimIndent())

        assertEquals(listOf(MusicLyricLine(5000, "同一行"), MusicLyricLine(9750, "下一行"),
            MusicLyricLine(60875, "同一行")), lines)
        assertEquals(-1, MusicLyrics.activeLine(lines, 4999))
        assertEquals(1, MusicLyrics.activeLine(lines, 9750))
        assertEquals(2, MusicLyrics.activeLine(lines, 65000))
    }

    @Test
    fun absentLyricsDoNotCreatePlaybackContent() {
        assertEquals(emptyList<MusicLyricLine>(), MusicJson.lyrics("""{"trackId":"track","lrc":null}""", "track"))
    }
}
