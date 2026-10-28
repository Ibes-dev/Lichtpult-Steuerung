package de.sebi.dmxc3remote.ui

import LumosProtobuf.Audio.AudioServiceTypes.ControlPlayerThreadRequest
import LumosProtobuf.Audio.AudioServiceTypes.PlayerThreadDescriptor
import LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest
import LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistDescriptor
import LumosProtobuf.Cuelist.CuelistServiceTypes.ESceneListState
import LumosProtobuf.Executor.ExecutorServiceTypes.ExecutorDescriptor
import LumosProtobuf.Macro.MacroServiceTypes.MacroDescriptor
import LumosProtobuf.Master.MasterServiceCRUDTypes.MasterActionRequest
import LumosProtobuf.Master.MasterServiceTypes.EMasterType
import LumosProtobuf.Master.MasterServiceTypes.MasterDescriptor
import LumosProtobuf.Timecode.TimecodeServiceCRUDTypes.ETimecodeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.UmbraSession
import kotlin.math.roundToInt

private const val ALL = "__all__"

fun pct(v: Double) = "${(v * 100).roundToInt()} %"

fun fmtTime(ms: Long): String {
    if (ms < 0) return "-" + fmtTime(-ms)
    val t = ms / 100
    return "%d:%02d.%d".format(t / 600, (t / 10) % 60, t % 10)
}

@Composable
fun stripWidth() = if (isWide()) 104.dp else 84.dp

@Composable
private fun TransportButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Desk.Raised),
        modifier = Modifier.size(44.dp)
    ) { Icon(icon, contentDescription = desc) }
}

// ============================================================== Executoren
@Composable
fun ExecutorScreen(s: UmbraSession) {
    val m = s.executors
    val executors by m.items.collectAsStateWithLifecycle()
    val pages by m.pages.collectAsStateWithLifecycle()
    val ctx by s.userContext.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()

    var couple by rememberSaveable { mutableStateOf(false) }
    var localPage by rememberSaveable { mutableStateOf<String?>(null) }
    val serverPage = ctx?.selectedExecutorPage?.ifBlank { null }
    val pageId = (if (couple) serverPage else localPage) ?: localPage ?: serverPage ?: ALL

    val sortedPages = remember(pages) { pages.values.sortedBy { it.pageNumber } }
    val visible: List<ExecutorDescriptor> = remember(executors, pages, pageId) {
        pages[pageId]?.executorIdsList?.mapNotNull { executors[it] } ?: executors.values.sortedBy { it.number }
    }

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        // Seitenwahl: Chips statt TabRow (deren Indikator crasht bei wechselnder Tab-Anzahl)
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
        ) {
            item {
                FilterChip(
                    selected = couple,
                    onClick = { couple = !couple },
                    leadingIcon = { Icon(Icons.Filled.Link, null, Modifier.size(16.dp)) },
                    label = { Text("Mit DMXC koppeln") }
                )
            }
            item { FilterChip(selected = pageId == ALL, onClick = { localPage = ALL }, label = { Text("Alle") }) }
            items(sortedPages, key = { it.id }) { p ->
                FilterChip(
                    selected = pageId == p.id,
                    onClick = {
                        localPage = p.id
                        if (couple) m.selectPage(p.id)
                    },
                    label = { Text(p.name.ifBlank { "Seite ${p.pageNumber}" }, maxLines = 1) }
                )
            }
        }
        if (visible.isEmpty()) {
            EmptyHint("Keine Executoren", "Diese Seite enthält keine Executoren. Andere Seite wählen oder in DMXControl Executoren anlegen.")
        } else {
            val w = stripWidth()
            LazyRow(
                Modifier.fillMaxSize().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) {
                items(visible) { ex -> ExecutorStrip(ex, w, s) }
            }
        }
    }
}

