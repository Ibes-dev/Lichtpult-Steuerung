package de.sebi.dmxc3remote.ui

import LumosProtobuf.CommonTypes.ObjectData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.FeedbackRules
import de.sebi.dmxc3remote.net.SdElement
import de.sebi.dmxc3remote.net.SdIo
import de.sebi.dmxc3remote.net.SdMode
import de.sebi.dmxc3remote.net.SdType
import de.sebi.dmxc3remote.net.numeric
import de.sebi.dmxc3remote.net.optionList
import de.sebi.dmxc3remote.net.Softdesk
import de.sebi.dmxc3remote.net.SoftdeskStore
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.asDouble
import de.sebi.dmxc3remote.net.isOn
import de.sebi.dmxc3remote.net.text
import kotlin.math.roundToInt

/** Elementfarben; Index wird im Element gespeichert. */
val SD_PALETTE = listOf(
    Desk.Amber, Desk.Cyan, Desk.Green, Desk.Red,
    Color(0xFFB9A6F2), Color(0xFF5B8DEF), Color(0xFFFF8A3D), Color(0xFFE8E6E1)
)

private fun SdElement.colorValue(): Color =
    colorHex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        ?: SD_PALETTE[color.mod(SD_PALETTE.size)]

@Composable
fun SoftdeskScreen(
    s: UmbraSession, store: SoftdeskStore, midi: de.sebi.dmxc3remote.midi.MidiHub,
    fullscreen: Boolean = false, onFullscreen: (() -> Unit)? = null
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray()) }
            s.emit("Bedienfelder exportiert")
        }.onFailure { s.emit("Export: ${it.message}", isError = true) }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> importUri = uri }
    val desks by store.desks.collectAsStateWithLifecycle()
    val rules by store.rules.collectAsStateWithLifecycle()
    val fb by s.softdesk.feedback.collectAsStateWithLifecycle()
    val registered by s.softdesk.registered.collectAsStateWithLifecycle()
    val server by s.softdesk.serverCounts.collectAsStateWithLifecycle()
    val err by s.softdesk.error.collectAsStateWithLifecycle()

    var deskId by rememberSaveable { mutableStateOf<String?>(null) }
    var edit by rememberSaveable { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var addAt by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var deskDialog by remember { mutableStateOf(false) }
    var rulesDialog by remember { mutableStateOf(false) }
    var diagDialog by remember { mutableStateOf(false) }
    val desk = desks.firstOrNull { it.id == deskId } ?: desks.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        if (!fullscreen || desks.size > 1) Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, end = if (fullscreen) 80.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                items(desks, key = { it.id }) { d ->
                    FilterChip(selected = desk?.id == d.id, onClick = { deskId = d.id }, label = { Text(d.name, maxLines = 1) })
                }
                if (edit) item {
                    FilterChip(
                        selected = false,
                        onClick = { deskId = store.addDesk("Bedienfeld ${desks.size + 1}") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                        label = { Text("Neu") }
                    )
                }
            }
            if (!fullscreen && onFullscreen != null && !edit) IconButton(onClick = onFullscreen) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "Vollbild", tint = Desk.TextDim)
            }
            if (!fullscreen) FilterChip(
                selected = edit,
                onClick = { edit = !edit },
                leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)) },
                label = { Text(if (edit) "Fertig" else "Bearbeiten") },
                modifier = Modifier.padding(end = 10.dp)
            )
        }
        if (edit) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (desk != null) OutlinedButton(onClick = { deskDialog = true }) { Text("Raster") }
                OutlinedButton(onClick = { rulesDialog = true }) { Text("Rückmeldung") }
                OutlinedButton(onClick = { exporter.launch("bedienfelder.json") }) { Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
                OutlinedButton(onClick = { diagDialog = true }) { Text("Diagnose") }
            }
            Text(
                server?.let { "In DMXControl: ${it.first} Eingänge, ${it.second} Ausgänge" } ?: "$registered angemeldet",
                style = MaterialTheme.typography.labelSmall,
                color = Desk.TextDim,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }

        if (desk == null) {
            EmptyHint(
                "Noch kein Bedienfeld",
                "Auf Bearbeiten tippen, dann Neu. Elemente legst du durch Antippen freier Rasterfelder an. " +
                    "In der DMXControl-Eingangszuweisung stehen sie unter \"Android: ${s.clientName}\"."
            )
        } else {
            DeskCanvas(
                desk = desk,
                edit = edit && !fullscreen,
                feedback = fb,
                rules = rules,
                s = s,
                onEdit = { editingId = it.id },
                onAdd = { x, y -> addAt = x to y },
                onUpdate = { store.updateElement(desk.id, it) }
            )
        }
    }

    val ed = desk?.elements?.firstOrNull { it.id == editingId }
    if (ed != null && desk != null) {
        ElementEditor(
            e = ed,
            desk = desk,
            sess = s,
            rules = rules,
            midi = midi,
            onDismiss = { editingId = null },
            onSave = { store.updateElement(desk.id, it); editingId = null },
            onDelete = { store.deleteElement(desk.id, ed.id); editingId = null },
            onDuplicate = { store.duplicate(desk.id, ed)?.let { c -> editingId = c.id } }
        )
    }
    val at = addAt
    if (at != null && desk != null) {
        TypePicker(onDismiss = { addAt = null }) { t ->
            addAt = null
            store.newElement(desk.id, t, at.first, at.second)?.let { editingId = it.id }
        }
    }
    if (deskDialog && desk != null) {
        DeskDialog(desk, onDismiss = { deskDialog = false }, onDelete = {
            store.deleteDesk(desk.id); deskId = null; deskDialog = false
        }) { store.updateDesk(it); deskDialog = false }
    }
    if (diagDialog) DiagDialog(s) { diagDialog = false }
    val iu = importUri
    if (iu != null) {
        fun doImport(replace: Boolean) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(iu)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Datei leer")
                val n = store.importJson(text, replace)
                s.emit("$n Bedienfeld(er) importiert")
            }.onFailure { s.emit("Import: ${it.message}", isError = true) }
            importUri = null
        }
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Bedienfelder importieren") },
            text = { Text("Ergänzen behält vorhandene Bedienfelder und ersetzt nur gleiche IDs. Ersetzen löscht alle vorhandenen.") },
            confirmButton = { TextButton(onClick = { doImport(false) }) { Text("Ergänzen") } },
            dismissButton = { TextButton(onClick = { doImport(true) }) { Text("Alles ersetzen", color = Desk.Red) } }
        )
    }
    if (rulesDialog) {
        RulesDialog(rules, onDismiss = { rulesDialog = false }) { store.setRules(it); rulesDialog = false }
    }
}

