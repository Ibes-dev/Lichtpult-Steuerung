package de.sebi.dmxc3remote.ui

import LumosProtobuf.CommonTypes.ObjectData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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

private fun SdElement.colorValue() = SD_PALETTE[color.mod(SD_PALETTE.size)]

@Composable
fun SoftdeskScreen(s: UmbraSession, store: SoftdeskStore) {
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
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        onClick = { deskId = store.addDesk("Softdesk ${desks.size + 1}") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                        label = { Text("Neu") }
                    )
                }
            }
            FilterChip(
                selected = edit,
                onClick = { edit = !edit },
                leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)) },
                label = { Text(if (edit) "Fertig" else "Bearbeiten") },
                modifier = Modifier.padding(end = 10.dp)
            )
        }
        if (edit) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (desk != null) OutlinedButton(onClick = { deskDialog = true }) { Text("Raster") }
                OutlinedButton(onClick = { rulesDialog = true }) { Text("Rückmeldung") }
                OutlinedButton(onClick = { diagDialog = true }) { Text("Diagnose") }
                Text(
                    server?.let { "DMXControl: ${it.first} Eingänge, ${it.second} Ausgänge" } ?: "$registered angemeldet",
                    style = MaterialTheme.typography.labelSmall,
                    color = Desk.TextDim,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    maxLines = 2
                )
            }
        }

        if (desk == null) {
            EmptyHint(
                "Noch kein Softdesk",
                "Auf Bearbeiten tippen, dann Neu. Elemente legst du durch Antippen freier Rasterfelder an. " +
                    "In der DMXControl-Eingangszuweisung stehen sie unter \"Android: ${s.clientName}\"."
            )
        } else {
            DeskCanvas(
                desk = desk,
                edit = edit,
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
            rules = rules,
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
private fun ElementContent(e: SdElement, fb: ObjectData?, rules: FeedbackRules, s: UmbraSession, enabled: Boolean) {
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
    val cur by rememberUpdatedState(e)
    val posNow by rememberUpdatedState(pos)
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(Modifier.fillMaxSize().clip(shape).background(Desk.Panel).border(1.dp, Desk.Line, shape)) {
        val vertical = maxHeight >= maxWidth
        val valueText = if (e.min == 0.0 && e.max == 1.0) "${(pos * 100).roundToInt()} %"
        else "%.1f".format(e.min + pos * span)
        Canvas(
            Modifier.fillMaxSize().then(
                if (!enabled) Modifier else Modifier.pointerInput(e.id, vertical) {
                    detectDragGestures(
                        onDragStart = { local = posNow; dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false }
                    ) { change, d ->
                        change.consume()
                        val travel = (if (vertical) size.height else size.width).toFloat().coerceAtLeast(1f)
                        local = (local + (if (vertical) -d.y else d.x) / travel).coerceIn(0f, 1f)
                        s.softdesk.fader(cur, local.toDouble())
                    }
                }
            )
        ) {
            val track = 8.dp.toPx()
            if (vertical) {
                val cx = size.width / 2
                drawRoundRect(Desk.Raised, Offset(cx - track / 2, 0f), Size(track, size.height), CornerRadius(track / 2))
                val top = size.height * (1 - pos)
                drawRoundRect(color.copy(alpha = 0.7f), Offset(cx - track / 2, top), Size(track, size.height - top), CornerRadius(track / 2))
                val capH = 22.dp.toPx()
                drawRoundRect(
                    if (dragging) color else Desk.Text,
                    Offset(6.dp.toPx(), (top - capH / 2).coerceIn(0f, size.height - capH)),
                    Size(size.width - 12.dp.toPx(), capH),
                    CornerRadius(4.dp.toPx())
                )
            } else {
                val cy = size.height / 2
                drawRoundRect(Desk.Raised, Offset(0f, cy - track / 2), Size(size.width, track), CornerRadius(track / 2))
                val right = size.width * pos
                drawRoundRect(color.copy(alpha = 0.7f), Offset(0f, cy - track / 2), Size(right, track), CornerRadius(track / 2))
                val capW = 22.dp.toPx()
                drawRoundRect(
                    if (dragging) color else Desk.Text,
                    Offset((right - capW / 2).coerceIn(0f, size.width - capW), 6.dp.toPx()),
                    Size(capW, size.height - 12.dp.toPx()),
                    CornerRadius(4.dp.toPx())
                )
            }
        }
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    SdType.BUTTON to "Sendet beim Drücken, leuchtet bei Rückmeldung",
                    SdType.FADER to "Wert stufenlos, folgt der Rückmeldung",
                    SdType.LED to "Zeigt einen Zustand an",
                    SdType.DISPLAY to "Zeigt einen Wert oder Text aus DMXControl",
                    SdType.TEXT to "Feste Beschriftung, ohne Verbindung"
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
    rules: FeedbackRules,
    onDismiss: () -> Unit,
    onSave: (SdElement) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    var d by remember(e.id) { mutableStateOf(e) }
    var minText by remember(e.id) { mutableStateOf(e.min.toString()) }
    var maxText by remember(e.id) { mutableStateOf(e.max.toString()) }
    var confirmDelete by remember(e.id) { mutableStateOf(false) }
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
                if (d.type == SdType.FADER) {
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
                    if (d.type != SdType.FADER) {
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
        title = { Text("Softdesk") },
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
