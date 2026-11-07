package com.meowv.tune

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeSetupTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composeContentRenders() {
        compose.setContent { MaterialTheme { Text("Meowv Tune") } }
        compose.onNodeWithText("Meowv Tune").assertIsDisplayed()
    }
}