@Composable
private fun ExecutorStrip(ex: ExecutorDescriptor, w: androidx.compose.ui.unit.Dp, s: UmbraSession) {
    val m = s.executors
    val accent = if (!ex.hasColor() || (ex.color.r == 0 && ex.color.g == 0 && ex.color.b == 0)) Desk.Amber
    else Color(ex.color.r, ex.color.g, ex.color.b)
    ChannelStrip("Exec ${ex.number}", ex.name.ifBlank { ex.faderDisplayName.ifBlank { "Ohne Namen" } }, accent, w) {
        val labels = listOf(ex.button1DisplayName, ex.button2DisplayName, ex.button3DisplayName, ex.button4DisplayName)
        val keys = listOf(ex.button1Key, ex.button2Key, ex.button3Key, ex.button4Key)
        val active = listOf(ex.button1Active, ex.button2Active, ex.button3Active, ex.button4Active)
        (0..3).filter { labels[it].isNotBlank() || keys[it].isNotBlank() }.ifEmpty { listOf(0) }.forEach { i ->
            ExecButton(labels[i].ifBlank { keys[i].ifBlank { "Taste ${i + 1}" } }, active[i], accent) { p ->
                m.setButton(ex.id, i + 1, p)
            }
        }
        Text(pct(ex.faderPosition), style = MaterialTheme.typography.labelMedium.merge(Numeric))
        VerticalFader(
            ex.faderPosition.toFloat().coerceIn(0f, 1f),
            { v -> m.setFader(ex.id, v.toDouble()) },
            accent,
            Modifier.fillMaxWidth().weight(1f)
        )
    }
}

// ============================================================== Cuelists
@Composable
fun CuelistScreen(s: UmbraSession, vm: AppViewModel) {
    val m = s.cuelists
    val goId by vm.goCuelistId.collectAsStateWithLifecycle()
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val lists by m.items.collectAsStateWithLifecycle()
    val groups by m.groups.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val compact by vm.cuelistCompact.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val visible = remember(lists, groups, group, query) {
        val g = group?.let { groups[it] }
        val q = query.trim().lowercase()
        (if (g != null) g.cuelistIdsList.mapNotNull { lists[it] } else lists.values.toList())
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.number.toString() == q }
            .sortedBy { it.number }
    }

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) {
                item { FilterChip(selected = group == null, onClick = { group = null }, label = { Text("Alle") }) }
                items(groups.values.sortedBy { it.number }, key = { it.id }) { g ->
                    FilterChip(selected = group == g.id, onClick = { group = g.id }, label = { Text(g.name, maxLines = 1) })
                }
            }
            OutlinedButton(onClick = m::stopAll, modifier = Modifier.padding(end = 10.dp)) { Text("Alle stoppen") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactSearch(query, { query = it }, "Cuelist suchen", Modifier.weight(1f))
        }
        if (visible.isEmpty()) {
            EmptyHint("Keine Cuelists", if (query.isBlank()) "Im Projekt sind keine Cuelists angelegt oder die gewählte Gruppe ist leer." else "Keine Cuelist passt zur Suche.")
        } else if (compact) {
            // Schlanke Zeilen für viele Cuelists: Status, Nummer, Name, aktueller Cue, Go, Stopp
            LazyVerticalGrid(
                GridCells.Adaptive(380.dp),
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(visible, key = { it.id }) { cl ->
                    CuelistRow(
                        cl, s,
                        isGo = goId == cl.id,
                        onDetails = { detailId = cl.id }
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(320.dp),
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible) { cl ->
                    CuelistCard(
                        cl, s,
                        isGo = goId == cl.id,
                        onGoTarget = { vm.setGoCuelist(if (goId == cl.id) "" else cl.id) },
                        onDetails = { detailId = cl.id }
                    )
                }
            }
        }
    }
    val det = detailId?.let { lists[it] }
    if (det != null) CuelistDetail(det, s) { detailId = null }

}

