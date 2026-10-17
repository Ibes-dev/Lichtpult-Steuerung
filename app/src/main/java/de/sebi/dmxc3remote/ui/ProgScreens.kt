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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
fun ProgrammerScreen(s: UmbraSession) {
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

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                group?.name?.ifBlank { null } ?: "Keine Gruppe gewählt",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(onClick = dm::clearProgrammer) { Text("Programmer leeren") }
        }
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
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                items(sortedGroups, key = { it.id }) { g ->
                    FilterChip(selected = selected == g.id, onClick = { select(g.id) }, label = { Text(g.name.ifBlank { "Gruppe ${g.number}" }, maxLines = 1) })
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
        val list = group?.propertiesList ?: emptyList()
        if (!compact || showAll) list
        else list.mapNotNull { p ->
            val k = p.kind(serverValue(group!!.id, p, progValues, fannedMap)?.valueCase)
            p.importance(k)?.let { it to p }
        }.sortedBy { it.first }.map { it.second }
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (group == null) {
            item {
                Text(
                    "Gerätegruppe wählen, um Dimmer, Farbe und Position zu setzen. Presets wirken ohne Auswahl auf alle enthaltenen Geräte.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Desk.TextDim
                )
            }
        } else {
            items(props) { p -> PropertyControl(s, group, p, compact && !showAll) }
            if (compact) item {
                androidx.compose.material3.TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Nur wichtige Funktionen" else "Alle ${group.propertiesCount} Funktionen anzeigen")
                }
            }
        }
        item {
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
    // Rücklesen: Wert aus dem DMXControl-Programmer übernehmen (auch Änderungen vom PC)
    LaunchedEffect(server) { server?.let { v -> fromServer(v, p, kind)?.let { localValues[key] = it } } }
    var effectDialog by remember { mutableStateOf(false) }
    var fanOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Desk.Panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        run {
            when (kind) {
                PropKind.DOUBLE -> {
                    val range = p.doubleRange()
                    val v = localValues[key]?.firstOrNull() ?: range.start.toFloat()
                    Text("$label  ${"%.2f".format(v)}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = v,
                        onValueChange = { nv ->
                            localValues[key] = listOf(nv)
                            dm.setDouble(g.id, p.id, nv.toDouble())
                        },
                        valueRange = range.start.toFloat()..range.endInclusive.toFloat()
                    )
                }
                PropKind.COLOR -> {
                    val max = p.colorMax()
                    val rgb = localValues[key] ?: listOf(1f, 1f, 1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                .background(Color(rgb[0], rgb[1], rgb[2]))
                        )
                    }
                    listOf("R", "G", "B").forEachIndexed { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c, modifier = Modifier.widthIn(min = 16.dp))
                            Slider(
                                value = rgb[i],
                                onValueChange = { nv ->
                                    val next = rgb.toMutableList().also { it[i] = nv }
                                    localValues[key] = next
                                    dm.setColor(g.id, p.id, next[0] * max, next[1] * max, next[2] * max)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            Color.White, Color.Red, Color(0xFFFF8000), Color.Yellow, Color.Green,
                            Color.Cyan, Color.Blue, Color.Magenta
                        ).forEach { c ->
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(c)
                                    .then(
                                        Modifier.clickableNoRipple {
                                            localValues[key] = listOf(c.red, c.green, c.blue)
                                            dm.setColor(g.id, p.id, c.red * max, c.green * max, c.blue * max)
                                        }
                                    )
                            )
                        }
                    }
                }
                PropKind.POSITION -> {
                    val r = p.positionRange()
                    val xy = localValues[key] ?: listOf(0.5f, 0.5f)
                    val pan = r.pan.start + xy[0] * (r.pan.endInclusive - r.pan.start)
                    val tilt = r.tilt.start + xy[1] * (r.tilt.endInclusive - r.tilt.start)
                    Text("$label  ${"Pan %.1f  Tilt %.1f".format(pan, tilt)}", style = MaterialTheme.typography.labelLarge)
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
                        Modifier.fillMaxWidth().aspectRatio(1.6f)
                    )
                }
                PropKind.BOOL -> {
                    val on = (localValues[key]?.firstOrNull() ?: 0f) > 0.5f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = { c ->
                            localValues[key] = listOf(if (c) 1f else 0f)
                            dm.setBool(g.id, p.id, c)
                        })
                    }
                }
                PropKind.KELVIN -> {
                    val r = p.kelvinRange()
                    val v = localValues[key]?.firstOrNull() ?: ((r.start + r.endInclusive) / 2).toFloat()
                    Text("$label  ${v.toInt()} K", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = v.coerceIn(r.start.toFloat(), r.endInclusive.toFloat()),
                        onValueChange = { nv -> localValues[key] = listOf(nv); dm.setKelvin(g.id, p.id, nv.toDouble()) },
                        valueRange = r.start.toFloat()..r.endInclusive.toFloat()
                    )
                }
                PropKind.STROBE -> {
                    val r = p.strobeRange()
                    val v = localValues[key]?.firstOrNull() ?: r.start.toFloat()
                    val types = p.type.enumValuesList.filter {
                        it.valueCase == LumosProtobuf.ValueTypes.IEnumObjectData.ValueCase.ENUMSTRING
                    }
                    var sel by remember(key) { mutableStateOf(types.firstOrNull()?.enumString) }
                    Text("$label  ${"%.1f".format(v)}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = v.coerceIn(r.start.toFloat(), r.endInclusive.toFloat()),
                        onValueChange = { nv -> localValues[key] = listOf(nv); dm.setStrobe(g.id, p.id, nv.toDouble(), sel) },
                        valueRange = r.start.toFloat()..r.endInclusive.toFloat()
                    )
                    if (types.isNotEmpty()) EnumChips(types.map { it.caption() }, types.indexOfFirst { it.enumString == sel }) { i ->
                        sel = types[i].enumString
                        dm.setStrobe(g.id, p.id, v.toDouble(), sel)
                    }
                }
                PropKind.STRING -> {
                    var text by remember(key) { mutableStateOf("") }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { dm.setString(g.id, p.id, text) }) { Text("Setzen") }
                    }
                }
                PropKind.MULTIDOUBLE -> {
                    val r = p.multiRange()
                    val v = localValues[key]?.firstOrNull() ?: r.start.toFloat()
                    Text("$label  ${"%.2f".format(v)}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = v.coerceIn(r.start.toFloat(), r.endInclusive.toFloat()),
                        onValueChange = { nv -> localValues[key] = listOf(nv); dm.setMultiDouble(g.id, p.id, p.multiKeys(), nv.toDouble()) },
                        valueRange = r.start.toFloat()..r.endInclusive.toFloat()
                    )
                    if (p.multiKeys().size > 1) Text("Wirkt auf alle ${p.multiKeys().size} Teilwerte gleich", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                }
                PropKind.ENUM -> {
                    val entries = p.type.enumValuesList
                    val selIdx = localValues[key]?.firstOrNull()?.toInt() ?: -1
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    EnumChips(entries.map { it.caption() }, selIdx, entries.map { e ->
                        if (e.valueCase == LumosProtobuf.ValueTypes.IEnumObjectData.ValueCase.ENUMCOLOR && e.enumColor.hasColor())
                            Color(e.enumColor.color.r, e.enumColor.color.g, e.enumColor.color.b) else null
                    }) { i ->
                        localValues[key] = listOf(i.toFloat())
                        dm.setEnum(g.id, p, entries[i])
                    }
                }
                PropKind.UNSUPPORTED -> Text(
                    "$label: Typ ${p.type.name.ifBlank { p.devicePropertyTypeAQ.substringBefore(',').substringAfterLast('.') }} " +
                        "(${p.boundCase()?.name ?: "ohne Grenzen"}) lässt sich in der App noch nicht einstellen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Desk.TextDim
                )
            }
        }
        val fanned = fannedMap[key]
        // "gefächert" nur, wenn die Geräte wirklich unterschiedliche Werte haben
        val reallyFanned = fanned != null && fanned.fannedValuesList.map { it.toByteString() }.distinct().size > 1
        if (!compact || server != null) Text(
            when {
                reallyFanned -> "Im Programmer gefächert" + fanned!!.fannedValuesList.takeIf { l ->
                    l.all { it.valueCase == LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.DOUBLEVALUE }
                }?.let { l -> ": " + l.joinToString(" … ") { "%.2f".format(it.doubleValue) } }.orEmpty()
                server != null -> "Im Programmer"
                else -> "Nicht im Programmer"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (server != null) Desk.Amber else Desk.TextDim
        )
        if (!compact && kind != PropKind.UNSUPPORTED) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.TextButton(onClick = { effectDialog = true }) { Text("Effekt …") }
                if (!appliedMap[key].isNullOrEmpty()) {
                    androidx.compose.material3.TextButton(onClick = { dm.removeEffects(g.id, p.id) }) { Text("Effekte entfernen") }
                }
                if (kind == PropKind.DOUBLE) {
                    androidx.compose.material3.TextButton(onClick = { fanOpen = !fanOpen }) { Text(if (fanOpen) "Fächern schließen" else "Fächern") }
                }
            }
        }
        if (fanOpen && kind == PropKind.DOUBLE) FanControl(s, g, p)
    }
    if (effectDialog) EffectDialog(s, g, p) { effectDialog = false }
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

