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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.UmbraSession
import kotlin.math.roundToInt

private const val ALL = "__all__"

@Composable
fun ModuleError(text: String?) {
    if (text != null) {
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

fun pct(v: Double) = "${(v * 100).roundToInt()}%"

fun fmtTime(ms: Long): String {
    if (ms < 0) return "-" + fmtTime(-ms)
    val t = ms / 100
    val tenth = t % 10
    val sec = (t / 10) % 60
    val min = t / 600
    return "%d:%02d.%d".format(min, sec, tenth)
}

// ============================================================== Executoren
@Composable
fun ExecutorScreen(s: UmbraSession) {
    val m = s.executors
    val executors by m.items.collectAsStateWithLifecycle()
    val pages by m.pages.collectAsStateWithLifecycle()
    val ctx by s.userContext.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()

    var syncPage by rememberSaveable { mutableStateOf(false) }
    var localPage by rememberSaveable { mutableStateOf<String?>(null) }
    val serverPage = ctx?.selectedExecutorPage?.ifBlank { null }
    val pageId = (if (syncPage) serverPage else localPage) ?: localPage ?: serverPage ?: ALL

    val sortedPages = remember(pages) { pages.values.sortedBy { it.pageNumber } }
    val visible: List<ExecutorDescriptor> = remember(executors, pages, pageId) {
        pages[pageId]?.executorIdsList?.mapNotNull { executors[it] } ?: executors.values.sortedBy { it.number }
    }

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Bewusst keine ScrollableTabRow: bei sich ändernder Tab-Anzahl + Index crasht deren Indikator
            LazyRow(
                Modifier.weight(1f).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    FilterChip(selected = pageId == ALL, onClick = { localPage = ALL }, label = { Text("Alle") })
                }
                items(sortedPages, key = { it.id }) { p ->
                    FilterChip(
                        selected = pageId == p.id,
                        onClick = {
                            localPage = p.id
                            if (syncPage) m.selectPage(p.id)
                        },
                        label = { Text("${p.pageNumber} ${p.name}".trim(), maxLines = 1) }
                    )
                }
            }
            Checkbox(checked = syncPage, onCheckedChange = { syncPage = it })
            Text("Sync", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Keine Executoren") }
        } else {
            LazyRow(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible) { ex ->
                    ExecutorStrip(ex, { v -> m.setFader(ex.id, v.toDouble()) }, { b, p -> m.setButton(ex.id, b, p) })
                }
            }
        }
    }
}

@Composable
fun accentOf(r: Int, g: Int, b: Int, has: Boolean): Color =
    if (!has || (r == 0 && g == 0 && b == 0)) MaterialTheme.colorScheme.primary else Color(r, g, b)

@Composable
private fun ExecutorStrip(ex: ExecutorDescriptor, onFader: (Float) -> Unit, onButton: (Int, Boolean) -> Unit) {
    val accent = accentOf(ex.color.r, ex.color.g, ex.color.b, ex.hasColor())
    StripFrame(accent) {
        Text("${ex.number}", style = MaterialTheme.typography.labelSmall)
        Text(
            ex.name.ifBlank { ex.faderDisplayName.ifBlank { "—" } },
            style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.height(34.dp)
        )
        val labels = listOf(ex.button1DisplayName, ex.button2DisplayName, ex.button3DisplayName, ex.button4DisplayName)
        val keys = listOf(ex.button1Key, ex.button2Key, ex.button3Key, ex.button4Key)
        val active = listOf(ex.button1Active, ex.button2Active, ex.button3Active, ex.button4Active)
        (0..3).filter { labels[it].isNotBlank() || keys[it].isNotBlank() }.ifEmpty { listOf(0) }.forEach { i ->
            ExecButton(labels[i].ifBlank { keys[i].ifBlank { "B${i + 1}" } }, active[i], accent) { p -> onButton(i + 1, p) }
        }
        Text(pct(ex.faderPosition), style = MaterialTheme.typography.labelMedium)
        VerticalFader(ex.faderPosition.toFloat().coerceIn(0f, 1f), onFader, accent, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
fun StripFrame(accent: Color, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(96.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = group == null, onClick = { group = null }, label = { Text("Alle") }) }
                items(groups.values.sortedBy { it.number }, key = { it.id }) { g ->
                    FilterChip(selected = group == g.id, onClick = { group = g.id }, label = { Text(g.name) })
                }
            }
            OutlinedButton(onClick = m::stopAll) { Text("Alle STOP") }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(300.dp),
            Modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visible) { cl -> CuelistCard(cl, s) }
        }
    }
}

private fun CuelistDescriptor.cueName(index: Int): String {
    if (index < 0) return "—"
    val c = cuesList.firstOrNull { it.cueIndex == index } ?: cuesList.getOrNull(index) ?: return "#${index + 1}"
    val nr = if (c.hasCueNumber()) c.cueNumber.numberList.joinToString(".") else "${index + 1}"
    return "$nr ${c.name}".trim()
}