/** Kompakte Zeile (ca. 52 dp hoch) statt Karte. */
@Composable
private fun CuelistRow(cl: CuelistDescriptor, s: UmbraSession, isGo: Boolean, onDetails: () -> Unit) {
    val m = s.cuelists
    val stateColor = when (cl.state) {
        ESceneListState.RUNNING -> Desk.Green
        ESceneListState.PAUSED -> Desk.Amber
        else -> Desk.Line
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp)).background(Desk.Panel).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(stateColor))
        Text(
            "${cl.number}",
            style = MaterialTheme.typography.labelMedium.merge(Numeric),
            color = if (isGo) Desk.Amber else Desk.TextDim,
            modifier = Modifier.width(44.dp).padding(start = 8.dp)
        )
        Column(Modifier.weight(1f).clickable(onClick = onDetails)) {
            Text(cl.name.ifBlank { "Cuelist ${cl.number}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cl.state != ESceneListState.STOPPED) {
                Text(cl.cueName(cl.index), style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        androidx.compose.material3.IconButton(onClick = { m.action(cl.id, CuelistActionRequest.EAction.STOP) }) {
            Icon(Icons.Filled.Stop, contentDescription = "Stopp", tint = Desk.TextDim)
        }
        Box(
            Modifier.width(64.dp).fillMaxHeight().background(Desk.Green).clickable { m.action(cl.id, CuelistActionRequest.EAction.GO) },
            contentAlignment = Alignment.Center
        ) { Text("Go", color = Color.Black, style = MaterialTheme.typography.titleSmall) }
    }
}

@Composable
private fun CuelistDetail(cl: CuelistDescriptor, s: UmbraSession, onClose: () -> Unit) {
    val progress by s.cuelists.progress.collectAsStateWithLifecycle()
    val p = progress[cl.id]
    var renameList by remember { mutableStateOf(false) }
    var renameCue by remember { mutableStateOf<Pair<String, String>?>(null) }   // cueId to Name
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cl.name.ifBlank { "Cuelist ${cl.number}" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.material3.IconButton(onClick = { renameList = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Cuelist umbenennen", tint = Desk.TextDim)
                }

            }
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexedCompat(s.cuelists.cuesOf(cl.id)) { pos, c ->
                    val idx = c.cueIndex
                    val st = p?.cueProgressStateMap?.get(idx)
                    val current = idx == cl.index
                    val next = idx == cl.nextIndex
                    val nr = if (c.hasCueNumber() && c.cueNumber.numberCount > 0) c.cueNumber.numberList.joinToString(".") else "${pos + 1}"
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (current) Desk.Green.copy(alpha = 0.18f) else Desk.Raised)
                            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nr, style = MaterialTheme.typography.labelLarge.merge(Numeric), modifier = Modifier.width(44.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifBlank { "Cue $nr" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (current) Text("aktiv", style = MaterialTheme.typography.labelSmall, color = Desk.Green)
                                else if (next) Text("als Nächstes", style = MaterialTheme.typography.labelSmall, color = Desk.Amber)
                            }
                            androidx.compose.material3.IconButton(onClick = { renameCue = c.cueId to c.name }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = "Cue umbenennen", tint = Desk.TextDim, modifier = Modifier.size(18.dp))
                            }
                            androidx.compose.material3.TextButton(onClick = { s.cuelists.load(cl.id, pos) }) { Text("Load") }
                            androidx.compose.material3.TextButton(onClick = { s.cuelists.goTo(cl.id, pos) }) { Text("Go To") }
                        }
                        if (st != null && st.progress > 0.0 && st.progress < 1.0) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { st.progress.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp),
                                color = Desk.Green
                            )
                            val left = maxOf(st.fadeTimeLeft, st.delayTimeLeft)
                            if (left > 0) Text("noch ${fmtTime(left)}", style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.TextDim)
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Schließen") } },
        dismissButton = {
            Text("Go To: zu diesem Cue springen. Load: als nächsten Cue vorbereiten.", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
        }
    )
    if (renameList) RenameDialog("Cuelist umbenennen", cl.name, { s.cuelists.renameCuelist(cl.id, it); renameList = false }) { renameList = false }

    renameCue?.let { (id, name) ->
        RenameDialog("Cue umbenennen", name, { s.cuelists.renameCue(cl.id, id, it); renameCue = null }) { renameCue = null }
    }
}

private fun CuelistDescriptor.cueName(index: Int): String {
    if (index < 0 || cuesCount == 0) return "–"
    val c = cuesList.firstOrNull { it.cueIndex == index } ?: cuesList.getOrNull(index) ?: return "Cue ${index + 1}"
    val nr = if (c.hasCueNumber() && c.cueNumber.numberCount > 0) c.cueNumber.numberList.joinToString(".") else "${index + 1}"
    return if (c.name.isBlank()) "Cue $nr" else "$nr  ${c.name}"
}

@Composable
private fun CuelistCard(cl: CuelistDescriptor, s: UmbraSession, isGo: Boolean, onGoTarget: () -> Unit, onDetails: () -> Unit) {
    val m = s.cuelists
    var local by remember(cl.id) { mutableFloatStateOf(cl.intensity.toFloat()) }
    var dragging by remember(cl.id) { mutableStateOf(false) }
    val (stateText, stateColor) = when (cl.state) {
        ESceneListState.RUNNING -> "Läuft" to Desk.Green
        ESceneListState.PAUSED -> "Pausiert" to Desk.Amber
        else -> "Gestoppt" to Desk.TextDim
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Desk.Panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onDetails)) {
                Text(cl.name.ifBlank { "Cuelist ${cl.number}" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Nr. ${cl.number}, Cues anzeigen", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
            }
            StatusPill(stateText, stateColor)
            androidx.compose.material3.IconButton(onClick = onGoTarget) {
                Icon(
                    if (isGo) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Go-Ziel für externe Tasten",
                    tint = if (isGo) Desk.Amber else Desk.TextDim
                )
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Surface).padding(10.dp)) {
            Text("Aktuell", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
            Text(cl.cueName(cl.index), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Als Nächstes", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(top = 4.dp))
            Text(cl.cueName(cl.nextIndex), style = MaterialTheme.typography.bodyMedium, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Steuerung wie im Cuelist-Editor von DMXControl: Go, Go Back, Go Next, Pause, Stop, Go To / Load
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { m.action(cl.id, CuelistActionRequest.EAction.GO) },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Desk.Green, contentColor = Color.Black)
            ) { Text("Go", style = MaterialTheme.typography.titleMedium) }
            TransportButton(Icons.Filled.Pause, "Pause") { m.action(cl.id, CuelistActionRequest.EAction.PAUSE) }
            TransportButton(Icons.Filled.Stop, "Stopp") { m.action(cl.id, CuelistActionRequest.EAction.STOP) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { m.action(cl.id, CuelistActionRequest.EAction.GO_BACK) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.SkipPrevious, null, Modifier.size(18.dp)); Text(" Go Back", maxLines = 1)
            }
            OutlinedButton(onClick = { m.action(cl.id, CuelistActionRequest.EAction.GO_NEXT) }, modifier = Modifier.weight(1f)) {
                Text("Go Next ", maxLines = 1); Icon(Icons.Filled.SkipNext, null, Modifier.size(18.dp))
            }
        }
        OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) {
            Text("Go To / Load: Cue auswählen", maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Intensität", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.width(72.dp))
            DeskFader(
                if (dragging) local else cl.intensity.toFloat().coerceIn(0f, 1f),
                { v -> dragging = true; local = v; m.setIntensity(cl.id, v.toDouble()) },
                Desk.Green,
                Modifier.weight(1f).height(36.dp),
                vertical = false,
                onRelease = { dragging = false }
            )
            Text(
                pct(if (dragging) local.toDouble() else cl.intensity),
                style = MaterialTheme.typography.labelMedium.merge(Numeric),
                modifier = Modifier.width(48.dp).padding(start = 6.dp)
            )
        }
    }
}

// ============================================================== Master
fun EMasterType.label(): String = when (this) {
    EMasterType.GRAND -> "Grand Master"
    EMasterType.GROUP -> "Gruppe"
    EMasterType.DEVICE -> "Gerät"
    EMasterType.SPECIAL -> "Spezial"
    EMasterType.PARAMETER -> "Parameter"
    EMasterType.COLOR -> "Farbe"
    EMasterType.POSITION -> "Position"
    EMasterType.SPEED -> "Geschwindigkeit"
    else -> "Master"
}

// ============================================================== Macros
@Composable
fun MacroScreen(s: UmbraSession) {
    val m = s.macros
    val macros by m.items.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    val sorted = remember(macros) { macros.values.sortedBy { it.number } }
    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        if (sorted.isEmpty()) {
            EmptyHint("Keine Macros", "Im Projekt sind keine Macros angelegt.")
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(240.dp),
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sorted, key = { it.id }) { mc -> MacroCard(mc, s) }
            }
        }
    }
}

