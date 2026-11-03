package br.com.primereef.app.net

import br.com.primereef.app.data.Alert
import br.com.primereef.app.data.Control
import br.com.primereef.app.data.ControlType
import br.com.primereef.app.data.DeviceType
import br.com.primereef.app.data.GenericState
import br.com.primereef.app.data.Level
import br.com.primereef.app.data.Sensor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow
import kotlin.math.roundToLong

/** Data centers da nuvem Tuya. Contas do Brasil ficam em "Américas (Oeste)". */
enum class TuyaRegion(val label: String, val host: String) {
    AMERICA("Américas (Brasil)", "openapi.tuyaus.com"),
    AMERICA_LESTE("Américas Leste", "openapi-ueaz.tuyaus.com"),
    EUROPA("Europa Central", "openapi.tuyaeu.com"),
    EUROPA_OESTE("Europa Ocidental", "openapi-weaz.tuyaeu.com"),
    INDIA("Índia", "openapi.tuyain.com"),
    CHINA("China", "openapi.tuyacn.com"),
}

data class TuyaCredentials(val accessId: String, val accessSecret: String, val region: TuyaRegion)

data class TuyaDeviceSummary(
    val id: String,
    val name: String,
    val category: String,
    val productName: String,
    val online: Boolean,
) {
    val deviceType: DeviceType get() = TuyaCloud.typeForCategory(category)
}

class TuyaException(val code: Int, message: String) : IOException(message)

/** Assinatura HMAC-SHA256 das requisições da Tuya OpenAPI (mesmo algoritmo do tinytuya). */
object TuyaSigner {
    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun sign(
        clientId: String,
        secret: String,
        token: String?,
        t: Long,
        method: String,
        pathWithQuery: String,
        body: String?,
        contentType: String?,
    ): String {
        val sb = StringBuilder(clientId)
        if (token != null) sb.append(token)
        sb.append(t)
        sb.append(method).append('\n')
        sb.append(sha256Hex(body ?: "")).append('\n')
        if (contentType != null) sb.append("Content-type:").append(contentType).append('\n')
        sb.append('\n').append(pathWithQuery)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(sb.toString().toByteArray()).joinToString("") { "%02X".format(it) }
    }
}

/**
 * Cliente da nuvem Tuya (OpenAPI) para equipamentos do app Smart Life.
 * O usuário cria um projeto gratuito em platform.tuya.com, vincula a conta do Smart Life
 * e informa aqui o Access ID e o Access Secret do projeto.
 */
class TuyaCloud(private val creds: TuyaCredentials) {

    private var token: String? = null
    private var tokenExpiresAt = 0L
    private val tokenLock = Mutex()

    private class Spec(val functions: Map<String, Pair<String, JSONObject>>, val status: Map<String, Pair<String, JSONObject>>)
    private val specCache = mutableMapOf<String, Spec>()

    private val base = "https://${creds.region.host}"

    // ---------------- Requisições ----------------

    private suspend fun accessToken(): String = tokenLock.withLock {
        val now = System.currentTimeMillis()
        token?.takeIf { now < tokenExpiresAt - 60_000 }?.let { return it }
        val r = raw("GET", "/v1.0/token?grant_type=1", null, null) as JSONObject
        token = r.getString("access_token")
        tokenExpiresAt = now + r.optLong("expire_time", 3600) * 1000
        token!!
    }

    /** Executa a chamada e devolve o campo "result". Renova o token uma vez se ele expirou. */
    private suspend fun call(method: String, path: String, body: String? = null): Any? {
        return try {
            raw(method, path, body, accessToken())
        } catch (e: TuyaException) {
            if (e.code in TOKEN_ERRORS) {
                tokenLock.withLock { token = null }
                raw(method, path, body, accessToken())
            } else {
                throw e
            }
        }
    }

    private suspend fun raw(method: String, path: String, body: String?, token: String?): Any? {
        val t = System.currentTimeMillis()
        val contentType = if (body != null) "application/json" else null
        val headers = mutableMapOf(
            "client_id" to creds.accessId,
            "sign" to TuyaSigner.sign(creds.accessId, creds.accessSecret, token, t, method, path, body, contentType),
            "t" to t.toString(),
            "sign_method" to "HMAC-SHA256",
        )
        if (token != null) headers["access_token"] = token
        if (contentType != null) headers["Signature-Headers"] = "Content-type"
        val text = Http.request(
            base, path, method, body, timeoutMs = 10_000, headers = headers,
            contentType = contentType ?: "text/plain",
        )
        val o = JSONObject(text)
        if (!o.optBoolean("success")) {
            val code = o.optInt("code")
            throw TuyaException(code, friendlyError(code, o.optString("msg")))
        }
        return o.opt("result")
    }

    // ---------------- API ----------------

    /** Testa as credenciais (pede um token). */
    suspend fun testConnection() {
        tokenLock.withLock { token = null }
        accessToken()
    }

