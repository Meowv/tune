package com.meowv.tune.music

import com.meowv.tune.playback.MediaUrlPolicy

internal object MusicAudioUrlPolicy {
    fun allows(value: String): Boolean = MediaUrlPolicy.parse(value)?.isHttps == true
}
