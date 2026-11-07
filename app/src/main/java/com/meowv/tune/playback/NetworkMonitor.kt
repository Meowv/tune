package com.meowv.tune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.meowv.tune.domain.NetworkState
import com.meowv.tune.domain.NetworkType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = mutable.asStateFlow()
    private var active: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            active = network
            manager.getNetworkCapabilities(network)?.let(::publish)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == active) publish(capabilities)
        }

        override fun onLost(network: Network) {
            if (network == active) {
                active = null
                mutable.value = NetworkState()
            }
        }
    }

    init {
        manager.activeNetwork?.let { network ->
            active = network
            manager.getNetworkCapabilities(network)?.let(::publish)
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: SecurityException) {
            mutable.value = NetworkState()
        }
    }

    private fun publish(capabilities: NetworkCapabilities) {
        mutable.value = snapshot(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }

    internal companion object {
        fun snapshot(
            hasInternet: Boolean,
            hasWifi: Boolean,
            hasEthernet: Boolean,
            hasCellular: Boolean,
            unmetered: Boolean
        ): NetworkState {
            if (!hasInternet) return NetworkState()
            val type = when {
                hasWifi -> NetworkType.WIFI
                hasEthernet -> NetworkType.ETHERNET
                hasCellular -> NetworkType.CELLULAR
                else -> NetworkType.OTHER
            }
            return NetworkState(
                type = type,
                metered = !unmetered
            )
        }
    }
}
