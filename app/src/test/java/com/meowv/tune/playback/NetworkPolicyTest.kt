package com.meowv.tune.playback

import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.domain.TuneSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPolicyTest {
    @Test fun offlineBlocksEveryConfiguration() {
        val network = NetworkState(NetworkType.OFFLINE, metered = false)
        assertEquals(NetworkDecision.OFFLINE, NetworkPolicy.evaluate(network, TuneSettings(), false))
        assertEquals(NetworkDecision.OFFLINE, NetworkPolicy.evaluate(
            network, TuneSettings(wifiOnly = true, dataWarning = false), true
        ))
    }

    @Test fun wifiOnlyAcceptsUnmeteredWifiAndEthernetAndRejectsCellular() {
        val settings = TuneSettings(wifiOnly = true, dataWarning = true)
        assertEquals(NetworkDecision.ALLOWED, NetworkPolicy.evaluate(
            NetworkState(NetworkType.WIFI, metered = false), settings, false
        ))
        assertEquals(NetworkDecision.ALLOWED, NetworkPolicy.evaluate(
            NetworkState(NetworkType.ETHERNET, metered = false), settings, false
        ))
        assertEquals(NetworkDecision.WIFI_ONLY, NetworkPolicy.evaluate(
            NetworkState(NetworkType.CELLULAR, metered = true), settings, true
        ))
    }

    @Test fun dataWarningRequiresOneExplicitAllowanceForMeteredNetwork() {
        val network = NetworkState(NetworkType.CELLULAR, metered = true)
        assertEquals(NetworkDecision.CONFIRM_METERED, NetworkPolicy.evaluate(
            network, TuneSettings(wifiOnly = false, dataWarning = true), false
        ))
        assertEquals(NetworkDecision.ALLOWED, NetworkPolicy.evaluate(
            network, TuneSettings(wifiOnly = false, dataWarning = true), true
        ))
        assertEquals(NetworkDecision.ALLOWED, NetworkPolicy.evaluate(
            network, TuneSettings(wifiOnly = false, dataWarning = false), false
        ))
    }

    @Test fun unmeteredOtherNetworkDoesNotTriggerDataWarning() {
        assertEquals(NetworkDecision.ALLOWED, NetworkPolicy.evaluate(
            NetworkState(NetworkType.OTHER, metered = false),
            TuneSettings(wifiOnly = false, dataWarning = true),
            false
        ))
    }
}
