package br.com.primereef.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Mostra o painel web que roda no próprio equipamento (ex.: a luminária), dentro do app.
 * Como a página vem do firmware, ela fica idêntica ao painel do aparelho e muda sozinha
 * quando o firmware é atualizado.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeviceWebPanel(url: String, modifier: Modifier = Modifier) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val deviceHost = remember(url) { Uri.parse(url).host }

    // Seletor de arquivo para o envio manual de firmware (.bin) do painel.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }

    Box(modifier.fillMaxSize().background(ReefColors.Background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0xFF0D1117.toInt())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true // o painel guarda a aba ativa no localStorage
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.url.host == deviceHost) return false
                            // Links externos abrem no navegador.
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                            if (request.isForMainFrame) {
                                error = err.description?.toString() ?: "erro de conexão"
                                loading = false
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            view: WebView,
                            callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams,
                        ): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            picker.launch("*/*")
                            return true
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
        )

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }

        error?.let { msg ->
            Column(
                Modifier.fillMaxSize().background(ReefColors.Background).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Equipamento não respondeu", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                MutedText(
                    "Verifique se ele está ligado e se o celular está no mesmo Wi-Fi.\n($msg)",
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    error = null
                    webView?.loadUrl(url)
                }) { Text("Tentar de novo") }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            webView?.destroy()
        }
    }
}