@Composable
private fun MacroCard(mc: MacroDescriptor, s: UmbraSession) {
    val m = s.macros
    Section(mc.name.ifBlank { "Macro ${mc.number}" }) {
        mc.buttonsList.sortedBy { it.number }.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { b ->
                    Box(Modifier.weight(1f)) {
                        ExecButton(b.label.ifBlank { "Taste ${b.number}" }, b.active, Desk.Amber) { p -> m.setButton(mc.id, b.number, p) }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
        mc.fadersList.sortedBy { it.number }.forEach { f ->
            var local by remember(mc.id, f.number) { mutableFloatStateOf(f.faderPosition.toFloat()) }
            var drag by remember(mc.id, f.number) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.label.ifBlank { "Fader ${f.number}" }, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pct(if (drag) local.toDouble() else f.faderPosition), style = MaterialTheme.typography.labelMedium.merge(Numeric))
            }
            DeskFader(
                if (drag) local else f.faderPosition.toFloat().coerceIn(0f, 1f),
                { v -> drag = true; local = v; m.setFader(mc.id, f.number, v.toDouble()) },
                Desk.Cyan,
                Modifier.fillMaxWidth().height(36.dp),
                vertical = false,
                onRelease = { drag = false }
            )
        }
        if (mc.buttonsCount == 0 && mc.fadersCount == 0) {
            Text("Dieses Macro hat keine Tasten oder Fader.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
        }
    }
}

// ============================================================== Beat & Medien
@Composable
fun MediaScreen(s: UmbraSession, vm: AppViewModel) {
    val wide = isWide()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BeatSection(s, vm)
                    MicSection(s, vm)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimecodeSection(s)
                    AudioSection(s)
                }
            }
        } else {
            BeatSection(s, vm)
            MicSection(s, vm)
            TimecodeSection(s)
            AudioSection(s)
        }
    }
}

