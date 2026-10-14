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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
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
fun CuelistScreen(s: UmbraSession) {
    val m = s.cuelists
    val lists by m.items.collectAsStateWithLifecycle()
    val groups by m.groups.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf<String?>(null) }

    val visible = remember(lists, groups, group) {
        val g = group?.let { groups[it] }
        (if (g != null) g.cuelistIdsList.mapNotNull { lists[it] } else lists.values.toList()).sortedBy { it.number }
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
        if (visible.isEmpty()) {
            EmptyHint("Keine Cuelists", "Im Projekt sind keine Cuelists angelegt oder die gewählte Gruppe ist leer.")
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(320.dp),
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible) { cl -> CuelistCard(cl, s) }
            }
        }
    }
}

private fun CuelistDescriptor.cueName(index: Int): String {
    if (index < 0 || cuesCount == 0) return "–"
    val c = cuesList.firstOrNull { it.cueIndex == index } ?: cuesList.getOrNull(index) ?: return "Cue ${index + 1}"
    val nr = if (c.hasCueNumber() && c.cueNumber.numberCount > 0) c.cueNumber.numberList.joinToString(".") else "${index + 1}"
    return if (c.name.isBlank()) "Cue $nr" else "$nr  ${c.name}"
}

@Composable
private fun CuelistCard(cl: CuelistDescriptor, s: UmbraSession) {
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
            Column(Modifier.weight(1f)) {
                Text(cl.name.ifBlank { "Cuelist ${cl.number}" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Nr. ${cl.number}", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
            }
            StatusPill(stateText, stateColor)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Surface).padding(10.dp)) {
            Text("Aktuell", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
            Text(cl.cueName(cl.index), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Als Nächstes", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(top = 4.dp))
            Text(cl.cueName(cl.nextIndex), style = MaterialTheme.typography.bodyMedium, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportButton(Icons.Filled.SkipPrevious, "Zurück") { m.action(cl.id, CuelistActionRequest.EAction.GO_BACK) }
            Button(
                onClick = { m.action(cl.id, CuelistActionRequest.EAction.GO) },
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Desk.Green, contentColor = Color.Black)
            ) { Text("Go", style = MaterialTheme.typography.titleMedium) }
            TransportButton(Icons.Filled.Pause, "Pause") { m.action(cl.id, CuelistActionRequest.EAction.PAUSE) }
            TransportButton(Icons.Filled.Stop, "Stopp") { m.action(cl.id, CuelistActionRequest.EAction.STOP) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Intensität", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.width(72.dp))
            Slider(
                value = if (dragging) local else cl.intensity.toFloat().coerceIn(0f, 1f),
                onValueChange = { v -> dragging = true; local = v; m.setIntensity(cl.id, v.toDouble()) },
                onValueChangeFinished = { dragging = false },
                modifier = Modifier.weight(1f)
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

@Composable
fun MasterScreen(s: UmbraSession) {
    val m = s.masters
    val masters by m.items.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    val sorted = remember(masters) { masters.values.sortedWith(compareBy({ it.typeValue }, { it.number })) }
    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        if (sorted.isEmpty()) {
            EmptyHint("Keine Master", "DMXControl hat keine Master gemeldet.")
        } else {
            val w = stripWidth()
            LazyRow(
                Modifier.fillMaxSize().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) {
                items(sorted, key = { it.masterId }) { ms -> MasterStrip(ms, w, s) }
            }
        }
    }
}

@Composable
private fun MasterStrip(ms: MasterDescriptor, w: androidx.compose.ui.unit.Dp, s: UmbraSession) {
    val m = s.masters
    val accent = if (ms.type == EMasterType.GRAND) Desk.Amber else if (ms.type == EMasterType.SPEED) Desk.Cyan else Color(0xFFB9A6F2)
    ChannelStrip(ms.type.label(), ms.displayName.ifBlank { ms.name.ifBlank { "Master ${ms.number}" } }, accent, w) {
        val value: Double? = when (ms.masterValueCase) {
            MasterDescriptor.MasterValueCase.INTENSITY -> ms.intensity
            MasterDescriptor.MasterValueCase.PARAMETER -> ms.parameter
            else -> null
        }
        if (ms.type == EMasterType.SPEED) {
            Text(
                if (ms.masterValueCase == MasterDescriptor.MasterValueCase.SPEED) "%.2f×".format(ms.speed) else "–",
                style = MaterialTheme.typography.titleMedium.merge(Numeric)
            )
            ExecButton("Lernen", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.LEARN) }
            ExecButton("Sync", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.SYNC) }
            ExecButton("Doppelt", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.DOUBLE_TIMES) }
            ExecButton("Halb", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.HALF_TIMES) }
        } else {
            ExecButton("Voll", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.FULL) }
            ExecButton("Aus", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.BLACK) }
            if (value != null) {
                Text(pct(value), style = MaterialTheme.typography.labelMedium.merge(Numeric))
                VerticalFader(value.toFloat().coerceIn(0f, 1f), { v -> m.setFader(ms, v.toDouble()) }, accent, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
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
            Slider(
                value = if (drag) local else f.faderPosition.toFloat().coerceIn(0f, 1f),
                onValueChange = { v -> drag = true; local = v; m.setFader(mc.id, f.number, v.toDouble()) },
                onValueChangeFinished = { drag = false }
            )
        }
        if (mc.buttonsCount == 0 && mc.fadersCount == 0) {
            Text("Dieses Macro hat keine Tasten oder Fader.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
        }
    }
}

// ============================================================== Beat & Medien
@Composable
fun MediaScreen(s: UmbraSession) {
    val wide = isWide()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { BeatSection(s) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimecodeSection(s)
                    AudioSection(s)
                }
            }
        } else {
            BeatSection(s)
            TimecodeSection(s)
            AudioSection(s)
        }
    }
}

@Composable
private fun BeatSection(s: UmbraSession) {
    val m = s.beat
    val interval by m.intervalMs.collectAsStateWithLifecycle()
    val enabled by m.enabled.collectAsStateWithLifecycle()
    val factor by m.factor.collectAsStateWithLifecycle()
    val beat by m.beat.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    var bpmText by remember { mutableStateOf("") }
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
            // vier Takt-LEDs, die aktuelle leuchtet
            (0 until 4).forEach { i ->
                Box(
                    Modifier.padding(start = 6.dp, bottom = 10.dp).size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if ((beat % 4L).toInt() == i) Desk.Cyan else Desk.Raised)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(2f).height(72.dp).clip(RoundedCornerShape(10.dp)).background(Desk.Cyan).clickable { m.tap() },
                contentAlignment = Alignment.Center
            ) { Text("Tippen im Takt", style = MaterialTheme.typography.titleMedium, color = Color.Black) }
            OutlinedButton(onClick = m::sync, modifier = Modifier.weight(1f).height(72.dp)) { Text("Sync") }
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
            Slider(vol.coerceIn(0f, 1f), { v -> vol = v; m.setVolume(p.id, v.toDouble()) }, Modifier.weight(1f))
        }
    }
}
