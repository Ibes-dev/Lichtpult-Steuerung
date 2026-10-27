package de.sebi.dmxc3remote.ui

import LumosProtobuf.Cuelist.CuelistServiceTypes.CueAddFromProgrammerRequest
import LumosProtobuf.DependencyTypes.DependencyData
import LumosProtobuf.DependencyTypes.DependencyObject
import LumosProtobuf.DependencyTypes.DependencySolution
import LumosProtobuf.DeviceServiceTypes.DevicePropertyValue
import LumosProtobuf.ProgrammerServiceTypes.GroupState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.ProgOrigin
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.caption
import de.sebi.dmxc3remote.net.text

/**
 * Programmer als Arbeitsbereich:
 *  Kopf: Herkunft (geladener Cue/Preset), Live/Blind, Speichern, Rückgängig, Leeren
 *  Werte   – Gruppe wählen, Eigenschaften, Fächern, Effekte
 *  Inhalt  – alles, was im Programmer steht; antippen öffnet die Gruppe, einzeln entfernbar
 *  Presets – aufrufen, zum Bearbeiten laden, Programmer in Preset speichern
 *  Cues    – Cue zum Bearbeiten laden, Programmer als Cue speichern
 */
@Composable
fun ProgrammerScreen(s: UmbraSession) {
    val p = s.programmer
    val content by p.content.collectAsStateWithLifecycle()
    val origin by p.origin.collectAsStateWithLifecycle()
    val blind by p.blind.collectAsStateWithLifecycle()
    val pending by p.pending.collectAsStateWithLifecycle()
    val err by p.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var focusGroup by remember { mutableStateOf<String?>(null) }
    var saveOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var blindLoad by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        // ---------------------------------------------------------- Kopf (eine Zeile)
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    origin?.label ?: "Programmer",
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    (if (content.isEmpty()) "leer" else "${content.size} Werte") + " · " + if (blind) "blind" else "live",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (blind) Desk.Cyan else Desk.TextDim
                )
            }
            IconButton(onClick = { p.setBlind(!blind) }) {
                Icon(
                    if (blind) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (blind) "Blind: aus" else "Blind: ein",
                    tint = if (blind) Desk.Cyan else Desk.TextDim
                )
            }
            IconButton(onClick = p::undo) { Icon(Icons.Filled.Undo, contentDescription = "Rückgängig", tint = Desk.TextDim) }
            IconButton(onClick = { confirmClear = true }, enabled = content.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Programmer leeren", tint = if (content.isNotEmpty()) Desk.TextDim else Desk.Line)
            }
            IconButton(onClick = { saveOpen = true }, enabled = content.isNotEmpty()) {
                Icon(Icons.Filled.Save, contentDescription = "Speichern", tint = if (content.isNotEmpty()) Desk.Amber else Desk.Line)
            }
        }
        SmallTabs(
            listOf("Werte", if (content.isEmpty()) "Inhalt" else "Inhalt ${content.size}", "Presets", "Cues"),
            tab, { tab = it }
        )
        HorizontalDivider(color = Desk.Line)
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> ProgrammerValuesTab(s, focusGroup)
                1 -> ContentTab(s, content) { gid -> focusGroup = gid; tab = 0 }
                2 -> PresetsTab(s, blindLoad) { blindLoad = it }
                else -> CuesTab(s, blindLoad) { blindLoad = it }
            }
        }
    }

    if (saveOpen) SaveDialog(s, origin) { saveOpen = false }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Programmer leeren?") },
        text = { Text("Alle ${content.size} Werte werden aus dem Programmer entfernt. Gespeicherte Cues und Presets bleiben unverändert.") },
        confirmButton = { TextButton(onClick = { p.clear(); confirmClear = false }) { Text("Leeren", color = Desk.Red) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } }
    )
    pending?.let { DependencyDialog(s, it.title, it.data) }
}

