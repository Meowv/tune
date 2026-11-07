package com.meowv.tune.playback

import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.TuneSettings

internal enum class NetworkDecision { ALLOWED, OFFLINE, WIFI_ONLY, CONFIRM_METERED }

internal object NetworkPolicy {
    fun evaluate(network: NetworkState, settings: TuneSettings, allowMetered: Boolean): NetworkDecision {
        if (!network.available) return NetworkDecision.OFFLINE
        val wiredOrWifi = network.type == NetworkType.WIFI || network.type == NetworkType.ETHERNET
        if (settings.wifiOnly && (!wiredOrWifi || network.metered)) return NetworkDecision.WIFI_ONLY
        if (settings.dataWarning && network.metered && !allowMetered) return NetworkDecision.CONFIRM_METERED
        return NetworkDecision.ALLOWED
    }
}
