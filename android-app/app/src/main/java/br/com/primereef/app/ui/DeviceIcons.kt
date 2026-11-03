package br.com.primereef.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.primereef.app.data.DeviceType

/** Cor de destaque de cada família de produto. */
fun DeviceType.accent(): Color = when (this) {
    DeviceType.LUMINARIA -> Color(0xFF29B6F6)
    DeviceType.KH -> Color(0xFFB388FF)
    DeviceType.ALIMENTADOR -> Color(0xFFFFB74D)
    DeviceType.DOSADORA -> Color(0xFF3FB950)
    DeviceType.BOMBA -> Color(0xFF4DD0E1)
    DeviceType.ATO -> Color(0xFF58A6FF)
    DeviceType.CONTROLADOR -> Color(0xFFF0A500)
    DeviceType.SENSOR -> Color(0xFFFF7043)
    DeviceType.OUTRO -> Color(0xFF8B949E)
}

/** Ícone desenhado do equipamento, dentro de um quadrado arredondado com a cor do produto. */
@Composable
fun DeviceIcon(type: DeviceType, size: Dp = 56.dp, dimmed: Boolean = false) {
    val color = type.accent().let { if (dimmed) it.copy(alpha = 0.45f) else it }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.62f)) { drawDevice(type, color) }
    }
}

private fun DrawScope.drawDevice(type: DeviceType, c: Color) {
    val w = size.width
    val h = size.height
    val sw = w * 0.07f
    val stroke = Stroke(width = sw, cap = StrokeCap.Round)
    when (type) {
        DeviceType.LUMINARIA -> {
            // Barra da luminária + feixes de luz + onda
            drawRoundRect(c, Offset(w * 0.08f, h * 0.12f), Size(w * 0.84f, h * 0.16f), CornerRadius(w * 0.05f))
            for (i in 0..4) {
                val x = w * (0.18f + i * 0.16f)
                drawLine(c.copy(alpha = 0.7f), Offset(x, h * 0.36f), Offset(x + (i - 2) * w * 0.05f, h * 0.62f), sw * 0.8f, StrokeCap.Round)
            }
            wave(c, h * 0.82f, sw)
        }
        DeviceType.KH -> {
            // Tubo de ensaio com líquido
            val p = Path().apply {
                moveTo(w * 0.36f, h * 0.06f); lineTo(w * 0.36f, h * 0.78f)
                quadraticBezierTo(w * 0.36f, h * 0.96f, w * 0.5f, h * 0.96f)
                quadraticBezierTo(w * 0.64f, h * 0.96f, w * 0.64f, h * 0.78f)
                lineTo(w * 0.64f, h * 0.06f)
            }
            drawPath(p, c, style = stroke)
            drawLine(c, Offset(w * 0.28f, h * 0.06f), Offset(w * 0.72f, h * 0.06f), sw, StrokeCap.Round)
            drawRect(c.copy(alpha = 0.6f), Offset(w * 0.40f, h * 0.48f), Size(w * 0.20f, h * 0.30f))
            drawCircle(c, w * 0.10f, Offset(w * 0.5f, h * 0.84f))
        }
        DeviceType.ALIMENTADOR -> {
            // Pote de ração com grãos caindo
            drawRoundRect(c, Offset(w * 0.22f, h * 0.04f), Size(w * 0.56f, h * 0.12f), CornerRadius(w * 0.04f))
            val jar = Path().apply {
                moveTo(w * 0.26f, h * 0.18f); lineTo(w * 0.74f, h * 0.18f)
                lineTo(w * 0.66f, h * 0.56f); lineTo(w * 0.34f, h * 0.56f); close()
            }
            drawPath(jar, c, style = stroke)
            drawRect(c.copy(alpha = 0.55f), Offset(w * 0.33f, h * 0.36f), Size(w * 0.34f, h * 0.17f))
            listOf(0.42f to 0.68f, 0.56f to 0.76f, 0.46f to 0.86f, 0.60f to 0.94f, 0.38f to 0.96f).forEach { (x, y) ->
                drawCircle(c, w * 0.045f, Offset(w * x, h * y))
            }
        }
        DeviceType.DOSADORA -> {
            // Corpo da bomba + cabeça peristáltica + gota
            drawRoundRect(c, Offset(w * 0.06f, h * 0.30f), Size(w * 0.58f, h * 0.56f), CornerRadius(w * 0.08f), style = stroke)
            drawCircle(c, w * 0.15f, Offset(w * 0.35f, h * 0.58f), style = stroke)
            drawCircle(c, w * 0.04f, Offset(w * 0.35f, h * 0.58f))
            drop(c, Offset(w * 0.82f, h * 0.30f), w * 0.13f)
            drawLine(c, Offset(w * 0.64f, h * 0.42f), Offset(w * 0.76f, h * 0.42f), sw, StrokeCap.Round)
        }
        DeviceType.BOMBA -> {
            // Hélice de 3 pás
            val center = Offset(w / 2, h / 2)
            for (i in 0 until 3) {
                rotateBlade(c, center, w, i * 120f)
            }
            drawCircle(c, w * 0.09f, center)
        }
        DeviceType.ATO -> {
            // Reservatório com nível e seta de reposição
            drawRoundRect(c, Offset(w * 0.12f, h * 0.22f), Size(w * 0.76f, h * 0.70f), CornerRadius(w * 0.06f), style = stroke)
            drawRect(c.copy(alpha = 0.55f), Offset(w * 0.16f, h * 0.58f), Size(w * 0.68f, h * 0.30f))
            drawLine(c, Offset(w * 0.5f, h * 0.0f), Offset(w * 0.5f, h * 0.42f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.38f, h * 0.30f), Offset(w * 0.5f, h * 0.42f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.62f, h * 0.30f), Offset(w * 0.5f, h * 0.42f), sw, StrokeCap.Round)
        }
        DeviceType.CONTROLADOR -> {
            // Chip
            drawRoundRect(c, Offset(w * 0.22f, h * 0.22f), Size(w * 0.56f, h * 0.56f), CornerRadius(w * 0.06f), style = stroke)
            drawRoundRect(c.copy(alpha = 0.6f), Offset(w * 0.36f, h * 0.36f), Size(w * 0.28f, h * 0.28f), CornerRadius(w * 0.03f))
            for (i in 0..2) {
                val t = w * (0.34f + i * 0.16f)
                drawLine(c, Offset(t, h * 0.04f), Offset(t, h * 0.20f), sw, StrokeCap.Round)
                drawLine(c, Offset(t, h * 0.80f), Offset(t, h * 0.96f), sw, StrokeCap.Round)
                drawLine(c, Offset(w * 0.04f, t), Offset(w * 0.20f, t), sw, StrokeCap.Round)
                drawLine(c, Offset(w * 0.80f, t), Offset(w * 0.96f, t), sw, StrokeCap.Round)
            }
        }
        DeviceType.SENSOR -> {
            // Termômetro
            drawRoundRect(c, Offset(w * 0.40f, h * 0.04f), Size(w * 0.20f, h * 0.66f), CornerRadius(w * 0.10f), style = stroke)
            drawCircle(c, w * 0.17f, Offset(w * 0.5f, h * 0.80f))
            drawLine(c, Offset(w * 0.5f, h * 0.30f), Offset(w * 0.5f, h * 0.72f), sw * 1.4f, StrokeCap.Round)
        }
        DeviceType.OUTRO -> {
            drawRoundRect(c, Offset(w * 0.14f, h * 0.14f), Size(w * 0.72f, h * 0.72f), CornerRadius(w * 0.12f), style = stroke)
            drawLine(c, Offset(w * 0.5f, h * 0.32f), Offset(w * 0.5f, h * 0.68f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.32f, h * 0.5f), Offset(w * 0.68f, h * 0.5f), sw, StrokeCap.Round)
        }
    }
}

