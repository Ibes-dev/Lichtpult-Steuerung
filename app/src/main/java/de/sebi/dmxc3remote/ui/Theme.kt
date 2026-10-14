package de.sebi.dmxc3remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Bühnen-Palette: dunkle Pultoberfläche (blendfrei im Saal), Bernstein wie Pultbeleuchtung
 * für aktive/globale Zustände, Rot nur für Blackout, Cyan für Takt/Feedback.
 */
object Desk {
    val Surface = Color(0xFF15171A)      // Pultoberfläche
    val Panel = Color(0xFF1E2126)        // Kanalzug / Karte
    val Raised = Color(0xFF2A2E35)       // Taste, Eingabe
    val Line = Color(0xFF3A3F47)         // Trennung
    val Text = Color(0xFFE8E6E1)
    val TextDim = Color(0xFF9DA3AB)
    val Amber = Color(0xFFF2A93B)        // aktiv / global
    val Red = Color(0xFFE5484D)          // Blackout, Fehler
    val Cyan = Color(0xFF4CC3D9)         // Beat, Rückmeldung
    val Green = Color(0xFF5BD18B)        // läuft / ok
}

private val scheme = darkColorScheme(
    primary = Desk.Amber,
    onPrimary = Color(0xFF231600),
    secondary = Desk.Cyan,
    onSecondary = Color(0xFF00222A),
    tertiary = Desk.Green,
    background = Desk.Surface,
    onBackground = Desk.Text,
    surface = Desk.Surface,
    onSurface = Desk.Text,
    surfaceVariant = Desk.Raised,
    onSurfaceVariant = Desk.TextDim,
    surfaceContainer = Desk.Panel,
    surfaceContainerHigh = Desk.Raised,
    surfaceContainerLow = Desk.Panel,
    outline = Desk.Line,
    outlineVariant = Desk.Line,
    error = Desk.Red,
    onError = Color.White
)

private val type = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.2.sp)
    )
}

/** Tabellarische Ziffern für Werte, die sich ständig ändern (kein Springen der Breite). */
val Numeric = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium)

@Composable
fun DeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
