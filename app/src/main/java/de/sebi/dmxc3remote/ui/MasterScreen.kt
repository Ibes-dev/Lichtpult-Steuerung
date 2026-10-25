package de.sebi.dmxc3remote.ui

import LumosProtobuf.Master.MasterServiceTypes.EMasterType
import LumosProtobuf.Master.MasterServiceTypes.MasterActionRequest
import LumosProtobuf.Master.MasterServiceTypes.MasterDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.UmbraSession

/** Abschnitte wie im Master-Fenster von DMXControl. */
private enum class MSec(val title: String, val color: Color) {
    GRAND("Grand", Desk.Amber), GROUP("Gruppen", Desk.Cyan), PARAM("Parameter", Color(0xFFB9A6F2)),
    SPEED("Speed", Color(0xFF5B8DEF)), POS("Position", Desk.Green), COLOR("Farbe", Desk.Red)
}

private fun MasterDescriptor.section() = when (type) {
    EMasterType.GRAND, EMasterType.SPECIAL -> MSec.GRAND
    EMasterType.PARAMETER -> MSec.PARAM
    EMasterType.SPEED -> MSec.SPEED
    EMasterType.POSITION -> MSec.POS
    EMasterType.COLOR -> MSec.COLOR
    else -> MSec.GROUP
}

/** Tasten des Masters aus den Daten von DMXControl: Name → aktiv. */
private fun MasterDescriptor.buttonStates(): List<Pair<String, Boolean>> =
    buttonsMap.entries.map { (k, b) -> b.name.ifBlank { k } to b.buttonValue }.sortedBy { it.first }

/**
 * Master-Controller: Abschnitte Grand · Gruppen · Parameter · Speed · Position · Farbe.
 * Regler und Tasten kommen aus den Daten von DMXControl (faders/buttons); direkt sichtbar sind die
 * meistgenutzten Tasten, der Rest liegt im ⋮-Menü. "Kompakt" (System → Steuerung): nur Regler und Flash.
 */