@Composable
private fun BeatSection(s: UmbraSession, vm: AppViewModel) {
    val m = s.beat
    val interval by m.intervalMs.collectAsStateWithLifecycle()
    val enabled by m.enabled.collectAsStateWithLifecycle()
    val factor by m.factor.collectAsStateWithLifecycle()
    val beat by m.beat.collectAsStateWithLifecycle()
    val model by m.localModel.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    val tapMode by vm.beatTapMode.collectAsStateWithLifecycle()
    val timeBase by vm.beatTimeBase.collectAsStateWithLifecycle()
    val rounding by vm.beatRounding.collectAsStateWithLifecycle()
    val haptic by vm.beatHaptic.collectAsStateWithLifecycle()
    val micAuto by vm.micAutoApply.collectAsStateWithLifecycle()
    var bpmText by remember { mutableStateOf("") }
    val view = androidx.compose.ui.platform.LocalView.current

    // Haptischer Puls auf jedem Server-Beat
    LaunchedEffect(beat, haptic) {
        if (haptic && beat > 0) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
    // Lokales Metronom aus dem App-Tempomodell (Taps/Mikrofon): blinkt zum vorhergesagten Beat
    var metro by remember { mutableStateOf(false) }
    LaunchedEffect(model) {
        val md = model ?: return@LaunchedEffect
        if (md.periodMs <= 0) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val since = ((now - md.anchorMs) % md.periodMs.toLong() + md.periodMs.toLong()) % md.periodMs.toLong()
            kotlinx.coroutines.delay((md.periodMs - since).toLong().coerceAtLeast(1))
            metro = true
            kotlinx.coroutines.delay(80)
            metro = false
        }
    }

    Section("Takt", error = err, actions = {
        Text("Manuell", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = enabled, onCheckedChange = m::setEnabled)
    }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (interval > 0) "%.1f".format(60_000.0 / interval) else "–",
                style = MaterialTheme.typography.displaySmall.merge(Numeric)
            )
            Text(" BPM", style = MaterialTheme.typography.titleMedium, color = Desk.TextDim, modifier = Modifier.padding(bottom = 6.dp))
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    (0 until 4).forEach { i ->
                        Box(
                            Modifier.padding(start = 6.dp).size(12.dp).clip(RoundedCornerShape(3.dp))
                                .background(if ((beat % 4L).toInt() == i) Desk.Cyan else Desk.Raised)
                        )
                    }
                }
                Text("DMXControl", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 12.dp)) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(if (metro) Desk.Amber else Desk.Raised))
                Text("App", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
            }
        }
        // Tap auf Finger-DOWN mit Ereigniszeit (nicht beim Loslassen)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(2f).height(80.dp).clip(RoundedCornerShape(10.dp)).background(Desk.Cyan)
                    .pointerInput(tapMode, timeBase, rounding, micAuto) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val ageMs = android.os.SystemClock.uptimeMillis() - down.uptimeMillis
                            val unix = System.currentTimeMillis() - ageMs
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            if (micAuto) m.syncAt(unix, timeBase)           // Hybrid: Mikro = Tempo, Tap = Phase
                            else m.tap(unix, tapMode, timeBase, rounding)
                            s.beatInputs.tapped()                          // Eingang "Takt: Tap" in DMXControl
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if (micAuto) "Tippen = Phase" else "Tippen im Takt", style = MaterialTheme.typography.titleMedium, color = Color.Black)
            }
            OutlinedButton(onClick = { m.syncAt(System.currentTimeMillis(), timeBase) }, modifier = Modifier.weight(1f).height(80.dp)) { Text("Sync") }
        }
        Text("Korrektur", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { m.nudgeBpm(-1.0) }, modifier = Modifier.weight(1f)) { Text("−1") }
            OutlinedButton(onClick = { m.nudgeBpm(1.0) }, modifier = Modifier.weight(1f)) { Text("+1") }
            OutlinedButton(onClick = { m.multiply(0.5) }, modifier = Modifier.weight(1f)) { Text("÷2") }
            OutlinedButton(onClick = { m.multiply(2.0) }, modifier = Modifier.weight(1f)) { Text("×2") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { m.nudgePhase(-10, timeBase) }, modifier = Modifier.weight(1f)) { Text("Phase −10 ms") }
            OutlinedButton(onClick = { m.nudgePhase(10, timeBase) }, modifier = Modifier.weight(1f)) { Text("Phase +10 ms") }
        }
        Text("Faktor", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.25 to "¼", 0.5 to "½", 1.0 to "1", 2.0 to "2", 4.0 to "4").forEach { (f, l) ->
                FilterChip(
                    selected = factor == f,
                    onClick = { m.setFactor(f) },
                    label = { Text("$l×") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Desk.Cyan, selectedLabelColor = Color.Black)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                bpmText, { bpmText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text("BPM eingeben") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Button(onClick = { bpmText.replace(',', '.').toDoubleOrNull()?.let(m::setBpm) }) { Text("Übernehmen") }
        }
        Text("Einstellungen", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = tapMode == 0, onClick = { vm.setBeatTapMode(0) }, label = { Text("App rechnet") })
            FilterChip(selected = tapMode == 1, onClick = { vm.setBeatTapMode(1) }, label = { Text("DMXControl lernt") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = rounding == 0, onClick = { vm.setBeatRounding(0) }, label = { Text("Exakt") })
            FilterChip(selected = rounding == 2, onClick = { vm.setBeatRounding(2) }, label = { Text("½ BPM") })
            FilterChip(selected = rounding == 1, onClick = { vm.setBeatRounding(1) }, label = { Text("Ganze BPM") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = timeBase == 0, onClick = { vm.setBeatTimeBase(0) }, label = { Text("Zeit: Unix-ms") })
            FilterChip(selected = timeBase == 1, onClick = { vm.setBeatTimeBase(1) }, label = { Text("Zeit: .NET-Ticks") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vibration auf jedem Beat", modifier = Modifier.weight(1f))
            Switch(checked = haptic, onCheckedChange = vm::setBeatHaptic)
        }
        Text(
            "Zeitbasis gilt für Sync, Phase und \"DMXControl lernt\". Welche der Server erwartet, ist ungeprüft: unter Diagnose testen.",
            style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
        )
    }
}

@Composable
private fun MicSection(s: UmbraSession, vm: AppViewModel) {
    val mic = vm.mic
    val running by mic.running.collectAsStateWithLifecycle()
    val bpm by mic.bpm.collectAsStateWithLifecycle()
    val conf by mic.confidence.collectAsStateWithLifecycle()
    val level by mic.level.collectAsStateWithLifecycle()
    val err by mic.error.collectAsStateWithLifecycle()
    val auto by vm.micAutoApply.collectAsStateWithLifecycle()
    val rounding by vm.beatRounding.collectAsStateWithLifecycle()
    val features by mic.features.collectAsStateWithLifecycle()
    val toDmxc by vm.micToDmxc.collectAsStateWithLifecycle()
    val sentReg by s.micFeatures.registered.collectAsStateWithLifecycle()
    val sendErr by s.micFeatures.error.collectAsStateWithLifecycle()
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startMic() }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Section("Mikrofon: Tempo und Stimmung", error = err ?: sendErr, actions = {
        Switch(checked = running, onCheckedChange = { on ->
            if (!on) vm.stopMic()
            else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) vm.startMic()
            else permission.launch(android.Manifest.permission.RECORD_AUDIO)
        })
    }) {
        Text(
            "Erkennt das Tempo am Bass. Schätzung, kein Ersatz für die Audioanalyse am PC: Hall, Lautstärkeregelung und Handy in der Tasche verfälschen sie.",
            style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
        )
        if (running) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(bpm?.let { "%.1f".format(it) } ?: "…", style = MaterialTheme.typography.headlineMedium.merge(Numeric))
                Text(" BPM", color = Desk.TextDim, modifier = Modifier.padding(bottom = 4.dp))
                Box(Modifier.weight(1f))
                StatusPill(
                    when {
                        conf >= 0.5 -> "sicher"
                        conf >= 0.3 -> "mittel"
                        else -> "unsicher"
                    },
                    when {
                        conf >= 0.5 -> Desk.Green
                        conf >= 0.3 -> Desk.Amber
                        else -> Desk.Red
                    }
                )
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { level.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (level > 0.9) Desk.Red else Desk.Cyan
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = bpm != null, onClick = { bpm?.let { s.beat.applyBpm(it, rounding) } }) { Text("Übernehmen") }
                Text("Automatisch", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Switch(checked = auto, onCheckedChange = vm::setMicAutoApply)
            }
            Text(
                "Automatisch übernimmt stabile, sichere Werte; die Tipp-Fläche setzt dann nur noch die Phase.",
                style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
            )
            features?.let { f ->
                HorizontalDividerCompat()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.mood, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (f.key.isNotBlank()) Text(f.key, style = MaterialTheme.typography.titleMedium, color = Desk.TextDim)
                }
                FeatureBar("Energie", f.energy, Desk.Amber)
                FeatureBar("Aktivierung", f.arousal, Desk.Red)
                FeatureBar("Helligkeit", f.brightness, Desk.Cyan)
                FeatureBar("Stimmung dunkel ↔ hell", (f.valence + 1) / 2, Desk.Green)
                Text("Anschläge: ${"%.1f".format(f.onsetRate)} pro Sekunde", style = MaterialTheme.typography.bodySmall.merge(Numeric), color = Desk.TextDim)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("An DMXControl senden")
                Text(
                    if (toDmxc && sentReg) "Eingänge unter \"Android: ${s.clientName}\" → Mikrofon-Analyse"
                    else "Stellt Stimmung, Tonart, Energie, Tempo usw. als Eingänge in der Eingangszuweisung bereit",
                    style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                )
            }
            Switch(checked = toDmxc, onCheckedChange = vm::setMicToDmxc)
        }
        Text(
            "Heuristik ohne KI: Dur/Moll und Helligkeit ergeben die Richtung, Lautheit, Anschläge und Tempo die Aktivierung. " +
                "Das Mikrofon läuft weiter, wenn du den Tab wechselst.",
            style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
        )
    }
}

