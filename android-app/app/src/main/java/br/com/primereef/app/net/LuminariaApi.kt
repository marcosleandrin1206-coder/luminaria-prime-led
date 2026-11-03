package br.com.primereef.app.net

import br.com.primereef.app.data.FoundPeer
import br.com.primereef.app.data.LedChannel
import br.com.primereef.app.data.LightStatus
import br.com.primereef.app.data.Peer
import br.com.primereef.app.data.Schedule
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Cliente da API HTTP própria do firmware atual da luminária Prime Reef Brasil (ESP32, porta 80).
 *
 * Endpoints mapeados a partir do firmware:
 *  GET  /status                -> {"b","az","w","u","t","manual","ml","cooler"}
 *  GET  /schedule              -> {"h1","m1","h2","m2","royal","blue","w","u"}
 *  GET  /preview?ch=&v=        -> pré-visualização enquanto o slider é arrastado
 *  GET  /live?ch=&v=           -> aplica valor manual (sincroniza com o grupo)
 *  GET  /cancelmanual          -> volta ao programa, retorna os níveis atuais
 *  GET  /save?t1=&t2=&royal=&blue=&w=&u=
 *  GET  /peers, /discover, /getnumber, /removepeer?host=, /forgetgroup
 *  POST /creategroup (text/plain: hosts separados por vírgula)
 *  GET  /checkupdate, /checkupdate_group
 */
class LuminariaApi(private val host: String, private val timeoutMs: Int = 4000) {

    suspend fun status(): LightStatus = parseStatus(JSONObject(get("/status")))

    /** Retorna true se o host responde como uma luminária Prime Reef. */
    suspend fun probe(): Boolean = runCatching {
        val o = JSONObject(get("/status"))
        o.has("cooler") || o.has("manual")
    }.getOrDefault(false)

    suspend fun schedule(): Schedule {
        val o = JSONObject(get("/schedule"))
        return Schedule(
            onHour = o.optInt("h1"),
            onMinute = o.optInt("m1"),
            offHour = o.optInt("h2"),
            offMinute = o.optInt("m2"),
            levels = mapOf(
                LedChannel.ROYAL to o.optInt("royal"),
                LedChannel.BLUE to o.optInt("blue"),
                LedChannel.WHITE to o.optInt("w"),
                LedChannel.UV to o.optInt("u"),
            ),
        )
    }

    suspend fun saveSchedule(s: Schedule) {
        val t1 = "%02d:%02d".format(s.onHour, s.onMinute)
        val t2 = "%02d:%02d".format(s.offHour, s.offMinute)
        get(
            "/save?t1=${enc(t1)}&t2=${enc(t2)}" +
                "&royal=${s.levels[LedChannel.ROYAL] ?: 0}" +
                "&blue=${s.levels[LedChannel.BLUE] ?: 0}" +
                "&w=${s.levels[LedChannel.WHITE] ?: 0}" +
                "&u=${s.levels[LedChannel.UV] ?: 0}",
        )
    }

    suspend fun preview(ch: LedChannel, value: Int) {
        get("/preview?ch=${ch.key}&v=$value", timeout = 1500)
    }

    suspend fun live(ch: LedChannel, value: Int) {
        get("/live?ch=${ch.key}&v=$value")
    }

    suspend fun cancelManual(): Map<LedChannel, Int> {
        val o = JSONObject(get("/cancelmanual"))
        return parseLevels(o)
    }

    suspend fun peers(): List<Peer> {
        val arr = JSONObject(get("/peers")).optJSONArray("peers") ?: return emptyList()
        return (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            Peer(p.optString("host"), p.optInt("num"))
        }
    }

    suspend fun discoverPeers(): List<FoundPeer> {
        val arr = JSONObject(get("/discover", timeout = 15000)).optJSONArray("found")
            ?: return emptyList()
        return (0 until arr.length()).map {
            val f = arr.getJSONObject(it)
            FoundPeer(f.optString("host"), f.looseBool("paired"))
        }
    }

    suspend fun groupNumber(): Int = get("/getnumber").trim().toIntOrNull() ?: 0

    suspend fun createGroup(hosts: List<String>) {
        request("/creategroup", "POST", hosts.joinToString(","), timeout = 15000)
    }

    suspend fun removePeer(host: String) {
        get("/removepeer?host=${enc(host)}")
    }

    suspend fun forgetGroup() {
        get("/forgetgroup", timeout = 10000)
    }

    /** O firmware baixa a versão mais nova do GitHub e reinicia se houver atualização. */
    suspend fun checkUpdate(): String = get("/checkupdate", timeout = 120_000).stripHtml()

    suspend fun checkUpdateGroup(): String = get("/checkupdate_group", timeout = 240_000).stripHtml()

    private suspend fun get(path: String, timeout: Int = timeoutMs) = request(path, "GET", null, timeout)

    private suspend fun request(
        path: String,
        method: String,
        body: String?,
        timeout: Int = timeoutMs,
    ): String = Http.request(host, path, method, body, timeout)

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun JSONObject.looseBool(key: String): Boolean = when (val v = opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "true" || v == "1"
            else -> false
        }

        private fun parseLevels(o: JSONObject) = mapOf(
            LedChannel.ROYAL to o.optInt("b"),
            LedChannel.BLUE to o.optInt("az"),
            LedChannel.WHITE to o.optInt("w"),
            LedChannel.UV to o.optInt("u"),
        )

        internal fun parseStatus(o: JSONObject) = LightStatus(
            levels = parseLevels(o),
            time = o.optString("t"),
            manual = o.looseBool("manual"),
            manualMinutesLeft = o.optInt("ml"),
            cooler = o.looseBool("cooler"),
        )

        private fun String.stripHtml() = replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&")
            .trim()
    }
}
