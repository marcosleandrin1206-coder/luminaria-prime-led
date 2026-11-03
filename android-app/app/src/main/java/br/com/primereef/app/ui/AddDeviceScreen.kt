package br.com.primereef.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.primereef.app.AppViewModel
import br.com.primereef.app.data.DeviceType

import br.com.primereef.app.net.Discovery

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(vm: AppViewModel, productType: DeviceType?, onBack: () -> Unit) {
    val product = PRODUCTS.firstOrNull { it.type == productType }
    val state by vm.discoveryState.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ip by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.startDiscovery() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(product?.name ?: "Adicionar equipamento", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ReefColors.Background),
            )
        },
        containerColor = ReefColors.Background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (product != null) {
                ReefCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceIcon(product.type, size = 64.dp, dimmed = !product.available)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(product.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            MutedText(product.description)
                        }
                    }
                    productNote(product.type)?.let {
                        Spacer(Modifier.height(10.dp))
                        MutedText(it)
                    }
                }
            }

            ReefCard(title = if (product != null) "Encontrados na rede" else "Equipamentos Prime Reef na rede") {
                if (state.running) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    MutedText("Buscando via mDNS e varrendo a rede...")
                    Spacer(Modifier.height(6.dp))
                }
                state.message?.let { MutedText(it) }
                val shown = state.found.filter { productType == null || it.type == productType.key }
                if (!state.running && state.message == null && shown.isEmpty()) {
                    MutedText("Nenhum ${product?.name ?: "equipamento"} encontrado nesta rede.")
                }
                shown.forEach { f ->
                    val already = devices.any { it.host == f.host || (f.id != null && it.id == f.id) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name)
                            MutedText("${DeviceType.of(f.type).label} · ${f.host}")
                        }
                        if (already) {
                            MutedText("Adicionada")
                        } else {
                            TextButton(onClick = { vm.addDevice(f) }) { Text("Adicionar") }
                        }
                    }
                }
                if (!state.running) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.startDiscovery() }, modifier = Modifier.weight(1f)) {
                            Text("Buscar de novo")
                        }
                        val pending = shown.filter { f ->
                            devices.none { it.host == f.host || (f.id != null && it.id == f.id) }
                        }
                        if (pending.size > 1) {
                            Button(onClick = { pending.forEach { vm.addDevice(it) } }, modifier = Modifier.weight(1f)) {
                                Text("Adicionar todas")
                            }
                        }
                    }
                }
            }

            ReefCard(title = "Adicionar pelo IP") {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP (ex.: 192.168.0.50)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.addManual(ip, name) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        ip = ""
                        name = ""
                    },
                    enabled = ip.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Adicionar") }
            }

            ReefCard(title = "Aparelho novo (primeira configuração)") {
                MutedText(
                    "1. Ligue o aparelho. Sem Wi-Fi configurado, ele cria uma rede Wi-Fi própria da Prime Reef.\n" +
                        "2. Conecte o celular nessa rede.\n" +
                        "3. Abra o painel, escolha seu Wi-Fi e digite a senha.\n" +
                        "4. Volte o celular para o seu Wi-Fi e toque em \"Buscar de novo\".",
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${Discovery.AP_IP}/")))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Abrir painel de configuração (${Discovery.AP_IP})") }
            }
        }
    }
}

/** Orientação específica de cada produto na tela de adicionar. */
private fun productNote(type: DeviceType): String? = when (type) {
    DeviceType.LUMINARIA -> "Deixe a luminária ligada e o celular no mesmo Wi-Fi. Ela é encontrada automaticamente."
    DeviceType.KH -> "O AquaPrime KH aparece aqui assim que o firmware dele usar o padrão Prime Reef (biblioteca PrimeReefDevice)."
    else -> "Produto em desenvolvimento. Assim que o firmware dele usar o padrão Prime Reef, ele aparece aqui automaticamente."
}
