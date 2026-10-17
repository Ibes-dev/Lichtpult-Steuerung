package de.sebi.dmxc3remote.ui

import LumosProtobuf.StageViewServiceTypes.EDeviceDisplayType
import LumosProtobuf.StageViewServiceTypes.StageViewDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.LiveOut
import de.sebi.dmxc3remote.net.UmbraSession
import kotlin.math.abs
import kotlin.math.sqrt

/** Ein Element der Bühne in Bühnenkoordinaten (nach optionaler GUI-Transformation). */
data class StageItem(
    val id: String,
    val number: String,
    val name: String,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val group: Boolean,
    val display: EDeviceDisplayType = EDeviceDisplayType.CUSTOM,
    val typeShort: String = "",
    /** für Geräte: zugehörige Teil-Elemente (Aliase), für Aliase: Gerät */
    val parent: String? = null
)

private fun colorOf(c: LumosProtobuf.ValueTypes.ColorData) = Color(c.r, c.g, c.b, if (c.a == 0) 255 else c.a)

/** .NET-Matrix (M11, M12, M21, M22, OffsetX, OffsetY) auf einen Punkt anwenden. UNVERIFIZIERT: Reihenfolge der Elemente. */
private fun transform(m: List<Float>, x: Float, y: Float): Pair<Float, Float> =
    if (m.size != 6) x to y else (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])

