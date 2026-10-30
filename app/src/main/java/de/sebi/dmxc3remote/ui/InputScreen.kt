package de.sebi.dmxc3remote.ui

import LumosProtobuf.Input.InputServiceTypes.ConnectionSetDescriptor
import LumosProtobuf.Input.InputServiceTypes.ENodeType
import LumosProtobuf.Input.InputServiceTypes.GraphNodePortIDData
import LumosProtobuf.Input.InputServiceTypes.NodeMetadataData
import LumosProtobuf.Input.InputServiceTypes.PortMetadataData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.GraphCatalogItem
import de.sebi.dmxc3remote.net.GraphEdge
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.portKey
import de.sebi.dmxc3remote.net.text
import kotlin.math.abs
import kotlin.math.max

// ================================================================== Farben und Hilfen
private fun NodeMetadataData.color() = when (type) {
    ENodeType.INPUT -> Desk.Cyan
    ENodeType.OUTPUT -> Desk.Amber
    else -> Color(0xFFB9A6F2)
}

private fun NodeMetadataData.kindLabel() = when (type) {
    ENodeType.INPUT -> "Eingang"
    ENodeType.OUTPUT -> "Ausgang"
    else -> "Baustein"
}

/** Art plus – falls vorhanden – Wert des Ausgangs "Name" (z. B. Cuelist: "Baustein · OF Bühne"). */
private fun NodeMetadataData.kindWithName(values: Map<String, String>): String {
    val n = portsList.firstOrNull { !it.isInput && it.label().equals("Name", true) }?.let { values[portKey(it.id)] }?.takeIf { it.isNotBlank() }
    return kindLabel() + (n?.let { " · $it" } ?: "")
}

private fun PortMetadataData.isNamePort() = !isInput && label().equals("Name", true)

private fun NodeMetadataData.title() = name.ifBlank { memberInputValue.ifBlank { typeId.substringAfterLast('.') } }

private fun PortMetadataData.label() = displayName.ifBlank { shortName.ifBlank { id.portId } }

/** Anschlüsse, die angezeigt werden: sichtbare und alle verbundenen. */
private fun NodeMetadataData.shownPorts() = portsList.filter { !it.isDefaultHidden || it.connectedPortsCount > 0 }

/** Signalfarbe nach Wert (Ein/Aus, Zahl, Text) – die Schnittstelle nennt keinen Typ je Anschluss. */
private fun wireColor(v: String?): Color = when {
    v == null -> Desk.Line
    v.equals("true", true) || v.equals("false", true) -> Desk.Green
    v.replace(',', '.').toDoubleOrNull() != null -> Desk.Cyan
    else -> Color(0xFFB9A6F2)
}

/** Positionen aus DMXControl; fehlen sie (alle 0), einfache Ebenen-Anordnung nach Signalfluss. */
private fun layout(set: ConnectionSetDescriptor, edges: List<GraphEdge>): Map<String, Offset> {
    val nodes = set.nodesList.filter { !it.hidden }
    val real = nodes.count { it.hasPosition() && (it.position.x != 0f || it.position.y != 0f) }
    if (real >= nodes.size / 2 && nodes.isNotEmpty()) return nodes.associate { it.id.nodeId to Offset(it.position.x, it.position.y) }
    val depth = HashMap<String, Int>()
    nodes.forEach { depth[it.id.nodeId] = if (it.type == ENodeType.INPUT) 0 else 1 }
    repeat(nodes.size) {
        edges.forEach { e -> val a = depth[e.from.nodeId.nodeId] ?: 0; val b = depth[e.to.nodeId.nodeId] ?: 0; if (b <= a) depth[e.to.nodeId.nodeId] = a + 1 }
    }
    val rows = HashMap<Int, Int>()
    return nodes.associate { n ->
        val d = depth[n.id.nodeId] ?: 0
        val r = rows[d] ?: 0
        rows[d] = r + 1
        n.id.nodeId to Offset(d * 220f, r * 90f)
    }
}

// ================================================================== Bildschirm
/**
 * Eingangszuweisung: Bänke → Verbindungs-Sets → Übersicht (echter Graph) → Fokus (ein Baustein mit Nachbarn).
 */
