package com.meowv.tune.playback

import com.meowv.tune.domain.NetworkState

internal enum class NetworkDecision { ALLOWED, OFFLINE }

// 不区分 Wi-Fi 与移动网络：只要系统报告网络可用就允许播放。
internal object NetworkPolicy {
    fun evaluate(network: NetworkState): NetworkDecision =
        if (network.available) NetworkDecision.ALLOWED else NetworkDecision.OFFLINE
}