// ============================================================== Inhalt
/** Lesbare Kurzform eines Programmer-Werts. */
fun stateText(g: GroupState): String = when (g.valueCase) {
    GroupState.ValueCase.DPV -> dpvText(g.dpv)
    GroupState.ValueCase.FPV -> "gefächert" + (g.fpv.operator.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    GroupState.ValueCase.OBJECTVALUE -> g.objectValue.text()
    else -> ""
}

fun dpvText(v: DevicePropertyValue): String = when (v.valueCase) {
    DevicePropertyValue.ValueCase.DOUBLEVALUE -> "%.2f".format(v.doubleValue)
    DevicePropertyValue.ValueCase.COLOR -> v.color.colorwheelMap.values.firstOrNull()?.caption?.takeIf { it.isNotBlank() } ?: "Farbe"
    DevicePropertyValue.ValueCase.POSITION -> "Pan %.0f, Tilt %.0f".format(v.position.pan, v.position.tilt)
    DevicePropertyValue.ValueCase.BOOLVALUE -> if (v.boolValue.value) "an" else "aus"
    DevicePropertyValue.ValueCase.KELVINVALUE -> "%.0f K".format(v.kelvinValue)
    DevicePropertyValue.ValueCase.STROBE -> v.strobe.type.caption.ifBlank { "Strobe" } + " %.1f".format(v.strobe.speed)
    DevicePropertyValue.ValueCase.GOBO -> v.gobo.gobovaluesMap.values.joinToString { it.caption.ifBlank { it.goboname } }.ifBlank { "Gobo" }
    DevicePropertyValue.ValueCase.STRINGVALUE -> v.stringValue
    DevicePropertyValue.ValueCase.LUMOSDOUBLE -> v.lumosDouble.doublevaluesMap.values.joinToString { "%.2f".format(it) }
    else -> "gesetzt"
}

/** Farbe eines Werts für ein kleines Farbfeld, falls es eine ist. */
private fun dpvColor(v: DevicePropertyValue): Color? {
    if (v.valueCase != DevicePropertyValue.ValueCase.COLOR) return null
    val c = v.color
    val vis = if (c.hasVisualizationColor()) c.visualizationColor else if (c.hasCalculatedColor()) c.calculatedColor else null
    if (vis != null && vis.r + vis.g + vis.b > 0) return Color(vis.r, vis.g, vis.b)
    val m = maxOf(1.0, c.r, c.g, c.b)
    return Color((c.r / m).toFloat(), (c.g / m).toFloat(), (c.b / m).toFloat())
}

@Composable
private fun ContentTab(s: UmbraSession, content: List<GroupState>, onOpen: (String) -> Unit) {
    if (content.isEmpty()) {
        EmptyHint("Programmer leer", "Werte setzen unter \"Werte\" oder einen Cue bzw. ein Preset zum Bearbeiten laden.")
        return
    }
    val byGroup = remember(content) { content.groupBy { it.id to it.name.ifBlank { "Gruppe" } } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        byGroup.forEach { (grp, rows) ->
            item(key = grp.first) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Desk.Panel).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(grp.second, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onOpen(grp.first) }) { Text("Bearbeiten") }
                    }
                    rows.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpen(g.id) }
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val col = if (g.valueCase == GroupState.ValueCase.DPV) dpvColor(g.dpv) else null
                            if (col != null) Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(col))
                            Column(Modifier.weight(1f).padding(start = if (col != null) 8.dp else 0.dp)) {
                                Text(g.propertyName.ifBlank { g.propertyId }, style = MaterialTheme.typography.bodyMedium)
                                val extra = listOfNotNull(
                                    stateText(g).takeIf { it.isNotBlank() },
                                    g.fromPreset.takeIf { it.isNotBlank() }?.let { "aus Preset" },
                                    if (g.dirty) "geändert" else null
                                )
                                if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
                            }
                            IconButton(onClick = { s.programmer.remove(g.id, g.propertyId) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Aus Programmer entfernen", tint = Desk.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================== Presets
@Composable
private fun PresetsTab(s: UmbraSession, blindLoad: Boolean, onBlind: (Boolean) -> Unit) {
    val presets by s.presets.items.collectAsStateWithLifecycle()
    val content by s.programmer.content.collectAsStateWithLifecycle()
    val err by s.presets.error.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var storeTo by remember { mutableStateOf<Pair<String, String>?>(null) }
    val list = remember(presets, q) {
        presets.values.filter { q.isBlank() || it.name.contains(q.trim(), true) }.sortedBy { it.name.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactSearch(q, { q = it }, "Preset suchen", Modifier.weight(1f))
            FilterChip(selected = blindLoad, onClick = { onBlind(!blindLoad) }, label = { Text("blind laden") }, modifier = Modifier.padding(start = 8.dp))
        }
        if (list.isEmpty()) {
            EmptyHint("Keine Presets", if (q.isBlank()) "Im Projekt sind keine Presets angelegt." else "Kein Preset passt zur Suche.")
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(list, key = { it.presetId }) { pr ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Desk.Panel).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
                ) {
                    Text(pr.name.ifBlank { "Preset" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (pr.isPropertyPreset) "Eigenschafts-Preset" else "Universal-Preset") +
                            if (pr.entriesCount == 0) " · leer" else " · ${pr.entriesCount} Einträge",
                        style = MaterialTheme.typography.labelSmall, color = Desk.TextDim
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { s.presets.invoke(pr.presetId, null) }, enabled = pr.entriesCount > 0) { Text("Aufrufen") }
                        TextButton(onClick = { s.programmer.loadPreset(pr.presetId, pr.name, blindLoad) }) { Text("Bearbeiten") }
                        TextButton(onClick = { storeTo = pr.presetId to pr.name }, enabled = content.isNotEmpty()) { Text("Programmer übernehmen …") }
                    }
                }
            }
        }
    }
    storeTo?.let { (id, name) -> PresetStoreDialog(s, id, name) { storeTo = null } }
}

