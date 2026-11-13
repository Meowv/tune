package com.meowv.tune.playback

// 仅记录阶段耗时，不保存音源地址、请求参数或其他台站数据。
internal data class PlaybackTiming(
    val directoryMillis: Long = 0,
    val resolveMillis: Long? = null,
    val prepareMillis: Long? = null,
    val totalMillis: Long? = null
)
