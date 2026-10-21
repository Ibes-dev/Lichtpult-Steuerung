package de.sebi.dmxc3remote.ui

import LumosProtobuf.CommandLineServiceTypes.CommandLineHistoryItem
import LumosProtobuf.DeviceServiceTypes.DeviceGroupDescriptor
import LumosProtobuf.DeviceServiceTypes.DevicePropertyDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.PropKind
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.boundCase
import de.sebi.dmxc3remote.net.caption
import de.sebi.dmxc3remote.net.colorMax
import de.sebi.dmxc3remote.net.importance
import de.sebi.dmxc3remote.net.kelvinRange
import de.sebi.dmxc3remote.net.multiKeys
import de.sebi.dmxc3remote.net.multiRange
import de.sebi.dmxc3remote.net.strobeRange
import de.sebi.dmxc3remote.net.doubleRange
import de.sebi.dmxc3remote.net.kind
import de.sebi.dmxc3remote.net.positionRange
import de.sebi.dmxc3remote.net.text

// ============================================================== Programmer
@Composable
internal fun ProgrammerValuesTab(s: UmbraSession, focusGroup: String?) {
    val dm = s.devices
    val groups by dm.groups.collectAsStateWithLifecycle()
    val presets by s.presets.items.collectAsStateWithLifecycle()
    val err by dm.error.collectAsStateWithLifecycle()
    val presetErr by s.presets.error.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val group = selected?.let { groups[it] }
    val sortedGroups = remember(groups) { groups.values.sortedBy { it.number } }
    val select: (String) -> Unit = { id ->
        selected = if (selected == id) null else id
        dm.select(listOfNotNull(selected))
    }
    // Sprung aus "Inhalt": Gruppe direkt öffnen
    LaunchedEffect(focusGroup) {
        if (focusGroup != null && focusGroup != selected) {
            selected = focusGroup
            dm.select(listOf(focusGroup))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        if (isWide()) {
            // Tablet: Gruppenliste links, Eigenschaften und Presets rechts
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.width(220.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sortedGroups, key = { it.id }) { g -> GroupRow(g, selected == g.id) { select(g.id) } }
                }
                VerticalDivider(color = Desk.Line)
                PropertyPane(s, group, presets.values.sortedBy { it.name }.map { it.presetId to it.name }, presetErr, Modifier.weight(1f))
            }
        } else {
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                items(sortedGroups, key = { it.id }) { g ->
                    SmallChip(g.name.ifBlank { "Gruppe ${g.number}" }, selected == g.id, { select(g.id) })
                }
            }
            PropertyPane(s, group, presets.values.sortedBy { it.name }.map { it.presetId to it.name }, presetErr, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GroupRow(g: DeviceGroupDescriptor, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Desk.Amber else Desk.Panel)
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            g.name.ifBlank { "Gruppe ${g.number}" },
            color = if (selected) Color.Black else Desk.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun PropertyPane(
    s: UmbraSession,
    group: DeviceGroupDescriptor?,
    presets: List<Pair<String, String>>,
    presetErr: String?,
    modifier: Modifier,
    compact: Boolean = false
) {
    val progValues by s.devices.progValues.collectAsStateWithLifecycle()
    val fannedMap by s.devices.progFanned.collectAsStateWithLifecycle()
    var showAll by remember(group?.id) { mutableStateOf(false) }
    // Kompakt: nur wichtige Funktionen, sortiert wie am Pult
    val props = remember(group, progValues, fannedMap, compact, showAll) {
        val gid = group?.id ?: ""
        // Nicht bedienbare Eigenschaften (Rohwerte ohne Typbeschreibung) werden gar nicht angezeigt
        val list = (group?.propertiesList ?: emptyList())
            .map { it to it.kind(serverValue(gid, it, progValues, fannedMap)?.valueCase) }
            .filter { it.second != PropKind.UNSUPPORTED }
        if (!compact || showAll) {
            // wichtige Funktionen zuerst, Rest alphabetisch
            list.sortedWith(compareBy({ it.first.importance(it.second) ?: 99 }, { it.first.displayName.ifBlank { it.first.name } })).map { it.first }
        } else list.mapNotNull { (p, k) -> p.importance(k)?.let { it to p } }.sortedBy { it.first }.map { it.second }
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (group == null) {
            item {
                Text(
                    "Gerätegruppe wählen, um Dimmer, Farbe, Position und Effekte zu setzen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Desk.TextDim
                )
            }
        } else {
            items(props) { p -> PropertyControl(s, group, p, compact && !showAll) }
            if (compact) item {
                androidx.compose.material3.TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Nur wichtige Funktionen" else "Alle Funktionen anzeigen")
                }
            }
        }
        if (compact) item {
            Section(if (group != null) "Presets für ${group.name}" else "Presets", error = presetErr) {
                if (presets.isEmpty()) Text("Keine Presets im Projekt.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                PresetGrid(presets) { id -> s.presets.invoke(id, group?.id) }
            }
        }
    }
}

@Composable
private fun PresetGrid(items: List<Pair<String, String>>, onClick: (String) -> Unit) {
    val cols = if (isWide()) 4 else 2
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (id, name) ->
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp)).background(Desk.Raised)
                            .clickableNoRipple { onClick(id) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(name.ifBlank { "Preset" }, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
                }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Lokaler Anzeigewert je Gruppe/Eigenschaft (der Server liefert hier keinen Push). */
private val localValues = mutableStateMapOf<String, List<Float>>()

/**
 * Eine Eigenschaft als kompakte Zeile (48 dp): Name · Bedienelement · Wert · Menü.
 * Farbe, Position, Auswahllisten und Texte klappen bei Bedarf ein Detailfeld auf.
 * Links markiert ein Balken, dass der Wert im Programmer steht.
 */
@Composable
private fun PropertyControl(s: UmbraSession, g: DeviceGroupDescriptor, p: DevicePropertyDescriptor, compact: Boolean = false) {
    val key = "${g.id}:${p.id}"
    val dm = s.devices
    val label = p.displayName.ifBlank { p.name.ifBlank { p.id } }
    val progValues by dm.progValues.collectAsStateWithLifecycle()
    val fannedMap by dm.progFanned.collectAsStateWithLifecycle()
    val appliedMap by dm.applied.collectAsStateWithLifecycle()
    val server = serverValue(g.id, p, progValues, fannedMap)
    val kind = p.kind(server?.valueCase)
    LaunchedEffect(server) { server?.let { v -> fromServer(v, p, kind)?.let { localValues[key] = it } } }
    var effectDialog by remember { mutableStateOf(false) }
    var fanOpen by remember { mutableStateOf(false) }
    var effectsOpen by remember { mutableStateOf(false) }
    var detail by remember(key) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val fanned = fannedMap[key]
    val reallyFanned = fanned != null && fanned.fannedValuesList.map { it.toByteString() }.distinct().size > 1
    val effects = appliedMap[key] ?: emptyList()
    val inProg = server != null || reallyFanned || effects.isNotEmpty()

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Panel)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (inProg) Desk.Amber else Color.Transparent))
            Column(Modifier.width(92.dp).padding(start = 9.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val tags = buildList {
                    if (reallyFanned) add("gefächert")
                    if (effects.isNotEmpty()) add("${effects.size} Effekt" + if (effects.size > 1) "e" else "")
                }
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Desk.Amber, maxLines = 1)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                when (kind) {
                    PropKind.DOUBLE -> {
                        val r = p.doubleRange()
                        val v = localValues[key]?.firstOrNull() ?: r.start.toFloat()
                        RowSlider(v, r.start.toFloat()..r.endInclusive.toFloat(), "%.2f".format(v)) { nv ->
                            localValues[key] = listOf(nv); dm.setDouble(g.id, p.id, nv.toDouble())
                        }
                    }
                    PropKind.KELVIN -> {
                        val r = p.kelvinRange()
                        val v = localValues[key]?.firstOrNull() ?: ((r.start + r.endInclusive) / 2).toFloat()
                        RowSlider(v, r.start.toFloat()..r.endInclusive.toFloat(), "${v.toInt()} K") { nv ->
                            localValues[key] = listOf(nv); dm.setKelvin(g.id, p.id, nv.toDouble())
                        }
                    }
                    PropKind.MULTIDOUBLE -> {
                        val r = p.multiRange()
                        val v = localValues[key]?.firstOrNull() ?: r.start.toFloat()
                        RowSlider(v, r.start.toFloat()..r.endInclusive.toFloat(), "%.2f".format(v)) { nv ->
                            localValues[key] = listOf(nv); dm.setMultiDouble(g.id, p.id, p.multiKeys(), nv.toDouble())
                        }
                    }
                    PropKind.STROBE -> {
                        val r = p.strobeRange()
                        val v = localValues[key]?.firstOrNull() ?: r.start.toFloat()
                        RowSlider(v, r.start.toFloat()..r.endInclusive.toFloat(), "%.1f".format(v)) { nv ->
                            localValues[key] = listOf(nv); dm.setStrobe(g.id, p.id, nv.toDouble(), strobeType[key])
                        }
                    }
                    PropKind.BOOL -> {
                        val on = (localValues[key]?.firstOrNull() ?: 0f) > 0.5f
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Switch(checked = on, onCheckedChange = { c ->
                                localValues[key] = listOf(if (c) 1f else 0f); dm.setBool(g.id, p.id, c)
                            })
                        }
                    }
                    PropKind.COLOR -> {
                        val rgb = localValues[key] ?: listOf(1f, 1f, 1f)
                        ValueButton(detail, { detail = !detail }, Color(rgb[0], rgb[1], rgb[2]),
                            "%.0f · %.0f · %.0f %%".format(rgb[0] * 100, rgb[1] * 100, rgb[2] * 100))
                    }
                    PropKind.POSITION -> {
                        val r = p.positionRange()
                        val xy = localValues[key] ?: listOf(0.5f, 0.5f)
                        ValueButton(detail, { detail = !detail }, null,
                            "Pan %.0f · Tilt %.0f".format(
                                r.pan.start + xy[0] * (r.pan.endInclusive - r.pan.start),
                                r.tilt.start + xy[1] * (r.tilt.endInclusive - r.tilt.start)
                            ))
                    }
                    PropKind.ENUM -> {
                        val entries = p.type.enumValuesList
                        val i = localValues[key]?.firstOrNull()?.toInt() ?: -1
                        val e = entries.getOrNull(i)
                        ValueButton(detail, { detail = !detail }, e?.let { enumSwatch(it) }, e?.caption() ?: "–")
                    }
                    PropKind.STRING -> ValueButton(detail, { detail = !detail }, null, "Text")
                    PropKind.UNSUPPORTED -> Unit
                }
            }
            if (!compact) {
                Box {
                    androidx.compose.material3.IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Mehr zu $label", tint = Desk.TextDim)
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (kind == PropKind.DOUBLE) androidx.compose.material3.DropdownMenuItem(
                            text = { Text(if (fanOpen) "Fächern ausblenden" else "Fächern …") },
                            onClick = { fanOpen = !fanOpen; menu = false }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(if (effects.isEmpty()) "Effekt hinzufügen …" else if (effectsOpen) "Effekte ausblenden" else "Effekte (${effects.size}) …") },
                            onClick = { if (effects.isEmpty()) effectDialog = true else effectsOpen = !effectsOpen; menu = false }
                        )
                        if (server != null) androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Aus Programmer entfernen") },
                            onClick = { s.programmer.remove(g.id, p.id); menu = false }
                        )
                    }
                }
            } else androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        }
        // ---------- aufgeklappte Details
        if (detail || (kind == PropKind.STROBE && p.type.enumValuesCount > 0 && !compact)) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (kind) {
                    PropKind.COLOR -> ColorDetail(key, p) { r, gg, b -> dm.setColor(g.id, p.id, r, gg, b) }
                    PropKind.POSITION -> {
                        val r = p.positionRange()
                        val xy = localValues[key] ?: listOf(0.5f, 0.5f)
                        XYPad(
                            xy[0], xy[1],
                            { x, y ->
                                localValues[key] = listOf(x, y)
                                dm.setPosition(
                                    g.id, p.id,
                                    r.pan.start + x * (r.pan.endInclusive - r.pan.start),
                                    r.tilt.start + y * (r.tilt.endInclusive - r.tilt.start)
                                )
                            },
                            MaterialTheme.colorScheme.primary,
                            Modifier.fillMaxWidth().aspectRatio(2f)
                        )
                    }
                    PropKind.ENUM -> {
                        val entries = p.type.enumValuesList
                        EnumChips(entries.map { it.caption() }, localValues[key]?.firstOrNull()?.toInt() ?: -1, entries.map { enumSwatch(it) }) { i ->
                            localValues[key] = listOf(i.toFloat()); dm.setEnum(g.id, p, entries[i]); detail = false
                        }
                    }
                    PropKind.STROBE -> {
                        val types = p.type.enumValuesList.filter { it.valueCase == LumosProtobuf.ValueTypes.IEnumObjectData.ValueCase.ENUMSTRING }
                        if (types.isNotEmpty()) EnumChips(types.map { it.caption() }, types.indexOfFirst { it.enumString == strobeType[key] }) { i ->
                            strobeType[key] = types[i].enumString
                            dm.setStrobe(g.id, p.id, (localValues[key]?.firstOrNull() ?: 0f).toDouble(), types[i].enumString)
                        }
                    }
                    PropKind.STRING -> {
                        var text by remember(key) { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CompactSearch(text, { text = it }, "Text eingeben", Modifier.weight(1f))
                            androidx.compose.material3.TextButton(onClick = { dm.setString(g.id, p.id, text); detail = false }) { Text("Setzen") }
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (fanOpen && kind == PropKind.DOUBLE) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) { FanControl(s, g, p) }
        if (effectsOpen && effects.isNotEmpty()) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            EffectsPanel(s, g, p, effects) { effectDialog = true }
        }
    }
    if (effectDialog) EffectDialog(s, g, p) { effectDialog = false; effectsOpen = true }
}

