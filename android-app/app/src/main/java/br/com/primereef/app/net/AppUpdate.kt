package br.com.primereef.app.net

import br.com.primereef.app.BuildConfig
import org.json.JSONObject

/** Verifica se existe um APK mais novo publicado no GitHub (release "app-latest"). */
object AppUpdate {
    const val DOWNLOAD_URL =
        "https://github.com/marcosleandrin1206-coder/luminaria-prime-led/releases/download/app-latest/PrimeReef.apk"
    private const val RELEASE_API =
        "https://api.github.com/repos/marcosleandrin1206-coder/luminaria-prime-led/releases/tags/app-latest"

    /** true se o APK publicado foi gerado de um commit diferente deste app. */
    suspend fun isUpdateAvailable(): Boolean {
        val local = BuildConfig.GIT_SHA
        if (local == "dev") return false // build local (Android Studio): não avisa
        val remote = JSONObject(Http.request(RELEASE_API, "", timeoutMs = 8000)).optString("target_commitish")
        return remote.length >= 7 && remote != local
    }
}