@Composable
fun InputScreen(s: UmbraSession) {
    val m = s.inputGraph
    val banks by m.banks.collectAsStateWithLifecycle()
    val sets by m.sets.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    var bankId by rememberSaveable { mutableStateOf<String?>(null) }
    var setId by rememberSaveable { mutableStateOf<String?>(null) }
    val trail = remember { mutableStateListOf<String>() }      // Fokus-Verlauf (Knoten-IDs)
    val bank = banks.firstOrNull { it.id == bankId } ?: banks.firstOrNull { it.active } ?: banks.firstOrNull()
    val set = setId?.let { sets[it] }

    LaunchedEffect(set?.id) { m.watch(set?.id) }
    DisposableEffect(Unit) { onDispose { m.watch(null) } }

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        when {
            set == null -> SetList(s, banks, bank, sets) { id -> setId = id; trail.clear() }
            trail.isEmpty() -> {
                GraphHeader(set.name, null, onBack = { setId = null }) { trail.clear() }
                Overview(s, set) { nodeId -> trail.clear(); trail.add(nodeId) }
            }
            else -> {
                val nodeId = trail.last()
                val node = set.nodesList.firstOrNull { it.id.nodeId == nodeId }
                if (node == null) { trail.clear(); return@Column }
                GraphHeader(set.name, trail.map { id -> set.nodesList.firstOrNull { it.id.nodeId == id }?.title() ?: "?" }, onBack = { trail.removeAt(trail.lastIndex) }) { i ->
                    if (i < 0) trail.clear() else while (trail.size > i + 1) trail.removeAt(trail.lastIndex)
                }
                Focus(s, set, node) { next -> val i = trail.indexOf(next); if (i >= 0) while (trail.size > i + 1) trail.removeAt(trail.lastIndex) else trail.add(next) }
            }
        }
    }
}

@Composable
private fun GraphHeader(title: String, crumbs: List<String>?, onBack: () -> Unit, onCrumb: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    if (crumbs != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Übersicht", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.clickableNoRipple { onCrumb(-1) }.padding(4.dp))
        crumbs.forEachIndexed { i, c ->
            Text("›", color = Desk.TextDim, style = MaterialTheme.typography.labelMedium)
            Text(c, style = MaterialTheme.typography.labelMedium, color = if (i == crumbs.lastIndex) Desk.Amber else Desk.TextDim,
                modifier = Modifier.clickableNoRipple { onCrumb(i) }.padding(4.dp), maxLines = 1)
        }
    }
}

