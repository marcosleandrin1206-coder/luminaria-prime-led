package br.com.primereef.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.primereef.app.data.Device
import br.com.primereef.app.data.DeviceLive
import br.com.primereef.app.data.DeviceRepository
import br.com.primereef.app.data.FoundDevice
import br.com.primereef.app.data.LedChannel
import br.com.primereef.app.data.Schedule
import br.com.primereef.app.net.Discovery
import br.com.primereef.app.net.PrimeReefApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveryState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val found: List<FoundDevice> = emptyList(),
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DeviceRepository(app)
    private val discovery = Discovery(app)

    private val _devices = MutableStateFlow(repo.load())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _live = MutableStateFlow<Map<String, DeviceLive>>(emptyMap())
    val live: StateFlow<Map<String, DeviceLive>> = _live.asStateFlow()

    private val _discovery = MutableStateFlow(DiscoveryState())
    val discoveryState: StateFlow<DiscoveryState> = _discovery.asStateFlow()

    private var pollJob: Job? = null
    private var discoveryJob: Job? = null

    fun api(device: Device) = PrimeReefApi(device.host)

    // ---------- Polling ----------

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshAll()
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    suspend fun refreshAll() = coroutineScope {
        _devices.value.map { d -> async { refresh(d) } }.awaitAll()
    }

    private suspend fun refresh(d: Device) {
        val result = runCatching { PrimeReefApi(d.host, timeoutMs = 2500).status() }
        _live.update { map ->
            val prev = map[d.id]
            map + (d.id to DeviceLive(
                status = result.getOrNull() ?: prev?.status,
                online = result.isSuccess,
                updatedAt = System.currentTimeMillis(),
            ))
        }
    }

    // ---------- Controle manual ----------

    private val lastPreview = mutableMapOf<String, Long>()
    private val pendingPreview = mutableMapOf<String, Int>()
    private val pendingJobs = mutableMapOf<String, Job>()

    /**
     * Enquanto o slider é arrastado, envia /preview com limite de 1 requisição a cada 80 ms
     * por luminária+canal (mesma estratégia do painel web do firmware), sempre mandando o último valor.
     */
    fun preview(targets: List<Device>, ch: LedChannel, value: Int) {
        targets.forEach { d ->
            val key = "${d.id}/${ch.key}"
            val now = System.currentTimeMillis()
            val last = lastPreview[key] ?: 0L
            if (now - last >= PREVIEW_MS) {
                lastPreview[key] = now
                viewModelScope.launch { runCatching { api(d).preview(ch, value) } }
            } else {
                pendingPreview[key] = value
                if (pendingJobs[key]?.isActive != true) {
                    pendingJobs[key] = viewModelScope.launch {
                        delay(PREVIEW_MS - (now - last))
                        lastPreview[key] = System.currentTimeMillis()
                        val v = pendingPreview.remove(key) ?: return@launch
                        runCatching { api(d).preview(ch, v) }
                    }
                }
            }
        }
    }

    /** Valor final do slider: grava em modo manual (/live). Tenta de novo uma vez se falhar. */
    fun commit(targets: List<Device>, ch: LedChannel, value: Int) {
        targets.forEach { d ->
            val key = "${d.id}/${ch.key}"
            pendingJobs.remove(key)?.cancel()
            pendingPreview.remove(key)
            viewModelScope.launch {
                val ok = runCatching { api(d).live(ch, value) }.isSuccess
                if (!ok) {
                    delay(300)
                    runCatching { api(d).live(ch, value) }
                }
                refresh(d)
            }
        }
    }

    fun cancelManual(targets: List<Device>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val ok = targets.map { d ->
                async { runCatching { api(d).cancelManual() }.isSuccess.also { refresh(d) } }
            }.awaitAll().count { it }
            onDone(ok)
        }
    }

    /** Salva o mesmo programa em várias luminárias. Retorna quantas aceitaram. */
    suspend fun saveSchedule(targets: List<Device>, schedule: Schedule): Int = coroutineScope {
        targets.map { d -> async { runCatching { api(d).saveSchedule(schedule) }.isSuccess } }
            .awaitAll().count { it }
    }

    // ---------- Gerenciamento de luminárias ----------

    fun addDevice(found: FoundDevice) {
        viewModelScope.launch {
            val number = runCatching { PrimeReefApi(found.host).groupNumber() }.getOrDefault(0)
            val existing = _devices.value.firstOrNull { it.id == found.name || it.host == found.host }
            val name = when {
                existing != null -> existing.name
                number > 0 -> "Luminária $number"
                else -> found.name
            }
            val id = existing?.id ?: if (found.source == "mDNS") found.name else found.host
            upsert(Device(id = id, name = name, host = found.host))
        }
    }

    fun addManual(host: String, name: String) {
        val h = host.trim().removePrefix("http://").trimEnd('/')
        if (h.isEmpty()) return
        addDevice(FoundDevice(h, name.ifBlank { h }, "manual"))
    }

    fun rename(device: Device, name: String) {
        if (name.isBlank()) return
        upsert(device.copy(name = name.trim()))
    }

    fun updateHost(device: Device, host: String) {
        if (host.isBlank()) return
        upsert(device.copy(host = host.trim().removePrefix("http://").trimEnd('/')))
    }

    fun remove(device: Device) {
        _devices.update { list -> list.filterNot { it.id == device.id } }
        _live.update { it - device.id }
        repo.save(_devices.value)
    }

    private fun upsert(device: Device) {
        _devices.update { list ->
            val idx = list.indexOfFirst { it.id == device.id }
            if (idx >= 0) list.toMutableList().also { it[idx] = device } else list + device
        }
        repo.save(_devices.value)
        viewModelScope.launch { refresh(device) }
    }

    // ---------- Busca na rede ----------

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        _discovery.value = DiscoveryState(running = true)
        discoveryJob = viewModelScope.launch {
            val mdnsJob = launch {
                withTimeoutOrNull(MDNS_TIMEOUT_MS) {
                    discovery.mdns().collect { addFound(it) }
                }
            }
            val scanned = discovery.scanSubnet { done, total ->
                _discovery.update { it.copy(progress = done.toFloat() / total) }
            }
            scanned.forEach { addFound(it) }
            mdnsJob.join()
            // Atualiza o IP das luminárias salvas que mudaram de endereço (encontradas pelo nome mDNS).
            _discovery.value.found.forEach { f ->
                _devices.value.firstOrNull { it.id == f.name && it.host != f.host }
                    ?.let { upsert(it.copy(host = f.host)) }
            }
            _discovery.update {
                it.copy(
                    running = false,
                    progress = 1f,
                    message = when {
                        discovery.localIpv4() == null -> "Conecte o celular ao Wi-Fi para buscar luminárias."
                        it.found.isEmpty() -> "Nenhuma luminária encontrada nesta rede."
                        else -> null
                    },
                )
            }
        }
    }

    private fun addFound(f: FoundDevice) {
        _discovery.update { s ->
            val existing = s.found.indexOfFirst { it.host == f.host }
            val list = when {
                existing < 0 -> s.found + f
                f.source == "mDNS" -> s.found.toMutableList().also { it[existing] = f } // nome melhor
                else -> s.found
            }
            s.copy(found = list)
        }
    }

    companion object {
        const val POLL_MS = 3000L
        const val PREVIEW_MS = 80L
        const val MDNS_TIMEOUT_MS = 10_000L
    }
}
