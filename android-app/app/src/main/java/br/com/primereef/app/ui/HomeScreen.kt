package br.com.primereef.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.primereef.app.AppViewModel
import br.com.primereef.app.R
import br.com.primereef.app.data.Device
import br.com.primereef.app.data.DeviceLive
import br.com.primereef.app.data.DeviceType
import br.com.primereef.app.data.LedChannel
import br.com.primereef.app.data.Level
import br.com.primereef.app.data.Protocol
import kotlinx.coroutines.launch

/** Produto do catálogo Prime Reef Brasil exibido em "Adicionar produto". */
data class Product(val type: DeviceType, val name: String, val description: String, val available: Boolean)

val PRODUCTS = listOf(
    Product(DeviceType.LUMINARIA, "Luminária Prime LED", "Iluminação marinha 4 canais", available = true),
    Product(DeviceType.KH, "AquaPrime KH", "Medidor automático de KH", available = true),
    Product(DeviceType.DOSADORA, "Dosadora", "Dosagem de suplementos", available = false),
    Product(DeviceType.BOMBA, "Bomba / Wavemaker", "Circulação e retorno", available = false),
    Product(DeviceType.ATO, "Reposição ATO", "Reposição de água doce", available = false),
    Product(DeviceType.CONTROLADOR, "Controlador", "Tomadas, relés e termostato", available = false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onOpen: (Device) -> Unit, onAdd: (DeviceType?) -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val luminarias = devices.filter { it.protocol == Protocol.LUMINARIA }
    // Ordem fixa por família de produto, depois por nome.
    val sorted = devices.sortedWith(compareBy({ it.deviceType.ordinal }, { it.name }))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo_prime_reef),
                            contentDescription = "Prime Reef",
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Prime Reef Brasil", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { vm.refreshAll() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ReefColors.Background),
            )
        },
        containerColor = ReefColors.Background,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (devices.isNotEmpty()) {
                fullWidth("overview") { OverviewCard(devices, live, onOpen) }
                fullWidth("meus") { SectionTitle("Meus equipamentos") }
                items(sorted, key = { it.id }) { d -> EquipmentTile(d, live[d.id]) { onOpen(d) } }
                if (luminarias.size > 1) {
                    fullWidth("group") {
                        GroupControlCard(
                            devices = luminarias,
                            live = live,
                            vm = vm,
                            onToast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                        )
                    }
                }
            } else {
                fullWidth("welcome") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.logo_prime_reef),
                            contentDescription = null,
                            modifier = Modifier.size(96.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Bem-vindo!", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        MutedText("Escolha abaixo o produto Prime Reef que você quer adicionar.")
                    }
                }
            }

            fullWidth("add-title") { SectionTitle("Adicionar produto Prime Reef") }
            items(PRODUCTS, key = { "p-" + it.type.key }) { p -> ProductTile(p) { onAdd(p.type) } }
            fullWidth("add-other") {
                OutlinedButton(onClick = { onAdd(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Buscar todos os equipamentos na rede")
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = ReefColors.Muted,
        modifier = Modifier.padding(top = 8.dp, start = 2.dp),
    )
}

@Composable
private fun Tile(onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(156.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ReefColors.Card),
        border = BorderStroke(1.dp, ReefColors.Border),
    ) {
        Box(Modifier.fillMaxSize().padding(14.dp)) { content() }
    }
}

/** Quadro de um equipamento: ícone, nome e o status mais importante. */
@Composable
private fun EquipmentTile(device: Device, live: DeviceLive?, onClick: () -> Unit) {
    val online = live?.online == true
    val (line, lineColor) = statusLine(device, live)
    val alarm = live?.state?.alerts?.maxOfOrNull { it.level }
    Tile(onClick) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.Top) {
                DeviceIcon(device.deviceType, size = 52.dp, dimmed = live != null && !online)
                Spacer(Modifier.weight(1f))
                if (alarm != null && alarm >= Level.WARN) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(levelColor(alarm)))
                    Spacer(Modifier.width(6.dp))
                }
                StatusDot(online)
            }
            Spacer(Modifier.weight(1f))
            Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.deviceType.label, fontSize = 11.sp, color = ReefColors.Muted, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(line, fontSize = 12.sp, color = lineColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun statusLine(device: Device, live: DeviceLive?): Pair<String, androidx.compose.ui.graphics.Color> {
    if (live == null) return "Conectando..." to ReefColors.Muted
    if (!live.online && live.status == null && live.state == null) return "Sem resposta" to ReefColors.Error
    val offline = if (!live.online) "Offline · " else ""
    live.status?.let { s ->
        val avg = s.levels.values.average().toInt()
        val mode = if (s.manual) "Manual" else "Programa"
        return "$offline${s.time} · $mode · ${avg}%" to (if (s.manual) ReefColors.Warn else ReefColors.Text)
    }
    live.state?.let { st ->
        st.alerts.firstOrNull { it.level == Level.ALARM || it.level == Level.WARN }?.let {
            return "$offline${it.message}" to levelColor(it.level)
        }
        st.sensors.firstOrNull()?.let {
            return "$offline${it.label}: ${it.value} ${it.unit}".trim() to levelColor(it.status)
        }
        return "$offline${st.controls.size} controles" to ReefColors.Muted
    }
    return "Offline" to ReefColors.Error
}

/** Quadro do catálogo de produtos Prime Reef. */
@Composable
private fun ProductTile(p: Product, onClick: () -> Unit) {
    Tile(onClick) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.Top) {
                DeviceIcon(p.type, size = 52.dp, dimmed = !p.available)
                Spacer(Modifier.weight(1f))
                if (!p.available) Badge("Em breve", ReefColors.Muted)
            }
            Spacer(Modifier.weight(1f))
            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.description, fontSize = 12.sp, color = ReefColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
                MutedText("$online de ${devices.size} equipamentos online")
            }
            when {
                alerts.any { it.second.level == Level.ALARM } -> Badge("Alarme", ReefColors.Error)
                alerts.isNotEmpty() || offline.isNotEmpty() -> Badge("Atenção", ReefColors.Warn)
                online == devices.size -> Badge("Tudo certo", ReefColors.Ok)
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