// ================================================================== Liste der Sets
@Composable
private fun SetList(
    s: UmbraSession, banks: List<LumosProtobuf.Input.InputServiceTypes.BankDescriptor>,
    bank: LumosProtobuf.Input.InputServiceTypes.BankDescriptor?, sets: Map<String, ConnectionSetDescriptor>, onOpen: (String) -> Unit
) {
    val m = s.inputGraph
    var newSet by remember { mutableStateOf(false) }
    var delSet by remember { mutableStateOf<ConnectionSetDescriptor?>(null) }
    var renSet by remember { mutableStateOf<ConnectionSetDescriptor?>(null) }
    var shownBank by rememberSaveable { mutableStateOf(bank?.id) }
    val cur = banks.firstOrNull { it.id == shownBank } ?: bank
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
        items(banks, key = { it.id }) { b ->
            SmallChip((if (b.active) "● " else "") + b.name.ifBlank { "Bank ${b.number}" }, cur?.id == b.id, { shownBank = b.id })
        }
    }
    if (cur == null) { EmptyHint("Keine Bänke", "In DMXControl ist keine Eingangszuweisung angelegt."); return }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (cur.active) "Bank aktiv" else "Bank nicht aktiv", style = MaterialTheme.typography.labelMedium,
            color = if (cur.active) Desk.Green else Desk.TextDim, modifier = Modifier.weight(1f))
        Switch(checked = cur.active, onCheckedChange = { m.setBankActive(cur.id, it) })
        TextButton(onClick = { newSet = true }) { Text("+ Set") }
    }
    val list = cur.connectionSetIdsList.mapNotNull { sets[it] }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (list.isEmpty()) item { Text("Keine Verbindungs-Sets in dieser Bank.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim) }
        items(list, key = { it.id }) { set ->
            val edges = remember(set) { m.edges(set) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Desk.Panel)
                    .then(if (set.state and 4 != 0) Modifier.border(1.dp, Desk.Red, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onOpen(set.id) }.padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(set.name.ifBlank { "Verbindungs-Set" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (set.state and 4 != 0) Text(
                        "Fehler", style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(8.dp)).background(Desk.Red).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    IconButton(onClick = { renSet = set }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Edit, contentDescription = "Set umbenennen", tint = Desk.TextDim) }
                    IconButton(onClick = { delSet = set }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Delete, contentDescription = "Set löschen", tint = Desk.TextDim) }
                    Switch(checked = set.active, onCheckedChange = { m.setSetActive(set.id, it) })
                }
                if (set.nodesCount > 0) MiniGraph(set, edges, Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp))
                Text("${set.nodesList.count { !it.hidden }} Bausteine · ${edges.size} Verbindungen", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (newSet) RenameDialog("Neues Verbindungs-Set", "Neues Set", { name -> m.createSet(cur.id, name); newSet = false }) { newSet = false }
    renSet?.let { r -> RenameDialog("Set umbenennen", r.name, { name -> m.renameSet(r.id, name); renSet = null }) { renSet = null } }
    delSet?.let { d ->
        AlertDialog(
            onDismissRequest = { delSet = null },
            title = { Text("„${d.name}“ löschen?") },
            text = { Text("Das Verbindungs-Set wird in DMXControl gelöscht.") },
            confirmButton = { TextButton(onClick = { m.deleteSet(d.id); delSet = null }) { Text("Löschen", color = Desk.Red) } },
            dismissButton = { TextButton(onClick = { delSet = null }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun MiniGraph(set: ConnectionSetDescriptor, edges: List<GraphEdge>, modifier: Modifier) {
    val pos = remember(set, edges) { layout(set, edges) }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Desk.Surface)) {
        if (pos.isEmpty()) return@Canvas
        val minX = pos.values.minOf { it.x }; val maxX = pos.values.maxOf { it.x } + 140f
        val minY = pos.values.minOf { it.y }; val maxY = pos.values.maxOf { it.y } + 46f
        val k = minOf((size.width - 12f) / (maxX - minX).coerceAtLeast(1f), (size.height - 12f) / (maxY - minY).coerceAtLeast(1f))
        fun p(o: Offset) = Offset(6f + (o.x - minX) * k, 6f + (o.y - minY) * k)
        edges.forEach { e ->
            val a = pos[e.from.nodeId.nodeId] ?: return@forEach; val b = pos[e.to.nodeId.nodeId] ?: return@forEach
            drawLine(Desk.Line, p(a + Offset(140f, 23f)), p(b + Offset(0f, 23f)), 2f)
        }
        set.nodesList.filter { !it.hidden }.forEach { n ->
            val o = pos[n.id.nodeId] ?: return@forEach
            drawRoundRect(n.color().copy(alpha = 0.8f), p(o), Size(140f * k, 46f * k), CornerRadius(4f))
        }
    }
}

// ================================================================== Übersicht: echter Graph
private const val NW = 150f
private const val NH = 54f

@Composable
private fun Overview(s: UmbraSession, set: ConnectionSetDescriptor, onPick: (String) -> Unit) {
    val m = s.inputGraph
    val values by m.values.collectAsStateWithLifecycle()
    val edges = remember(set) { m.edges(set) }
    val pos = remember(set, edges) { layout(set, edges) }
    val nodes = remember(set) { set.nodesList.filter { !it.hidden } }
    var zoom by remember { mutableFloatStateOf(0f) }        // 0 = noch nicht eingepasst
    var pan by remember { mutableStateOf(Offset.Zero) }
    var addOpen by remember { mutableStateOf(false) }
    val pick by rememberUpdatedState(onPick)
    // Verschieben: lange drücken, ziehen, loslassen → Position an DMXControl
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val shown = if (dragId != null) pos + (dragId!! to dragPos) else pos

    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Weltkoordinaten (DMXControl-Positionen) gelten als dp: Pixel = Welt × zoom × Dichte
        val wPx = constraints.maxWidth / dens; val hPx = constraints.maxHeight / dens
        val minX = pos.values.minOfOrNull { it.x } ?: 0f; val minY = pos.values.minOfOrNull { it.y } ?: 0f
        val maxX = (pos.values.maxOfOrNull { it.x } ?: 0f) + NW; val maxY = (pos.values.maxOfOrNull { it.y } ?: 0f) + NH
        // Einpassen beim Öffnen und mit ⤢ (zoom = 0)
        LaunchedEffect(zoom == 0f, pos, wPx, hPx) {
            if (zoom == 0f && pos.isNotEmpty()) {
                val nz = minOf(wPx / (maxX - minX + 40f), hPx / (maxY - minY + 40f)).coerceIn(0.6f, 2f)
                pan = Offset((20f - minX) * nz * dens, (20f - minY) * nz * dens)
                zoom = nz
            }
        }
        val z = (zoom.takeIf { it > 0f } ?: 1f) * dens
        Canvas(
            Modifier.fillMaxSize().background(Desk.Surface)
                // äußerster Detektor = erhält Ereignisse zuletzt (Main-Durchlauf von innen nach außen)
                .pointerInput(Unit) {
                    detectTransformGestures { c, p, zz, _ ->
                        val nz = (zoom * zz).coerceIn(0.2f, 3f)
                        pan = c - (c - pan) * (nz / zoom) + p; zoom = nz
                    }
                }
                .pointerInput(nodes, pos) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { t ->
                            val w = (t - pan) / (zoom * dens)
                            nodes.lastOrNull { n -> pos[n.id.nodeId]?.let { Rect(it, Size(NW, NH)).contains(w) } == true }?.let {
                                dragId = it.id.nodeId; dragPos = pos[it.id.nodeId] ?: Offset.Zero
                            }
                        },
                        onDragEnd = {
                            val id = dragId
                            val n = nodes.firstOrNull { it.id.nodeId == id }
                            if (n != null) m.moveNode(set.id, n, dragPos.x, dragPos.y)
                            dragId = null
                        },
                        onDragCancel = { dragId = null }
                    ) { change, d -> if (dragId != null) { change.consume(); dragPos += d / (zoom * dens) } }
                }
                .pointerInput(nodes, pos) {
                    detectTapGestures { t ->
                        val w = (t - pan) / (zoom * dens)
                        nodes.lastOrNull { n -> pos[n.id.nodeId]?.let { Rect(it, Size(NW, NH)).contains(w) } == true }?.let { pick(it.id.nodeId) }
                    }
                }
        ) {
            // Punkteraster
            val step = 24f * z
            if (step > 8f * dens) {
                var x = pan.x % step; while (x < size.width) { var y = pan.y % step; while (y < size.height) { drawCircle(Desk.Line, 1.2f, Offset(x, y)); y += step }; x += step }
            }
            fun sc(o: Offset) = Offset(pan.x + o.x * z, pan.y + o.y * z)
            edges.forEach { e ->
                val a = shown[e.from.nodeId.nodeId] ?: return@forEach; val b = shown[e.to.nodeId.nodeId] ?: return@forEach
                val p = sc(a + Offset(NW, NH / 2)); val q = sc(b + Offset(0f, NH / 2))
                val dx = max(40f * z, abs(q.x - p.x) * 0.45f)
                val path = Path().apply { moveTo(p.x, p.y); cubicTo(p.x + dx, p.y, q.x - dx, q.y, q.x, q.y) }
                drawPath(path, wireColor(values[portKey(e.from)]), style = Stroke(2f * dens))
            }
            // Schrift wächst mit dem Zoom, so dass sie immer in den Baustein passt (13 dp bei Zoom 1)
            val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 13f * z; color = android.graphics.Color.WHITE; isFakeBoldText = true }
            val small = android.graphics.Paint(paint).apply { textSize = paint.textSize * 0.8f; isFakeBoldText = false; color = android.graphics.Color.rgb(141, 149, 163) }
            val green = android.graphics.Paint(small).apply { color = android.graphics.Color.rgb(91, 209, 139) }
            nodes.forEach { n ->
                val o = shown[n.id.nodeId] ?: return@forEach
                val tl = sc(o); val sz = Size(NW * z, NH * z)
                drawRoundRect(Desk.Panel, tl, sz, CornerRadius(8f * z))
                if (n.id.nodeId == dragId) drawRoundRect(Desk.Amber, tl, sz, CornerRadius(8f * z), style = Stroke(2f * dens))
                drawRoundRect(n.color(), tl, Size(sz.width, 3f * z.coerceAtLeast(1f)), CornerRadius(2f))
                if (paint.textSize >= 7f * dens) {
                    val nc = drawContext.canvas.nativeCanvas
                    val pad = 8f * z
                    nc.drawText(ellipsize(n.title(), paint, sz.width - 2 * pad), tl.x + pad, tl.y + 19f * z, paint)
                    nc.drawText(ellipsize(n.kindWithName(values), small, sz.width - 2 * pad), tl.x + pad, tl.y + 33f * z, small)
                    n.shownPorts().firstOrNull { !it.isInput && !it.isNamePort() && values[portKey(it.id)] != null }?.let { pp ->
                        nc.drawText(ellipsize("${pp.label()}: ${values[portKey(pp.id)]}", green, sz.width - 2 * pad), tl.x + pad, tl.y + 47f * z, green)
                    }
                } else {
                    // weit herausgezoomt: nur Kurzname mittig, ohne Unterzeilen
                    val tiny = android.graphics.Paint(paint).apply { textSize = 7f * dens }
                    if (sz.width > 24f * dens) drawContext.canvas.nativeCanvas.drawText(ellipsize(n.title(), tiny, sz.width - 4f), tl.x + 2f, tl.y + sz.height / 2 + 3f * dens, tiny)
                }
            }
        }
        Text("Antippen = bearbeiten · lange drücken = Baustein verschieben · zwei Finger = zoomen", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Desk.Surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(6.dp))
        Column(Modifier.align(Alignment.BottomEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundBtn("⤢") { zoom = 0f }
            RoundBtn("+", primary = true) { addOpen = true }
        }
    }
    if (addOpen) AddSheet(s, set, null, Offset((pos.values.maxOfOrNull { it.x } ?: 0f) + 200f, pos.values.minOfOrNull { it.y } ?: 0f)) { addOpen = false; it?.let(onPick) }
}

private fun ellipsize(t: String, p: android.graphics.Paint, w: Float): String {
    if (p.measureText(t) <= w) return t
    var e = t; while (e.length > 1 && p.measureText("$e…") > w) e = e.dropLast(1)
    return "$e…"
}

@Composable
private fun RoundBtn(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (primary) Desk.Amber else Desk.Raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, style = MaterialTheme.typography.titleMedium, color = if (primary) Color.Black else Desk.Text) }
}