/** Gewählte Strobe-Art je Eigenschaft (bleibt beim Ziehen des Reglers erhalten). */
private val strobeType = androidx.compose.runtime.mutableStateMapOf<String, LumosProtobuf.ValueTypes.EnumStringData>()

/** Regler in der Zeile mit Wertanzeige rechts. */
@Composable
private fun RowSlider(v: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(v.coerceIn(range.start, range.endInclusive), onChange, Modifier.weight(1f).height(36.dp), valueRange = range)
        Text(
            text, style = MaterialTheme.typography.labelMedium.merge(Numeric), color = Desk.TextDim,
            maxLines = 1, modifier = Modifier.width(52.dp).padding(start = 6.dp)
        )
    }
}

/** Wert als antippbare Fläche (öffnet das Detailfeld), optional mit Farbfeld. */
@Composable
private fun ValueButton(open: Boolean, onClick: () -> Unit, swatch: Color?, text: String) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(end = 4.dp).clip(RoundedCornerShape(6.dp))
            .background(if (open) Desk.Raised else Color.Transparent)
            .clickableNoRipple(onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (swatch != null) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(swatch))
            Box(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null, tint = Desk.TextDim, modifier = Modifier.size(18.dp)
        )
    }
}

private fun enumSwatch(e: LumosProtobuf.ValueTypes.IEnumObjectData): Color? =
    if (e.valueCase == LumosProtobuf.ValueTypes.IEnumObjectData.ValueCase.ENUMCOLOR && e.enumColor.hasColor())
        Color(e.enumColor.color.r, e.enumColor.color.g, e.enumColor.color.b) else null

