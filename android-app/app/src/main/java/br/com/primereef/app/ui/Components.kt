package br.com.primereef.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.primereef.app.data.LedChannel
import br.com.primereef.app.data.Schedule
import java.util.Calendar

@Composable
fun ReefCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ReefColors.Card),
        border = androidx.compose.foundation.BorderStroke(1.dp, ReefColors.Border),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, color = ReefColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
fun StatusDot(online: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (online) ReefColors.Ok else ReefColors.Error),
    )
}

@Composable
fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

/** Barras compactas com o nível atual de cada canal. */
@Composable
fun LevelBars(levels: Map<LedChannel, Int>?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LedChannel.entries.forEach { ch ->
            val v = levels?.get(ch) ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.label, fontSize = 11.sp, color = ReefColors.Muted, modifier = Modifier.width(76.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ReefColors.Border),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(v.coerceIn(0, 100) / 100f)
                            .height(6.dp)
                            .background(Color(ch.color)),
                    )
                }
                Text("$v%", fontSize = 11.sp, color = ReefColors.Text, modifier = Modifier.width(40.dp).padding(start = 6.dp))
            }
        }
    }
}

/**
 * Sliders dos 4 canais. Enquanto o usuário arrasta (ou logo após soltar), o valor local tem
 * prioridade sobre o que vem do polling, para o slider não "pular" de volta.
 */
@Composable
fun ChannelSliders(
    levels: Map<LedChannel, Int>?,
    enabled: Boolean = true,
    onPreview: (LedChannel, Int) -> Unit,
    onCommit: (LedChannel, Int) -> Unit,
) {
    val local = remember { mutableStateMapOf<LedChannel, Int>() }
    val touchedAt = remember { mutableStateMapOf<LedChannel, Long>() }
    var dragging by remember { mutableStateOf<LedChannel?>(null) }

    LedChannel.entries.forEach { ch ->
        val recent = (System.currentTimeMillis() - (touchedAt[ch] ?: 0L)) < HOLD_MS
        val shown = if (dragging == ch || recent || levels == null) local[ch] ?: levels?.get(ch) ?: 0
        else levels?.get(ch) ?: 0
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ch.label, fontSize = 14.sp)
            Text("$shown%", fontSize = 14.sp, color = Color(ch.color), fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = {
                val v = it.toInt()
                dragging = ch
                local[ch] = v
                touchedAt[ch] = System.currentTimeMillis()
                onPreview(ch, v)
            },
            onValueChangeFinished = {
                val v = local[ch] ?: shown
                dragging = null
                touchedAt[ch] = System.currentTimeMillis()
                onCommit(ch, v)
            },
            valueRange = 0f..100f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color(ch.color),
                activeTrackColor = Color(ch.color),
                inactiveTrackColor = ReefColors.Border,
            ),
        )
    }
}

private const val HOLD_MS = 6000L

/** Rampa automática do firmware: 60 min, limitada a metade da janela ligada. */
private const val RAMP_SEC = 3600

data class Curve(val points: List<Float>, val plateauStart: Int, val plateauEnd: Int, val hasPlateau: Boolean)

/** Reproduz o cálculo de curva do firmware (pontos a cada 5 min em 24h, 0..pctMax). */
fun calcCurve(pctMax: Int, s: Schedule): Curve {
    val start = s.onHour * 3600 + s.onMinute * 60
    val end = s.offHour * 3600 + s.offMinute * 60
    var length = end - start
    if (length < 0) length += 86400
    val ramp = minOf(RAMP_SEC, maxOf(length / 2, 1))
    val pts = (0..86400 step 300).map { t ->
        var e = t - start
        if (e < 0) e += 86400
        val f = when {
            length == 0 || e >= length -> 0f
            e < ramp -> e.toFloat() / ramp
            e > length - ramp -> (length - e).toFloat() / ramp
            else -> 1f
        }
        pctMax * f
    }
    return Curve(pts, start + ramp, end - ramp, length > 2 * ramp)
}

fun fmtHM(sec: Int): String {
    val s = ((sec % 86400) + 86400) % 86400
    return "%02d:%02d".format(s / 3600, (s % 3600) / 60)
}

@Composable
fun ScheduleChart(schedule: Schedule, modifier: Modifier = Modifier) {
    val grid = ReefColors.Border
    val nowColor = ReefColors.Warn
    Canvas(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ReefColors.Background),
    ) {
        val left = 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val w = right - left
        val h = bottom - top
        for (hh in 0..24 step 6) {
            val x = left + hh / 24f * w
            drawLine(grid, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
        }
        drawLine(grid, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1f)
        LedChannel.entries.forEach { ch ->
            val curve = calcCurve(schedule.levels[ch] ?: 0, schedule)
            val path = Path()
            curve.points.forEachIndexed { i, p ->
                val x = left + i.toFloat() / (curve.points.size - 1) * w
                val y = bottom - p / 100f * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(ch.color), style = Stroke(width = 2.dp.toPx()))
        }
        val c = Calendar.getInstance()
        val nowSec = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60
        val xNow = left + nowSec / 86400f * w
        drawLine(
            nowColor, Offset(xNow, top), Offset(xNow, bottom), strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0h", "6h", "12h", "18h", "24h").forEach {
            Text(it, fontSize = 10.sp, color = ReefColors.Muted)
        }
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = ReefColors.Muted, fontSize = 13.sp, style = MaterialTheme.typography.bodySmall)
}
