package br.com.primereef.app.ui

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit

/** Um endereço encontrado no texto: posição e URL que abre ao tocar. */
data class FoundLink(val start: Int, val end: Int, val url: String)

object Links {
    // http(s)://..., domínios (platform.tuya.com, web.esphome.io...) e IPs (192.168.4.1).
    private val PATTERN = Regex(
        """https?://[^\s<>"]+""" +
            """|\b(?:[a-z0-9-]+\.)+(?:com\.br|com|cc|io|net|org|br)(?:/[^\s<>"]*)?""" +
            """|\b\d{1,3}(?:\.\d{1,3}){3}\b""",
        RegexOption.IGNORE_CASE,
    )

    fun find(text: String): List<FoundLink> = PATTERN.findAll(text).map { m ->
        // Pontuação no fim da frase não faz parte do link.
        val raw = m.value.trimEnd('.', ',', ';', ':', ')', '!', '?')
        val url = if (raw.startsWith("http", ignoreCase = true)) raw else "http${if (raw.first().isDigit()) "" else "s"}://$raw"
        FoundLink(m.range.first, m.range.first + raw.length, url)
    }.toList()

    fun annotate(text: String, color: Color): AnnotatedString {
        val links = find(text)
        if (links.isEmpty()) return AnnotatedString(text)
        val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        return buildAnnotatedString {
            var pos = 0
            links.forEach { l ->
                append(text.substring(pos, l.start))
                withLink(LinkAnnotation.Url(l.url, style)) { append(text.substring(l.start, l.end)) }
                pos = l.end
            }
            append(text.substring(pos))
        }
    }
}

/** Texto comum em que endereços de sites e IPs viram links clicáveis. */
@Composable
fun LinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val annotated = remember(text) { Links.annotate(text, ReefColors.Accent) }
    Text(annotated, modifier = modifier, color = color, fontSize = fontSize, inlineContent = emptyMap<String, InlineTextContent>())
}