// ================================================================== Raster
@Composable
private fun DeskCanvas(
    desk: Softdesk,
    edit: Boolean,
    feedback: Map<String, ObjectData>,
    rules: FeedbackRules,
    s: UmbraSession,
    onEdit: (SdElement) -> Unit,
    onAdd: (Int, Int) -> Unit,
    onUpdate: (SdElement) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
        val cellW = maxWidth / desk.cols
        val cellH = if (desk.fit) maxHeight / desk.rows else cellW.coerceIn(56.dp, 140.dp)
        val scroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (desk.fit) Modifier.fillMaxHeight() else Modifier.verticalScroll(scroll))
        ) {
            Box(Modifier.fillMaxWidth().height(cellH * desk.rows)) {
                if (edit) {
                    // freie Felder: antippen fügt ein Element hinzu
                    val taken = HashSet<Pair<Int, Int>>()
                    desk.elements.forEach { e ->
                        for (yy in e.y until e.y + e.h) for (xx in e.x until e.x + e.w) taken += xx to yy
                    }
                    for (y in 0 until desk.rows) for (x in 0 until desk.cols) {
                        if ((x to y) in taken) continue
                        Box(
                            Modifier
                                .offset(cellW * x, cellH * y)
                                .size(cellW, cellH)
                                .padding(3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Desk.Line, RoundedCornerShape(8.dp))
                                .pointerInput(x, y) { detectTapGestures { onAdd(x, y) } },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Add, contentDescription = "Element hinzufügen", tint = Desk.Line) }
                    }
                }
                desk.elements.forEach { e ->
                    ElementFrame(e, desk, cellW, cellH, edit, feedback[e.id], rules, s, onEdit, onUpdate)
                }
            }
        }
    }
}

@Composable
private fun ElementFrame(
    e: SdElement,
    desk: Softdesk,
    cellW: Dp,
    cellH: Dp,
    edit: Boolean,
    fb: ObjectData?,
    rules: FeedbackRules,
    s: UmbraSession,
    onEdit: (SdElement) -> Unit,
    onUpdate: (SdElement) -> Unit
) {
    val density = LocalDensity.current
    val cw = with(density) { cellW.toPx() }
    val ch = with(density) { cellH.toPx() }
    var move by remember(e.id) { mutableStateOf(Offset.Zero) }
    var grow by remember(e.id) { mutableStateOf(Offset.Zero) }
    val cur by rememberUpdatedState(e)

    val wPx = (cw * e.w + grow.x).coerceAtLeast(cw * 0.6f)
    val hPx = (ch * e.h + grow.y).coerceAtLeast(ch * 0.6f)
    Box(
        Modifier
            .offset(cellW * e.x, cellH * e.y)
            .offset { IntOffset(move.x.roundToInt(), move.y.roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .padding(3.dp)
    ) {
        ElementContent(e, fb, rules, s, enabled = !edit)
        if (edit) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = if (move != Offset.Zero) 0.2f else 0.45f))
                    .border(2.dp, if (move != Offset.Zero) Desk.Amber else Desk.Line, RoundedCornerShape(10.dp))
                    .pointerInput(e.id) { detectTapGestures { onEdit(cur) } }
                    .pointerInput(e.id, cw, ch) {
                        detectDragGestures(
                            onDragEnd = {
                                val c = cur
                                val nx = (c.x + (move.x / cw).roundToInt()).coerceIn(0, desk.cols - c.w)
                                val ny = (c.y + (move.y / ch).roundToInt()).coerceAtLeast(0)
                                move = Offset.Zero
                                if (nx != c.x || ny != c.y) onUpdate(c.copy(x = nx, y = ny))
                            },
                            onDragCancel = { move = Offset.Zero }
                        ) { change, d -> change.consume(); move += d }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(e.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // Größe: Griff unten rechts ziehen
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(30.dp)
                    .clip(RoundedCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .background(Desk.Amber)
                    .pointerInput(e.id, cw, ch) {
                        detectDragGestures(
                            onDragEnd = {
                                val c = cur
                                val nw = (c.w + (grow.x / cw).roundToInt()).coerceIn(1, desk.cols - c.x)
                                val nh = (c.h + (grow.y / ch).roundToInt()).coerceAtLeast(1)
                                grow = Offset.Zero
                                if (nw != c.w || nh != c.h) onUpdate(c.copy(w = nw, h = nh))
                            },
                            onDragCancel = { grow = Offset.Zero }
                        ) { change, d -> change.consume(); grow += d }
                    },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.OpenInFull, contentDescription = "Größe ändern", tint = Color.Black, modifier = Modifier.size(16.dp)) }
        }
    }
}