/** Farbe: Schnellwahl und drei schlanke RGB-Regler. */
@Composable
private fun ColorDetail(key: String, p: DevicePropertyDescriptor, send: (Double, Double, Double) -> Unit) {
    val max = p.colorMax()
    val rgb = localValues[key] ?: listOf(1f, 1f, 1f)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Color.White, Color.Red, Color(0xFFFF8000), Color.Yellow, Color.Green,
            Color.Cyan, Color.Blue, Color.Magenta
        ).forEach { c ->
            Box(
                Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp)).background(c)
                    .clickableNoRipple {
                        localValues[key] = listOf(c.red, c.green, c.blue)
                        send(c.red * max, c.green * max, c.blue * max)
                    }
            )
        }
    }
    listOf("R" to Color(0xFFE5484D), "G" to Color(0xFF5BD18B), "B" to Color(0xFF4C8DE5)).forEachIndexed { i, (n, col) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n, style = MaterialTheme.typography.labelMedium, color = col, modifier = Modifier.width(16.dp))
            Slider(
                rgb[i],
                { nv ->
                    val next = rgb.toMutableList().also { it[i] = nv }
                    localValues[key] = next
                    send(next[0] * max, next[1] * max, next[2] * max)
                },
                Modifier.weight(1f).height(32.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = col, activeTrackColor = col)
            )
            Text("%.0f".format(rgb[i] * 100), style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.TextDim, modifier = Modifier.width(32.dp))
        }
    }
}

