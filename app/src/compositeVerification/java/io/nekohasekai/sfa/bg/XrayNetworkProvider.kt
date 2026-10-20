package io.nekohasekai.sfa.bg

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import io.nekohasekai.composite.LookupRequest
import io.nekohasekai.composite.NetworkProvider
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One immutable Android network generation; not yet selected by production runtime. */
internal class XrayNetworkProvider private constructor(
    private val service: VpnService,
    private val network: Network,
) : NetworkProvider {
    override fun protectAndBind(fd: Long) {
        require(fd in 0..Int.MAX_VALUE.toLong()) { "Invalid socket descriptor" }
        check(service.protect(fd.toInt())) { "VPN socket protection denied" }
        // fromFd duplicates: close only our duplicate, never the Go-owned fd.
        ParcelFileDescriptor.fromFd(fd.toInt()).use { network.bindSocket(it.fileDescriptor) }
    }

    override fun lookup(domain: String, request: LookupRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lookupModern(domain, request)
        } else {
            lookupLegacy(domain, request)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun lookupModern(domain: String, request: LookupRequest) {
        val signal = CancellationSignal()
        request.onCancel { signal.cancel() }
        if (signal.isCanceled) return
        DnsResolver.getInstance().query(
            network,
            domain,
            DnsResolver.FLAG_NO_RETRY,
            immediateCallbacks,
            signal,
            object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    if (rcode == 0) {
                        // Completion can race Stop/replacement. Go rejects stale replies.
                        runCatching { request.success(answer.mapNotNull { it.hostAddress }.joinToString("\n")) }
                    } else {
                        runCatching { request.fail("DNS response code $rcode") }
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    runCatching { request.fail(error.toString()) }
                }
            },
        )
    }

    private fun lookupLegacy(domain: String, request: LookupRequest) {
        // Android's blocking pre-Q DNS call may ignore interruption. Bound both
        // worker count and queue; JNI Stop never waits for the platform lookup.
        val task = FutureTask<Unit> {
            try {
                val addresses = network.getAllByName(domain)
                runCatching { request.success(addresses.mapNotNull { it.hostAddress }.joinToString("\n")) }
            } catch (error: Exception) {
                runCatching { request.fail(error.toString()) }
            }
        }
        request.onCancel {
            task.cancel(true)
            legacyWorkers.remove(task)
        }
        if (!task.isCancelled) legacyWorkers.execute(task)
    }

    companion object {
        private val immediateCallbacks = Executor { it.run() }
        private val legacyWorkers = ThreadPoolExecutor(
            4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
            { runnable -> Thread(runnable, "xray-network-dns").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

        /** Reject VPN networks at capture; callbacks never consult a live default. */
        fun capture(service: VpnService, manager: ConnectivityManager, network: Network): XrayNetworkProvider {
            val capabilities = manager.getNetworkCapabilities(network)
            require(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                "An available underlying non-VPN network is required"
            }
            return XrayNetworkProvider(service, network)
        }
    }
}
