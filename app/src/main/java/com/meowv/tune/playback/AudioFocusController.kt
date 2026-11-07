package com.meowv.tune.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

internal enum class AudioFocusEvent { GAIN, DUCK, TRANSIENT_LOSS, PERMANENT_LOSS, DENIED }

internal class AudioFocusController(
    context: Context,
    private val onEvent: (AudioFocusEvent) -> Unit
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::handleFocusChange)
        .build()

    fun acquire(): Boolean {
        val result = manager.requestAudioFocus(request)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) onEvent(AudioFocusEvent.DENIED)
        return granted
    }

    fun abandon() {
        manager.abandonAudioFocusRequest(request)
    }

    internal fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onEvent(AudioFocusEvent.GAIN)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onEvent(AudioFocusEvent.DUCK)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onEvent(AudioFocusEvent.TRANSIENT_LOSS)
            AudioManager.AUDIOFOCUS_LOSS -> onEvent(AudioFocusEvent.PERMANENT_LOSS)
        }
    }
}