@Composable
private fun HorizontalDividerCompat() = androidx.compose.material3.HorizontalDivider(color = Desk.Line)

@Composable
private fun FeatureBar(label: String, v: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.width(150.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { v.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
            color = color
        )
    }
}

@Composable
private fun TimecodeSection(s: UmbraSession) {
    val m = s.timecodes
    val tcs by m.items.collectAsStateWithLifecycle()
    val elapsed by m.elapsed.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    Section("Timecode", error = err) {
        if (tcs.isEmpty()) Text("Keine Timecode-Shows im Projekt.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
        tcs.values.sortedBy { it.number }.forEach { tc ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tc.name.ifBlank { "Timecode ${tc.number}" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${fmtTime(elapsed[tc.id] ?: tc.timeElapsed)} von ${fmtTime(tc.duration)}" + if (tc.timecodeIsRecording) ", nimmt auf" else "",
                        style = MaterialTheme.typography.bodySmall.merge(Numeric),
                        color = Desk.TextDim
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TransportButton(Icons.Filled.PlayArrow, "Abspielen") { m.action(tc.id, ETimecodeAction.PLAY) }
                    TransportButton(Icons.Filled.Pause, "Pause") { m.action(tc.id, ETimecodeAction.PAUSE) }
                    TransportButton(Icons.Filled.Stop, "Stopp") { m.action(tc.id, ETimecodeAction.STOP) }
                }
            }
        }
    }
}

