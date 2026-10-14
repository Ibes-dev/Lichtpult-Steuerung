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
import de.sebi.dmxc3remote.net.colorMax
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
private fun PropertyPane(
    s: UmbraSession,
    group: DeviceGroupDescriptor?,
    presets: List<Pair<String, String>>,
    presetErr: String?,
    modifier: Modifier
) {
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
            items(group.propertiesList) { p -> PropertyControl(s, group, p) }
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
private fun PropertyControl(s: UmbraSession, g: DeviceGroupDescriptor, p: DevicePropertyDescriptor) {
    val key = "${g.id}:${p.id}"
    val dm = s.devices
    val label = p.displayName.ifBlank { p.name.ifBlank { p.id } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Desk.Panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        run {
            when (p.kind()) {
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
                PropKind.UNSUPPORTED -> Text(
                    "$label: Dieser Eigenschaftstyp (${p.type.name.ifBlank { p.devicePropertyTypeAQ.substringBefore(',').substringAfterLast('.') }}) lässt sich in der App noch nicht einstellen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Desk.TextDim
                )
            }
        }
    }
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
