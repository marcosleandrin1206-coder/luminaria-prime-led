package br.com.primereef.app.telas

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.primereef.app.MainActivity
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Gera imagens das telas do app com equipamentos simulados (servidores HTTP locais que imitam a
 * luminária, a dosadora e o AquaPrime KH). Rodar com: ./gradlew testDebugUnitTest -Ptelas
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1100dp-xhdpi")
class TelasTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val servers = mutableListOf<ServerSocket>()

    @Before
    fun setup() {
        assumeTrue("Rode com -Ptelas para gerar as telas", System.getProperty("telas") == "true")
        val lum1 = serve(::luminaria)
        val lum2 = serve(::luminaria)
        val dos = serve(::dosadora)
        val kh = serve(::kh)
        val devices = JSONArray()
            .put(dev("reef-a1b2c3", "Luminária Display", "127.0.0.1:$lum1", "luminaria", "LUMINARIA"))
            .put(dev("reef-d4e5f6", "Luminária Refúgio", "127.0.0.1:$lum2", "luminaria", "LUMINARIA"))
            .put(dev("kh-0a1b2c", "Medidor KH Sump", "127.0.0.1:$kh", "kh", "PRP1"))
            .put(dev("dosadora-3c4d5e", "Dosadora Cálcio", "127.0.0.1:$dos", "dosadora", "PRP1"))
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("prime_reef", Context.MODE_PRIVATE)
            .edit().putString("devices", devices.toString()).commit()
    }

    @After
    fun tearDown() = servers.forEach { runCatching { it.close() } }

    @Test
    fun capturarTelas() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun back() = scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            esperarTexto("Programa", substring = true)
            esperarTexto("KH:", substring = true)
            esperarTexto("Galão abaixo", substring = true)
            foto("01_tela_inicial")

            // Luminária: controles do app (o painel web do firmware não roda fora do celular)
            compose.onNodeWithText("Luminária Display").performClick()
            compose.onNodeWithContentDescription("Mais opções").performClick()
            compose.onNodeWithText("Ver controles do app").performClick()
            esperarTexto("Controle Manual")
            foto("02_luminaria_controle")
            compose.onNodeWithText("Programação").performClick()
            esperarTexto("Curva do Dia")
            foto("03_luminaria_programacao")
            compose.onNodeWithText("Grupo").performClick()
            esperarTexto("Luminária 2")
            foto("04_luminaria_grupo")
            back()

            compose.onNodeWithText("Medidor KH Sump").performClick()
            esperarTexto("Leituras")
            foto("05_aquaprime_kh")
            back()

            compose.onNodeWithText("Dosadora Cálcio").performClick()
            esperarTexto("Dosado hoje")
            foto("06_dosadora")
            back()

            compose.onNodeWithText("Dosadora Prime Reef").performClick()
            esperarTexto("Adicionar pelo IP")
            foto("07_adicionar_dosadora")
            back()

            compose.onNodeWithText("Luminária Prime LED").performClick()
            esperarTexto("Adicionar pelo IP")
            foto("08_adicionar_produto")
        }
    }

    // ---------------- auxiliares ----------------

    private fun foto(nome: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/telas/$nome.png")
    }

    private fun esperarTexto(texto: String, substring: Boolean = false) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(texto, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dev(id: String, name: String, host: String, type: String, protocol: String) =
        JSONObject().put("id", id).put("name", name).put("host", host).put("type", type).put("protocol", protocol)

    /** Servidor HTTP mínimo: responde cada requisição com o JSON devolvido por [handler]. */
    private fun serve(handler: (path: String, q: Map<String, String>) -> String): Int {
        val ss = ServerSocket(0)
        servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        val first = reader.readLine() ?: return@thread
                        while (!reader.readLine().isNullOrEmpty()) { /* ignora cabeçalhos */ }
                        val target = first.split(" ").getOrElse(1) { "/" }
                        val path = target.substringBefore('?')
                        val q = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                        }
                        val body = handler(path, q).toByteArray()
                        val out = sock.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }
        return ss.localPort
    }

    // ---------------- equipamentos simulados ----------------

    private fun luminaria(path: String, q: Map<String, String>): String = when (path) {
        "/status" -> """{"b":85,"az":70,"w":45,"u":30,"t":"14:32","manual":false,"ml":0,"cooler":true}"""
        "/schedule" -> """{"h1":10,"m1":0,"h2":20,"m2":0,"royal":90,"blue":80,"w":50,"u":40}"""
        "/getnumber" -> "1"
        "/peers" -> """{"peers":[{"host":"192.168.0.31","num":2}]}"""
        else -> "{}"
    }

    private fun dosadora(path: String, q: Map<String, String>): String = when (path) {
        "/api/info" -> """{"api":1,"id":"dosadora-3c4d5e","type":"dosadora","model":"Dosadora Prime Reef 1 canal","name":"Dosadora","fw":"1"}"""
        else -> """
            {"sensors":[
              {"id":"hoje","label":"Dosado hoje","value":12.5,"unit":"ml"},
              {"id":"galao","label":"Galão","value":18,"unit":"%","status":"warn"},
              {"id":"proxima","label":"Próxima dose","value":"16:00 · 2.5 ml"},
              {"id":"rssi","label":"Sinal Wi-Fi","value":-58,"unit":"dBm"}],
             "controls":[
              {"id":"auto","label":"Dosagem automática","type":"switch","value":true},
              {"id":"ml","label":"Volume por dia","type":"slider","value":30,"min":0,"max":500,"step":1,"unit":"ml"},
              {"id":"doses","label":"Doses por dia","type":"slider","value":12,"min":1,"max":24,"step":1},
              {"id":"inicio","label":"Horário da 1ª dose","type":"slider","value":8,"min":0,"max":23,"step":1,"unit":"h"},
              {"id":"d1","label":"Dosar 1 ml agora","type":"button","confirm":"Dosar 1 ml agora?"},
              {"id":"vazao","label":"Vazão calibrada","type":"number","value":1.18,"min":0.01,"max":20,"step":0.01,"unit":"ml/s"},
              {"id":"encher","label":"Galão reabastecido","type":"button","confirm":"Confirmar?"}],
             "alerts":[{"level":"warn","msg":"Galão abaixo de 20%"}]}
        """.trimIndent()
    }

    private fun kh(path: String, q: Map<String, String>): String = when (path) {
        "/api/info" -> """{"api":1,"id":"kh-0a1b2c","type":"kh","model":"AquaPrime KH","name":"AquaPrime KH","fw":"1"}"""
        else -> """
            {"sensors":[
              {"id":"kh","label":"KH","value":8.2,"unit":"dKH","status":"ok"},
              {"id":"ph","label":"pH da amostra","value":8.12},
              {"id":"ultima","label":"Última medição","value":"13:00"},
              {"id":"proxima","label":"Próxima medição","value":"17:00"}],
             "controls":[
              {"id":"auto","label":"Medição automática","type":"switch","value":true},
              {"id":"intervalo","label":"Intervalo entre medições","type":"slider","value":4,"min":1,"max":24,"step":1,"unit":"h"},
              {"id":"min","label":"KH mínimo ideal","type":"number","value":7.5,"min":5,"max":12,"step":0.1,"unit":"dKH"},
              {"id":"medir","label":"Medir agora","type":"button","confirm":"Iniciar uma medição agora?"}],
             "alerts":[]}
        """.trimIndent()
    }
}
