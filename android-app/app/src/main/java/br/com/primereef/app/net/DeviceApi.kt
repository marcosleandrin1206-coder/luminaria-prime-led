package br.com.primereef.app.net

import br.com.primereef.app.data.Alert
import br.com.primereef.app.data.Control
import br.com.primereef.app.data.ControlType
import br.com.primereef.app.data.DeviceInfo
import br.com.primereef.app.data.GenericState
import br.com.primereef.app.data.Level
import br.com.primereef.app.data.Sensor
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Cliente do Protocolo Prime Reef v1 (docs/PROTOCOLO.md), comum a todos os aparelhos novos. */
class DeviceApi(private val host: String, private val timeoutMs: Int = 4000) {

    suspend fun info(): DeviceInfo {
        val o = JSONObject(Http.request(host, "/api/info", timeoutMs = timeoutMs))
        return DeviceInfo(
            api = o.optInt("api", 1),
            id = o.optString("id"),
            type = o.optString("type", "outro"),
            model = o.optString("model"),
            name = o.optString("name"),
            fw = o.opt("fw")?.toString().orEmpty(),
        )
    }

    suspend fun state(): GenericState =
        parseState(JSONObject(Http.request(host, "/api/state", timeoutMs = timeoutMs)))

    /** Altera um controle; retorna o novo estado (ou null se o aparelho não devolveu JSON). */
    suspend fun set(id: String, value: String): GenericState? {
        val text = Http.request(host, "/api/set?id=${enc(id)}&v=${enc(value)}", timeoutMs = timeoutMs)
        return runCatching { parseState(JSONObject(text)) }.getOrNull()
            ?.takeIf { it.sensors.isNotEmpty() || it.controls.isNotEmpty() }
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }
        }

        /** Converte o valor JSON para texto, sem ".0" desnecessário em números inteiros. */
        private fun valueText(v: Any?): String? = when (v) {
            null, JSONObject.NULL -> null
            is Boolean -> v.toString()
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString()
                else "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
            }
            else -> v.toString()
        }

        fun parseState(o: JSONObject) = GenericState(
            sensors = o.optJSONArray("sensors").mapObjects { s ->
                Sensor(
                    id = s.optString("id"),
                    label = s.optString("label", s.optString("id")),
                    value = valueText(s.opt("value")) ?: "--",
                    unit = s.optString("unit"),
                    status = Level.of(s.optString("status")),
                )
            },
            controls = o.optJSONArray("controls").mapObjects { c ->
                val opts = c.optJSONArray("options")
                Control(
                    id = c.optString("id"),
                    label = c.optString("label", c.optString("id")),
                    type = when (c.optString("type").lowercase()) {
                        "switch" -> ControlType.SWITCH
                        "slider" -> ControlType.SLIDER
                        "number" -> ControlType.NUMBER
                        "select" -> ControlType.SELECT
                        "button" -> ControlType.BUTTON
                        else -> ControlType.UNKNOWN
                    },
                    value = valueText(c.opt("value")),
                    min = c.optDouble("min", 0.0),
                    max = c.optDouble("max", 100.0),
                    step = c.optDouble("step", 1.0),
                    unit = c.optString("unit"),
                    options = if (opts == null) emptyList() else (0 until opts.length()).map { opts.optString(it) },
                    confirm = c.optString("confirm").ifBlank { null },
                )
            },
            alerts = o.optJSONArray("alerts").mapObjects { a ->
                Alert(Level.of(a.optString("level")), a.optString("msg"))
            },
        )
    }
}