// ================================================================== Elemente im Betrieb
@Composable
private fun ElementContent(e0: SdElement, fb: ObjectData?, rules: FeedbackRules, s: UmbraSession, enabled: Boolean) {
    // Anpassungen: Beschriftung ausblenden, Schriftgröße
    val e = if (e0.hideLabel) e0.copy(name = "") else e0
    val dens = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(dens.density, dens.fontScale * e0.textScale.coerceIn(0.6f, 2.5f))
    ) {
        Box(Modifier.fillMaxSize().then(if (e0.outline) Modifier.border(2.dp, e0.colorValue(), RoundedCornerShape(10.dp)) else Modifier)) {
            ElementBody(e, fb, rules, s, enabled)
        }
    }
}

@Composable
private fun ElementBody(e: SdElement, fb: ObjectData?, rules: FeedbackRules, s: UmbraSession, enabled: Boolean) {
    val color = e.colorValue()
    val on = fb?.isOn(rules, e.fbOn) == true
    val shape = RoundedCornerShape(10.dp)
    when (e.type) {
        SdType.BUTTON -> SdButton(e, on, fb, color, s, enabled)
        SdType.FADER -> SdFader(e, fb, color, s, enabled)
        SdType.LED -> Row(
            Modifier.fillMaxSize().clip(shape).background(Desk.Panel).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) color else color.copy(alpha = 0.15f)))
            Column(Modifier.padding(start = 10.dp)) {
                Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                if (e.showValue && fb != null) Text(fb.text(), style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 1)
            }
        }
        SdType.DISPLAY -> Column(
            Modifier.fillMaxSize().clip(shape).background(if (on) color.copy(alpha = 0.25f) else Desk.Panel)
                .border(1.dp, if (on) color else Desk.Line, shape).padding(10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(e.name, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                fb?.text()?.ifBlank { "–" } ?: "–",
                style = MaterialTheme.typography.titleLarge.merge(Numeric),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        SdType.TEXT -> Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.CenterStart) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        SdType.ROTARY -> SdRotary(e, fb, color, s, enabled)
        SdType.XY -> SdXY(e, color, s, enabled)
        SdType.RADIO -> SdRadio(e, fb, color, s, enabled)
        SdType.CUELIST_MONITOR -> SdCuelistMonitor(e, color, s, enabled)
        SdType.CUELIST_SELECTOR -> SdCuelistSelector(e, color, s, enabled)
        SdType.DEVICE_SELECTOR -> SdDeviceSelector(e, color, s, enabled)
        SdType.CLOCK -> SdClock(e, color, s)
    }
}

@Composable
private fun SdButton(e: SdElement, on: Boolean, fb: ObjectData?, color: Color, s: UmbraSession, enabled: Boolean) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember(e.id) { mutableStateOf(false) }
    var latched by remember(e.id) { mutableStateOf(false) }
    val lit = pressed || (if (fb != null) on else e.mode == SdMode.TOGGLE && latched)
    val cur by rememberUpdatedState(e)
    val litNow by rememberUpdatedState(lit)
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(if (lit) color else Desk.Raised)
            .border(1.dp, color.copy(alpha = if (lit) 1f else 0.5f), shape)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(e.id) {
                    detectTapGestures(onPress = {
                        val el = cur
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (el.mode == SdMode.MOMENTARY) {
                            pressed = true
                            s.softdesk.button(el, true)
                            try { tryAwaitRelease() } finally { pressed = false; s.softdesk.button(el, false) }
                        } else {
                            val next = !litNow
                            latched = next
                            s.softdesk.button(el, next)
                        }
                    })
                }
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            e.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (lit) Color.Black else Desk.Text,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (e.showValue && fb != null) {
            Text(fb.text(), style = MaterialTheme.typography.labelSmall, color = if (lit) Color.Black else Desk.TextDim, maxLines = 1)
        }
    }
}

