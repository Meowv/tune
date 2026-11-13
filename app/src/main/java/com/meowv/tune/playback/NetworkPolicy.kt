package com.meowv.tune.playback

import com.meowv.tune.domain.NetworkState

internal enum class NetworkDecision { ALLOWED, OFFLINE }

// 登录门户暂不播放；部分车机不会通过系统验证，未验证网络仍允许实际请求判断可达性。
internal object NetworkPolicy {
    fun evaluate(network: NetworkState): NetworkDecision =
        if (network.available && !network.captivePortal) NetworkDecision.ALLOWED else NetworkDecision.OFFLINE
}
