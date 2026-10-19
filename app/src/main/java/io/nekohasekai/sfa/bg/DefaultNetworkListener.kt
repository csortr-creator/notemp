/*
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package io.nekohasekai.sfa.bg

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.Channel
import android.util.Log

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage() {
            val response = CompletableDeferred<Unit>()
        }
        class Get : NetworkMessage() { val response = CompletableDeferred<Network>() }
        class CancelGet(val response: CompletableDeferred<Network>) : NetworkMessage()
        class Stop(val key: Any, val expectedListener: ((Network?) -> Unit)? = null) : NetworkMessage() { val response = CompletableDeferred<Unit>() }
        class Put(val token: Long, val network: Network) : NetworkMessage()
        class Update(val token: Long, val network: Network) : NetworkMessage()
        class Lost(val token: Long, val network: Network) : NetworkMessage()
    }

    @OptIn(DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
    private val networkActor = GlobalScope.actor<NetworkMessage>(Dispatchers.Default, capacity = Channel.UNLIMITED) {
        val state = NetworkListenerState<Network>(
            register = ::register,
            unregister = ::unregister,
            onFailure = { Log.w("DefaultNetworkListener", "Network listener callback failed") },
        )
        for (message in channel) {
            when (message) {
                is NetworkMessage.Start -> {
                    try {
                        state.start(message.key, message.listener)
                        message.response.complete(Unit)
                    } catch (e: Exception) { message.response.completeExceptionally(e) }
                }
                is NetworkMessage.Stop -> {
                    state.stop(message.key, message.expectedListener)
                    message.response.complete(Unit)
                }
                is NetworkMessage.Get -> {
                    try { state.get(message.response, if (fallback) ::underlyingNetwork else null) }
                    catch (e: Exception) { message.response.completeExceptionally(e) }
                }
                is NetworkMessage.CancelGet -> state.cancelGet(message.response)
                is NetworkMessage.Put -> state.available(message.token, message.network)
                is NetworkMessage.Update -> state.update(message.token, message.network)
                is NetworkMessage.Lost -> state.lost(message.token, message.network)
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) {
        val message = NetworkMessage.Start(key, listener)
        networkActor.send(message)
        try { message.response.await() } catch (e: kotlinx.coroutines.CancellationException) {
            // Registration may already be queued; its matching Stop must still reach the actor.
            networkActor.trySend(NetworkMessage.Stop(key, message.listener))
            throw e
        }
    }

    suspend fun get(): Network {
        val message = NetworkMessage.Get()
        try {
            networkActor.send(message)
            return message.response.await()
        } finally {
            message.response.cancel()
            networkActor.trySend(NetworkMessage.CancelGet(message.response))
        }
    }

    suspend fun stop(key: Any) {
        val message = NetworkMessage.Stop(key)
        networkActor.send(message)
        message.response.await()
    }

    // Connectivity callbacks enqueue without blocking Main/ConnectivityThread.
    private class Callback(private val token: Long) : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networkActor.trySend(NetworkMessage.Put(token, network)) }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            networkActor.trySend(NetworkMessage.Update(token, network))
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networkActor.trySend(NetworkMessage.Update(token, network))
        }
        override fun onLost(network: Network) { networkActor.trySend(NetworkMessage.Lost(token, network)) }
    }

    private var registeredCallback: Callback? = null

    private fun underlyingNetwork(): Network? {
        fun usable(network: Network): Boolean = Application.connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
        if (Build.VERSION.SDK_INT >= 23) {
            Application.connectivity.activeNetwork?.takeIf(::usable)?.let { return it }
        }
        return Application.connectivity.allNetworks.firstOrNull(::usable)
    }

    private var fallback = false
    private val request =
        NetworkRequest.Builder().apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            if (Build.VERSION.SDK_INT == 23) { // workarounds for OEM bugs
                removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            }
        }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun register(token: Long): Network? {
        val callback = Callback(token)
        registeredCallback = callback
        fallback = false
        when (Build.VERSION.SDK_INT) {
            in 31..Int.MAX_VALUE ->
                @TargetApi(31)
                {
                    Application.connectivity.registerBestMatchingNetworkCallback(
                        request,
                        callback,
                        mainHandler,
                    )
                }

            in 28 until 31 ->
                @TargetApi(28)
                { // we want REQUEST here instead of LISTEN
                    Application.connectivity.requestNetwork(request, callback, mainHandler)
                }

            in 26 until 28 ->
                @TargetApi(26)
                {
                    Application.connectivity.registerDefaultNetworkCallback(callback, mainHandler)
                }

            in 24 until 26 ->
                @TargetApi(24)
                {
                    Application.connectivity.registerDefaultNetworkCallback(callback)
                }

            else ->
                try {
                    fallback = false
                    Application.connectivity.requestNetwork(request, callback)
                } catch (e: RuntimeException) {
                    fallback =
                        true // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
        }
        return if (fallback) underlyingNetwork() else null
    }

    private fun unregister() {
        val callback = registeredCallback ?: return
        registeredCallback = null
        fallback = false
        runCatching { Application.connectivity.unregisterNetworkCallback(callback) }
    }
}
