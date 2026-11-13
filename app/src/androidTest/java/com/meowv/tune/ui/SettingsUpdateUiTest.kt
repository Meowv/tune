package com.meowv.tune.ui

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.ui.theme.TuneTheme
import com.meowv.tune.update.*
import com.meowv.tune.update.install.InstallFailure
import com.meowv.tune.update.install.InstallState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val local = LocalVersion("com.meowv.tune", "test-local", 10, 28, "stable")
    private val info = UpdateInfo(local.packageName, "stable", "test-remote", 11, 28,
        "2026-11-13", "Release", (1..8).map { "更新条目 $it" }, "https://example.com/app.apk", 100, "")

    @Test fun notesStartWithFiveEntriesAndRestoreExpandedState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TuneTheme { Surface { RenderUpdate(UpdateUiState.UpdateAvailable(info)) } }
        }
        compose.onNodeWithText("更新条目 5").assertExists()
        compose.onNodeWithText("更新条目 6").assertDoesNotExist()
        compose.onNodeWithTag("update_notes_toggle").performScrollTo().performClick()
        compose.onNodeWithText("更新条目 8").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("更新条目 8").assertExists()
        compose.onNodeWithTag("update_notes_toggle").performScrollTo().performClick()
        compose.onNodeWithText("更新条目 6").assertDoesNotExist()
    }

    @Test fun expandedNotesSurviveSwitchingBetweenOneAndTwoColumns() {
        val width = mutableStateOf(320.dp)
        compose.setContent { TuneTheme { Surface {
            UpdateScreen(false, local, UpdateUiState.UpdateAvailable(info), InstallState.Idle,
                null, {}, {}, {}, {}, {}, Modifier.requiredWidth(width.value).fillMaxSize())
        } } }
        compose.onNodeWithTag("update_notes_toggle").performScrollTo().performClick()
        compose.onNodeWithText("更新条目 8").assertExists()
        compose.runOnIdle { width.value = 1000.dp }
        compose.onNodeWithText("更新条目 8").assertExists()
        compose.runOnIdle { width.value = 320.dp }
        compose.onNodeWithText("更新条目 8").assertExists()
    }

    @Test fun checkStatesAndInstallStatesExposeMatchingActions() {
        val state = mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
        val install = mutableStateOf<InstallState>(InstallState.Idle)
        var checks = 0
        var installs = 0
        compose.setContent { TuneTheme { Surface {
            UpdateScreen(false, local, state.value, install.value, null, {}, { checks++ }, {}, {}, { installs++ }, Modifier.fillMaxSize())
        } } }
        compose.onNodeWithText("检查应用更新").assertExists()
        compose.onNodeWithTag("update_check").performClick()
        assertEquals(1, checks)
        compose.runOnIdle { state.value = UpdateUiState.Checking }
        compose.onNodeWithTag("update_check").assertIsNotEnabled()
        compose.runOnIdle { state.value = UpdateUiState.Error(UpdateFailure.OFFLINE) }
        compose.onNodeWithText("重试检查").assertExists()
        compose.onNodeWithText("已是最新版本").assertDoesNotExist()
        compose.runOnIdle { state.value = UpdateUiState.UpToDate(info) }
        compose.onNodeWithText("重新检查").assertExists()
        compose.runOnIdle { state.value = UpdateUiState.UpdateAvailable(info); install.value = InstallState.Downloading(45, 100) }
        compose.onNodeWithText("45%").assertExists()
        compose.onNodeWithTag("update_cancel").assertExists()
        compose.runOnIdle { install.value = InstallState.Downloaded }
        compose.onNodeWithTag("update_install").performClick()
        assertEquals(1, installs)
        compose.runOnIdle { install.value = InstallState.InstallPermissionRequired }
        compose.onNodeWithTag("update_permission").assertExists()
        compose.runOnIdle { install.value = InstallState.Failed(InstallFailure.CHECKSUM_MISMATCH) }
        compose.onNodeWithText("SHA-256 校验不通过，已删除。").assertExists()
        compose.runOnIdle { install.value = InstallState.Failed(InstallFailure.INSTALL_CANCELLED) }
        compose.onNodeWithTag("update_install").performClick()
        assertEquals(2, installs)
    }

    @Test fun settingsSwitchTogglesOnceAndRefreshDisablesWhileRunning() {
        val settings = mutableStateOf(TuneSettings(autoPlay = false))
        val refreshing = mutableStateOf(false)
        var toggles = 0
        var refreshes = 0
        compose.setContent { TuneTheme { Surface {
            SettingsScreen(false, settings.value, refreshing.value, "vtest · 检查更新失败",
                { settings.value = it(settings.value); toggles++ }, { refreshes++ }, {}, {})
        } } }
        compose.onNodeWithText("自动播放").performClick().assertIsOn()
        assertEquals(1, toggles)
        compose.onNodeWithText("刷新数据").performClick()
        assertEquals(1, refreshes)
        compose.runOnIdle { refreshing.value = true }
        compose.onNodeWithText("刷新数据").assertIsNotEnabled()
        compose.onNodeWithTag("page_settings").performScrollToNode(hasText("版本与更新"))
        compose.onNodeWithText("vtest · 检查更新失败").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun RenderUpdate(state: UpdateUiState) {
        UpdateScreen(false, local, state, InstallState.Idle, null, {}, {}, {}, {}, {}, Modifier.fillMaxSize())
    }
}
