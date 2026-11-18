package com.meowv.tune.music

import com.meowv.tune.domain.music.MusicLyricLine

internal object MusicLyrics {
    private val timestamp = Regex("""\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?]""")
    private val offset = Regex("""\[offset:([+-]?\d{1,6})]""", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): List<MusicLyricLine> {
        val shift = offset.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return lrc.lineSequence().flatMap { line ->
            val marks = timestamp.findAll(line).toList()
            val text = marks.lastOrNull()?.let { line.substring(it.range.last + 1).trim() }.orEmpty()
            if (marks.firstOrNull()?.range?.first != 0 || text.isEmpty()) return@flatMap emptySequence()
            marks.asSequence().map { mark ->
                val time = mark.groupValues[1].toLong() * 60_000 + mark.groupValues[2].toLong() * 1000 +
                    mark.groupValues[3].padEnd(3, '0').toLong() + shift
                MusicLyricLine(time.coerceAtLeast(0), text)
            }
        }.take(1000).distinctBy(MusicLyricLine::timeMs).sortedBy(MusicLyricLine::timeMs).toList()
    }

    fun activeLine(lines: List<MusicLyricLine>, positionMs: Long): Int {
        val result = lines.binarySearchBy(positionMs, selector = MusicLyricLine::timeMs)
        return if (result >= 0) result else -result - 2
    }
}
