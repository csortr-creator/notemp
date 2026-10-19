package android.net
import android.os.Handler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class Network(val id: Int) {
    fun bindSocket(fd: java.io.FileDescriptor) {}
    fun getAllByName(host: String): Array<java.net.InetAddress> = java.net.InetAddress.getAllByName(host)
}
class LinkProperties(var interfaceName: String? = null)
class NetworkCapabilities(private val flags: Set<Int> = setOf(NET_CAPABILITY_NOT_VPN, NET_CAPABILITY_INTERNET)) {
    fun hasCapability(flag: Int) = flag in flags
    companion object {
        const val NET_CAPABILITY_NOT_VPN = 1
        const val NET_CAPABILITY_INTERNET = 2
        const val NET_CAPABILITY_NOT_RESTRICTED = 3
        const val NET_CAPABILITY_VALIDATED = 4
        const val NET_CAPABILITY_CAPTIVE_PORTAL = 5
    }
}
class NetworkRequest { class Builder {
    fun addCapability(value: Int) = this
    fun removeCapability(value: Int) = this
    fun build() = NetworkRequest()
} }
class ConnectivityManager {
    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
        open fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {}
        open fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {}
        open fun onLost(network: Network) {}
    }
    val callbacks = CopyOnWriteArrayList<NetworkCallback>()
    val links = ConcurrentHashMap<Network, LinkProperties>()
    val capabilities = ConcurrentHashMap<Network, NetworkCapabilities>()
    var activeNetwork: Network? = null
    val allNetworks: Array<Network> get() = capabilities.keys.toTypedArray()
    fun getLinkProperties(network: Network) = links[network]
    fun getNetworkCapabilities(network: Network) = capabilities[network]
    fun registerBestMatchingNetworkCallback(request: NetworkRequest, callback: NetworkCallback, handler: Handler) { callbacks.add(callback) }
    fun requestNetwork(request: NetworkRequest, callback: NetworkCallback, handler: Handler) { callbacks.add(callback) }
    fun requestNetwork(request: NetworkRequest, callback: NetworkCallback) { callbacks.add(callback) }
    fun registerDefaultNetworkCallback(callback: NetworkCallback, handler: Handler) { callbacks.add(callback) }
    fun registerDefaultNetworkCallback(callback: NetworkCallback) { callbacks.add(callback) }
    fun unregisterNetworkCallback(callback: NetworkCallback) { callbacks.remove(callback) }
}
