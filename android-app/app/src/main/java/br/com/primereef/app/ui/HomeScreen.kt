package br.com.primereef.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import br.com.primereef.app.AppViewModel
import br.com.primereef.app.data.Device
import br.com.primereef.app.data.DeviceLive
import br.com.primereef.app.data.ControlType
import br.com.primereef.app.data.DeviceType
import br.com.primereef.app.data.LedChannel
import br.com.primereef.app.data.Level
import br.com.primereef.app.data.Protocol
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onOpen: (Device) -> Unit, onAdd: () -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prime Reef Brasil", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { scope.launch { vm.refreshAll() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ReefColors.Background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Adicionar") },
            )
        },
        containerColor = ReefColors.Background,
    ) { padding ->
        if (devices.isEmpty()) {
            EmptyState(Modifier.padding(padding), onAdd)
            return@Scaffold
        }
        val sections = DeviceType.entries
            .map { type -> type to devices.filter { it.deviceType == type } }
            .filter { it.second.isNotEmpty() }
        val luminarias = devices.filter { it.protocol == Protocol.LUMINARIA }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OverviewCard(devices, live, onOpen) }
            sections.forEach { (type, list) ->
                item(key = "header-${type.key}") {
                    Text(
                        type.plural,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ReefColors.Muted,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                if (type == DeviceType.LUMINARIA && luminarias.size > 1) {
                    item(key = "group-luminarias") {
                        GroupControlCard(
                            devices = luminarias,
                            live = live,
                            vm = vm,
                            onToast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                        )
                    }
                }
                items(list, key = { it.id }) { d ->
                    if (d.protocol == Protocol.LUMINARIA) DeviceCard(d, live[d.id]) { onOpen(d) }
                    else GenericDeviceCard(d, live[d.id]) { onOpen(d) }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nenhum aparelho adicionado", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        MutedText(
            "Conecte o celular no mesmo Wi-Fi dos seus aparelhos Prime Reef (luminárias, dosadoras, " +
                "bombas, ATO, controladores...) e toque em Adicionar para buscá-los automaticamente.",
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAdd) { Text("Buscar aparelhos") }
    }
}

/** Resumo do aquário: aparelhos online e alertas ativos de todo o ecossistema. */
@Composable
private fun OverviewCard(devices: List<Device>, live: Map<String, DeviceLive>, onOpen: (Device) -> Unit) {
    val online = devices.count { live[it.id]?.online == true }
    val offline = devices.filter { live[it.id]?.online == false }
    val alerts = devices.flatMap { d ->
        live[d.id]?.state?.alerts.orEmpty()
            .filter { it.level == Level.WARN || it.level == Level.ALARM }
            .map { d to it }
    }
    ReefCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Meu aquário", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                MutedText("$online de ${devices.size} aparelhos online")
            }
            when {
                alerts.any { it.second.level == Level.ALARM } -> Badge("Alarme", ReefColors.Error)
                alerts.isNotEmpty() || offline.isNotEmpty() -> Badge("Atenção", ReefColors.Warn)
                devices.isNotEmpty() && online == devices.size -> Badge("Tudo certo", ReefColors.Ok)
            }
        }
        if (alerts.isNotEmpty() || offline.isNotEmpty()) Spacer(Modifier.height(8.dp))
        alerts.forEach { (d, a) ->
            Text(
                "• ${d.name}: ${a.message}",
                fontSize = 13.sp,
                color = levelColor(a.level),
                modifier = Modifier.clickable { onOpen(d) }.padding(vertical = 2.dp),
            )
        }
        offline.forEach { d ->
            Text(
                "• ${d.name}: sem resposta",
                fontSize = 13.sp,
                color = ReefColors.Muted,
                modifier = Modifier.clickable { onOpen(d) }.padding(vertical = 2.dp),
            )
        }
    }
}

/** Cartão de um aparelho do Protocolo Prime Reef v1: mostra sensores e controles principais. */
@Composable
private fun GenericDeviceCard(device: Device, live: DeviceLive?, onClick: () -> Unit) {
    val state = live?.state
    val online = live?.online == true
    ReefCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(online)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                MutedText("${device.deviceType.label} · ${device.host}")
            }
            val worst = state?.alerts?.maxOfOrNull { it.level }
            if (worst == Level.ALARM || worst == Level.WARN) {
                Badge(if (worst == Level.ALARM) "Alarme" else "Atenção", levelColor(worst))
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            live == null -> MutedText("Conectando...")
            state == null -> MutedText("Sem resposta. Verifique se o aparelho está ligado e na mesma rede.")
            else -> {
                if (!online) {
                    Badge("Offline", ReefColors.Error)
                    Spacer(Modifier.height(6.dp))
                }
                SensorGrid(state.sensors.take(4))
                val switches = state.controls.filter { it.type == ControlType.SWITCH }.take(3)
                if (switches.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        switches.forEach { c ->
                            Badge(
                                "${c.label}: ${if (c.boolValue) "ligado" else "desligado"}",
                                if (c.boolValue) ReefColors.Ok else ReefColors.Muted,
                            )
                        }
                    }
                }
                if (state.sensors.isEmpty() && switches.isEmpty()) {
                    MutedText("${state.controls.size} controles disponíveis")
                }
            }
        }
    }
}

/** Controle simultâneo de todas as luminárias online. */
@Composable
private fun GroupControlCard(
    devices: List<Device>,
    live: Map<String, DeviceLive>,
    vm: AppViewModel,
    onToast: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val online = devices.filter { live[it.id]?.online == true }
    // Mostra a média dos níveis atuais como posição inicial dos sliders.
    val avg: Map<LedChannel, Int>? = online.mapNotNull { live[it.id]?.status?.levels }
        .takeIf { it.isNotEmpty() }
        ?.let { all -> LedChannel.entries.associateWith { ch -> all.sumOf { it[ch] ?: 0 } / all.size } }

    ReefCard(title = "Todas as luminárias") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MutedText("${online.size} de ${devices.size} online", Modifier.weight(1f))
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Ocultar" else "Controlar todas")
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            ChannelSliders(
                levels = avg,
                enabled = online.isNotEmpty(),
                onPreview = { ch, v -> vm.preview(online, ch, v) },
                onCommit = { ch, v -> vm.commit(online, ch, v) },
            )
            OutlinedButton(
                onClick = {
                    vm.cancelManual(online) { ok -> onToast("Programa retomado em $ok luminária(s)") }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = online.isNotEmpty(),
            ) { Text("Voltar todas ao programa") }
        }
    }
}

@Composable
private fun DeviceCard(device: Device, live: DeviceLive?, onClick: () -> Unit) {
    val status = live?.status
    val online = live?.online == true
    ReefCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(online)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                MutedText(device.host)
            }
            if (status != null) {
                Text(status.time, fontSize = 20.sp, fontWeight = FontWeight.Light)
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            live == null -> MutedText("Conectando...")
            !online && status == null -> MutedText("Sem resposta. Verifique se a luminária está ligada e na mesma rede.")
            else -> {
                LevelBars(status?.levels)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!online) Badge("Offline", ReefColors.Error)
                    if (status?.manual == true) Badge("Manual · ${status.manualMinutesLeft} min", ReefColors.Warn)
                    else if (status != null) Badge("Programa", ReefColors.Accent)
                    if (status != null) {
                        Badge(
                            if (status.cooler) "Cooler ligado" else "Cooler desligado",
                            if (status.cooler) ReefColors.Ok else ReefColors.Muted,
                        )
                    }
                }
            }
        }
    }
}