// ================================================================== Fokus
@Composable
private fun Focus(s: UmbraSession, set: ConnectionSetDescriptor, node: NodeMetadataData, onGo: (String) -> Unit) {
    val m = s.inputGraph
    val values by m.values.collectAsStateWithLifecycle()
    val edges = remember(set) { m.edges(set) }
    val byId = remember(set) { set.nodesList.associateBy { it.id.nodeId } }
    val myId = node.id.nodeId
    val inE = edges.filter { it.to.nodeId.nodeId == myId }
    val outE = edges.filter { it.from.nodeId.nodeId == myId }
    var connectFor by remember { mutableStateOf<PortMetadataData?>(null) }
    var paramsOpen by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    // Kabel-Endpunkte: Layout-Koordinaten der Elemente, beim Zeichnen relativ zur Zeile umgerechnet –
    // so stimmen die Kabel auch, wenn die Seite gescrollt ist
    val coords = remember { HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>() }
    var rowC by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var layoutTick by remember { mutableStateOf(0) }

    fun portLabel(p: GraphNodePortIDData) = byId[p.nodeId.nodeId]?.portsList?.firstOrNull { it.id.portId == p.portId }?.label() ?: p.portId

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { rowC = it; layoutTick++ }
                .drawBehind {
                    layoutTick   // bei jedem Layout neu zeichnen
                    val rc = rowC ?: return@drawBehind
                    fun box(k: String): Rect? = coords[k]?.takeIf { it.isAttached && rc.isAttached }?.let { rc.localBoundingBoxOf(it, false) }
                    fun wire(a: Rect?, b: Rect?, c: Color) {
                        if (a == null || b == null) return
                        val p = Offset(a.right, a.center.y); val q = Offset(b.left, b.center.y)
                        val dx = max(10f, (q.x - p.x) * 0.5f)
                        drawPath(Path().apply { moveTo(p.x, p.y); cubicTo(p.x + dx, p.y, q.x - dx, q.y, q.x, q.y) }, c, style = Stroke(2.5f))
                    }
                    inE.forEach { e -> wire(box("up:${e.key}"), box("me:${e.to.portId}"), wireColor(values[portKey(e.from)])) }
                    outE.forEach { e -> wire(box("me:${e.from.portId}"), box("dn:${e.key}"), wireColor(values[portKey(e.from)])) }
                },
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // Kommt von
            Column(Modifier.weight(1f).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("KOMMT VON", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
                if (inE.isEmpty()) Text("–", color = Desk.TextDim)
                inE.forEach { e ->
                    val n = byId[e.from.nodeId.nodeId]
                    NeighborCard(n, n?.kindWithName(values), portLabel(e.from), values[portKey(e.from)], Modifier.onGloballyPositioned { coords["up:${e.key}"] = it }) { n?.let { onGo(it.id.nodeId) } }
                }
            }
            // Der Baustein
            Column(Modifier.weight(1.3f).clip(RoundedCornerShape(12.dp)).background(Desk.Panel)) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(node.color()))
                Column(Modifier.padding(8.dp)) {
                    Text(node.title(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(node.kindWithName(values) + (node.description.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SmallChip("⚙ Werte", false, { paramsOpen = true; m.loadNodeParams(set.id, node) })
                        SmallChip("Entfernen", false, { confirmDel = true })
                    }
                }
                node.shownPorts().sortedBy { !it.isInput }.forEach { p ->
                    val linked = p.connectedPortsCount > 0
                    val v = values[portKey(p.id)]
                    HorizontalDivider(color = Desk.Line)
                    Column(Modifier.fillMaxWidth().onGloballyPositioned { coords["me:${p.id.portId}"] = it }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.isInput) Box(Modifier.size(10.dp).clip(CircleShape).background(if (linked) wireColor(v) else Desk.Line))
                            Text(p.label(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (p.isInput) null else androidx.compose.ui.text.style.TextAlign.End)
                            if (!p.isInput) Box(Modifier.size(10.dp).clip(CircleShape).background(if (linked) wireColor(v) else Desk.Line))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!p.isInput) Box(Modifier.weight(1f))
                            if (v != null) Text(v, style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.Green, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp))
                            Text("+ verbinden", style = MaterialTheme.typography.labelSmall, color = Desk.Amber, modifier = Modifier.clickableNoRipple { connectFor = p }.padding(4.dp))
                        }
                    }
                }
            }
            // Geht an
            Column(Modifier.weight(1f).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GEHT AN", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
                if (outE.isEmpty()) Text("–", color = Desk.TextDim)
                outE.forEach { e ->
                    val n = byId[e.to.nodeId.nodeId]
                    NeighborCard(n, n?.kindWithName(values), portLabel(e.to), values[portKey(e.to)], Modifier.onGloballyPositioned { coords["dn:${e.key}"] = it }) { n?.let { onGo(it.id.nodeId) } }
                }
            }
        }
        // Verbindungen als Zeilen mit Lösen
        if (inE.isNotEmpty() || outE.isNotEmpty()) {
            Text("Verbindungen", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            (inE + outE).forEach { e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Desk.Panel).padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(14.dp).height(3.dp).background(wireColor(values[portKey(e.from)])))
                    Text(
                        "${byId[e.from.nodeId.nodeId]?.title()} › ${portLabel(e.from)}  →  ${byId[e.to.nodeId.nodeId]?.title()} › ${portLabel(e.to)}",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { m.disconnect(set.id, e) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Lösen", tint = Desk.Red, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    connectFor?.let { p -> ConnectSheet(s, set, node, p) { connectFor = null } }
    if (paramsOpen) NodeParamsSheet(s, set.id, node) { paramsOpen = false }
    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false },
        title = { Text("„${node.title()}“ entfernen?") },
        text = { Text("Der Baustein und seine Verbindungen werden aus dem Set entfernt.") },
        confirmButton = { TextButton(onClick = { m.deleteNode(set.id, node.id); confirmDel = false }) { Text("Entfernen", color = Desk.Red) } },
        dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("Abbrechen") } }
    )
}

