package de.sebi.dmxc3remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Farben eines Designs. */
data class DeskPalette(
    val surface: Color, val panel: Color, val raised: Color, val line: Color,
    val text: Color, val textDim: Color,
    val amber: Color, val red: Color, val cyan: Color, val green: Color,
    val light: Boolean = false
)

/** Wählbare Designs (System → Projekt → Design). */
enum class DeskStyle(val label: String, val palette: DeskPalette) {
    DUNKEL("Dunkel", DeskPalette(
        Color(0xFF15171A), Color(0xFF1E2126), Color(0xFF2A2E35), Color(0xFF3A3F47),
        Color(0xFFE8E6E1), Color(0xFF9DA3AB),
        Color(0xFFF2A93B), Color(0xFFE5484D), Color(0xFF4CC3D9), Color(0xFF5BD18B))),
    SCHWARZ("Schwarz (OLED)", DeskPalette(
        Color(0xFF000000), Color(0xFF0E0F11), Color(0xFF1A1C20), Color(0xFF2A2D33),
        Color(0xFFF2F2F2), Color(0xFF8A9099),
        Color(0xFFF2A93B), Color(0xFFE5484D), Color(0xFF4CC3D9), Color(0xFF5BD18B))),
    KLASSISCH("Klassisch (Pult)", DeskPalette(
        Color(0xFF1B1F24), Color(0xFF252B33), Color(0xFF313944), Color(0xFF44505E),
        Color(0xFFE6EAF0), Color(0xFFA0AAB6),
        Color(0xFFFFB000), Color(0xFFFF4D4D), Color(0xFF3FC8FF), Color(0xFF3DDC84))),
    MODERN("Modern", DeskPalette(
        Color(0xFF12121A), Color(0xFF1C1C28), Color(0xFF282838), Color(0xFF3A3A50),
        Color(0xFFEEEEF5), Color(0xFF9C9CB4),
        Color(0xFF8C9EFF), Color(0xFFF56565), Color(0xFF4FD1C5), Color(0xFF48BB78))),
    HELL("Hell", DeskPalette(
        Color(0xFFF4F5F7), Color(0xFFFFFFFF), Color(0xFFE7E9EE), Color(0xFFD0D4DB),
        Color(0xFF1B1E23), Color(0xFF5D6672),
        Color(0xFFC77700), Color(0xFFD93036), Color(0xFF0A8EA8), Color(0xFF1E9E5A), light = true))
}

/**
 * Bühnen-Palette des gewählten Designs. Die Farben werden bei jedem Zugriff aus dem aktuellen
 * Design gelesen (Compose-State) – ein Wechsel färbt die ganze App sofort um.
 */
object Desk {
    var style by androidx.compose.runtime.mutableStateOf(DeskStyle.DUNKEL)
    private val p get() = style.palette
    val Surface get() = p.surface        // Pultoberfläche
    val Panel get() = p.panel            // Kanalzug / Karte
    val Raised get() = p.raised          // Taste, Eingabe
    val Line get() = p.line              // Trennung
    val Text get() = p.text
    val TextDim get() = p.textDim
    val Amber get() = p.amber            // aktiv / global
    val Red get() = p.red                // Blackout, Fehler
    val Cyan get() = p.cyan              // Beat, Rückmeldung
    val Green get() = p.green            // läuft / ok
    val isLight get() = p.light
}

private fun schemeFor(d: DeskPalette) = if (d.light) androidx.compose.material3.lightColorScheme(
    primary = d.amber, onPrimary = Color.White, secondary = d.cyan, onSecondary = Color.White, tertiary = d.green,
    background = d.surface, onBackground = d.text, surface = d.surface, onSurface = d.text,
    surfaceVariant = d.raised, onSurfaceVariant = d.textDim, surfaceContainer = d.panel, surfaceContainerHigh = d.raised,
    surfaceContainerLow = d.panel, outline = d.line, outlineVariant = d.line, error = d.red, onError = Color.White
) else darkColorScheme(
    primary = d.amber, onPrimary = Color(0xFF231600), secondary = d.cyan, onSecondary = Color(0xFF00222A), tertiary = d.green,
    background = d.surface, onBackground = d.text, surface = d.surface, onSurface = d.text,
    surfaceVariant = d.raised, onSurfaceVariant = d.textDim, surfaceContainer = d.panel, surfaceContainerHigh = d.raised,
    surfaceContainerLow = d.panel, outline = d.line, outlineVariant = d.line, error = d.red, onError = Color.White
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
    MaterialTheme(colorScheme = schemeFor(Desk.style.palette), typography = type, content = content)
}