@Composable
fun StageScreen(s: UmbraSession) {
    val views by s.stage.views.collectAsStateWithLifecycle()
    val devices by s.stage.devices.collectAsStateWithLifecycle()
    val groups by s.devices.groups.collectAsStateWithLifecycle()
    val live by s.stage.live.collectAsStateWithLifecycle()
    val highlight by s.stage.highlight.collectAsStateWithLifecycle()
    val err by s.stage.error.collectAsStateWithLifecycle()

    var viewId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(listOf<String>()) }   // Reihenfolge = Antipp-Reihenfolge
    var labelMode by rememberSaveable { mutableStateOf(0) }          // 0 Nummer, 1 Name, 2 beides, 3 aus
    var liveOn by rememberSaveable { mutableStateOf(true) }
    var guiLayout by rememberSaveable { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var newViewDialog by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    val sorted = remember(views) { views.values.sortedBy { if (it.orderIndexSet) it.orderIndex else Int.MAX_VALUE } }
    val view = views[viewId] ?: sorted.firstOrNull()

    fun applySelection(ids: List<String>) {
        selected = ids
        s.devices.selectIds(ids)
    }

    // Live-Abfrage nur für Geräte der sichtbaren Ansicht
    LaunchedEffect(view?.id, liveOn) {
        s.stage.setLiveDevices(if (liveOn && view != null) view.devicesList.map { it.deviceId } + view.devicesList.flatMap { it.aliasesList } else emptyList())
    }
    DisposableEffect(Unit) { onDispose { s.stage.setLiveDevices(emptyList()) } }

    val wide = isWide()
    val selGroup by s.devices.selectionGroup.collectAsStateWithLifecycle()
    val presets by s.presets.items.collectAsStateWithLifecycle()
    val presetErr by s.presets.error.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(sorted, key = { it.id }) { v ->
                FilterChip(selected = view?.id == v.id, onClick = { viewId = v.id; zoom = 1f; pan = Offset.Zero }, label = { Text(v.name.ifBlank { "Ansicht" }) })
            }
        }
        // Werkzeugleiste (horizontal scrollbar, damit auf dem Handy nichts abgeschnitten wird)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val all = view?.let { v -> v.devicesList.map { it.deviceId } } ?: emptyList()
            OutlinedButton(onClick = { applySelection(all) }, enabled = !edit) { Text("Alle") }
            OutlinedButton(onClick = { applySelection(emptyList()) }, enabled = !edit) { Text("Keine") }
            OutlinedButton(onClick = { applySelection(all.filter { it !in selected }) }, enabled = !edit) { Text("Umkehren") }
            FilterChip(selected = highlight, onClick = { s.stage.setHighlight(!highlight) }, label = { Text("Highlight") })
            FilterChip(selected = liveOn, onClick = { liveOn = !liveOn }, label = { Text("Live") })
            FilterChip(selected = guiLayout, onClick = { guiLayout = !guiLayout; zoom = 1f; pan = Offset.Zero }, enabled = !edit, label = { Text("Layout wie DMXControl") })
            FilterChip(
                selected = labelMode != 3,
                onClick = { labelMode = (labelMode + 1) % 4 },
                label = { Text(listOf("Nummer", "Name", "Nr. + Name", "ohne Text")[labelMode]) }
            )
            OutlinedButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Einpassen") }
            FilterChip(selected = edit, onClick = { edit = !edit; if (edit) guiLayout = false }, label = { Text(if (edit) "Bearbeiten: an" else "Bearbeiten") })
            if (edit) {
                OutlinedButton(onClick = { newViewDialog = true }) { Text("Neue Ansicht") }
                if (view != null) OutlinedButton(onClick = { deleteConfirm = true }) { Text("Ansicht löschen", color = Desk.Red) }
            }
        }
        if (view == null) {
            EmptyHint("Keine Bühnenansicht", "In DMXControl ist keine StageView angelegt. Unter Bearbeiten lässt sich eine anlegen.")
        } else {
            val items = remember(view, devices, groups, guiLayout) { buildItems(view, devices, groups, guiLayout) }
            Text(
                when {
                    edit -> "Bearbeiten: Gerät ziehen verschiebt es in DMXControl. Ohne Drehung dargestellt."
                    selected.isEmpty() -> "Antippen wählt aus, lange drücken und ziehen wählt per Rahmen, zwei Finger zoomen. Doppeltipp passt ein."
                    else -> "${selected.size} ausgewählt, Reihenfolge wie angetippt"
                },
                style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(horizontal = 10.dp)
            )
            val canvas: @Composable (Modifier) -> Unit = { mod ->
                StageCanvas(
                    mod, view, items, live, liveOn, guiLayout, labelMode, selected, edit, zoom, pan,
                    onTransform = { z, p -> zoom = z; pan = p },
                    onToggle = { id -> applySelection(if (id in selected) selected - id else selected + id) },
                    onLasso = { ids -> applySelection(selected + ids.filter { it !in selected }) },
                    onMove = { item, nx, ny -> if (item.group) s.stage.moveGroup(view.id, item.id, nx, ny) else s.stage.moveDevice(view.id, item.id, nx, ny) }
                )
            }
            val panel: @Composable (Modifier) -> Unit = { mod ->
                Column(mod) {
                    Text(
                        when {
                            selected.isEmpty() -> "Nichts ausgewählt"
                            selGroup == null -> "Auswahl ohne Gruppe von DMXControl"
                            else -> "Auswahl: ${selGroup!!.name.ifBlank { "${selected.size} Elemente" }}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    if (selected.isNotEmpty()) {
                        PropertyPane(s, selGroup, presets.values.sortedBy { it.name }.map { it.presetId to it.name }, presetErr, Modifier.fillMaxSize(), compact = true)
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(1f).fillMaxSize())
                    if (!edit) panel(Modifier.width(400.dp).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(if (selected.isEmpty() || edit) 1f else 0.45f).fillMaxWidth())
                    if (selected.isNotEmpty() && !edit) panel(Modifier.weight(0.55f).fillMaxWidth())
                }
            }
        }
    }

    if (newViewDialog) {
        var name by remember { mutableStateOf("Neue Ansicht") }
        AlertDialog(
            onDismissRequest = { newViewDialog = false },
            title = { Text("Bühnenansicht anlegen") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { s.stage.createView(view?.id, name.trim()); newViewDialog = false }) { Text("Als Kopie anlegen") }
            },
            dismissButton = {
                TextButton(onClick = { s.stage.createView(null, name.trim()); newViewDialog = false }) { Text("Leer anlegen") }
            }
        )
    }
    if (deleteConfirm && view != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Ansicht löschen?") },
            text = { Text("\"${view.name}\" wird in DMXControl gelöscht.") },
            confirmButton = { TextButton(onClick = { s.stage.deleteView(view.id); viewId = null; deleteConfirm = false }) { Text("Löschen", color = Desk.Red) } },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Abbrechen") } }
        )
    }
}