@Composable
private fun CuelistCard(cl: CuelistDescriptor, s: UmbraSession) {
    val m = s.cuelists
    var local by remember(cl.id) { mutableFloatStateOf(cl.intensity.toFloat()) }
    var dragging by remember(cl.id) { mutableStateOf(false) }
    val running = cl.state == ESceneListState.RUNNING
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${cl.number}  ${cl.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text(
                    when (cl.state) {
                        ESceneListState.RUNNING -> "läuft"
                        ESceneListState.PAUSED -> "Pause"
                        else -> "gestoppt"
                    },
                    color = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text("Aktuell: ${cl.cueName(cl.index)}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text("Nächste: ${cl.cueName(cl.nextIndex)}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { m.action(cl.id, CuelistActionRequest.EAction.GO_BACK) }, modifier = Modifier.weight(1f)) { Text("◀") }
                Button(onClick = { m.action(cl.id, CuelistActionRequest.EAction.GO) }, modifier = Modifier.weight(2f)) { Text("GO") }
                OutlinedButton(onClick = { m.action(cl.id, CuelistActionRequest.EAction.PAUSE) }, modifier = Modifier.weight(1f)) { Text("❚❚") }
                OutlinedButton(onClick = { m.action(cl.id, CuelistActionRequest.EAction.STOP) }, modifier = Modifier.weight(1f)) { Text("■") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Int.", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = if (dragging) local else cl.intensity.toFloat().coerceIn(0f, 1f),
                    onValueChange = { v ->
                        dragging = true
                        local = v
                        m.setIntensity(cl.id, v.toDouble())
                    },
                    onValueChangeFinished = { dragging = false },
                    modifier = Modifier.weight(1f)
                )
                Text(pct(if (dragging) local.toDouble() else cl.intensity), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ============================================================== Master
@Composable
fun MasterScreen(s: UmbraSession) {
    val m = s.masters
    val masters by m.items.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    val sorted = remember(masters) { masters.values.sortedWith(compareBy({ it.typeValue }, { it.number })) }
    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        LazyRow(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { it.masterId }) { ms -> MasterStrip(ms, s) }
        }
    }
}

@Composable
private fun MasterStrip(ms: MasterDescriptor, s: UmbraSession) {
    val m = s.masters
    val accent = if (ms.type == EMasterType.GRAND) Color(0xFFFFB300) else MaterialTheme.colorScheme.secondary
    StripFrame(accent) {
        Text(ms.type.name, style = MaterialTheme.typography.labelSmall)
        Text(
            ms.displayName.ifBlank { ms.name }, style = MaterialTheme.typography.labelMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.height(34.dp)
        )
        val value: Double? = when (ms.masterValueCase) {
            MasterDescriptor.MasterValueCase.INTENSITY -> ms.intensity
            MasterDescriptor.MasterValueCase.PARAMETER -> ms.parameter
            else -> null
        }
        when (ms.type) {
            EMasterType.SPEED -> {
                Text(if (ms.masterValueCase == MasterDescriptor.MasterValueCase.SPEED) "%.2f".format(ms.speed) else "—")
                ExecButton("LEARN", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.LEARN) }
                ExecButton("SYNC", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.SYNC) }
                ExecButton("×2", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.DOUBLE_TIMES) }
                ExecButton("÷2", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.HALF_TIMES) }
            }
            else -> {
                ExecButton("FULL", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.FULL) }
                ExecButton("BLACK", false, accent) { p -> if (p) m.action(ms.masterId, MasterActionRequest.EAction.BLACK) }
                if (value != null) {
                    Text(pct(value), style = MaterialTheme.typography.labelMedium)
                    VerticalFader(
                        value.toFloat().coerceIn(0f, 1f),
                        { v -> m.setFader(ms, v.toDouble()) },
                        accent,
                        Modifier.fillMaxWidth().weight(1f)
                    )
                }
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Keine Macros") }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(220.dp),
            Modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sorted, key = { it.id }) { mc -> MacroCard(mc, s) }
        }
    }
}