@Composable
private fun SdFader(e: SdElement, fb: ObjectData?, color: Color, s: UmbraSession, enabled: Boolean) {
    var local by remember(e.id) { mutableStateOf(0f) }
    var dragging by remember(e.id) { mutableStateOf(false) }
    val span = (e.max - e.min).takeIf { it != 0.0 } ?: 1.0
    val fbPos = fb?.asDouble()?.let { ((it - e.min) / span).toFloat().coerceIn(0f, 1f) }
    val pos = if (dragging) local else fbPos ?: local
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(Modifier.fillMaxSize().clip(shape).background(Desk.Panel).border(1.dp, Desk.Line, shape)) {
        val vertical = maxHeight >= maxWidth
        val valueText = if (e.min == 0.0 && e.max == 1.0) "${(pos * 100).roundToInt()} %"
        else "%.1f".format(e.min + pos * span)
        // derselbe Fader wie bei Executoren und Mastern
        DeskFader(
            pos,
            { v -> dragging = true; local = v; s.softdesk.fader(e, v.toDouble()) },
            color,
            Modifier.fillMaxSize().padding(
                start = if (vertical) 6.dp else 8.dp, end = if (vertical) 6.dp else 8.dp,
                top = if (vertical) 40.dp else 26.dp, bottom = 6.dp
            ),
            vertical = vertical,
            enabled = enabled,
            onRelease = { dragging = false }
        )
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text(e.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(valueText, style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.TextDim)
        }
    }
}

