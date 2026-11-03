package br.com.primereef.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import br.com.primereef.app.data.FoundDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Encontra luminárias na rede local de duas formas:
 *  1. mDNS/NSD: o firmware anuncia o serviço "_primereef._tcp" (hostname reef-xxxxxx.local).
 *  2. Varredura da sub-rede /24 testando GET /status em cada IP (fallback quando o mDNS falha).
 */
class Discovery(private val context: Context) {

    fun mdns(): Flow<FoundDevice> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("primereef")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                queue.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                this@callbackFlow.close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        // O NsdManager só resolve um serviço por vez: resolvemos em fila.
        launch {
            for (info in queue) {
                val resolved = withTimeoutOrNull(5000) { resolve(nsd, info) } ?: continue
                @Suppress("DEPRECATION")
                val addr = resolved.host
                if (addr is Inet4Address) {
                    send(FoundDevice(addr.hostAddress.orEmpty(), resolved.serviceName, "mDNS"))
                }
            }
        }

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            queue.close()
            lock?.release()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }
            })
        }

    /** IPv4 do celular na rede atual (Wi-Fi). */
    fun localIpv4(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    suspend fun scanSubnet(onProgress: (done: Int, total: Int) -> Unit): List<FoundDevice> = coroutineScope {
        val self = localIpv4() ?: return@coroutineScope emptyList()
        val prefix = self.substringBeforeLast('.')
        val gate = Semaphore(40)
        val done = AtomicInteger()
        (1..254).map { i ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val ip = "$prefix.$i"
                    val ok = ip != self && PrimeReefApi(ip, timeoutMs = 900).probe()
                    onProgress(done.incrementAndGet(), 254)
                    if (ok) FoundDevice(ip, "Luminária $ip", "rede") else null
                }
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        const val SERVICE_TYPE = "_primereef._tcp"
        /** IP da luminária quando ela está em modo ponto de acesso (configuração inicial). */
        const val AP_IP = "192.168.4.1"
    }
}
