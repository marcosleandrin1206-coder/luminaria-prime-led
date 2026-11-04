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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.primereef.app.AppViewModel
import br.com.primereef.app.data.Device
import br.com.primereef.app.data.FoundPeer
import br.com.primereef.app.data.LedChannel
import br.com.primereef.app.data.Peer
import br.com.primereef.app.data.Protocol
import br.com.primereef.app.net.Http
import br.com.primereef.app.data.Schedule
import kotlinx.coroutines.launch

private val tabs = listOf("Controle", "Programação", "Grupo", "Sistema")

@Composable
fun DeviceScreen(vm: AppViewModel, deviceId: String, onBack: () -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == deviceId }
    if (device == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // Equipamento com página própria (luminária, dosadora...): abre a página do firmware, idêntica à do
    // navegador e atualizada junto com o firmware. Os controles do app ficam disponíveis no menu.
    var nativeView by rememberSaveable { mutableStateOf(false) }
    var hasPage by remember(device.host) { mutableStateOf(device.protocol == Protocol.LUMINARIA) }
    LaunchedEffect(device.host) {
        if (device.protocol == Protocol.PRP1) {
            hasPage = runCatching {
                Http.request(device.host, "/", timeoutMs = 3000).contains("<html", ignoreCase = true)
            }.getOrDefault(false)
        }
    }
    val altView = if (hasPage) {
        (if (nativeView) "Ver página do equipamento" else "Ver controles do app") to { nativeView = !nativeView }
    } else null
    DeviceScaffold(vm, device, onBack, altView) {
        if (hasPage && !nativeView) {
            // Pausa o polling do app enquanto a página (que já faz o seu) está aberta.
            DisposableEffect(device.id) {
                vm.pausePolling(device.id, true)
                onDispose { vm.pausePolling(device.id, false) }
            }
            DeviceWebPanel("http://${device.host}/")
        } else {
            when (device.protocol) {
                Protocol.LUMINARIA -> LuminariaContent(vm, device)
                Protocol.PRP1 -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { GenericDeviceContent(vm, device) }
            }
        }
    }
}

