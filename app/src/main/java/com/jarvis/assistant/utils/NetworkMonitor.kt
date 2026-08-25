package com.jarvis.assistant.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a command that needs the network can be attempted at all. Used to give
 * the user "an internet connection is required" instead of a provider timeout.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    val isOnline: Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            private val available = mutableSetOf<Network>()

            override fun onAvailable(network: Network) {
                available += network
                trySend(true)
            }

            override fun onLost(network: Network) {
                available -= network
                trySend(available.isNotEmpty())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        manager.registerNetworkCallback(request, callback)
        trySend(currentlyOnline())

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()

    /** Synchronous check for call sites that cannot suspend. */
    fun currentlyOnline(): Boolean {
        val manager = connectivityManager ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