/** Server-Wert in die lokale Darstellung (normiert wie die Bedienelemente) umrechnen. */
/** Programmer-Wert: direkter Wert, sonst erster gefächerter Wert (bei Gruppen meldet DMXControl je Gerät). */
private fun serverValue(
    groupId: String,
    p: DevicePropertyDescriptor,
    values: Map<String, LumosProtobuf.DeviceServiceTypes.DevicePropertyValue>,
    fanned: Map<String, LumosProtobuf.DeviceServiceTypes.FannedPropertyValue>
): LumosProtobuf.DeviceServiceTypes.DevicePropertyValue? {
    val key = "$groupId:${p.id}"
    return values[key] ?: fanned[key]?.fannedValuesList?.firstOrNull {
        it.valueCase != LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.VALUE_NOT_SET
    }
}

private fun fromServer(v: LumosProtobuf.DeviceServiceTypes.DevicePropertyValue, p: DevicePropertyDescriptor, kind: PropKind): List<Float>? {
    return when (v.valueCase) {
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.DOUBLEVALUE -> listOf(v.doubleValue.toFloat())
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.COLOR -> {
            val max = p.colorMax().toFloat()
            listOf((v.color.r / max).toFloat(), (v.color.g / max).toFloat(), (v.color.b / max).toFloat()).map { it.coerceIn(0f, 1f) }
        }
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.POSITION -> {
            val r = p.positionRange()
            val x = ((v.position.pan - r.pan.start) / (r.pan.endInclusive - r.pan.start)).toFloat()
            val y = ((v.position.tilt - r.tilt.start) / (r.tilt.endInclusive - r.tilt.start)).toFloat()
            listOf(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.BOOLVALUE -> listOf(if (v.boolValue.value) 1f else 0f)
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.KELVINVALUE -> listOf(v.kelvinValue.toFloat())
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.STROBE -> listOf(v.strobe.speed.toFloat())
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.LUMOSDOUBLE ->
            v.lumosDouble.doublevaluesMap.values.firstOrNull()?.let { listOf(it.toFloat()) }
        else -> null
    }?.let { list ->
        // Aufzählung: gewählten Eintrag über die Beschriftung wiederfinden
        if (kind != PropKind.ENUM) list else null
    } ?: if (kind == PropKind.ENUM) enumIndexOf(v, p)?.let { listOf(it.toFloat()) } else null
}

private fun enumIndexOf(v: LumosProtobuf.DeviceServiceTypes.DevicePropertyValue, p: DevicePropertyDescriptor): Int? {
    val captions: List<String> = when (v.valueCase) {
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.COLOR -> v.color.colorwheelMap.values.map { it.caption }
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.GOBO -> v.gobo.gobovaluesMap.values.map { it.caption.ifBlank { it.goboname } }
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.STRINGVALUE -> listOf(v.stringValue)
        LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.STROBE -> listOf(v.strobe.type.caption)
        else -> emptyList()
    }.filter { it.isNotBlank() }
    if (captions.isEmpty()) return null
    return p.type.enumValuesList.indexOfFirst { it.caption() in captions }.takeIf { it >= 0 }
}

/** Auswahlchips für Aufzählungen, optional mit Farbfeld. */
@Composable
private fun EnumChips(labels: List<String>, selected: Int, colors: List<Color?> = emptyList(), onPick: (Int) -> Unit) {
    val cols = if (isWide()) 4 else 3
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.indices.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { i ->
                    val sel = i == selected
                    Row(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Desk.Amber else Desk.Raised)
                            .clickableNoRipple { onPick(i) }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        colors.getOrNull(i)?.let { c ->
                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(c))
                            Box(Modifier.width(6.dp))
                        }
                        Text(
                            labels[i], maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sel) Color.Black else Desk.Text
                        )
                    }
                }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Fächern: Operator (Verlauf), Start- und Endwert, wie Gruppen behandelt werden, Gruppengröße.
 * Werte werden live gesendet; die Statuszeile darüber zeigt, was DMXControl übernommen hat.
 */