private fun DrawScope.wave(c: Color, y: Float, sw: Float) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.04f, y)
        var x = w * 0.04f
        val seg = w * 0.23f
        repeat(4) {
            quadraticBezierTo(x + seg / 4, y - size.height * 0.08f, x + seg / 2, y)
            quadraticBezierTo(x + seg * 3 / 4, y + size.height * 0.08f, x + seg, y)
            x += seg
        }
    }
    drawPath(p, c, style = Stroke(width = sw, cap = StrokeCap.Round))
}

private fun DrawScope.drop(c: Color, top: Offset, r: Float) {
    val p = Path().apply {
        moveTo(top.x, top.y)
        quadraticBezierTo(top.x + r * 1.4f, top.y + r * 1.8f, top.x, top.y + r * 2.6f)
        quadraticBezierTo(top.x - r * 1.4f, top.y + r * 1.8f, top.x, top.y)
    }
    drawPath(p, c)
}

private fun DrawScope.rotateBlade(c: Color, center: Offset, w: Float, deg: Float) {
    val rad = Math.toRadians(deg.toDouble())
    fun rot(x: Float, y: Float): Offset {
        val dx = x - center.x
        val dy = y - center.y
        return Offset(
            center.x + (dx * Math.cos(rad) - dy * Math.sin(rad)).toFloat(),
            center.y + (dx * Math.sin(rad) + dy * Math.cos(rad)).toFloat(),
        )
    }
    val a = rot(center.x, center.y - w * 0.08f)
    val b = rot(center.x + w * 0.30f, center.y - w * 0.44f)
    val d = rot(center.x - w * 0.12f, center.y - w * 0.46f)
    val p = Path().apply {
        moveTo(a.x, a.y)
        quadraticBezierTo(b.x, b.y, d.x, d.y)
        close()
    }
    drawPath(p, c)
}