/** Barra superior e menu comuns a todos os aparelhos (renomear, IP, painel web, remover). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceScaffold(
    vm: AppViewModel,
    device: Device,
    onBack: () -> Unit,
    altView: Pair<String, () -> Unit>?,
    content: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceIcon(device.deviceType, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text(device.deviceType.label, fontSize = 12.sp, color = ReefColors.Muted)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Mais opções")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        altView?.let { (label, toggle) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; toggle() })
                        }
                        DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Alterar IP") }, onClick = { menu = false; editingHost = true })
                        DropdownMenuItem(text = { Text("Abrir painel web") }, onClick = {
                            menu = false
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${device.host}/")))
                        })
                        DropdownMenuItem(text = { Text("Remover do app") }, onClick = { menu = false; confirmRemove = true })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ReefColors.Background),
            )
        },
        containerColor = ReefColors.Background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) { content() }
    }

    if (renaming) {
        TextInputDialog("Renomear", device.name, onDismiss = { renaming = false }) {
            vm.rename(device, it); renaming = false
        }
    }
    if (editingHost) {
        TextInputDialog("Endereço IP", device.host, onDismiss = { editingHost = false }) {
            vm.updateHost(device, it); editingHost = false
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remover ${device.name}?") },
            text = { Text("O aparelho só é removido deste app. Nada é alterado nele.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; vm.remove(device) }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LuminariaContent(vm: AppViewModel, device: Device) {
    var tab by rememberSaveable { mutableStateOf(0) }
    TabRow(selectedTabIndex = tab, containerColor = ReefColors.Background) {
        tabs.forEachIndexed { i, t ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (tab) {
            0 -> ControlTab(vm, device)
            1 -> ScheduleTab(vm, device)
            2 -> GroupTab(vm, device)
            3 -> SystemTab(vm, device)
        }
    }
}

// ---------------- Controle ----------------

@Composable
private fun ControlTab(vm: AppViewModel, device: Device) {
    val live by vm.live.collectAsStateWithLifecycle()
    val state = live[device.id]
    val status = state?.status
    val context = LocalContext.current

    ReefCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state?.online == true)
            Spacer(Modifier.width(8.dp))
            Text(if (state?.online == true) "Online" else "Offline", modifier = Modifier.weight(1f))
            Text(status?.time ?: "--:--", fontSize = 32.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status != null) {
                Badge(
                    if (status.cooler) "Cooler ligado" else "Cooler desligado",
                    if (status.cooler) ReefColors.Ok else ReefColors.Muted,
                )
            }
        }
    }

    ReefCard(title = "Controle Manual") {
        if (status?.manual == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge("Manual ativo · expira em ${status.manualMinutesLeft} min", ReefColors.Warn)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    vm.cancelManual(listOf(device)) { ok ->
                        if (ok == 0) Toast.makeText(context, "Falha ao cancelar", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Cancelar") }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            MutedText("Ajustar um canal ativa o modo manual temporário. Depois a luminária volta ao programa.")
            Spacer(Modifier.height(8.dp))
        }
        ChannelSliders(
            levels = status?.levels,
            enabled = state?.online == true,
            onPreview = { ch, v -> vm.preview(listOf(device), ch, v) },
            onCommit = { ch, v -> vm.commit(listOf(device), ch, v) },
        )
    }
}

// ---------------- Programação ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTab(vm: AppViewModel, device: Device) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val allDevices by vm.devices.collectAsStateWithLifecycle()
    val devices = allDevices.filter { it.protocol == Protocol.LUMINARIA }
    var schedule by remember { mutableStateOf<Schedule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var applyAll by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Int?>(null) } // 0 = ligar, 1 = desligar

    LaunchedEffect(device.host) {
        error = null
        runCatching { vm.api(device).schedule() }
            .onSuccess { schedule = it }
            .onFailure { error = "Não foi possível ler a programação (${it.message})." }
    }

    val s = schedule
    if (s == null) {
        ReefCard {
            if (error != null) MutedText(error!!) else Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text("Carregando programação...")
            }
        }
        return
    }

    ReefCard(title = "Curva do Dia") {
        ScheduleChart(s)
        Spacer(Modifier.height(8.dp))
        val curve = calcCurve(100, s)
        MutedText(
            if (curve.hasPlateau) "Pico (100% do programado) entre ${fmtHM(curve.plateauStart)} e ${fmtHM(curve.plateauEnd)}"
            else "Janela curta: a rampa de subida emenda direto com a de descida",
        )
    }

    ReefCard(title = "Horário") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picking = 0 }, modifier = Modifier.weight(1f)) {
                Text("Ligar  %02d:%02d".format(s.onHour, s.onMinute))
            }
            OutlinedButton(onClick = { picking = 1 }, modifier = Modifier.weight(1f)) {
                Text("Desligar  %02d:%02d".format(s.offHour, s.offMinute))
            }
        }
        Spacer(Modifier.height(6.dp))
        MutedText("Rampa automática de 60 min no ligar e no desligar.")
    }

    ReefCard(title = "Intensidade máxima") {
        LedChannel.entries.forEach { ch ->
            val v = s.levels[ch] ?: 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ch.label, fontSize = 14.sp)
                Text("$v%", fontSize = 14.sp, color = Color(ch.color), fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = v.toFloat(),
                onValueChange = { schedule = s.copy(levels = s.levels + (ch to it.toInt())) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(ch.color),
                    activeTrackColor = Color(ch.color),
                    inactiveTrackColor = ReefColors.Border,
                ),
            )
        }
    }

    if (devices.size > 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
            Text("Aplicar em todas as ${devices.size} luminárias")
        }
    }
    Button(
        onClick = {
            saving = true
            scope.launch {
                val targets = if (applyAll) devices else listOf(device)
                val ok = vm.saveSchedule(targets, s)
                saving = false
                val msg = if (ok == targets.size) "Programação salva" else "Salvo em $ok de ${targets.size}"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        },
        enabled = !saving,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (saving) "Salvando..." else "SALVAR PROGRAMAÇÃO") }

    picking?.let { which -> key(which) {
        val state = rememberTimePickerState(
            initialHour = if (which == 0) s.onHour else s.offHour,
            initialMinute = if (which == 0) s.onMinute else s.offMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (which == 0) "Horário de ligar" else "Horário de desligar") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    schedule = if (which == 0) s.copy(onHour = state.hour, onMinute = state.minute)
                    else s.copy(offHour = state.hour, offMinute = state.minute)
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancelar") } },
        )
    } }
}

// ---------------- Grupo ----------------

@Composable
private fun GroupTab(vm: AppViewModel, device: Device) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var number by remember { mutableStateOf<Int?>(null) }
    var peers by remember { mutableStateOf<List<Peer>?>(null) }
    var found by remember { mutableStateOf<List<FoundPeer>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val api = vm.api(device)

    suspend fun reload() {
        number = runCatching { api.groupNumber() }.getOrNull()
        peers = runCatching { api.peers() }.getOrNull()
    }
    LaunchedEffect(device.host) { reload() }

    ReefCard(title = "Grupo de Luminárias") {
        MutedText("Luminárias do mesmo grupo sincronizam ajustes manuais e programação entre si, direto pelo Wi-Fi.")
        Spacer(Modifier.height(10.dp))
        Row {
            Text("Esta luminária", Modifier.weight(1f))
            Text(
                when (val n = number) {
                    null -> "..."
                    0 -> "Ainda não agrupada"
                    else -> "Luminária $n"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        val list = peers
        when {
            list == null -> MutedText("Carregando...")
            list.isEmpty() -> MutedText("Nenhuma luminária pareada ainda")
            else -> list.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (p.number > 0) "Luminária ${p.number}" else p.host, Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch { runCatching { api.removePeer(p.host) }; reload() }
                    }) { Text("Remover", color = ReefColors.Error) }
                }
            }
        }
    }

    ReefCard(title = "Adicionar ao grupo") {
        OutlinedButton(
            onClick = {
                searching = true
                found = null
                scope.launch {
                    found = runCatching { api.discoverPeers() }.getOrElse {
                        Toast.makeText(context, "Falha na busca: ${it.message}", Toast.LENGTH_SHORT).show()
                        emptyList()
                    }
                    searching = false
                }
            },
            enabled = !searching,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (searching) "Buscando na rede..." else "Buscar luminárias") }

        val novas = found?.filter { !it.paired }
        if (novas != null) {
            Spacer(Modifier.height(8.dp))
            if (novas.isEmpty()) {
                MutedText("Nenhuma luminária nova encontrada")
            } else {
                novas.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = f.host in selected,
                            onCheckedChange = { if (it) selected.add(f.host) else selected.remove(f.host) },
                        )
                        Text(f.host)
                    }
                }
                Button(
                    onClick = {
                        val hosts = selected.toList()
                        scope.launch {
                            runCatching { api.createGroup(hosts) }
                                .onFailure { Toast.makeText(context, "Falha: ${it.message}", Toast.LENGTH_SHORT).show() }
                            selected.clear()
                            found = null
                            reload()
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("ADICIONAR AO GRUPO") }
            }
        }
    }

    OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Sair do grupo", color = ReefColors.Error)
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Sair do grupo?") },
            text = { Text("Isso desconecta esta luminária de todo o grupo e zera o número dela.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch { runCatching { api.forgetGroup() }; reload() }
                }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
        )
    }
}

// ---------------- Sistema ----------------

@Composable
private fun SystemTab(vm: AppViewModel, device: Device) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val api = vm.api(device)

    fun run(label: String, block: suspend () -> String) {
        busy = true
        result = label
        scope.launch {
            result = runCatching { block() }.getOrElse {
                "Conexão perdida — se a luminária reiniciou, a atualização foi aplicada."
            }
            busy = false
        }
    }

    ReefCard(title = "Atualização Remota") {
        MutedText("A luminária baixa o firmware mais recente do GitHub e reinicia sozinha se houver versão nova.")
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { run("Verificando...") { api.checkUpdate() } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Verificar atualização agora") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { run("Verificando esta e todo o grupo... pode levar um tempo.") { api.checkUpdateGroup() } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Verificar no grupo inteiro") }
        result?.let {
            Spacer(Modifier.height(10.dp))
            LinkText(it, fontSize = 13.sp)
        }
    }

    ReefCard(title = "Wi-Fi e firmware manual") {
        MutedText("Trocar a rede Wi-Fi ou enviar um .bin manualmente é feito pelo painel web da luminária.")
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${device.host}/"))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Abrir painel web") }
    }

    ReefCard(title = "Informações") {
        InfoRow("Nome", device.name)
        InfoRow("Endereço", device.host)
        InfoRow("ID", device.id)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = ReefColors.Muted, modifier = Modifier.weight(1f))
        Text(value)
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
