package com.meowv.tune.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteMonitorTest {
    @Test fun externalSinkRemovalTriggersDisconnectPolicy() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE
        ).forEach { type -> assertTrue("$type", AudioRouteMonitor.shouldPauseForRemoval(type, true)) }
        assertFalse(AudioRouteMonitor.shouldPauseForRemoval(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, true))
        assertFalse(AudioRouteMonitor.shouldPauseForRemoval(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, false))
    }
}
