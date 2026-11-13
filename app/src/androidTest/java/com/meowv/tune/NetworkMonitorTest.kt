package com.meowv.tune

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import com.meowv.tune.playback.NetworkMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkMonitorTest {
    @Test fun retainsValidationAndCaptivePortalSeparatelyFromTransport() {
        val state = NetworkMonitor.snapshot(hasInternet = true, hasWifi = true, hasEthernet = false,
            hasCellular = false, unmetered = true, validated = false, captivePortal = true)
        assertTrue(state.available)
        assertFalse(state.validated)
        assertTrue(state.captivePortal)
    }
    @Test fun mapsWifiEthernetCellularAndMissingInternet() {
        assertEquals(NetworkState(NetworkType.WIFI, metered = false),
            NetworkMonitor.snapshot(hasInternet = true, hasWifi = true, hasEthernet = false,
                hasCellular = false, unmetered = true))
        assertEquals(NetworkState(NetworkType.ETHERNET, metered = false),
            NetworkMonitor.snapshot(hasInternet = true, hasWifi = false, hasEthernet = true,
                hasCellular = false, unmetered = true))
        assertEquals(NetworkState(NetworkType.CELLULAR, metered = true),
            NetworkMonitor.snapshot(hasInternet = true, hasWifi = false, hasEthernet = false,
                hasCellular = true, unmetered = false))
        assertEquals(NetworkState(),
            NetworkMonitor.snapshot(hasInternet = false, hasWifi = true, hasEthernet = false,
                hasCellular = false, unmetered = true))
    }
}