    /** Todos os equipamentos das contas Smart Life vinculadas ao projeto. */
    suspend fun devices(): List<TuyaDeviceSummary> {
        val out = mutableListOf<TuyaDeviceSummary>()
        var lastRowKey: String? = null
        do {
            // Parâmetros em ordem alfabética (exigência da assinatura).
            val q = (if (lastRowKey != null) "last_row_key=$lastRowKey&" else "") + "size=50"
            val r = call("GET", "/v1.0/iot-01/associated-users/devices?$q") as? JSONObject ?: break
            val arr = r.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                out += TuyaDeviceSummary(
                    id = d.optString("id"),
                    name = d.optString("name"),
                    category = d.optString("category"),
                    productName = d.optString("product_name"),
                    online = d.optBoolean("online"),
                )
            }
            lastRowKey = r.optString("last_row_key").ifBlank { null }
        } while (r.optBoolean("has_more") && lastRowKey != null)
        return out
    }

    private suspend fun spec(deviceId: String): Spec {
        specCache[deviceId]?.let { return it }
        val r = call("GET", "/v1.0/devices/$deviceId/specifications") as? JSONObject ?: JSONObject()
        fun parse(arr: JSONArray?): Map<String, Pair<String, JSONObject>> {
            if (arr == null) return emptyMap()
            return (0 until arr.length()).associate { i ->
                val f = arr.getJSONObject(i)
                val values = runCatching { JSONObject(f.optString("values", "{}")) }.getOrDefault(JSONObject())
                f.optString("code") to (f.optString("type") to values)
            }
        }
        return Spec(parse(r.optJSONArray("functions")), parse(r.optJSONArray("status")))
            .also { specCache[deviceId] = it }
    }

    /** Estado atual convertido para o mesmo modelo de tela dos aparelhos Prime Reef. */
    suspend fun state(deviceId: String): Pair<Boolean, GenericState> {
        val spec = spec(deviceId)
        val d = call("GET", "/v1.0/devices/$deviceId") as? JSONObject ?: JSONObject()
        val online = d.optBoolean("online")
        val values = mutableMapOf<String, Any?>()
        d.optJSONArray("status")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                values[s.optString("code")] = s.opt("value")
            }
        }
        return online to buildState(spec, values, online)
    }

    /** Envia um comando. [value] vem da tela como texto e é convertido para o tipo do equipamento. */
    suspend fun send(deviceId: String, code: String, value: String) {
        val (type, v) = spec(deviceId).functions[code] ?: throw TuyaException(0, "Comando desconhecido: $code")
        val typed: Any = when (type) {
            "Boolean" -> value == "1" || value == "true"
            "Integer" -> {
                val scale = v.optInt("scale", 0)
                ((value.replace(',', '.').toDoubleOrNull() ?: 0.0) * 10.0.pow(scale)).roundToLong()
            }
            else -> value
        }
        val body = JSONObject().put(
            "commands",
            JSONArray().put(JSONObject().put("code", code).put("value", typed)),
        ).toString()
        call("POST", "/v1.0/iot-03/devices/$deviceId/commands", body)
    }

    // ---------------- Conversão para a tela genérica ----------------

    private fun buildState(spec: Spec, values: Map<String, Any?>, online: Boolean): GenericState {
        val controls = mutableListOf<Control>()
        val sensors = mutableListOf<Sensor>()
        val alerts = mutableListOf<Alert>()

        spec.functions.forEach { (code, tv) ->
            if (code in HIDDEN) return@forEach
            val (type, v) = tv
            val current = values[code]
            val label = labelFor(code)
            when (type) {
                "Boolean" -> controls += control(code, label, ControlType.SWITCH, (current == true).toString())
                "Integer" -> {
                    val div = 10.0.pow(v.optInt("scale", 0))
                    val min = v.optDouble("min", 0.0) / div
                    val max = v.optDouble("max", 100.0) / div
                    val step = v.optDouble("step", 1.0) / div
                    val isFeed = code in FEED_CODES
                    controls += Control(
                        id = code,
                        label = label,
                        type = if (isFeed || max - min > 1000) ControlType.NUMBER else ControlType.SLIDER,
                        // Alimentar: sugere 1 porção em vez do último valor enviado.
                        value = if (isFeed) formatNum(maxOf(min, 1.0)) else (current as? Number)?.let { formatNum(it.toDouble() / div) },
                        min = min, max = max, step = step,
                        unit = unitFor(code, v.optString("unit")),
                        options = emptyList(),
                        confirm = null,
                        applyLabel = if (isFeed) "Alimentar" else "Aplicar",
                    )
                }
                "Enum" -> {
                    val range = v.optJSONArray("range")
                    val opts = if (range == null) emptyList() else (0 until range.length()).map { range.optString(it) }
                    controls += Control(
                        id = code, label = label, type = ControlType.SELECT, value = current?.toString(),
                        min = 0.0, max = 0.0, step = 1.0, unit = "", options = opts, confirm = null,
                    )
                }
                // Raw/Json/String (ex.: plano de refeições) ainda não são editáveis pelo app.
            }
        }

        spec.status.forEach { (code, tv) ->
            if (code in spec.functions || code in HIDDEN) return@forEach
            val (type, v) = tv
            val current = values[code] ?: return@forEach
            val label = labelFor(code)
            when (type) {
                "Integer" -> {
                    val n = (current as? Number)?.toDouble()?.div(10.0.pow(v.optInt("scale", 0))) ?: return@forEach
                    val level = if (code.contains("battery")) {
                        when {
                            n < 10 -> Level.ALARM
                            n < 20 -> Level.WARN
                            else -> Level.OK
                        }
                    } else Level.OK
                    sensors += Sensor(code, label, formatNum(n), unitFor(code, v.optString("unit")), level)
                }
                "Enum" -> sensors += Sensor(code, label, translate(current.toString()), "", Level.OK)
                "Boolean" -> sensors += Sensor(code, label, if (current == true) "Sim" else "Não", "", Level.OK)
                "Bitmap" -> if ((current as? Number)?.toLong() ?: 0L != 0L) {
                    alerts += Alert(Level.ALARM, "Falha no equipamento (código $current)")
                }
                "String" -> current.toString().takeIf { it.isNotBlank() && it.length <= 40 }?.let {
                    sensors += Sensor(code, label, it, "", Level.OK)
                }
            }
        }
        if (!online) alerts += Alert(Level.WARN, "Equipamento offline na nuvem Tuya")
        return GenericState(sensors, controls, alerts)
    }

    private fun control(code: String, label: String, type: ControlType, value: String?) = Control(
        id = code, label = label, type = type, value = value, min = 0.0, max = 1.0, step = 1.0,
        unit = "", options = emptyList(), confirm = null,
    )

    companion object {
        private val TOKEN_ERRORS = setOf(1010, 1011, 1012)
        private val FEED_CODES = setOf("manual_feed", "feed_num", "quick_feed_num")
        private val HIDDEN = setOf("factory_reset", "meal_plan", "voice_times", "export_calibrate", "weight_calibrate")

        private val LABELS = mapOf(
            "manual_feed" to "Alimentar agora (porções)",
            "quick_feed" to "Alimentação rápida",
            "slow_feed" to "Alimentação lenta",
            "feed_state" to "Estado",
            "feed_report" to "Última alimentação (porções)",
            "light" to "Luz indicadora",
            "switch" to "Ligado",
            "switch_led" to "Luz",
            "switch_1" to "Saída 1",
            "switch_2" to "Saída 2",
            "switch_3" to "Saída 3",
            "switch_4" to "Saída 4",
            "battery_percentage" to "Bateria",
            "charge_state" to "Carregando",
            "temp_current" to "Temperatura",
            "temp_set" to "Temperatura desejada",
            "countdown_1" to "Temporizador",
            "work_mode" to "Modo",
            "fault" to "Falha",
            "child_lock" to "Trava",
        )

        private val VALUES = mapOf(
            "standby" to "Em espera",
            "feeding" to "Alimentando",
            "done" to "Concluído",
            "charging" to "Carregando",
            "full" to "Completo",
            "low" to "Baixo",
            "normal" to "Normal",
        )

        fun labelFor(code: String): String = LABELS[code]
            ?: code.replace('_', ' ').replaceFirstChar { it.uppercase() }

        private fun translate(v: String) = VALUES[v] ?: v

        private fun unitFor(code: String, unit: String) = when {
            code.contains("battery") -> "%"
            else -> unit
        }

        private fun formatNum(d: Double): String =
            if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')

        private fun friendlyError(code: Int, msg: String): String = when (code) {
            1004 -> "Access Secret incorreto (assinatura inválida)"
            1005, 2009 -> "Access ID incorreto"
            1106 -> "Sem permissão: vincule sua conta Smart Life ao projeto Tuya"
            28841101, 28841002 -> "Plano da API Tuya expirado: renove o IoT Core (gratuito) em platform.tuya.com"
            else -> "Tuya: $msg (código $code)"
        }

        /** Categoria Tuya -> família de produto no app. */
        fun typeForCategory(category: String): DeviceType = when (category) {
            "cwwsq", "cwwqfsq" -> DeviceType.ALIMENTADOR
            "cwysj", "sb" -> DeviceType.BOMBA
            "dj", "dd", "dc", "fwd", "xdd", "tgq", "tgkg", "sxd" -> DeviceType.LUMINARIA
            "cz", "pc", "kg", "tdq", "wkf", "wk", "qn" -> DeviceType.CONTROLADOR
            "wsdcg", "wnykq", "ldcg", "pm2.5", "co2bj", "sj", "ywbj" -> DeviceType.SENSOR
            else -> DeviceType.OUTRO
        }
    }
}