private fun buildItems(
    view: StageViewDescriptor,
    devices: Map<String, LumosProtobuf.DeviceServiceTypes.DeviceDescriptor>,
    groups: Map<String, LumosProtobuf.DeviceServiceTypes.DeviceGroupDescriptor>,
    guiLayout: Boolean
): List<StageItem> {
    val m = if (guiLayout) view.transformationMatrixList else emptyList()
    val sc = if (m.size == 6) sqrt(abs(m[0] * m[3] - m[1] * m[2])).takeIf { it > 0f } ?: 1f else 1f
    fun pos(x: Float, y: Float) = transform(m, x, y)
    val out = ArrayList<StageItem>()
    view.deviceGroupsList.forEach { g ->
        val (x, y) = pos(g.position.x, g.position.y)
        val grp = groups[g.deviceGroupId]
        out += StageItem(
            g.deviceGroupId, grp?.number?.toString() ?: "", grp?.name ?: "Gruppe", x, y,
            (g.size.width.toFloat().takeIf { it > 0 } ?: 60f) * sc, (g.size.height.toFloat().takeIf { it > 0 } ?: 30f) * sc,
            true, g.displayType
        )
    }
    view.devicesList.forEach { d ->
        val dev = devices[d.deviceId]
        val (x, y) = pos(d.position.x, d.position.y)
        val w = (d.size.width.toFloat().takeIf { it > 0 } ?: 40f) * sc
        val h = (d.size.height.toFloat().takeIf { it > 0 } ?: 40f) * sc
        out += StageItem(
            d.deviceId, dev?.number?.toString() ?: "?", dev?.name ?: "", x, y, w, h, false, d.displayType,
            dev?.type?.take(3)?.uppercase() ?: ""
        )
        // Teil-Elemente (z. B. LED-Bar-Zellen) an eigener Position. UNVERIFIZIERT: Alias = Teilgeräte-ID.
        d.aliasPositionsMap.forEach { (alias, p) ->
            val (ax, ay) = pos(p.x, p.y)
            val sub = devices[alias]
            out += StageItem(alias, sub?.number?.toString() ?: "", sub?.name ?: "", ax, ay, w / 3, h / 3, false, EDeviceDisplayType.PREVIEW, "", d.deviceId)
        }
    }
    return out
}

