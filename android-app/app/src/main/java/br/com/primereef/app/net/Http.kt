package br.com.primereef.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** O aparelho respondeu, mas com erro HTTP (ex.: 404 = endpoint não existe nesse firmware). */
class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code")

/** Cliente HTTP mínimo usado para falar com os aparelhos na rede local. */
object Http {
    fun baseUrl(host: String) = if (host.startsWith("http")) host.trimEnd('/') else "http://$host"

    suspend fun request(
        host: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        timeoutMs: Int = 4000,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "text/plain",
    ): String = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl(host) + path).openConnection() as HttpURLConnection
        conn.connectTimeout = minOf(timeoutMs, 5000)
        conn.readTimeout = timeoutMs
        conn.requestMethod = method
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw HttpStatusException(code, text)
            text
        } finally {
            conn.disconnect()
        }
    }
}
