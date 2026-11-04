package br.com.primereef.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.primereef.app.AppViewModel
import br.com.primereef.app.data.DeviceType
import br.com.primereef.app.net.TuyaCredentials
import br.com.primereef.app.net.TuyaRegion

/** Conecta a conta do app Smart Life (nuvem Tuya) e importa os equipamentos dela. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuyaScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.tuyaState.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saved = remember { vm.tuyaCredentials() }
    var accessId by remember { mutableStateOf(saved?.accessId.orEmpty()) }
    var secret by remember { mutableStateOf(saved?.accessSecret.orEmpty()) }
    var region by remember { mutableStateOf(saved?.region ?: TuyaRegion.AMERICA) }
    var regionMenu by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(!state.connected) }

    LaunchedEffect(Unit) { if (state.connected && state.devices.isEmpty()) vm.reloadTuyaDevices() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Life / Tuya", fontWeight = FontWeight.SemiBold) },
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
            ReefCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceIcon(DeviceType.ALIMENTADOR, size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Equipamentos do Smart Life", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        MutedText("Alimentador, dosadora, tomadas e outros aparelhos Tuya, controlados pela nuvem Tuya.")
                    }
                }
            }

            if (state.connected) {
                ReefCard(title = "Equipamentos da sua conta") {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.message?.let { MutedText(it) }
                    state.devices.forEach { d ->
                        val added = devices.any { it.id == d.id }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeviceIcon(d.deviceType, size = 40.dp, dimmed = !d.online)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.name)
                                MutedText("${d.productName.ifBlank { d.deviceType.label }}${if (d.online) "" else " · offline"}")
                            }
                            if (added) MutedText("Adicionado")
                            else TextButton(onClick = { vm.addTuyaDevice(d) }) { Text("Adicionar") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.reloadTuyaDevices() }, modifier = Modifier.weight(1f)) { Text("Atualizar lista") }
                        OutlinedButton(onClick = { vm.disconnectTuya(); showGuide = true }, modifier = Modifier.weight(1f)) {
                            Text("Desconectar", color = ReefColors.Error)
                        }
                    }
                }
            }

            if (!state.connected) {
                ReefCard(title = "Chaves do projeto Tuya") {
                    LinkText(
                        "Atenção: aqui NÃO vai o e-mail e a senha do Smart Life. A Tuya não permite login " +
                            "de outros apps com eles. Use as duas chaves do projeto gratuito criado em " +
                            "platform.tuya.com (passo a passo logo abaixo).",
                        color = ReefColors.Warn,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    val looksLikeEmail = accessId.contains('@')
                    OutlinedTextField(
                        value = accessId, onValueChange = { accessId = it.trim() },
                        label = { Text("Access ID (do projeto Tuya)") },
                        placeholder = { Text("ex.: k7x9d2mqp4r8tw3n5vyc") },
                        singleLine = true,
                        isError = looksLikeEmail,
                        supportingText = if (looksLikeEmail) {
                            { Text("Isto é um e-mail. Cole aqui o Access ID do projeto, não o login do Smart Life.") }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secret, onValueChange = { secret = it.trim() },
                        label = { Text("Access Secret (do projeto Tuya)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Data center", Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { regionMenu = true }) { Text(region.label) }
                            DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                                TuyaRegion.entries.forEach { r ->
                                    DropdownMenuItem(text = { Text(r.label) }, onClick = { region = r; regionMenu = false })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    state.message?.let {
                        Text(it, color = ReefColors.Error, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { vm.connectTuya(TuyaCredentials(accessId, secret, region)) },
                        enabled = accessId.isNotBlank() && secret.isNotBlank() && !looksLikeEmail && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Conectar e buscar equipamentos") }
                    MutedText("As chaves ficam salvas só neste celular.", Modifier.padding(top = 8.dp))
                }
            }

            ReefCard(title = "Como obter as chaves (uma vez só)") {
                if (!showGuide) {
                    TextButton(onClick = { showGuide = true }) { Text("Mostrar passo a passo") }
                } else {
                    GuideStep(1, "Crie uma conta gratuita em platform.tuya.com (pode usar o mesmo e-mail do Smart Life).")
                    GuideStep(2, "Vá em Cloud → Development → Create Cloud Project. Em Industry e Development Method escolha \"Smart Home\" e em Data Center escolha \"Western America\" (Brasil).")
                    GuideStep(3, "Na etapa de serviços, mantenha \"IoT Core\" e \"Authorization Token Management\" marcados.")
                    GuideStep(4, "Abra o projeto → aba Devices → Link App Account → Add App Account. Vai aparecer um QR code.")
                    GuideStep(5, "No app Smart Life: aba Eu → ícone de leitura no canto superior → leia o QR code e confirme.")
                    GuideStep(6, "Na aba Overview do projeto, copie o Access ID e o Access Secret e cole aqui em cima.")
                    MutedText(
                        "O plano gratuito da Tuya (IoT Core) precisa ser renovado de tempos em tempos no próprio site, sem custo. " +
                            "Se o app parar de responder com \"plano expirado\", é só renovar.",
                        Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.tuya.com/cloud"))) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Abrir platform.tuya.com") }
                }
            }
        }
    }
}

@Composable
private fun GuideStep(n: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$n.", fontWeight = FontWeight.SemiBold, color = ReefColors.Accent, modifier = Modifier.width(22.dp))
        LinkText(text, fontSize = 14.sp)
    }
}