@Composable
private fun StageCanvas(
    modifier: Modifier,
    view: StageViewDescriptor,
    items: List<StageItem>,
    live: Map<String, LiveOut>,
    liveOn: Boolean,
    guiLayout: Boolean,
    labelMode: Int,
    selected: List<String>,
    edit: Boolean,
    zoom: Float,
    pan: Offset,
    onTransform: (Float, Offset) -> Unit,
    onToggle: (String) -> Unit,
    onLasso: (List<String>) -> Unit,
    onMove: (StageItem, Float, Float) -> Unit
) {
    if (items.isEmpty()) {
        EmptyHint("Leere Ansicht", "Diese StageView enthält keine Geräte.")
        return
    }
    val cur = rememberUpdatedState(Triple(zoom, pan, items))
    val toggle by rememberUpdatedState(onToggle)
    val lasso by rememberUpdatedState(onLasso)
    val move by rememberUpdatedState(onMove)
    val transformCb by rememberUpdatedState(onTransform)
    var lassoRect by remember { mutableStateOf<Rect?>(null) }
    var drag by remember { mutableStateOf<Pair<String, Offset>?>(null) }

    val bg = if (guiLayout && view.hasBackColor() && view.backColor.a > 0) colorOf(view.backColor) else Desk.Panel
    val fg = if (guiLayout && view.hasForeColor() && view.foreColor.a > 0) colorOf(view.foreColor) else Desk.Text
    val selColor = if (guiLayout && view.hasBackColorSelected() && view.backColorSelected.a > 0) colorOf(view.backColorSelected) else Desk.Amber

    BoxWithConstraints(modifier.padding(10.dp).clip(RoundedCornerShape(10.dp)).background(bg)) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val minX = items.minOf { it.x }; val minY = items.minOf { it.y }
        val maxX = items.maxOf { it.x + it.w }; val maxY = items.maxOf { it.y + it.h }
        val pad = 20f
        val base = minOf((wPx - 2 * pad) / (maxX - minX).coerceAtLeast(1f), (hPx - 2 * pad) / (maxY - minY).coerceAtLeast(1f))

        fun toScreen(x: Float, y: Float, z: Float, p: Offset) = Offset((pad + (x - minX) * base) * z + p.x, (pad + (y - minY) * base) * z + p.y)
        fun rectOf(i: StageItem, z: Float, p: Offset): Rect {
            val tl = toScreen(i.x, i.y, z, p)
            return Rect(tl, Size(i.w * base * z, i.h * base * z))
        }
        fun hit(pos: Offset): StageItem? {
            val (z, p, its) = cur.value
            // Teil-Elemente vor Geräten vor Gruppen
            return its.filter { it.parent != null }.lastOrNull { rectOf(it, z, p).inflate(4f).contains(pos) }
                ?: its.filter { !it.group && it.parent == null }.lastOrNull { rectOf(it, z, p).inflate(6f).contains(pos) }
                ?: its.filter { it.group }.lastOrNull { rectOf(it, z, p).contains(pos) }
        }

        val gestures = if (edit) {
            Modifier.pointerInput(base) {
                detectDragGestures(
                    onDragStart = { pos -> hit(pos)?.let { drag = it.id to Offset.Zero } },
                    onDragEnd = {
                        val d = drag
                        drag = null
                        if (d != null) {
                            val (z, _, its) = cur.value
                            val item = its.firstOrNull { it.id == d.first && it.parent == null }
                            if (item != null) move(item, item.x + d.second.x / (base * z), item.y + d.second.y / (base * z))
                        }
                    },
                    onDragCancel = { drag = null }
                ) { change, amount ->
                    change.consume()
                    drag = drag?.let { it.first to (it.second + amount) }
                }
            }.pointerInput(Unit) {
                detectTransformGestures { _, p, z, _ ->
                    val (oz, op, _) = cur.value
                    if (z != 1f) transformCb((oz * z).coerceIn(0.5f, 8f), op + p)
                }
            }
        } else {
            Modifier
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, p, z, _ ->
                        val (oz, op, _) = cur.value
                        val nz = (oz * z).coerceIn(0.5f, 8f)
                        // um den Mittelpunkt der Finger zoomen
                        val np = centroid - (centroid - op) * (nz / oz) + p
                        transformCb(nz, np)
                    }
                }
                .pointerInput(base) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos -> lassoRect = Rect(pos, pos) },
                        onDragEnd = {
                            val r = lassoRect
                            lassoRect = null
                            if (r != null) {
                                val (z, p, its) = cur.value
                                val norm = Rect(minOf(r.left, r.right), minOf(r.top, r.bottom), maxOf(r.left, r.right), maxOf(r.top, r.bottom))
                                lasso(its.filter { !it.group && it.parent == null && norm.contains(rectOf(it, z, p).center) }.map { it.id })
                            }
                        },
                        onDragCancel = { lassoRect = null }
                    ) { change, amount ->
                        change.consume()
                        lassoRect = lassoRect?.let { Rect(it.topLeft, it.bottomRight + amount) }
                    }
                }
                .pointerInput(base) {
                    detectTapGestures(
                        onTap = { pos -> hit(pos)?.let { toggle(it.id) } },
                        onDoubleTap = { transformCb(1f, Offset.Zero) }
                    )
                }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 11.dp.toPx()
            }
            // Raster aus der StageView (nur ohne Drehung sinnvoll)
            val grid = view.gridSpace.toFloat()
            if (grid > 0 && !guiLayout && grid * base * zoom >= 12f) {
                val gridColor = Desk.Line.copy(alpha = 0.35f)
                var gx = (kotlin.math.floor(minX / grid) * grid)
                while (gx <= maxX) {
                    val sx = toScreen(gx, minY, zoom, pan).x
                    drawLine(gridColor, Offset(sx, 0f), Offset(sx, size.height)); gx += grid
                }
                var gy = (kotlin.math.floor(minY / grid) * grid)
                while (gy <= maxY) {
                    val sy = toScreen(minX, gy, zoom, pan).y
                    drawLine(gridColor, Offset(0f, sy), Offset(size.width, sy)); gy += grid
                }
            }
            val order = items.sortedBy { if (it.group) 0 else if (it.parent == null) 1 else 2 }
            order.forEach { i ->
                var r = rectOf(i, zoom, pan)
                val dragging = drag?.takeIf { it.first == i.id }
                if (dragging != null) r = r.translate(dragging.second)
                val selIdx = selected.indexOf(i.id)
                val lo = if (liveOn) live[i.id] else null
                val liveColor = lo?.let { o ->
                    val k = o.intensity ?: 1f
                    if (o.hasColor) Color(o.r * k, o.g * k, o.b * k) else Color(k, k, k)
                }
                if (i.group) {
                    drawRoundRect(
                        if (selIdx >= 0) selColor.copy(alpha = 0.25f) else Color(0xFF3A3F6B).copy(alpha = 0.35f),
                        r.topLeft, r.size, CornerRadius(8f)
                    )
                    drawRoundRect(
                        if (selIdx >= 0) selColor else Color(0xFF6B72B0), r.topLeft, r.size, CornerRadius(8f),
                        style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                    )
                } else {
                    val fill = liveColor ?: Desk.Raised
                    when (i.display) {
                        EDeviceDisplayType.PREVIEW -> drawCircle(fill, minOf(r.width, r.height) / 2, r.center)
                        EDeviceDisplayType.IMAGE -> {
                            drawRoundRect(Desk.Raised, r.topLeft, r.size, CornerRadius(10f))
                            drawCircle(fill, minOf(r.width, r.height) / 4, Offset(r.center.x, r.top + r.height * 0.35f))
                        }
                        else -> drawRoundRect(fill, r.topLeft, r.size, CornerRadius(6f))
                    }
                    if (selIdx >= 0) {
                        drawRoundRect(selColor, r.topLeft, r.size, CornerRadius(6f), style = Stroke(3.dp.toPx()))
                    } else {
                        drawRoundRect(Desk.Line, r.topLeft, r.size, CornerRadius(6f), style = Stroke(1.2f))
                    }
                }
                // Beschriftung
                if (labelMode != 3 && r.width > 14f) {
                    val text = when (labelMode) {
                        0 -> i.number
                        1 -> i.name.ifBlank { i.number }
                        else -> listOf(i.number, i.name).filter { it.isNotBlank() }.joinToString(" ")
                    } + if (i.display == EDeviceDisplayType.IMAGE && i.typeShort.isNotBlank() && labelMode != 1) " ${i.typeShort}" else ""
                    val bright = liveColor?.let { 0.3f * it.red + 0.6f * it.green + 0.1f * it.blue > 0.6f } ?: false
                    paint.color = (if (bright && !i.group) Color.Black else fg).let { android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
                    val ty = if (i.group) r.top + paint.textSize + 2 else r.center.y + paint.textSize / 3
                    drawContext.canvas.nativeCanvas.drawText(text.take(18), r.center.x, ty, paint)
                }
                // Reihenfolge der Auswahl
                if (selIdx >= 0 && !i.group) {
                    drawCircle(selColor, 8.dp.toPx(), Offset(r.left + 2, r.top + 2))
                    paint.color = android.graphics.Color.BLACK
                    paint.textSize = 9.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText("${selIdx + 1}", r.left + 2, r.top + 2 + paint.textSize / 3, paint)
                    paint.textSize = 11.dp.toPx()
                }
            }
            lassoRect?.let { lr ->
                val norm = Rect(minOf(lr.left, lr.right), minOf(lr.top, lr.bottom), maxOf(lr.left, lr.right), maxOf(lr.top, lr.bottom))
                drawRect(selColor.copy(alpha = 0.15f), norm.topLeft, norm.size)
                drawRect(selColor, norm.topLeft, norm.size, style = Stroke(1.5f))
            }
        }
    }
}
