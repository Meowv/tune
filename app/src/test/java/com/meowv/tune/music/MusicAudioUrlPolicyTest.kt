package com.meowv.tune.music

import org.junit.Assert.*
import org.junit.Test

class MusicAudioUrlPolicyTest {
    @Test fun musicRequiresHttpsAndRejectsRetiredDemoUrlsAndPrivateHosts() {
        assertTrue(MusicAudioUrlPolicy.allows("https://m7.music.126.net/audio.mp3"))
        for (url in listOf("http://10.0.2.2:5000/api/music/demo-audio/original-001.mp3",
            "http://example.org/audio.mp3", "https://127.0.0.1/audio.mp3", "https://localhost/audio.mp3",
            "https://user:placeholder@example.org/audio.mp3")) assertFalse(MusicAudioUrlPolicy.allows(url))
    }
}
