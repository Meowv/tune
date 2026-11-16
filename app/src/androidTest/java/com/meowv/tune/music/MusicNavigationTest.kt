package com.meowv.tune.music

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meowv.tune.MainActivity
import org.junit.Rule
import org.junit.Test

class MusicNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unconfiguredMusicOpensSettingsWithoutStartingPlayback() {
        compose.onNodeWithTag("nav_music").performClick()
        compose.onNodeWithText("音乐服务尚未配置").assertIsDisplayed()
        compose.onNodeWithTag("music_configure").performScrollTo().performClick()
        compose.onNodeWithTag("page_settings").assertIsDisplayed()
    }
}
