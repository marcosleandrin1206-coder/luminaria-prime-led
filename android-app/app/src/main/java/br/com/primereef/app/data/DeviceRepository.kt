package br.com.primereef.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Guarda a lista de aparelhos do usuário em SharedPreferences. */
class DeviceRepository(context: Context) {
    private val prefs = context.getSharedPreferences("prime_reef", Context.MODE_PRIVATE)

    fun load(): List<Device> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Device(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    type = o.optString("type", DeviceType.LUMINARIA.key),
                    protocol = runCatching { Protocol.valueOf(o.optString("protocol")) }
                        .getOrDefault(Protocol.LUMINARIA),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(devices: List<Device>) {
        val arr = JSONArray()
        devices.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("host", it.host)
                    .put("type", it.type)
                    .put("protocol", it.protocol.name),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "devices"
    }
}
