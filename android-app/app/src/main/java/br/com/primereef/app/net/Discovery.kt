package br.com.primereef.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import br.com.primereef.app.data.DeviceType
import br.com.primereef.app.data.FoundDevice
import br.com.primereef.app.data.Protocol
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
 * Encontra aparelhos Prime Reef na rede local de duas formas:
 *  1. mDNS/NSD: todo aparelho anuncia o serviço "_primereef._tcp".
 *  2. Varredura da sub-rede /24 (fallback quando o mDNS falha).
 * Cada endereço encontrado passa por [identify], que descobre o tipo e o protocolo do aparelho.
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
                val ip = (addr as? Inet4Address)?.hostAddress ?: continue
                val found = identify(ip, "mDNS", 3000) ?: continue
                // Firmware antigo da luminária não informa id: usa o nome mDNS (reef-xxxxxx).
                send(if (found.id == null) found.copy(name = resolved.serviceName, id = resolved.serviceName) else found)
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
                    val found = if (ip != self) identify(ip, "rede", 900) else null
                    onProgress(done.incrementAndGet(), 254)
                    found
                }
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        /**
         * Descobre se [host] é um aparelho Prime Reef e qual protocolo usar:
         *  - responde /api/info -> Protocolo Prime Reef v1 (exceto luminária, que mantém a tela própria)
         *  - responde /status no formato da luminária -> firmware atual da luminária
         */
        suspend fun identify(host: String, source: String, timeoutMs: Int = 3000): FoundDevice? {
            val info = try {
                DeviceApi(host, timeoutMs).info()
            } catch (e: HttpStatusException) {
                null // o aparelho existe mas não tem /api: pode ser a luminária atual
            } catch (e: org.json.JSONException) {
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return null // sem resposta: não há aparelho nesse IP
            }
            val isLuminaria = (info == null || info.type == DeviceType.LUMINARIA.key) &&
                LuminariaApi(host, timeoutMs).probe()
            return when {
                isLuminaria -> FoundDevice(
                    host = host,
                    name = info?.name?.ifBlank { null } ?: "Luminária $host",
                    source = source,
                    type = DeviceType.LUMINARIA.key,
                    protocol = Protocol.LUMINARIA,
                    id = info?.id?.ifBlank { null },
                )
                info != null -> FoundDevice(
                    host = host,
                    name = info.name.ifBlank { info.model }.ifBlank { DeviceType.of(info.type).label },
                    source = source,
                    type = info.type,
                    protocol = Protocol.PRP1,
                    id = info.id.ifBlank { null },
                )
                else -> null
            }
        }

        const val SERVICE_TYPE = "_primereef._tcp"
        /** IP do aparelho quando está em modo ponto de acesso (configuração inicial). */
        const val AP_IP = "192.168.4.1"
    }
}
