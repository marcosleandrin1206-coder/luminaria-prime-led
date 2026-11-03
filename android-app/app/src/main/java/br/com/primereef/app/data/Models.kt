package br.com.primereef.app.data

/** Como o app conversa com o aparelho. */
enum class Protocol {
    /** Firmware atual da luminária (/status, /live, /schedule...): tela especializada. */
    LUMINARIA,
    /** Protocolo Prime Reef v1 (/api/info, /api/state, /api/set): tela montada a partir do aparelho. */
    PRP1,
}

/** Famílias de aparelhos do ecossistema Prime Reef Brasil. */
enum class DeviceType(val key: String, val label: String, val plural: String) {
    LUMINARIA("luminaria", "Luminária", "Iluminação"),
    DOSADORA("dosadora", "Dosadora", "Dosadoras"),
    BOMBA("bomba", "Bomba", "Bombas e circulação"),
    ATO("ato", "Reposição (ATO)", "Reposição de água"),
    CONTROLADOR("controlador", "Controlador", "Controladores"),
    SENSOR("sensor", "Sensor", "Sensores e monitores"),
    OUTRO("outro", "Dispositivo", "Outros dispositivos");

    companion object {
        fun of(key: String?): DeviceType = entries.firstOrNull { it.key == key } ?: OUTRO
    }
}

/** Um aparelho salvo no app. [id] é estável (id do aparelho, nome mDNS ou IP); [host] pode mudar com o DHCP. */
data class Device(
    val id: String,
    val name: String,
    val host: String,
    val type: String = DeviceType.LUMINARIA.key,
    val protocol: Protocol = Protocol.LUMINARIA,
) {
    val deviceType: DeviceType get() = DeviceType.of(type)
}

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

/** Aparelho encontrado na busca da rede. */
data class FoundDevice(
    val host: String,
    val name: String,
    val source: String,
    val type: String = DeviceType.LUMINARIA.key,
    val protocol: Protocol = Protocol.LUMINARIA,
    /** id informado pelo próprio aparelho (PRP1) ou nome mDNS; null = usar o IP. */
    val id: String? = null,
)

// ---------- Protocolo Prime Reef v1 (ver docs/PROTOCOLO.md) ----------

data class DeviceInfo(
    val api: Int,
    val id: String,
    val type: String,
    val model: String,
    val name: String,
    val fw: String,
)

enum class Level { OK, INFO, WARN, ALARM;
    companion object {
        fun of(s: String?): Level = when (s?.lowercase()) {
            "warn" -> WARN
            "alarm" -> ALARM
            "info" -> INFO
            else -> OK
        }
    }
}

data class Sensor(
    val id: String,
    val label: String,
    val value: String,
    val unit: String,
    val status: Level,
)

enum class ControlType { SWITCH, SLIDER, NUMBER, SELECT, BUTTON, UNKNOWN }

data class Control(
    val id: String,
    val label: String,
    val type: ControlType,
    val value: String?,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: String,
    val options: List<String>,
    val confirm: String?,
) {
    val boolValue: Boolean get() = value == "true" || value == "1"
    val numValue: Double get() = value?.toDoubleOrNull() ?: min
}

data class Alert(val level: Level, val message: String)

data class GenericState(
    val sensors: List<Sensor>,
    val controls: List<Control>,
    val alerts: List<Alert>,
)

/** Estado "ao vivo" de cada aparelho, atualizado pelo polling. */
data class DeviceLive(
    /** Preenchido para luminárias (protocolo LUMINARIA). */
    val status: LightStatus?,
    /** Preenchido para aparelhos PRP1. */
    val state: GenericState? = null,
    val online: Boolean,
    val updatedAt: Long,
)
