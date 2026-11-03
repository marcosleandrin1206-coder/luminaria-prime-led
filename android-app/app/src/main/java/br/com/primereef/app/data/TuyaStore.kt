package br.com.primereef.app.data

import android.content.Context
import br.com.primereef.app.net.TuyaCredentials
import br.com.primereef.app.net.TuyaRegion

/** Guarda as credenciais do projeto Tuya (ficam só neste celular). */
class TuyaStore(context: Context) {
    private val prefs = context.getSharedPreferences("tuya", Context.MODE_PRIVATE)

    fun load(): TuyaCredentials? {
        val id = prefs.getString("access_id", null) ?: return null
        val secret = prefs.getString("access_secret", null) ?: return null
        val region = runCatching { TuyaRegion.valueOf(prefs.getString("region", "")!!) }.getOrDefault(TuyaRegion.AMERICA)
        return TuyaCredentials(id, secret, region)
    }

    fun save(c: TuyaCredentials) {
        prefs.edit()
            .putString("access_id", c.accessId)
            .putString("access_secret", c.accessSecret)
            .putString("region", c.region.name)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