/** Programmer in ein vorhandenes Preset schreiben: vier Arten wie in DMXControl. */
@Composable
private fun PresetStoreDialog(s: UmbraSession, id: String, name: String, onClose: () -> Unit) {
    val options = listOf(
        Triple("Ersetzen", "Preset enthält danach genau den Programmer-Inhalt") { s.programmer.presetReplace(id, false, name) },
        Triple("Zusammenführen", "Werte aus dem Programmer überschreiben gleiche Einträge, der Rest bleibt") { s.programmer.presetMerge(id, false, name) },
        Triple("Anhängen", "Nur neue Einträge aus dem Programmer kommen hinzu") { s.programmer.presetMerge(id, true, name) },
        Triple("Abziehen", "Einträge, die im Programmer stehen, werden aus dem Preset entfernt") { s.programmer.presetReplace(id, true, name) }
    )
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("In \"$name\" speichern") },
        text = { OptionList(options.map { Triple(it.first, it.second) { it.third(); onClose() } }) },
        confirmButton = { TextButton(onClick = onClose) { Text("Abbrechen") } }
    )
}

@Composable
private fun OptionList(options: List<Triple<String, String, () -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (t, d, a) ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Desk.Raised).clickable(onClick = a).padding(12.dp)
            ) {
                Text(t, style = MaterialTheme.typography.titleSmall)
                Text(d, style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
            }
        }
    }
}

// ============================================================== Cues
@Composable
private fun CuesTab(s: UmbraSession, blindLoad: Boolean, onBlind: (Boolean) -> Unit) {
    val lists by s.cuelists.items.collectAsStateWithLifecycle()
    val content by s.programmer.content.collectAsStateWithLifecycle()
    var listId by rememberSaveable { mutableStateOf<String?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    val sorted = remember(lists, q) {
        lists.values.filter { q.isBlank() || it.name.contains(q.trim(), true) || it.number.toString() == q.trim() }.sortedBy { it.number }
    }
    val cl = listId?.let { lists[it] } ?: sorted.firstOrNull()
    var renameCue by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renameList by remember { mutableStateOf(false) }
    var newList by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactSearch(q, { q = it }, "Cuelist suchen", Modifier.weight(1f))
            FilterChip(selected = blindLoad, onClick = { onBlind(!blindLoad) }, label = { Text("blind laden") }, modifier = Modifier.padding(start = 8.dp))
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
            items(sorted, key = { it.id }) { c ->
                FilterChip(selected = cl?.id == c.id, onClick = { listId = c.id }, label = { Text("${c.number} ${c.name}".trim(), maxLines = 1) })
            }
        }
        if (cl == null) {
            EmptyHint("Keine Cuelist", "Im Projekt sind keine Cuelists angelegt.")
            return@Column
        }
        val cues = s.cuelists.cuesOf(cl.id)
        Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { s.programmer.storeToCue(cl.id, CueAddFromProgrammerRequest.EAddType.ADD, cues.size, "Neuer Cue in ${cl.name}") },
                enabled = content.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("Als neuen Cue anhängen", maxLines = 1) }
            OutlinedButton(onClick = { renameList = true }) { Text("Umbenennen") }
            OutlinedButton(onClick = { newList = true }) { Text("Neu") }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(cues.size) { pos ->
                val c = cues[pos]
                val nr = if (c.hasCueNumber() && c.cueNumber.numberCount > 0) c.cueNumber.numberList.joinToString(".") else "${pos + 1}"
                val label = "Cue $nr" + (c.name.takeIf { it.isNotBlank() }?.let { " „$it“" } ?: "")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (c.cueIndex == cl.index) Desk.Green.copy(alpha = 0.15f) else Desk.Panel)
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nr, style = MaterialTheme.typography.labelLarge.merge(Numeric), modifier = Modifier.width(44.dp))
                        Text(c.name.ifBlank { "Cue $nr" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { s.programmer.loadCue(cl.id, c.cueId, "$label (${cl.name})", blindLoad) }) { Text("Bearbeiten") }
                        TextButton(onClick = { renameCue = c.cueId to c.name }) { Text("Umbenennen") }
                        TextButton(
                            onClick = { s.programmer.storeToCue(cl.id, CueAddFromProgrammerRequest.EAddType.REPLACE, s.cuelists.indexOf(cl.id, pos), "$label ersetzt") },
                            enabled = content.isNotEmpty()
                        ) { Text("Ersetzen") }
                        TextButton(
                            onClick = { s.programmer.storeToCue(cl.id, CueAddFromProgrammerRequest.EAddType.MERGE, s.cuelists.indexOf(cl.id, pos), "In $label zusammengeführt") },
                            enabled = content.isNotEmpty()
                        ) { Text("Zusammenführen") }
                    }
                }
            }
        }
    }
    val clNow = cl
    if (newList) NewCuelistDialog(clNow?.let { it.id to it.name }, { name, copy -> s.cuelists.createCuelist(name, copy); newList = false }) { newList = false }
    if (renameList && clNow != null) RenameDialog("Cuelist umbenennen", clNow.name, { s.cuelists.renameCuelist(clNow.id, it); renameList = false }) { renameList = false }
    renameCue?.let { (id, name) ->
        if (clNow != null) RenameDialog("Cue umbenennen", name, { s.cuelists.renameCue(clNow.id, id, it); renameCue = null }) { renameCue = null }
    }
}