// ================================================================== Dialoge
@Composable
private fun TypePicker(onDismiss: () -> Unit, onPick: (SdType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Element hinzufügen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    SdType.BUTTON to "Sendet beim Drücken, leuchtet bei Rückmeldung",
                    SdType.FADER to "Wert stufenlos, folgt der Rückmeldung",
                    SdType.LED to "Zeigt einen Zustand an",
                    SdType.DISPLAY to "Zeigt einen Wert oder Text aus DMXControl",
                    SdType.TEXT to "Feste Beschriftung, ohne Verbindung",
                    SdType.ROTARY to "Drehregler (kreisförmig ziehen), Wert wie ein Fader",
                    SdType.XY to "Zweidimensionales Feld, sendet X und Y als zwei Eingänge",
                    SdType.RADIO to "Mehrere Optionen, eine ist aktiv (z. B. Szene A/B/C)",
                    SdType.CUELIST_MONITOR to "Zeigt eine Cuelist: Zustand, aktueller und nächster Cue, Go/Stop",
                    SdType.CUELIST_SELECTOR to "Liste aller Cuelists: antippen = Go, lange drücken = Stop",
                    SdType.DEVICE_SELECTOR to "Gerätegruppen oder Geräte für den Programmer auswählen",
                    SdType.CLOCK to "Uhrzeit, optional mit Datum"
                ).forEach { (t, hint) ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Desk.Raised)
                            .pointerInput(t) { detectTapGestures { onPick(t) } }
                            .padding(12.dp)
                    ) {
                        Text(t.label, style = MaterialTheme.typography.titleSmall)
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium.merge(Numeric), modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
        TextButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun EditorHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Desk.Amber, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ElementEditor(
    e: SdElement,
    desk: Softdesk,
    sess: UmbraSession,
    rules: FeedbackRules,
    midi: de.sebi.dmxc3remote.midi.MidiHub,
    onDismiss: () -> Unit,
    onSave: (SdElement) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    var d by remember(e.id) { mutableStateOf(e) }
    var minText by remember(e.id) { mutableStateOf(e.min.toString()) }
    var maxText by remember(e.id) { mutableStateOf(e.max.toString()) }
    var confirmDelete by remember(e.id) { mutableStateOf(false) }
    val learning by midi.learning.collectAsStateWithLifecycle()
    val learned by midi.learned.collectAsStateWithLifecycle()
    // gelernte Zuordnung in die Bearbeitungskopie übernehmen (sonst überschreibt Speichern sie)
    androidx.compose.runtime.LaunchedEffect(learned) {
        val l = learned
        if (l != null && l.first == e.id) d = d.copy(midi = l.second)
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (midi.learning.value == e.id) midi.learning.value = null } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${d.type.label} bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(d.name, { d = d.copy(name = it) }, label = { Text("Beschriftung") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Farbe", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SD_PALETTE.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c)
                                .border(3.dp, if (d.color == i) Desk.Text else Color.Transparent, CircleShape)
                                .pointerInput(i) { detectTapGestures { d = d.copy(color = i) } }
                        )
                    }
                }

                EditorHeading("Darstellung")
                OutlinedTextField(
                    d.colorHex, { d = d.copy(colorHex = it.trim().take(7)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Eigene Farbe (#RRGGBB, leer = Palette)") },
                    trailingIcon = { Box(Modifier.size(20.dp).clip(CircleShape).background(d.colorValue())) }
                )
                Text("Schriftgröße", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
                ChoiceRow(listOf(0.8f, 1f, 1.3f, 1.7f, 2.2f), d.textScale, { when (it) { 0.8f -> "S"; 1f -> "M"; 1.3f -> "L"; 1.7f -> "XL"; else -> "XXL" } }) { d = d.copy(textScale = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Beschriftung ausblenden", modifier = Modifier.weight(1f)); Switch(checked = d.hideLabel, onCheckedChange = { d = d.copy(hideLabel = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Farbiger Rahmen", modifier = Modifier.weight(1f)); Switch(checked = d.outline, onCheckedChange = { d = d.copy(outline = it) })
                }

                if (d.type == SdType.RADIO) {
                    EditorHeading("Optionen")
                    OutlinedTextField(
                        d.options, { d = d.copy(options = it) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                        label = { Text("Eine Option je Zeile: Beschriftung=Wert") }
                    )
                    Text("Werttyp", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
                    ChoiceRow(SdIo.entries, d.sendIo, { it.label }) { d = d.copy(sendIo = it, fbIo = it) }
                }
                if (d.type == SdType.CUELIST_MONITOR) {
                    EditorHeading("Cuelist")
                    CuelistPick(sess, d.target) { d = d.copy(target = it) }
                }
                if (d.type == SdType.DEVICE_SELECTOR) {
                    EditorHeading("Auswahl")
                    ChoiceRow(listOf("", "devices"), d.target, { if (it == "devices") "Einzelgeräte" else "Gerätegruppen" }) { d = d.copy(target = it) }
                }
                if (d.type == SdType.CLOCK) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Datum anzeigen", modifier = Modifier.weight(1f)); Switch(checked = d.showValue, onCheckedChange = { d = d.copy(showValue = it) })
                    }
                }

                EditorHeading("Position und Größe")
                Stepper("Spalte", d.x + 1, 1, desk.cols - d.w + 1) { d = d.copy(x = it - 1) }
                Stepper("Zeile", d.y + 1, 1, 99) { d = d.copy(y = it - 1) }
                Stepper("Breite", d.w, 1, desk.cols - d.x) { d = d.copy(w = it) }
                Stepper("Höhe", d.h, 1, 20) { d = d.copy(h = it) }

                if (d.type == SdType.BUTTON) {
                    EditorHeading("Senden an DMXControl")
                    ChoiceRow(SdMode.entries, d.mode, { it.label }) { d = d.copy(mode = it) }
                    Text(
                        if (d.mode == SdMode.MOMENTARY) "Sendet beim Drücken den An-Wert, beim Loslassen den Aus-Wert."
                        else "Jeder Druck wechselt zwischen An- und Aus-Wert.",
                        style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                    )
                    ChoiceRow(SdIo.entries, d.sendIo, { it.label }) { d = d.copy(sendIo = it) }
                    if (d.sendIo != SdIo.BOOL) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(d.onValue, { d = d.copy(onValue = it) }, label = { Text("An-Wert") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(d.offValue, { d = d.copy(offValue = it) }, label = { Text("Aus-Wert") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (d.type.numeric) {
                    EditorHeading("Wertebereich")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            minText, { minText = it }, label = { Text("Unten") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            maxText, { maxText = it }, label = { Text("Oben") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                    Text("Ausrichtung ergibt sich aus der Form: hoch = senkrecht, breit = waagrecht.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                }
                if (d.type.hasSink) {
                    EditorHeading("Rückmeldung von DMXControl")
                    if (!d.type.numeric && d.type != SdType.RADIO) {
                        Text("Werttyp des Ausgangs", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim)
                        ChoiceRow(SdIo.entries, d.fbIo, { it.label }) { d = d.copy(fbIo = it) }
                        OutlinedTextField(
                            d.fbOn, { d = d.copy(fbOn = it) },
                            label = { Text("Gilt als an bei") },
                            placeholder = { Text(rules.onWords.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingText = { Text("Kommagetrennt, z. B. Running, 1. Leer = globale Regeln.") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Empfangenen Wert anzeigen", modifier = Modifier.weight(1f))
                        Switch(checked = d.showValue, onCheckedChange = { d = d.copy(showValue = it) })
                    }
                }
                if (d.type == SdType.BUTTON || d.type == SdType.FADER || d.type == SdType.LED || d.type == SdType.ROTARY) {
                    EditorHeading("MIDI-Controller")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (d.midi.isBlank()) "Keine Zuordnung" else midiLabel(d.midi),
                            modifier = Modifier.weight(1f)
                        )
                        if (d.midi.isNotBlank()) TextButton(onClick = { d = d.copy(midi = "") }) { Text("Entfernen") }
                        TextButton(onClick = { midi.learning.value = if (learning == e.id) null else e.id }) {
                            Text(if (learning == e.id) "Warte auf MIDI …" else "MIDI lernen")
                        }
                    }
                    if (d.midi.startsWith("N:") && !d.type.numeric) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                d.midiOn.toString(), { v -> v.toIntOrNull()?.let { d = d.copy(midiOn = it.coerceIn(0, 127)) } },
                                label = { Text("LED an") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                d.midiOff.toString(), { v -> v.toIntOrNull()?.let { d = d.copy(midiOff = it.coerceIn(0, 127)) } },
                                label = { Text("LED aus") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        Text(
                            "Velocity der LED-Rückmeldung. Beim APC mini mk2 bestimmt sie die Farbe (z. B. 5 rot, 21 grün).",
                            style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Row {
                    TextButton(onClick = onDuplicate) { Text("Duplizieren") }
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Wirklich löschen" else "Löschen", color = Desk.Red)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = d.name.isNotBlank(),
                onClick = {
                    val mn = minText.replace(',', '.').toDoubleOrNull() ?: d.min
                    val mx = maxText.replace(',', '.').toDoubleOrNull() ?: d.max
                    onSave(d.copy(name = d.name.trim(), min = mn, max = if (mx == mn) mn + 1 else mx))
                }
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun DeskDialog(desk: Softdesk, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Softdesk) -> Unit) {
    var d by remember(desk.id) { mutableStateOf(desk) }
    var confirmDelete by remember { mutableStateOf(false) }
    val minCols = desk.elements.maxOfOrNull { it.x + it.w } ?: 1
    val minRows = desk.elements.maxOfOrNull { it.y + it.h } ?: 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bedienfeld") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(d.name, { d = d.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Stepper("Spalten", d.cols, maxOf(1, minCols), 24) { d = d.copy(cols = it) }
                Stepper("Zeilen", d.rows, maxOf(1, minRows), 40) { d = d.copy(rows = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("An Bildschirmhöhe anpassen")
                        Text("Aus: quadratische Felder, Raster scrollt", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                    }
                    Switch(checked = d.fit, onCheckedChange = { d = d.copy(fit = it) })
                }
                Text(
                    "Spalten und Zeilen lassen sich nicht unter die belegte Fläche verkleinern.",
                    style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                )
            }
        },
        confirmButton = { TextButton(enabled = d.name.isNotBlank(), onClick = { onSave(d.copy(name = d.name.trim())) }) { Text("Speichern") } },
        dismissButton = {
            Row {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "Wirklich löschen" else "Löschen", color = Desk.Red)
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}

@Composable
private fun RulesDialog(rules: FeedbackRules, onDismiss: () -> Unit, onSave: (FeedbackRules) -> Unit) {
    var words by remember { mutableStateOf(rules.onWords.joinToString(", ")) }
    var threshold by remember { mutableStateOf(rules.threshold.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rückmeldung auswerten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Gilt für alle Tasten, LEDs und Anzeigen ohne eigene Angabe. Ja/Nein-Werte gelten direkt.",
                    style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                )
                OutlinedTextField(
                    words, { words = it },
                    label = { Text("Als an gelten") },
                    supportingText = { Text("Kommagetrennt, Groß-/Kleinschreibung egal") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    threshold, { threshold = it },
                    label = { Text("Zahlen an ab über") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = {
                    words = FeedbackRules.DEFAULT_ON.joinToString(", ")
                    threshold = "0.5"
                }) { Text("Standard wiederherstellen") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    FeedbackRules(
                        words.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        threshold.replace(',', '.').toDoubleOrNull() ?: 0.5
                    )
                )
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun DiagDialog(s: UmbraSession, onDismiss: () -> Unit) {
    val lines by s.softdesk.diag.collectAsStateWithLifecycle()
    val c by s.softdesk.diagCounts.collectAsStateWithLifecycle()
    val server by s.softdesk.serverCounts.collectAsStateWithLifecycle()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnose Rückkanal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    server?.let { "In DMXControl angemeldet: ${it.first} Eingänge, ${it.second} Ausgänge" } ?: "Anmeldung nicht bestätigt",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Empfangen: ${c.proxyMsgs} Proxy-Nachrichten, ${c.sinkMsgs} Sink-Nachrichten, ${c.matched} einem Element zugeordnet",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { s.softdesk.cleanup(false) }) { Text("Verwaiste Kanäle entfernen") }
                OutlinedButton(onClick = { s.softdesk.cleanup(true) }) { Text("Auch Reste früherer Installationen") }
                Text(
                    "Die zweite Variante entfernt alle Kanäle dieser App mit anderer Runtime-ID, auch die eines zweiten Geräts mit der App.",
                    style = MaterialTheme.typography.labelSmall, color = Desk.TextDim
                )
                HorizontalDivider()
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    if (lines.isEmpty()) Text("Noch nichts empfangen.", color = Desk.TextDim, style = MaterialTheme.typography.bodySmall)
                    lines.forEach { l ->
                        Text(l, style = MaterialTheme.typography.labelSmall.merge(Numeric), color = if ("nicht zugeordnet" in l) Desk.Amber else Desk.Text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(s.softdesk.diagReport()))
                s.emit("Diagnose in die Zwischenablage kopiert")
            }) { Text("Kopieren") }
        }
    )
}

private fun midiLabel(key: String): String {
    val p = key.split(':')
    if (p.size != 3) return key
    val kind = if (p[0] == "N") "Note" else "Regler (CC)"
    return "$kind ${p[2]}, Kanal ${(p[1].toIntOrNull() ?: 0) + 1}"
}

// ================================================================== Neue Elemente
/** Drehregler: senkrecht oder waagerecht ziehen (relativ, wie der Fader), Bogen zeigt den Wert. */
@Composable
private fun SdRotary(e: SdElement, fb: ObjectData?, color: Color, s: UmbraSession, enabled: Boolean) {
    var local by remember(e.id) { mutableStateOf(0f) }
    var dragging by remember(e.id) { mutableStateOf(false) }
    val span = (e.max - e.min).takeIf { it != 0.0 } ?: 1.0
    val fbPos = fb?.asDouble()?.let { ((it - e.min) / span).toFloat().coerceIn(0f, 1f) }
    val pos = if (dragging) local else fbPos ?: local
    val cur by rememberUpdatedState(pos)
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(Desk.Panel).border(1.dp, Desk.Line, shape).padding(6.dp)
            .then(if (!enabled) Modifier else Modifier.pointerInput(e.id) {
                detectDragGestures(
                    onDragStart = { local = cur; dragging = true },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false }
                ) { change, d ->
                    change.consume()
                    // nach oben/rechts = mehr; 200 dp Weg = voller Bereich
                    local = (local + (d.x - d.y) / (200.dp.toPx())).coerceIn(0f, 1f)
                    s.softdesk.fader(e, local.toDouble())
                }
            }),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (e.name.isNotBlank()) Text(e.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val d = minOf(size.width, size.height) * 0.86f
                val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                val st = androidx.compose.ui.graphics.drawscope.Stroke(d * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawArc(Desk.Raised, 135f, 270f, false, tl, androidx.compose.ui.geometry.Size(d, d), style = st)
                drawArc(color, 135f, 270f * pos, false, tl, androidx.compose.ui.geometry.Size(d, d), style = st)
                val a = Math.toRadians((135.0 + 270.0 * pos))
                val c = Offset(size.width / 2, size.height / 2)
                drawLine(Desk.Text, c, c + Offset((kotlin.math.cos(a) * d * 0.32).toFloat(), (kotlin.math.sin(a) * d * 0.32).toFloat()), d * 0.05f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            if (e.showValue) Text(
                if (e.min == 0.0 && e.max == 1.0) "${(pos * 100).roundToInt()}" else "%.1f".format(e.min + pos * span),
                style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.TextDim, modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** XY-Feld: sendet X und Y als zwei Eingänge ("<Name> X", "<Name> Y"). */
@Composable
private fun SdXY(e: SdElement, color: Color, s: UmbraSession, enabled: Boolean) {
    var x by remember(e.id) { mutableStateOf(0.5f) }
    var y by remember(e.id) { mutableStateOf(0.5f) }
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Desk.Panel).padding(6.dp)) {
        if (e.name.isNotBlank()) Text(e.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        XYPad(x, y, { nx, ny ->
            if (enabled) { x = nx; y = ny; s.softdesk.xy(e, nx.toDouble(), (1f - ny).toDouble()) }
        }, color, Modifier.fillMaxWidth().weight(1f))
    }
}

/** Auswahl: eine von mehreren Optionen, aktive Option aus der Rückmeldung (oder zuletzt gewählt). */
@Composable
private fun SdRadio(e: SdElement, fb: ObjectData?, color: Color, s: UmbraSession, enabled: Boolean) {
    val opts = remember(e.options) { e.optionList() }
    var last by remember(e.id) { mutableStateOf<String?>(null) }
    val active = fb?.text()?.takeIf { it.isNotBlank() } ?: last
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vertical = maxHeight > maxWidth * 1.2f
        @Composable
        fun cell(label: String, value: String, mod: Modifier) {
            val on = active != null && (active == value || active.toDoubleOrNull()?.let { a -> value.replace(',', '.').toDoubleOrNull() == a } == true)
            Box(
                mod.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(if (on) color else Desk.Raised)
                    .then(if (enabled) Modifier.pointerInput(value) { detectTapGestures { last = value; s.softdesk.radio(e, value) } } else Modifier),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (on) Color.Black else Desk.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(Modifier.fillMaxSize()) {
            if (e.name.isNotBlank()) Text(e.name, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            if (vertical) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { opts.forEach { (l, v) -> cell(l, v, Modifier.weight(1f)) } }
            else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) { opts.forEach { (l, v) -> cell(l, v, Modifier.weight(1f)) } }
        }
    }
}

/** Cuelist-Monitor: Zustand, aktueller/nächster Cue, Fortschritt, Go/Stop/Zurück. */
@Composable
private fun SdCuelistMonitor(e: SdElement, color: Color, s: UmbraSession, enabled: Boolean) {
    val lists by s.cuelists.items.collectAsStateWithLifecycle()
    val progress by s.cuelists.progress.collectAsStateWithLifecycle()
    val cl = lists[e.target]
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxSize().clip(shape).background(Desk.Panel).border(1.dp, Desk.Line, shape).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (cl == null) { Text(if (e.target.isBlank()) "Cuelist im Bearbeiten-Modus wählen" else "Cuelist nicht gefunden", color = Desk.TextDim, style = MaterialTheme.typography.bodySmall); return@Column }
        val cues = s.cuelists.cuesOf(cl.id)
        val cur = cues.firstOrNull { it.cueIndex == cl.index }
        val next = cues.firstOrNull { it.cueIndex == cl.nextIndex }
        val p = progress[cl.id]?.cueProgressStateMap?.get(cl.index)?.progress
        val stateColor = when (cl.state) {
            LumosProtobuf.Cuelist.CuelistServiceTypes.ESceneListState.RUNNING -> Desk.Green
            LumosProtobuf.Cuelist.CuelistServiceTypes.ESceneListState.PAUSED -> Desk.Amber
            else -> Desk.Line
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor))
            Text((e.name.ifBlank { cl.name }), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▶ " + (cur?.name?.ifBlank { null } ?: if (cur != null) "Cue ${cl.index + 1}" else "–"), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Nächster: " + (next?.name?.ifBlank { null } ?: if (next != null) "Cue ${cl.nextIndex + 1}" else "–"), style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        androidx.compose.material3.LinearProgressIndicator(progress = { ((p ?: 0.0).toFloat()).coerceIn(0f, 1f) }, color = color, trackColor = Desk.Raised, modifier = Modifier.fillMaxWidth().height(4.dp))
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("◀" to LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest.EAction.GO_BACK,
                "GO" to LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest.EAction.GO,
                "■" to LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest.EAction.STOP).forEach { (l, a) ->
                Box(
                    Modifier.weight(if (l == "GO") 2f else 1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (l == "GO") color else Desk.Raised)
                        .then(if (enabled) Modifier.pointerInput(a) { detectTapGestures { s.cuelists.action(cl.id, a) } } else Modifier),
                    contentAlignment = Alignment.Center
                ) { Text(l, color = if (l == "GO") Color.Black else Desk.Text, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** Cuelist-Auswahl: alle Cuelists, antippen = Go, lange drücken = Stop. */
@Composable
private fun SdCuelistSelector(e: SdElement, color: Color, s: UmbraSession, enabled: Boolean) {
    val lists by s.cuelists.items.collectAsStateWithLifecycle()
    val sorted = remember(lists) { lists.values.sortedBy { it.number } }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxSize().clip(shape).background(Desk.Panel).border(1.dp, Desk.Line, shape).padding(4.dp)) {
        if (e.name.isNotBlank()) Text(e.name, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(4.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(sorted, key = { it.id }) { cl ->
                val running = cl.state == LumosProtobuf.Cuelist.CuelistServiceTypes.ESceneListState.RUNNING
                Row(
                    Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (running) color.copy(alpha = 0.3f) else Desk.Raised)
                        .then(if (enabled) Modifier.pointerInput(cl.id) {
                            detectTapGestures(
                                onTap = { s.cuelists.action(cl.id, LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest.EAction.GO) },
                                onLongPress = { s.cuelists.action(cl.id, LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest.EAction.STOP) }
                            )
                        } else Modifier)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${cl.number}", style = MaterialTheme.typography.labelMedium.merge(Numeric), color = Desk.TextDim, modifier = Modifier.width(28.dp))
                    Text(cl.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (running) Box(Modifier.size(8.dp).clip(CircleShape).background(Desk.Green))
                }
            }
        }
    }
}

/** Geräte-Auswahl: Gerätegruppen (oder Einzelgeräte) für den Programmer wählen. */
@Composable
private fun SdDeviceSelector(e: SdElement, color: Color, s: UmbraSession, enabled: Boolean) {
    val groups by s.devices.groups.collectAsStateWithLifecycle()
    val devices by s.stage.devices.collectAsStateWithLifecycle()
    val ctx by s.userContext.collectAsStateWithLifecycle()
    val single = e.target == "devices"
    val entries = remember(groups, devices, single) {
        if (single) devices.values.sortedBy { it.number }.map { Triple(it.id, "${it.number}", it.name) }
        else groups.values.sortedBy { it.number }.map { Triple(it.id, "${it.number}", it.name) }
    }
    var picked by remember(e.id) { mutableStateOf(setOf<String>()) }
    val selected = if (single) picked else (ctx?.selectedDeviceGroupsList?.toSet().orEmpty() + listOfNotNull(ctx?.selectedDeviceGroup?.ifBlank { null }))
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxSize().clip(shape).background(Desk.Panel).border(1.dp, Desk.Line, shape).padding(4.dp)) {
        if (e.name.isNotBlank()) Text(e.name, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(4.dp))
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Adaptive(88.dp), Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(entries.size) { i ->
                val (id, nr, name) = entries[i]
                val on = id in selected
                Column(
                    Modifier.height(44.dp).clip(RoundedCornerShape(6.dp)).background(if (on) color else Desk.Raised)
                        .then(if (enabled) Modifier.pointerInput(id, on) {
                            detectTapGestures {
                                if (single) {
                                    picked = if (on) picked - id else picked + id
                                    s.devices.selectIds(picked.toList())
                                } else s.devices.select(listOf(id))
                            }
                        } else Modifier)
                        .padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(name, style = MaterialTheme.typography.labelMedium, color = if (on) Color.Black else Desk.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(nr, style = MaterialTheme.typography.labelSmall, color = if (on) Color.Black.copy(alpha = 0.7f) else Desk.TextDim)
                }
            }
        }
    }
}

/** Uhr (optional mit Datum). */
@Composable
private fun SdClock(e: SdElement, color: Color, s: UmbraSession) {
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { now = java.time.LocalDateTime.now(); kotlinx.coroutines.delay(1000) } }
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Desk.Panel).padding(6.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (e.name.isNotBlank()) Text(e.name, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
        Text(now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")), style = MaterialTheme.typography.headlineSmall.merge(Numeric), color = color)
        if (e.showValue) Text(now.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")), style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
    }
}

/** Cuelist für den Monitor wählen (im Editor). */
@Composable
private fun CuelistPick(s: UmbraSession, selected: String, onPick: (String) -> Unit) {
    val lists by s.cuelists.items.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lists.values.sortedBy { it.number }.forEach { cl ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (cl.id == selected) Desk.Amber.copy(alpha = 0.3f) else Desk.Raised)
                    .pointerInput(cl.id) { detectTapGestures { onPick(cl.id) } }.padding(8.dp)
            ) {
                Text("${cl.number}", modifier = Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium)
                Text(cl.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
