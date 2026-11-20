package com.meowv.tune.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.music.MusicDevicePhase
import com.meowv.tune.music.MusicDeviceState
import com.meowv.tune.ui.theme.TuneTheme
import org.junit.Rule
import org.junit.Test

class MusicDeviceSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneShowsReadOnlyDeviceState() = verifySettings(isCar = false)

    @Test fun carShowsReadOnlyDeviceState() = verifySettings(isCar = true)

    private fun verifySettings(isCar: Boolean) {
        val device = mutableStateOf(MusicDeviceState(MusicDevicePhase.PENDING, "A1B2-C3D4"))
        compose.setContent {
            TuneTheme {
                SettingsScreen(isCar, TuneSettings(), false, "", {}, {}, {}, {}, musicDevice = device.value)
            }
        }
        compose.onNodeWithTag("page_settings").performScrollToNode(hasText("音乐设备"))
        compose.onNodeWithText("音乐设备").assertHasNoClickAction()
        compose.onNodeWithText("待管理员激活 · 短码 A1B2-C3D4").assertIsDisplayed()
        captureDeviceStatus(isCar, "pending")
        compose.runOnIdle { device.value = MusicDeviceState(MusicDevicePhase.CHECKING) }
        compose.onNodeWithText("正在登记或检查设备").assertIsDisplayed()
        captureDeviceStatus(isCar, "checking")
        compose.runOnIdle { device.value = MusicDeviceState(MusicDevicePhase.ACTIVE, "A1B2-C3D4") }
        compose.onNodeWithText("已激活 · 短码 A1B2-C3D4").assertIsDisplayed()
        captureDeviceStatus(isCar, "active")
        compose.runOnIdle { device.value = device.value.copy(phase = MusicDevicePhase.REVOKED) }
        compose.onNodeWithText("已撤销 · 短码 A1B2-C3D4").assertIsDisplayed()
        captureDeviceStatus(isCar, "revoked")
    }

    private fun captureDeviceStatus(isCar: Boolean, name: String) {
        if (!isCar || InstrumentationRegistry.getArguments().getString("captureV5States") != "true") return
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, "v5-device-$name.png").outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
