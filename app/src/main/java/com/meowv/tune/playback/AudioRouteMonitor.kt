package com.meowv.tune.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

internal class AudioRouteMonitor(
    context: Context,
    private val onExternalOutputRemoved: () -> Unit
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { shouldPauseForRemoval(it.type, it.isSink) }) onExternalOutputRemoved()
        }
    }

    init {
        manager.registerAudioDeviceCallback(callback, null)
    }

    fun close() {
        manager.unregisterAudioDeviceCallback(callback)
    }

    internal companion object {
        private val externalOutputTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_HEARING_AID
        )

        fun shouldPauseForRemoval(type: Int, isSink: Boolean): Boolean = isSink && type in externalOutputTypes
    }
}
