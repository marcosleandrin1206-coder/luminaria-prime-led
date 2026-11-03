package br.com.primereef.app.data

/** Uma luminária salva no app. [id] é estável (nome mDNS ou IP); [host] pode mudar com o DHCP. */
data class Device(
    val id: String,
    val name: String,
    val host: String,
)

/** Canais de LED da luminária, com a chave usada pelo firmware em /live, /preview e /status. */
enum class LedChannel(val key: String, val label: String, val color: Long) {
    ROYAL("b", "Azul Royal", 0xFF29B6F6),
    BLUE("az", "Azul", 0xFF1565C0),
    WHITE("w", "Branco", 0xFFE0E0E0),
    UV("u", "UV", 0xFFD50000),
}

/** Resposta de GET /status. */
data class LightStatus(
    val levels: Map<LedChannel, Int>,
    val time: String,
    val manual: Boolean,
    val manualMinutesLeft: Int,
    val cooler: Boolean,
)

/** Resposta de GET /schedule: horário de ligar/desligar e intensidade máxima de cada canal. */
data class Schedule(
    val onHour: Int,
    val onMinute: Int,
    val offHour: Int,
    val offMinute: Int,
    val levels: Map<LedChannel, Int>,
)

data class Peer(val host: String, val number: Int)

data class FoundPeer(val host: String, val paired: Boolean)

/** Luminária encontrada na busca da rede. */
data class FoundDevice(val host: String, val name: String, val source: String)

/** Estado "ao vivo" de cada luminária, atualizado pelo polling. */
data class DeviceLive(
    val status: LightStatus?,
    val online: Boolean,
    val updatedAt: Long,
)