@Composable
private fun MacroCard(mc: MacroDescriptor, s: UmbraSession) {
    val m = s.macros
    val accent = MaterialTheme.colorScheme.primary
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${mc.number}  ${mc.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            mc.buttonsList.sortedBy { it.number }.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { b ->
                        Box(Modifier.weight(1f)) {
                            ExecButton(b.label.ifBlank { "T${b.number}" }, b.active, accent) { p ->
                                m.setButton(mc.id, b.number, p)
                            }
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            mc.faderList().forEach { f ->
                var local by remember(mc.id, f.number) { mutableFloatStateOf(f.faderPosition.toFloat()) }
                var drag by remember(mc.id, f.number) { mutableStateOf(false) }
                Text("${f.label.ifBlank { "F${f.number}" }}  ${pct(if (drag) local.toDouble() else f.faderPosition)}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = if (drag) local else f.faderPosition.toFloat().coerceIn(0f, 1f),
                    onValueChange = { v ->
                        drag = true
                        local = v
                        m.setFader(mc.id, f.number, v.toDouble())
                    },
                    onValueChangeFinished = { drag = false }
                )
            }
        }
    }
}

private fun MacroDescriptor.faderList() = fadersList.sortedBy { it.number }

// ============================================================== Medien: Beat, Timecode, Audio
@Composable
fun MediaScreen(s: UmbraSession) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BeatCard(s)
        TimecodeSection(s)
        AudioSection(s)
    }
}

@Composable
private fun BeatCard(s: UmbraSession) {
    val m = s.beat
    val interval by m.intervalMs.collectAsStateWithLifecycle()
    val enabled by m.enabled.collectAsStateWithLifecycle()
    val factor by m.factor.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    var bpmText by remember { mutableStateOf("") }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Beat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("Manuell ")
                Switch(checked = enabled, onCheckedChange = m::setEnabled)
            }
            ModuleError(err)
            val bpm = if (interval > 0) 60_000.0 / interval else 0.0
            Text("%.1f BPM  ·  Faktor %.2f".format(bpm, factor), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = m::tap, modifier = Modifier.weight(2f).height(56.dp)) { Text("TAP") }
                OutlinedButton(onClick = m::sync, modifier = Modifier.weight(1f).height(56.dp)) { Text("SYNC") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0.25, 0.5, 1.0, 2.0, 4.0).forEach { f ->
                    FilterChip(selected = factor == f, onClick = { m.setFactor(f) }, label = { Text("×${if (f < 1) f.toString() else f.toInt().toString()}") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    bpmText, { bpmText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("BPM") }, singleLine = true, modifier = Modifier.width(120.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                TextButton(onClick = { bpmText.toDoubleOrNull()?.let(m::setBpm) }) { Text("Setzen") }
            }
        }
    }
}

@Composable
private fun TimecodeSection(s: UmbraSession) {
    val m = s.timecodes
    val tcs by m.items.collectAsStateWithLifecycle()
    val elapsed by m.elapsed.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    Text("Timecode", style = MaterialTheme.typography.titleMedium)
    ModuleError(err)
    if (tcs.isEmpty()) Text("Keine Timecode-Shows", style = MaterialTheme.typography.bodySmall)
    tcs.values.sortedBy { it.number }.forEach { tc ->
        Card {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${tc.number}  ${tc.name}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${fmtTime(elapsed[tc.id] ?: tc.timeElapsed)} / ${fmtTime(tc.duration)}" +
                            if (tc.timecodeIsRecording) "  ● REC" else "",
                        fontFamily = FontFamily.Monospace
                    )
                }
                TextButton(onClick = { m.action(tc.id, ETimecodeAction.PLAY) }) { Text("▶") }
                TextButton(onClick = { m.action(tc.id, ETimecodeAction.PAUSE) }) { Text("❚❚") }
                TextButton(onClick = { m.action(tc.id, ETimecodeAction.STOP) }) { Text("■") }
            }
        }
    }
}

@Composable
private fun AudioSection(s: UmbraSession) {
    val m = s.audio
    val players by m.items.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    Text("Audio", style = MaterialTheme.typography.titleMedium)
    ModuleError(err)
    if (players.isEmpty()) Text("Keine Player", style = MaterialTheme.typography.bodySmall)
    players.values.sortedBy { it.name }.forEach { p -> AudioCard(p, s) }
}

@Composable
private fun AudioCard(p: PlayerThreadDescriptor, s: UmbraSession) {
    val m = s.audio
    var vol by remember(p.id) { mutableFloatStateOf(p.volume.toFloat()) }
    LaunchedEffect(p.volume) { vol = p.volume.toFloat() }
    Card {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "${p.playbackState}  ${fmtTime(p.currentTime)} / ${fmtTime(p.totalTime)}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { m.control(p.id, ControlPlayerThreadRequest.EPlayMode.Play) }) { Text("▶") }
                TextButton(onClick = { m.control(p.id, ControlPlayerThreadRequest.EPlayMode.Pause) }) { Text("❚❚") }
                TextButton(onClick = { m.control(p.id, ControlPlayerThreadRequest.EPlayMode.Stop) }) { Text("■") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vol", style = MaterialTheme.typography.labelSmall)
                Slider(vol.coerceIn(0f, 1f), { v -> vol = v; m.setVolume(p.id, v.toDouble()) }, Modifier.weight(1f))
            }
        }
    }
}
