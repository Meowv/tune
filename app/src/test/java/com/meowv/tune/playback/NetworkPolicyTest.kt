package com.meowv.tune.playback

import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPolicyTest {
    @Test fun captivePortalBlocksPlaybackButUnvalidatedCarNetworkCanTryTheStream() {
        val wifi = NetworkState(NetworkType.WIFI, validated = false)
        assertEquals(NetworkDecision.ALLOWED, NetworkPolicy.evaluate(wifi))
        assertEquals(NetworkDecision.OFFLINE, NetworkPolicy.evaluate(wifi.copy(captivePortal = true)))
    }
    @Test fun offlineBlocksPlayback() {
        assertEquals(NetworkDecision.OFFLINE, NetworkPolicy.evaluate(NetworkState(NetworkType.OFFLINE)))
    }

    @Test fun everyAvailableNetworkIsAllowed() {
        listOf(NetworkType.WIFI, NetworkType.ETHERNET, NetworkType.CELLULAR, NetworkType.OTHER).forEach { type ->
            assertEquals("$type", NetworkDecision.ALLOWED, NetworkPolicy.evaluate(NetworkState(type, metered = true)))
        }
    }
}