@Composable
private fun FanControl(s: UmbraSession, g: DeviceGroupDescriptor, p: DevicePropertyDescriptor) {
    val range = p.doubleRange()
    var from by remember(g.id, p.id) { mutableStateOf(range.start.toFloat()) }
    var to by remember(g.id, p.id) { mutableStateOf(range.endInclusive.toFloat()) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Surface).padding(8.dp)
    ) {
        Text("Von ${"%.2f".format(from)} bis ${"%.2f".format(to)} über die Geräte der Gruppe", style = MaterialTheme.typography.labelMedium)
        Slider(from, { from = it; s.devices.setFanned(g.id, p, from.toDouble(), to.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat())
        Slider(to, { to = it; s.devices.setFanned(g.id, p, from.toDouble(), to.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat())
        Text(
            "Ungeprüft, ob DMXControl dieses Format annimmt. Erscheint oben \"Im Programmer gefächert\", hat es geklappt.",
            style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
        )
    }
}

@Composable
private fun EffectDialog(s: UmbraSession, g: DeviceGroupDescriptor, p: DevicePropertyDescriptor, onClose: () -> Unit) {
    val all by s.devices.attachables.collectAsStateWithLifecycle()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Effekt für ${p.displayName.ifBlank { p.name }}") },
        text = {
            if (all.isEmpty()) {
                Text("DMXControl hat keine Effekte gemeldet.", color = Desk.TextDim)
            } else {
                LazyColumn(Modifier.height(400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(all) { a ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Raised)
                                .clickableNoRipple { s.devices.applyEffect(g.id, p.id, a); onClose() }
                                .padding(10.dp)
                        ) {
                            Text(a.displayName.ifBlank { a.name })
                            if (a.attachableGroup.isNotBlank()) Text(a.attachableGroup, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
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