// ============================================================== Speichern
@Composable
private fun SaveDialog(s: UmbraSession, origin: ProgOrigin?, onClose: () -> Unit) {
    var step by remember { mutableStateOf(0) }       // 0 Auswahl, 1 neues Preset
    var clearAfter by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("Neues Preset") }
    var propertyPreset by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (step == 0) "Programmer speichern" else "Neues Preset") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 0) {
                    if (origin != null) {
                        OptionList(listOf(Triple("Zurück in ${origin.label}", "Änderungen in den geladenen ${if (origin is ProgOrigin.Cue) "Cue" else "Preset"} schreiben") {
                            s.programmer.storeToOrigin(clearAfter); onClose()
                        }))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Danach Programmer leeren", modifier = Modifier.weight(1f))
                            Switch(checked = clearAfter, onCheckedChange = { clearAfter = it })
                        }
                        HorizontalDivider(color = Desk.Line)
                    }
                    OptionList(listOf(
                        Triple("Als neues Preset", "Programmer-Inhalt als eigenes Preset anlegen") { step = 1 }
                    ))
                    Text(
                        "In einen bestimmten Cue oder ein bestehendes Preset speichern: unter \"Cues\" bzw. \"Presets\".",
                        style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                    )
                } else {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !propertyPreset, onClick = { propertyPreset = false }, label = { Text("Universal") })
                        FilterChip(selected = propertyPreset, onClick = { propertyPreset = true }, label = { Text("Eigenschaft") })
                    }
                    Text(
                        if (propertyPreset) "Eigenschafts-Preset: speichert nur die Eigenschaften und lässt sich auf andere Gruppen anwenden."
                        else "Universal-Preset: speichert Werte samt Geräten.",
                        style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                    )
                }
            }
        },
        confirmButton = {
            if (step == 1) TextButton(enabled = name.isNotBlank(), onClick = { s.programmer.createPreset(name.trim(), propertyPreset); onClose() }) { Text("Anlegen") }
            else TextButton(onClick = onClose) { Text("Abbrechen") }
        },
        dismissButton = { if (step == 1) TextButton(onClick = { step = 0 }) { Text("Zurück") } }
    )
}

/** Rückfrage von DMXControl beim Speichern: je Abhängigkeit eine Lösung wählen. */
@Composable
private fun DependencyDialog(s: UmbraSession, title: String, data: DependencyData) {
    val objects = remember(data) {
        fun flat(d: DependencyData): List<DependencyObject> = d.dependenciesList + d.childrenList.flatMap { flat(it) }
        flat(data)
    }
    var choice by remember(data) {
        mutableStateOf(objects.associate { o ->
            o.id to (if (o.hasSolution()) o.solution else o.possibleSolutionsList.firstOrNull() ?: DependencySolution.getDefaultInstance())
        })
    }
    AlertDialog(
        onDismissRequest = s.programmer::cancelPending,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DMXControl fragt nach, wie mit betroffenen Objekten umgegangen werden soll:", style = MaterialTheme.typography.bodySmall)
                objects.forEach { o ->
                    Column {
                        Text(o.name.ifBlank { o.type }, style = MaterialTheme.typography.titleSmall)
                        o.possibleSolutionsList.forEach { sol ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { choice = choice + (o.id to sol) }.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.RadioButton(selected = choice[o.id]?.id == sol.id, onClick = { choice = choice + (o.id to sol) })
                                Text(sol.description.ifBlank { sol.solution }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { s.programmer.resolvePending(choice) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = s.programmer::cancelPending) { Text("Abbrechen") } }
    )
}
