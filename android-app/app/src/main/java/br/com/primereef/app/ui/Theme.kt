package br.com.primereef.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Mesma paleta do painel web da luminária.
object ReefColors {
    val Background = Color(0xFF0D1117)
    val Card = Color(0xFF161B22)
    val Border = Color(0xFF30363D)
    val Accent = Color(0xFF58A6FF)
    val Muted = Color(0xFF8B949E)
    val Text = Color(0xFFC9D1D9)
    val Ok = Color(0xFF3FB950)
    val Warn = Color(0xFFF0A500)
    val Error = Color(0xFFF85149)
}

private val scheme = darkColorScheme(
    primary = ReefColors.Accent,
    onPrimary = Color(0xFF0D1117),
    secondary = Color(0xFF29B6F6),
    background = ReefColors.Background,
    onBackground = ReefColors.Text,
    surface = ReefColors.Background,
    onSurface = ReefColors.Text,
    surfaceVariant = ReefColors.Card,
    onSurfaceVariant = ReefColors.Muted,
    surfaceContainer = ReefColors.Card,
    surfaceContainerHigh = ReefColors.Card,
    surfaceContainerHighest = Color(0xFF21262D),
    surfaceContainerLow = ReefColors.Card,
    outline = ReefColors.Border,
    outlineVariant = ReefColors.Border,
    error = ReefColors.Error,
)

@Composable
fun PrimeReefTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