@Composable
private fun NeighborCard(n: NodeMetadataData?, kind: String?, port: String, value: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Desk.Panel).clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(n?.color() ?: Desk.Line))
        Column(Modifier.padding(6.dp)) {
            Text(n?.title() ?: "?", style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (kind != null) Text(kind, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("› $port", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) Text(value, style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.Green, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ================================================================== Blätter
@Composable
private fun SheetFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Box(Modifier.heightIn(max = 480.dp)) { content() } },
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } }
    )
}

/** Gegenstück für einen Anschluss wählen – alle passenden Anschlüsse im Set, mit Suche. */
@Composable
private fun ConnectSheet(s: UmbraSession, set: ConnectionSetDescriptor, node: NodeMetadataData, p: PortMetadataData, onClose: () -> Unit) {
    val m = s.inputGraph
    var q by remember { mutableStateOf("") }
    var add by remember { mutableStateOf(false) }
    val wantInput = !p.isInput
    val groups = set.nodesList.filter { !it.hidden && it.id.nodeId != node.id.nodeId }
        .map { n -> n to n.portsList.filter { it.isInput == wantInput && (q.isBlank() || (n.title() + " " + it.label()).contains(q.trim(), true)) } }
        .filter { it.second.isNotEmpty() }
    if (add) {
        AddSheet(s, set, p, Offset(node.position.x + (if (p.isInput) -200f else 200f), node.position.y)) { onClose() }
        return
    }
    SheetFrame("${node.title()} › ${p.label()} verbinden mit …", onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactSearch(q, { q = it }, "Baustein oder Anschluss", Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Raised).clickable { add = true }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { Icon(Icons.Filled.Add, null, tint = Desk.Amber); Text("  Neuer Baustein / Ein- oder Ausgang …", style = MaterialTheme.typography.bodyMedium) }
            groups.forEach { (n, ports) ->
                Text("${n.title()} · ${n.kindLabel()}", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.padding(top = 6.dp))
                ports.forEach { op ->
                    Text(op.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Raised)
                        .clickable {
                            if (p.isInput) m.connect(set.id, op.id, p.id) else m.connect(set.id, p.id, op.id)
                            onClose()
                        }.padding(10.dp))
                }
            }
            if (groups.isEmpty()) Text("Keine passenden Anschlüsse im Set.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
        }
    }
}