@Composable
fun MasterScreen(s: UmbraSession, vm: AppViewModel) {
    val masters by s.masters.items.collectAsStateWithLifecycle()
    val err by s.masters.error.collectAsStateWithLifecycle()
    val compact by vm.masterCompact.collectAsStateWithLifecycle()
    val bySec = remember(masters) { masters.values.groupBy { it.section() }.mapValues { (_, l) -> l.sortedBy { it.number } } }
    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        if (masters.isEmpty()) {
            EmptyHint("Keine Master", "DMXControl hat keine Master gemeldet.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MSec.entries.forEach { sec ->
                val list = bySec[sec] ?: return@forEach
                item(key = sec.name) {
                    Column {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(sec.color))
                            Text(sec.title, style = MaterialTheme.typography.labelLarge, color = Desk.TextDim, modifier = Modifier.padding(start = 6.dp))
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            items(list, key = { it.masterId }) { m ->
                                when (sec) {
                                    MSec.GRAND, MSec.GROUP -> IntensityStrip(s, m, sec.color, compact)
                                    MSec.PARAM -> ParameterStrip(s, m, sec.color, compact)
                                    MSec.SPEED -> SpeedStrip(s, m, sec.color, compact)
                                    MSec.POS -> PositionStrip(s, m, sec.color, compact)
                                    MSec.COLOR -> ColorStrip(s, m, sec.color, compact)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== Bausteine
private fun stripHeight(compact: Boolean) = if (compact) 260.dp else 380.dp

/** Kanalzug im Stil der Executoren: farbiger Kopf, Inhalt, optional ⋮-Menü mit weiteren Tasten. */
@Composable
private fun MasterFrame(
    s: UmbraSession, m: MasterDescriptor, color: Color, width: Dp, compact: Boolean,
    primary: Set<String>, content: @Composable ColumnScope.() -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val more = m.buttonStates().filter { (n, _) -> primary.none { it.equals(n, true) } }
    Box(Modifier.width(width).height(stripHeight(compact))) {
        ChannelStrip("${m.number}", m.displayName.ifBlank { m.name.ifBlank { "Master ${m.number}" } }, color, width) {
            Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
        }
        if (!compact && more.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Tasten", tint = Color.Black.copy(alpha = 0.7f))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    more.forEach { (n, on) ->
                        DropdownMenuItem(
                            text = { Text(n, color = if (on) color else Desk.Text) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (on) color else Desk.Raised)) },
                            onClick = {
                                // wie eine Hardware-Taste: kurz drücken und loslassen
                                s.masters.button(m, n, true); s.masters.button(m, n, false)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Taste: hält beim Drücken (momentary), leuchtet, wenn DMXControl sie als aktiv meldet. */
@Composable
private fun MButton(label: String, active: Boolean, color: Color, modifier: Modifier = Modifier, onPress: (Boolean) -> Unit) {
    ExecButtonBox(label, active, color, modifier, onPress)
}

@Composable
private fun ExecButtonBox(label: String, active: Boolean, color: Color, modifier: Modifier, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.height(34.dp).clip(RoundedCornerShape(6.dp))
            .background(if (pressed) color else if (active) color.copy(alpha = 0.7f) else Desk.Raised)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true; onPress(true)
                    waitForUpOrCancellation()
                    pressed = false; onPress(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (pressed || active) Color.Black else Desk.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun MasterDescriptor.isOn(name: String) = buttonStates().any { it.first.equals(name, true) && it.second }

// ================================================================== Intensität (Grand, Gruppe, Gerät)
@Composable
private fun IntensityStrip(s: UmbraSession, m: MasterDescriptor, color: Color, compact: Boolean) {
    val v = if (m.masterValueCase == MasterDescriptor.MasterValueCase.INTENSITY) m.intensity else null
    MasterFrame(s, m, color, 104.dp, compact, setOf("Flash", "Full", "Black")) {
        Text(v?.let { pct(it) } ?: "–", style = MaterialTheme.typography.titleMedium.merge(Numeric), color = color)
        if (v != null) VerticalFader(v.toFloat().coerceIn(0f, 1f), { s.masters.setFader(m, it.toDouble()) }, color, Modifier.fillMaxWidth().weight(1f))
        MButton("Flash", m.isOn("Flash"), color, Modifier.fillMaxWidth()) { s.masters.button(m, "Flash", it) }
        if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MButton("Full", m.isOn("Full"), color, Modifier.weight(1f)) { if (it) s.masters.action(m.masterId, MasterActionRequest.EAction.FULL) }
            MButton("Black", m.isOn("Black"), color, Modifier.weight(1f)) { if (it) s.masters.action(m.masterId, MasterActionRequest.EAction.BLACK) }
        }
    }
}

// ================================================================== Parameter
@Composable
private fun ParameterStrip(s: UmbraSession, m: MasterDescriptor, color: Color, compact: Boolean) {
    val raw = if (m.masterValueCase == MasterDescriptor.MasterValueCase.PARAMETER) m.parameter else null
    // DMXControl zeigt 0–100; Werte über 1 werden als Prozent gelesen
    val norm = raw?.let { if (it > 1.0) it / 100.0 else it }
    MasterFrame(s, m, color, 104.dp, compact, setOf("Max", "Center", "Min")) {
        Text(raw?.let { "%.2f".format(it) } ?: "–", style = MaterialTheme.typography.titleMedium.merge(Numeric), color = color)
        if (norm != null) VerticalFader(norm.toFloat().coerceIn(0f, 1f), { s.masters.setFader(m, it.toDouble()) }, color, Modifier.fillMaxWidth().weight(1f))
        if (!compact) listOf("Max", "Center", "Min").forEach { n ->
            MButton(n, m.isOn(n), color, Modifier.fillMaxWidth()) { s.masters.button(m, n, it) }
        }
    }
}

// ================================================================== Speed
@Composable
private fun SpeedStrip(s: UmbraSession, m: MasterDescriptor, color: Color, compact: Boolean) {
    val bpm = if (m.masterValueCase == MasterDescriptor.MasterValueCase.SPEED) m.speed else null
    var tapDown by remember { mutableStateOf(false) }
    MasterFrame(s, m, color, 150.dp, compact, setOf("Learn", "Sync", "Double", "Half")) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bpm?.let { "%.0f".format(it) } ?: "–", style = MaterialTheme.typography.headlineMedium.merge(Numeric), color = color)
            Text(" BPM", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        }
        // Tipp-Fläche: jeder Tipp ist ein "Learn" in DMXControl
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp))
                .background(if (tapDown) color else color.copy(alpha = 0.75f))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        tapDown = true
                        s.masters.action(m.masterId, MasterActionRequest.EAction.LEARN)
                        waitForUpOrCancellation()
                        tapDown = false
                    }
                },
            contentAlignment = Alignment.Center
        ) { Text("Im Takt tippen", style = MaterialTheme.typography.titleSmall, color = Color.Black) }
        if (!compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MButton("Sync", false, color, Modifier.weight(1f)) { if (it) s.masters.action(m.masterId, MasterActionRequest.EAction.SYNC) }
                MButton("×2", false, color, Modifier.weight(1f)) { if (it) s.masters.action(m.masterId, MasterActionRequest.EAction.DOUBLE_TIMES) }
                MButton("½", false, color, Modifier.weight(1f)) { if (it) s.masters.action(m.masterId, MasterActionRequest.EAction.HALF_TIMES) }
            }
        }
    }
}

// ================================================================== Position
@Composable
private fun PositionStrip(s: UmbraSession, m: MasterDescriptor, color: Color, compact: Boolean) {
    val pos = if (m.masterValueCase == MasterDescriptor.MasterValueCase.POSITION) m.position else null
    // Anzeige: Pan/Tilt aus DMXControl; Feld 0..1 (Mitte = 0°), Bereich laut Master-Fenster −90…90
    val x = ((pos?.pan ?: 0.0) / 180.0 + 0.5).toFloat().coerceIn(0f, 1f)
    val y = (0.5 - (pos?.tilt ?: 0.0) / 180.0).toFloat().coerceIn(0f, 1f)
    val panName = m.fadersMap.entries.firstOrNull { (k, f) -> (k + f.name).contains("pan", true) }?.let { it.value.name.ifBlank { it.key } } ?: "Pan"
    val tiltName = m.fadersMap.entries.firstOrNull { (k, f) -> (k + f.name).contains("tilt", true) }?.let { it.value.name.ifBlank { it.key } } ?: "Tilt"
    MasterFrame(s, m, color, 200.dp, compact, setOf("Zero", "Zero (Pan)", "Zero (Tilt)")) {
        Text("Pan %.0f · Tilt %.0f".format(pos?.pan ?: 0.0, pos?.tilt ?: 0.0), style = MaterialTheme.typography.titleSmall.merge(Numeric), color = color)
        XYPad(x, y, { nx, ny ->
            s.masters.setNamedFader(m, panName, nx.toDouble())
            s.masters.setNamedFader(m, tiltName, (1f - ny).toDouble())
        }, color, Modifier.fillMaxWidth().weight(1f))
        if (!compact) {
            MButton("Zero", m.isOn("Zero"), color, Modifier.fillMaxWidth()) { s.masters.button(m, "Zero", it) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MButton("Zero Pan", m.isOn("Zero (Pan)"), color, Modifier.weight(1f)) { s.masters.button(m, "Zero (Pan)", it) }
                MButton("Zero Tilt", m.isOn("Zero (Tilt)"), color, Modifier.weight(1f)) { s.masters.button(m, "Zero (Tilt)", it) }
            }
        }
    }
}

// ================================================================== Farbe
private val colorChips = listOf(
    "White" to Color.White, "Red" to Color(0xFFFF2A2A), "Yellow" to Color(0xFFFFE500), "Green" to Color(0xFF22E05A),
    "Cyan" to Color(0xFF22E0E0), "Blue" to Color(0xFF2A5BFF), "Magenta" to Color(0xFFFF2AD4), "Black" to Color.Black
)

@Composable
private fun ColorStrip(s: UmbraSession, m: MasterDescriptor, color: Color, compact: Boolean) {
    val c = if (m.masterValueCase == MasterDescriptor.MasterValueCase.COLOR) m.color else null
    val swatch = c?.let {
        val vis = if (it.hasVisualizationColor()) it.visualizationColor else if (it.hasCalculatedColor()) it.calculatedColor else null
        if (vis != null && vis.r + vis.g + vis.b > 0) Color(vis.r, vis.g, vis.b)
        else { val mx = maxOf(1.0, it.r, it.g, it.b); Color((it.r / mx).toFloat(), (it.g / mx).toFloat(), (it.b / mx).toFloat()) }
    } ?: Desk.Raised
    val hueName = m.fadersMap.entries.firstOrNull { (k, f) -> (k + f.name).contains("hue", true) }?.let { it.value.name.ifBlank { it.key } } ?: "Hue"
    var hue by remember { mutableStateOf(0f) }
    MasterFrame(s, m, color, 200.dp, compact, colorChips.map { it.first }.toSet()) {
        Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(swatch).border(1.dp, Desk.Line, RoundedCornerShape(8.dp)))
        Text("Farbton", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
        DeskFader(hue, { hue = it; s.masters.setNamedFader(m, hueName, it.toDouble()) }, color, Modifier.fillMaxWidth().height(36.dp), vertical = false)
        if (!compact) {
            colorChips.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (n, col) ->
                        val on = m.isOn(n)
                        Box(
                            Modifier.weight(1f).aspectRatio(1.4f).clip(RoundedCornerShape(6.dp)).background(col)
                                .border(2.dp, if (on) Desk.Amber else Desk.Line, RoundedCornerShape(6.dp))
                                .pointerInput(n) {
                                    awaitEachGesture {
                                        awaitFirstDown(); s.masters.button(m, n, true)
                                        waitForUpOrCancellation(); s.masters.button(m, n, false)
                                    }
                                }
                        )
                    }
                }
            }
        }
    }
}