@Composable
private fun AudioSection(s: UmbraSession) {
    val m = s.audio
    val players by m.items.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    Section("Audio", error = err) {
        if (players.isEmpty()) Text("Keine Audio-Player aktiv.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
        players.values.sortedBy { it.name }.forEach { p -> AudioRow(p, s) }
    }
}

@Composable
private fun AudioRow(p: PlayerThreadDescriptor, s: UmbraSession) {
    val m = s.audio
    var vol by remember(p.id) { mutableFloatStateOf(p.volume.toFloat()) }
    LaunchedEffect(p.volume) { vol = p.volume.toFloat() }
    val state = when (p.playbackState) {
        PlayerThreadDescriptor.EPlaybackState.Playing -> "Spielt"
        PlayerThreadDescriptor.EPlaybackState.Paused -> "Pausiert"
        else -> "Gestoppt"
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name.ifBlank { "Player" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$state, ${fmtTime(p.currentTime)} von ${fmtTime(p.totalTime)}",
                    style = MaterialTheme.typography.bodySmall.merge(Numeric),
                    color = Desk.TextDim
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TransportButton(Icons.Filled.PlayArrow, "Abspielen") { m.control(p.id, ControlPlayerThreadRequest.EPlayMode.Play) }
                TransportButton(Icons.Filled.Pause, "Pause") { m.control(p.id, ControlPlayerThreadRequest.EPlayMode.Pause) }
                TransportButton(Icons.Filled.Stop, "Stopp") { m.control(p.id, ControlPlayerThreadRequest.EPlayMode.Stop) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lautstärke", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.width(80.dp))
            DeskSlider(vol.coerceIn(0f, 1f), { v -> vol = v; m.setVolume(p.id, v.toDouble()) }, 0f..1f, Modifier.weight(1f), accent = Desk.Cyan)
        }
    }
}

/** items mit Index für LazyColumn (ohne zusätzlichen Import-Konflikt). */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    list: List<T>,
    content: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int, T) -> Unit
) = items(list.size) { i -> content(i, list[i]) }
