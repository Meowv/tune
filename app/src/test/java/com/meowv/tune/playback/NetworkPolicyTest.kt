package com.meowv.tune.playback

import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPolicyTest {
    @Test fun offlineBlocksPlayback() {
        assertEquals(NetworkDecision.OFFLINE, NetworkPolicy.evaluate(NetworkState(NetworkType.OFFLINE)))
    }

    @Test fun everyAvailableNetworkIsAllowed() {
        listOf(NetworkType.WIFI, NetworkType.ETHERNET, NetworkType.CELLULAR, NetworkType.OTHER).forEach { type ->
            assertEquals("$type", NetworkDecision.ALLOWED, NetworkPolicy.evaluate(NetworkState(type, metered = true)))
        }
    }
}
