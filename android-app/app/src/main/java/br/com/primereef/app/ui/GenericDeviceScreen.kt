package br.com.primereef.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import br.com.primereef.app.data.Control
import br.com.primereef.app.data.ControlType
import br.com.primereef.app.data.Device
import br.com.primereef.app.data.DeviceInfo
import br.com.primereef.app.data.Protocol
import kotlin.math.roundToInt

/**
 * Tela de qualquer aparelho que fala o Protocolo Prime Reef v1. Ela é montada a partir do que o
 * próprio aparelho informa em /api/state: sensores, controles e alertas.
 */
@Composable
fun GenericDeviceContent(vm: AppViewModel, device: Device) {
    val live by vm.live.collectAsStateWithLifecycle()
    val entry = live[device.id]
    val state = entry?.state
    val context = LocalContext.current
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    LaunchedEffect(device.host) {
        if (device.protocol == Protocol.PRP1) info = runCatching { vm.deviceApi(device).info() }.getOrNull()
    }

    val send: (Control, String) -> Unit = { c, v ->
        vm.setControl(device, c.id, v) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    ReefCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(entry?.online == true)
            Spacer(Modifier.width(8.dp))
            Text(if (entry?.online == true) "Online" else if (entry == null) "Conectando..." else "Offline")
        }
        if (state == null && entry != null) {
            Spacer(Modifier.height(6.dp))
            MutedText("Sem resposta. Verifique se o aparelho está ligado e na mesma rede.")
        }
    }

    if (state == null) return

    if (state.alerts.isNotEmpty()) {
        ReefCard(title = "Alertas") {
            state.alerts.forEach { a ->
                LinkText("• ${a.message}", color = levelColor(a.level), fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }

    if (state.sensors.isNotEmpty()) {
        ReefCard(title = "Leituras") { SensorGrid(state.sensors) }
    }

    if (state.controls.isNotEmpty()) {
        ReefCard(title = "Controles") {
            state.controls.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = ReefColors.Border)
                ControlView(c, enabled = entry?.online == true, onSend = { send(c, it) })
            }
        }
    }

    ReefCard(title = "Informações") {
        InfoLine("Tipo", device.deviceType.label)
        info?.model?.ifBlank { null }?.let { InfoLine("Modelo", it) }
        info?.fw?.ifBlank { null }?.let { InfoLine("Firmware", "v$it") }
        InfoLine("Conexão", if (device.protocol == Protocol.TUYA) "Nuvem Tuya (Smart Life)" else device.host)
        InfoLine("ID", device.id)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = ReefColors.Muted, modifier = Modifier.weight(1f))
        Text(value)
    }
}

private fun formatNumber(v: Double, step: Double): String {
    val decimals = when {
        step >= 1 -> 0
        step >= 0.1 -> 1
        else -> 2
    }
    return if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
}

@Composable
private fun ControlView(c: Control, enabled: Boolean, onSend: (String) -> Unit) {
    when (c.type) {
        ControlType.SWITCH -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, Modifier.weight(1f))
            Switch(checked = c.boolValue, enabled = enabled, onCheckedChange = { onSend(if (it) "1" else "0") })
        }

        ControlType.SLIDER -> {
            // Valor local enquanto arrasta; enviado só ao soltar.
            var dragging by remember { mutableStateOf<Float?>(null) }
            val shown = dragging ?: c.numValue.toFloat()
            Row(Modifier.fillMaxWidth()) {
                Text(c.label, Modifier.weight(1f))
                Text("${formatNumber(shown.toDouble(), c.step)} ${c.unit}".trim(), fontWeight = FontWeight.SemiBold, color = ReefColors.Accent)
            }
            val steps = if (c.step > 0) (((c.max - c.min) / c.step).roundToInt() - 1).coerceIn(0, 200) else 0
            Slider(
                value = shown.coerceIn(c.min.toFloat(), c.max.toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSend(formatNumber(it.toDouble(), c.step).replace(',', '.')) }
                    dragging = null
                },
                valueRange = c.min.toFloat()..c.max.toFloat(),
                steps = if (steps in 1..100) steps else 0,
                enabled = enabled,
            )
        }

        ControlType.NUMBER -> {
            var text by remember(c.value) { mutableStateOf(c.value.orEmpty()) }
            Text(c.label)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    suffix = { if (c.unit.isNotBlank()) Text(c.unit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val parsed = text.replace(',', '.').toDoubleOrNull()
                Button(
                    onClick = { parsed?.let { onSend(text.replace(',', '.')) } },
                    enabled = enabled && parsed != null && parsed >= c.min && parsed <= c.max,
                ) { Text(c.applyLabel) }
            }
            MutedText("Entre ${formatNumber(c.min, c.step)} e ${formatNumber(c.max, c.step)} ${c.unit}".trim())
        }

        ControlType.SELECT -> {
            var open by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(c.label, Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(c.value ?: "Escolher") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        c.options.forEach { opt ->
                            DropdownMenuItem(text = { Text(opt) }, onClick = { open = false; onSend(opt) })
                        }
                    }
                }
            }
        }

        ControlType.BUTTON -> {
            var confirming by remember { mutableStateOf(false) }
            Button(
                onClick = { if (c.confirm != null) confirming = true else onSend("1") },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(c.label) }
            if (confirming) {
                AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text(c.label) },
                    text = { Text(c.confirm.orEmpty()) },
                    confirmButton = { TextButton(onClick = { confirming = false; onSend("1") }) { Text("Confirmar") } },
                    dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancelar") } },
                )
            }
        }

        ControlType.UNKNOWN -> Row(Modifier.fillMaxWidth()) {
            Text(c.label, Modifier.weight(1f))
            MutedText(c.value ?: "—")
        }
    }
}