@Composable
private fun FanControl(s: UmbraSession, g: DeviceGroupDescriptor, p: DevicePropertyDescriptor) {
    val range = p.doubleRange()
    val operators by s.programmer.operators.collectAsStateWithLifecycle()
    var op by remember(g.id, p.id, operators) { mutableStateOf(operators.firstOrNull() ?: "") }
    var from by remember(g.id, p.id) { mutableStateOf(range.start.toFloat()) }
    var to by remember(g.id, p.id) { mutableStateOf(range.endInclusive.toFloat()) }
    var mode by remember(g.id, p.id) { mutableStateOf(de.sebi.dmxc3remote.net.GroupMode.CONCAT) }
    var size by remember(g.id, p.id) { mutableStateOf(1) }
    val send = { s.programmer.setFan(g.id, p, op, from.toDouble(), to.toDouble()) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (operators.isNotEmpty()) {
            Text("Verlauf", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(operators) { o -> FilterChip(selected = op == o, onClick = { op = o; send() }, label = { Text(o) }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
            Slider(from, { from = it; send() }, Modifier.weight(1f), valueRange = range.start.toFloat()..range.endInclusive.toFloat())
            Text("%.2f".format(from), style = MaterialTheme.typography.labelSmall.merge(Numeric), modifier = Modifier.width(48.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ende", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
            Slider(to, { to = it; send() }, Modifier.weight(1f), valueRange = range.start.toFloat()..range.endInclusive.toFloat())
            Text("%.2f".format(to), style = MaterialTheme.typography.labelSmall.merge(Numeric), modifier = Modifier.width(48.dp))
        }
        Text("Mehrere Gruppen", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(de.sebi.dmxc3remote.net.GroupMode.entries) { m ->
                FilterChip(selected = mode == m, onClick = { mode = m; s.programmer.setGroupMode(g.id, p.id, m) }, label = { Text(m.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gruppengröße", style = MaterialTheme.typography.labelMedium)
                Text("So viele Geräte bekommen jeweils denselben Wert", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
            }
            androidx.compose.material3.TextButton(onClick = { size = (size - 1).coerceAtLeast(1); s.programmer.setFanGroupSize(g.id, p.id, size) }, enabled = size > 1) { Text("−") }
            Text("$size", style = MaterialTheme.typography.titleMedium.merge(Numeric))
            androidx.compose.material3.TextButton(onClick = { size += 1; s.programmer.setFanGroupSize(g.id, p.id, size) }) { Text("+") }
        }
    }
}

/** Effekte auf dieser Eigenschaft: Parameter direkt einstellbar, einzeln entfernbar. */
@Composable
private fun EffectsPanel(s: UmbraSession, g: DeviceGroupDescriptor, p: DevicePropertyDescriptor, effects: List<String>, onAdd: () -> Unit) {
    val names by s.devices.effectNames.collectAsStateWithLifecycle()
    val params by s.programmer.effectParams.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        effects.forEach { id ->
            LaunchedEffect(id) { if (params[id] == null) s.programmer.loadEffectParams(id) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EffectIcon(names[id] ?: "", Modifier.size(24.dp), Desk.Amber)
                Text(names[id] ?: "Effekt", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                androidx.compose.material3.TextButton(onClick = { s.devices.removeEffect(g.id, p.id, id) }) { Text("Entfernen") }
            }
            val list = params[id]
            when {
                list == null -> Text("Parameter werden geladen …", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                list.isEmpty() -> Text("Dieser Effekt hat keine einstellbaren Parameter.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                else -> list.filter { it.isEditable() }.sortedBy { it.desc.saveOrder }.forEach { prm ->
                    ParamEditor(prm) { v -> s.programmer.setEffectParam(id, prm.desc, v) }
                }
            }
            HorizontalDivider(color = Desk.Line)
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Effekt hinzufügen") }
    }
}

/** Effektauswahl als Raster mit Symbolen, gruppiert wie in DMXControl (Effect 1D, Effect 2D, Filter …). */
@Composable
private fun EffectDialog(s: UmbraSession, g: DeviceGroupDescriptor, p: DevicePropertyDescriptor, onClose: () -> Unit) {
    val all by s.devices.attachables.collectAsStateWithLifecycle()
    val groups = remember(all) {
        all.groupBy { it.attachableGroup.ifBlank { it.type.ifBlank { "Weitere" } } }
            .toList()
            .sortedBy { (k, _) ->
                when {
                    k.contains("1D", true) -> 0
                    k.contains("2D", true) -> 1
                    k.contains("filter", true) -> 3
                    else -> 2
                }
            }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Effekt für ${p.displayName.ifBlank { p.name }}") },
        text = {
            if (all.isEmpty()) {
                Text("DMXControl hat keine Effekte gemeldet.", color = Desk.TextDim)
            } else {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    androidx.compose.foundation.lazy.grid.GridCells.Adaptive(84.dp),
                    Modifier.height(460.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    groups.forEach { (grp, list) ->
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(grp, style = MaterialTheme.typography.labelMedium, color = Desk.Amber, modifier = Modifier.padding(top = 6.dp))
                        }
                        items(list.size) { i ->
                            val a = list[i]
                            val name = a.displayName.ifBlank { a.name }
                            Column(
                                Modifier.clip(RoundedCornerShape(10.dp)).background(Desk.Raised)
                                    .clickableNoRipple { s.devices.applyEffect(g.id, p.id, a); onClose() }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                EffectIcon(a.name + " " + a.displayName, Modifier.size(36.dp), Desk.Text)
                                Text(
                                    name, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Schließen") } }
    )
}

// ============================================================== Konsole
@Composable
fun ConsoleScreen(s: UmbraSession) {
    val m = s.commandLine
    val current by m.current.collectAsStateWithLifecycle()
    val history by m.history.collectAsStateWithLifecycle()
    val notes by s.notifications.items.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModuleError(err)
        Text(if (current.isBlank()) "Eingabezeile leer" else "Eingabezeile: $current", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input, { input = it }, label = { Text("Befehl") }, singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) { m.execute(input); input = "" }
                })
            )
            Button(onClick = { if (input.isNotBlank()) { m.execute(input); input = "" } }) { Text("Ausführen") }
        }
        Text("Verlauf", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f)) {
            items(history.reversed()) { h ->
                val col = when (h.status) {
                    CommandLineHistoryItem.EItemStatus.Error -> MaterialTheme.colorScheme.error
                    CommandLineHistoryItem.EItemStatus.Warning -> Color(0xFFFFB300)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Column(Modifier.padding(vertical = 2.dp)) {
                    Text(h.commandLine, fontFamily = FontFamily.Monospace, color = col)
                    val msg = h.message.text()
                    if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
        Text("Meldungen", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f)) {
            items(notes) { n ->
                Text(
                    n.text,
                    color = if (n.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