/**
 * Neues Element: Eingang (Quelle aus DMXControl), Baustein (aus der Liste der verfügbaren Bausteine) oder Ausgang.
 * Mit [from] wird das neue Element gleich mit diesem Anschluss verbunden.
 */
@Composable
private fun AddSheet(s: UmbraSession, set: ConnectionSetDescriptor, from: PortMetadataData?, at: Offset, onDone: (String?) -> Unit) {
    val m = s.inputGraph
    val workers by m.workers.collectAsStateWithLifecycle()
    val sources by m.sources.collectAsStateWithLifecycle()
    val sinks by m.sinks.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(if (from == null) 1 else if (from.isInput) 0 else 1) }
    var q by remember { mutableStateOf("") }
    val list: List<GraphCatalogItem> = when (tab) { 0 -> sources; 1 -> workers; else -> sinks }
    val filtered = list.filter { q.isBlank() || (it.category + " " + it.name).contains(q.trim(), true) }.take(300)

    fun created(nodeId: LumosProtobuf.Input.InputServiceTypes.GraphNodeIDData) {
        // gleich verbinden: erster passender Anschluss des neuen Elements
        if (from != null) m.connectToNew(set.id, from, nodeId)
        onDone(nodeId.nodeId)
    }

    SheetFrame(if (from != null) "Neu und verbinden" else "Hinzufügen", { onDone(null) }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallTabs(listOf("Eingang", "Baustein", "Ausgang"), tab, { tab = it; q = "" })
            CompactSearch(q, { q = it }, "Suchen", Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (filtered.isEmpty()) item { Text("Nichts gefunden.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim) }
                items(filtered, key = { it.category + it.id }) { it2 ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Desk.Raised).clickable {
                        when (tab) {
                            1 -> m.addWorker(set.id, it2.id, at.x, at.y) { id -> created(id) }
                            else -> m.addPort(set.id, it2.id, tab == 0, at.x, at.y) { id -> created(id) }
                        }
                    }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(it2.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(it2.category, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeParamsSheet(s: UmbraSession, setId: String, node: NodeMetadataData, onClose: () -> Unit) {
    val m = s.inputGraph
    val all by m.nodeParams.collectAsStateWithLifecycle()
    val list = all[node.id.nodeId]
    SheetFrame("${node.title()} – Werte", onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                list == null -> Text("Werte werden geladen …", color = Desk.TextDim)
                list.isEmpty() -> Text("Dieser Baustein hat keine einstellbaren Werte.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                else -> {
                    // Alle Parameter als Textfelder (Position zusätzlich per Ziehen); Eingangs-Festwerte als eigene Gruppe
                    val shown = list.sortedBy { it.desc.saveOrder }
                    val (ports, own) = shown.partition { it.desc.metadataMap["IsPortParameter"] == "true" }
                    own.forEach { p -> NodeParam(p) { v -> m.setNodeParam(setId, node, p, v) } }
                    if (ports.isNotEmpty()) {
                        Text("Feste Werte der Eingänge", style = MaterialTheme.typography.labelMedium, color = Desk.Amber, modifier = Modifier.padding(top = 4.dp))
                        Text("Gelten, solange am Eingang kein Kabel steckt.", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
                        ports.forEach { p -> NodeParam(p) { v -> m.setNodeParam(setId, node, p, v) } }
                    }
                }
            }
        }
    }
}

/**
 * Ein Baustein-Parameter als Textfeld – wie in der Eigenschaftsliste der Windows-Oberfläche.
 * Die Eingabe wird passend zum Typ des Parameters umgewandelt:
 *  Double/Single → Kommazahl, Int → ganze Zahl, Boolean → True/False, String → Text,
 *  MultiTypeValue (z. B. On/Off Value) → True/False, Zahl oder beliebiger Text,
 *  Point (Position) → "x, y".
 * Auswahllisten (enumValues) bleiben Auswahl; nur-lesen-Parameter werden nur angezeigt.
 */
@Composable
private fun NodeParam(p: de.sebi.dmxc3remote.net.DevParam, onSet: (LumosProtobuf.CommonTypes.ObjectData) -> Unit) {
    val v = p.value
    val readOnly = p.desc.options and 2 != 0
    if (p.desc.enumValuesCount > 0 && !readOnly) { ParamEditor(p, onSet = onSet); return }
    val current = nodeValueText(v)
    var text by remember(p.desc.name, current) { mutableStateOf(current) }
    var bad by remember(p.desc.name) { mutableStateOf(false) }
    fun commit() {
        val o = nodeValueFromText(p, text)
        bad = o == null
        if (o != null && text != current) onSet(o)
    }
    Column {
        Text(p.label, style = MaterialTheme.typography.labelLarge)
        if (readOnly) {
            Text(current.ifBlank { "–" }, style = MaterialTheme.typography.bodyMedium, color = Desk.TextDim)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    text, { text = it; bad = false }, singleLine = true, isError = bad,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                    supportingText = { if (bad) Text(typeHint(p)) }
                )
                TextButton(onClick = { commit() }, enabled = text != current) { Text("Setzen") }
            }
        }
    }
}

private fun aqShort(p: de.sebi.dmxc3remote.net.DevParam) = p.desc.parameterValueTypeAQ.substringBefore(',').substringAfterLast('.')

private fun typeHint(p: de.sebi.dmxc3remote.net.DevParam) = when (aqShort(p)) {
    "Double", "Single", "Decimal" -> "Zahl erwartet, z. B. 0.5"
    "Int16", "Int32", "Int64", "Byte", "UInt16", "UInt32" -> "Ganze Zahl erwartet"
    "Boolean" -> "True oder False"
    "Point" -> "Format: x, y"
    else -> "Ungültiger Wert"
}

/** Anzeige eines Parameterwerts als Text (wie in der Windows-Oberfläche). */
private fun nodeValueText(v: LumosProtobuf.CommonTypes.ObjectData?): String {
    if (v == null) return ""
    de.sebi.dmxc3remote.net.DotNetValue.parsePoint(v)?.let { (x, y) -> return "$x, $y" }
    if (de.sebi.dmxc3remote.net.DotNetValue.isMulti(v)) return de.sebi.dmxc3remote.net.DotNetValue.multiText(v)
    return when (v.valueCase) {
        LumosProtobuf.CommonTypes.ObjectData.ValueCase.BOOLVALUE -> if (v.boolValue) "True" else "False"
        LumosProtobuf.CommonTypes.ObjectData.ValueCase.DOUBLEVALUE -> java.math.BigDecimal(v.doubleValue).stripTrailingZeros().toPlainString()
        LumosProtobuf.CommonTypes.ObjectData.ValueCase.FLOATVALUE -> java.math.BigDecimal(v.floatValue.toDouble()).stripTrailingZeros().toPlainString()
        else -> v.text()
    }
}

/** Texteingabe → Wert im Typ des Parameters; null = Eingabe passt nicht. */
private fun nodeValueFromText(p: de.sebi.dmxc3remote.net.DevParam, t: String): LumosProtobuf.CommonTypes.ObjectData? {
    val b = LumosProtobuf.CommonTypes.ObjectData.newBuilder()
    val n = t.trim().replace(',', '.')
    val v = p.value
    if (de.sebi.dmxc3remote.net.DotNetValue.isMulti(v))
        return de.sebi.dmxc3remote.net.DotNetValue.buildMulti(v!!, de.sebi.dmxc3remote.net.DotNetValue.multiFromText(t))
    return when (aqShort(p)) {
        "Point" -> Regex("""^\s*(-?\d+)\s*[;,]\s*(-?\d+)\s*$""").matchEntire(t)?.let { m ->
            de.sebi.dmxc3remote.net.DotNetValue.buildPoint(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        "Double", "Decimal" -> n.toDoubleOrNull()?.let { b.setDoubleValue(it).build() }
        "Single" -> n.toFloatOrNull()?.let { b.setFloatValue(it).build() }
        "Int32" -> n.toIntOrNull()?.let { b.setIntValue(it).build() }
        "Int16" -> n.toIntOrNull()?.let { b.setShortValue(it).build() }
        "Int64" -> n.toLongOrNull()?.let { b.setLongValue(it).build() }
        "Byte" -> n.toIntOrNull()?.let { b.setByteValue(it).build() }
        "UInt32" -> n.toIntOrNull()?.let { b.setUintValue(it).build() }
        "UInt16" -> n.toIntOrNull()?.let { b.setUshortValue(it).build() }
        "Boolean" -> when { n.equals("true", true) -> b.setBoolValue(true).build(); n.equals("false", true) -> b.setBoolValue(false).build(); else -> null }
        "String" -> b.setStringValue(t).build()
        // unbekannter Typ: im Format des gelesenen Werts
        else -> when (v?.valueCase) {
            LumosProtobuf.CommonTypes.ObjectData.ValueCase.DOUBLEVALUE -> n.toDoubleOrNull()?.let { b.setDoubleValue(it).build() }
            LumosProtobuf.CommonTypes.ObjectData.ValueCase.INTVALUE -> n.toIntOrNull()?.let { b.setIntValue(it).build() }
            LumosProtobuf.CommonTypes.ObjectData.ValueCase.BOOLVALUE -> n.toBooleanStrictOrNull()?.let { b.setBoolValue(it).build() }
            else -> b.setStringValue(t).build()
        }
    }
}
