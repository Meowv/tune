package com.meowv.tune.music

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meowv.tune.MainActivity
import org.junit.Rule
import org.junit.Test

class MusicNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unconfiguredMusicHasNoConfigurationEntry() {
        compose.onNodeWithTag("nav_music").performClick()
        compose.onNodeWithText("音乐服务暂未就绪").assertIsDisplayed()
        compose.onNodeWithTag("music_configure").assertDoesNotExist()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("page_settings").assertIsDisplayed()
    }
}
