package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.LiveOut
import de.sebi.dmxc3remote.net.Rig3D
import de.sebi.dmxc3remote.net.Stage3DStore
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.View3D
import de.sebi.dmxc3remote.net.kind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private val floatSaver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.MutableFloatState, Float>(
    save = { it.floatValue }, restore = { mutableFloatStateOf(it) }
)

// ================================================================== kleine 3D-Mathematik
private data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(this dot this)
    fun norm() = len().let { if (it < 1e-6f) this else this * (1f / it) }
}

private fun rad(d: Float) = d * PI.toFloat() / 180f

/** Lochkamera, die um einen Zielpunkt kreist (Bühne von vorn = yaw 0). */
private class Camera(target: V3, yaw: Float, pitch: Float, dist: Float, val w: Float, val h: Float) {
    private val eye = target + V3(sin(rad(yaw)) * cos(rad(pitch)), cos(rad(yaw)) * cos(rad(pitch)), sin(rad(pitch))) * dist
    private val fwd = (target - eye).norm()
    private val right = (fwd cross V3(0f, 0f, 1f)).norm()
    private val up = right cross fwd
    private val f = h * 0.9f

    /** Bildschirmpunkt oder null, wenn hinter der Kamera. */
    fun project(p: V3): Offset? {
        val v = p - eye
        val z = v dot fwd
        if (z < 0.1f) return null
        return Offset(w / 2 + (v dot right) / z * f, h / 2 - (v dot up) / z * f)
    }

    fun depth(p: V3) = (p - eye) dot fwd
}

/** Ein Scheinwerfer in Weltkoordinaten (Meter; x rechts, y Richtung Publikum, z oben). */
private data class Fixture(val id: String, val label: String, val pos: V3, val rig: Rig3D, val moving: Boolean)

/**
 * 3D-Bühne: Scheinwerfer an den Positionen einer StageView, Höhe und Ausrichtung aus den Einstellungen,
 * Lichtkegel in Live-Farbe und -Helligkeit, Bewegung über Pan/Tilt, Zoom, Gobo-Andeutung und Strobe.
 */
@Composable
fun Stage3DScreen(s: UmbraSession, store: Stage3DStore) {
    val views by s.stage.views.collectAsStateWithLifecycle()
    val devices by s.stage.devices.collectAsStateWithLifecycle()
    val rigs by store.rigs.collectAsStateWithLifecycle()
    val cfg by store.view.collectAsStateWithLifecycle()
    var setup by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    // Kamera als State-Objekte: werden nur in der Zeichenphase gelesen -> Gesten lösen kein Neuaufbauen
    // der Oberfläche aus, nur ein Neuzeichnen der Leinwand
    val yawS = rememberSaveable(saver = floatSaver) { mutableFloatStateOf(20f) }
    val pitchS = rememberSaveable(saver = floatSaver) { mutableFloatStateOf(25f) }
    val distS = rememberSaveable(saver = floatSaver) { mutableFloatStateOf(16f) }
    var haze by rememberSaveable { mutableFloatStateOf(0.5f) }
    // geglättete Pan/Tilt/Zoom-Werte je Gerät (Bewegung zwischen den Abfragen weich interpolieren)
    val smooth = remember { HashMap<String, FloatArray>() }
    val lastT = remember { LongArray(1) }

    val sortedViews = remember(views) { views.values.sortedBy { if (it.orderIndexSet) it.orderIndex else Int.MAX_VALUE } }
    val view = views[cfg.stageViewId] ?: sortedViews.firstOrNull()

    // Live-Werte inkl. Position/Zoom/Gobo/Strobe für alle Geräte der Ansicht
    LaunchedEffect(view?.id) {
        s.stage.setLiveDevices(view?.devicesList?.map { it.deviceId } ?: emptyList(), extended = true)
    }
    DisposableEffect(Unit) { onDispose { s.stage.setLiveDevices(emptyList()) } }

    // Geräte der StageView in Meter umrechnen: Breite der Ansicht = eingestellte Bühnenbreite
    val fixtures = remember(view, devices, rigs, cfg.widthM) {
        val list = view?.devicesList ?: emptyList()
        if (list.isEmpty()) return@remember emptyList<Fixture>()
        val minX = list.minOf { it.position.x }; val maxX = list.maxOf { it.position.x + it.size.width }
        val minY = list.minOf { it.position.y }; val maxY = list.maxOf { it.position.y + it.size.height }
        val scale = cfg.widthM / (maxX - minX).coerceAtLeast(1f)
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        list.map { d ->
            val dev = devices[d.deviceId]
            val rig = rigs[d.deviceId] ?: Rig3D()
            val px = (d.position.x + d.size.width / 2 - cx) * scale
            val py = (d.position.y + d.size.height / 2 - cy) * scale
            val moving = dev?.beamsList?.flatMap { it.propertiesList }?.any { it.kind() == de.sebi.dmxc3remote.net.PropKind.POSITION } == true
            Fixture(d.deviceId, dev?.number?.toString() ?: "?", V3(px, py, if (rig.hanging) rig.height else 0.3f), rig, moving)
        }
    }

    // Bildtakt: solange die Ansicht offen ist, jede Bildschirmaktualisierung (für Glättung und Strobe).
    // Der Wert wird nur beim Zeichnen gelesen.
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame.longValue = it / 1_000_000 }
    }

    Column(Modifier.fillMaxSize()) {
        // Kopf: Ansicht wählen, Einrichten
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sortedViews, key = { it.id }) { v ->
                    SmallChip(v.name.ifBlank { "Ansicht" }, view?.id == v.id, { store.setView(cfg.copy(stageViewId = v.id)) })
                }
            }
            SmallChip(if (setup) "Fertig" else "Einrichten", setup, { setup = !setup }, Modifier.padding(start = 8.dp))
        }
        if (view == null || fixtures.isEmpty()) {
            EmptyHint("Keine Positionen", "Die 3D-Ansicht nutzt die Gerätepositionen einer StageView aus DMXControl. Dort eine Bühnenansicht mit Geräten anlegen.")
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF07080A))) {
            val fixRef by rememberUpdatedState(fixtures)
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            yawS.floatValue = (yawS.floatValue - pan.x * 0.3f) % 360f
                            pitchS.floatValue = (pitchS.floatValue + pan.y * 0.2f).coerceIn(5f, 85f)
                            distS.floatValue = (distS.floatValue / zoom).coerceIn(4f, 60f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { yawS.floatValue = 20f; pitchS.floatValue = 25f; distS.floatValue = 16f })
                    }
            ) {
                val now = frame.longValue
                drawStage(fixRef, s.stage.live.value, yawS.floatValue, pitchS.floatValue, distS.floatValue, haze, cfg, selected, now, smooth, lastT)
            }
            Text(
                "Ziehen dreht, zwei Finger zoomen, Doppeltipp setzt zurück",
                style = MaterialTheme.typography.labelSmall, color = Desk.TextDim,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )
        }
        if (setup) SetupPanel(store, cfg, fixtures, haze, { haze = it }, selected) { selected = it }
    }
}

// ================================================================== Zeichnen
private fun DrawScope.drawStage(
    fixtures: List<Fixture>, live: Map<String, LiveOut>, yaw: Float, pitch: Float, dist: Float,
    haze: Float, cfg: View3D, selected: String?, clock: Long, smooth: HashMap<String, FloatArray>, lastT: LongArray
) {
    // Zeit seit dem letzten Bild für die Glättung (max. 100 ms, damit nach Pausen nichts springt)
    val dt = (if (lastT[0] == 0L) 0L else (clock - lastT[0]).coerceIn(0, 100)) / 1000f
    lastT[0] = clock
    val kk = (dt * 12f).coerceIn(0f, 1f)
    val minX = fixtures.minOf { it.pos.x } - 2f; val maxX = fixtures.maxOf { it.pos.x } + 2f
    val minY = fixtures.minOf { it.pos.y } - 2f; val maxY = fixtures.maxOf { it.pos.y } + 4f
    val cam = Camera(V3(0f, (minY + maxY) / 2, 1.2f), yaw, pitch, dist, size.width, size.height)

    // Boden mit Meterraster
    val floor = listOf(V3(minX, minY, 0f), V3(maxX, minY, 0f), V3(maxX, maxY, 0f), V3(minX, maxY, 0f)).mapNotNull { cam.project(it) }
    if (floor.size == 4) drawPath(Path().apply { moveTo(floor[0].x, floor[0].y); floor.drop(1).forEach { lineTo(it.x, it.y) }; close() }, Color(0xFF14161A))
    val grid = Color(0xFF262A31)
    var gx = kotlin.math.floor(minX)
    while (gx <= maxX) { line3(cam, V3(gx, minY, 0f), V3(gx, maxY, 0f), grid); gx += 1f }
    var gy = kotlin.math.floor(minY)
    while (gy <= maxY) { line3(cam, V3(minX, gy, 0f), V3(maxX, gy, 0f), grid); gy += 1f }
    // Bühnenvorderkante
    line3(cam, V3(minX, maxY - 2f, 0f), V3(maxX, maxY - 2f, 0f), Color(0xFF3A3F48), 2f)

    data class Beam(val f: Fixture, val color: Color, val a: Float, val end: V3, val dir: V3, val radius: Float, val gobo: String?)
    val beams = fixtures.mapNotNull { f ->
        val o = live[f.id] ?: return@mapNotNull null
        val k = (o.intensity ?: if (o.hasColor) 1f else 0f)
        if (k <= 0.01f) return@mapNotNull null
        // Strobe: an/aus mit bis zu 12 Hz
        o.strobe?.let { sp -> val hz = sp.coerceIn(1f, 12f); if ((clock * hz / 1000f).toInt() % 2 == 1) return@mapNotNull null }
        val base = if (o.hasColor) Color(o.r, o.g, o.b) else Color.White
        // Richtung: Ausrichtung + Pan um die Hochachse, Neigung aus der Senkrechten
        // weich nachführen: [pan, tilt, zoom]
        val st = smooth.getOrPut(f.id) { floatArrayOf(o.pan?.toFloat() ?: 0f, o.tilt?.toFloat() ?: f.rig.tilt, o.zoom ?: 0.5f) }
        st[0] += ((o.pan?.toFloat() ?: 0f) - st[0]) * kk
        st[1] += ((o.tilt?.toFloat() ?: f.rig.tilt) - st[1]) * kk
        st[2] += ((o.zoom ?: st[2]) - st[2]) * kk
        val panDeg = f.rig.yaw + st[0]
        val tiltDeg = if (f.moving && o.tilt != null) st[1] else f.rig.tilt
        val fwd = V3(sin(rad(panDeg)), cos(rad(panDeg)), 0f)
        val vert = V3(0f, 0f, if (f.rig.hanging) -1f else 1f)
        val dir = (vert * cos(rad(tiltDeg)) + fwd * sin(rad(tiltDeg))).norm()
        val beamDeg = if (o.zoom != null) 5f + st[2] * 40f else f.rig.beam
        val len = if (dir.z < -0.05f) (f.pos.z / -dir.z).coerceAtMost(30f) else 12f
        val end = f.pos + dir * len
        Beam(f, base, k, end, dir, len * tan(rad(beamDeg / 2)), o.gobo)
    }.sortedByDescending { cam.depth(it.f.pos) }

    // Lichtflecken auf dem Boden (additiv)
    beams.filter { it.dir.z < -0.05f }.forEach { b ->
        val ring = circle(b.end, b.dir, b.radius, 18).mapNotNull { cam.project(it) }
        if (ring.size < 8) return@forEach
        val c = cam.project(b.end) ?: return@forEach
        val r = ring.maxOf { (it - c).getDistance() }
        if (b.gobo != null) {
            // Gobo angedeutet: Muster aus Lichtpunkten statt vollem Kreis
            circle(b.end, b.dir, b.radius * 0.55f, 6).forEach { p ->
                cam.project(p)?.let { q -> drawCircle(Brush.radialGradient(listOf(b.color.copy(alpha = 0.9f * b.a), Color.Transparent), q, r * 0.32f), r * 0.32f, q, blendMode = BlendMode.Plus) }
            }
            drawCircle(Brush.radialGradient(listOf(b.color.copy(alpha = 0.7f * b.a), Color.Transparent), c, r * 0.25f), r * 0.25f, c, blendMode = BlendMode.Plus)
        } else {
            drawPath(polyPath(ring), Brush.radialGradient(listOf(b.color.copy(alpha = 0.85f * b.a), b.color.copy(alpha = 0.25f * b.a)), c, r), blendMode = BlendMode.Plus)
        }
    }
    // Lichtkegel im Dunst (additiv, zur Quelle hin heller)
    beams.forEach { b ->
        val src = cam.project(b.f.pos) ?: return@forEach
        val ring = circle(b.end, b.dir, b.radius, 14).mapNotNull { cam.project(it) }
        if (ring.size < 6) return@forEach
        val hull = convexHull(ring + src)
        val endC = cam.project(b.end) ?: ring.reduce { acc, o -> acc + o } / ring.size.toFloat()
        drawPath(
            polyPath(hull),
            Brush.linearGradient(listOf(b.color.copy(alpha = (0.55f * haze * b.a).coerceIn(0f, 1f)), b.color.copy(alpha = 0.06f * haze * b.a)), src, endC),
            blendMode = BlendMode.Plus
        )
    }
    // Scheinwerfer
    fixtures.sortedByDescending { cam.depth(it.pos) }.forEach { f ->
        val p = cam.project(f.pos) ?: return@forEach
        val r = (220f / cam.depth(f.pos)).coerceIn(4f, 18f)
        drawCircle(Color(0xFF3A3F48), r, p)
        val o = live[f.id]
        if (o != null && (o.intensity ?: 0f) > 0.01f) drawCircle(if (o.hasColor) Color(o.r, o.g, o.b) else Color.White, r * 0.5f, p)
        if (f.id == selected) drawCircle(Desk.Amber, r + 4f, p, style = Stroke(3f))
        // Aufhängung andeuten
        if (f.rig.hanging) line3(cam, f.pos, f.pos + V3(0f, 0f, 0.4f), Color(0xFF555B66), 2f)
    }
}

private fun DrawScope.line3(cam: Camera, a: V3, b: V3, color: Color, width: Float = 1f) {
    val p = cam.project(a) ?: return; val q = cam.project(b) ?: return
    drawLine(color, p, q, width)
}

/** Kreis um [center] senkrecht zu [axis]. */
private fun circle(center: V3, axis: V3, r: Float, n: Int): List<V3> {
    val helper = if (abs(axis.z) < 0.9f) V3(0f, 0f, 1f) else V3(1f, 0f, 0f)
    val u = (axis cross helper).norm(); val v = (axis cross u).norm()
    return (0 until n).map { i -> val a = 2 * PI.toFloat() * i / n; center + u * (r * cos(a)) + v * (r * sin(a)) }
}

private fun polyPath(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
}

/** Konvexe Hülle (Monotone Chain) – Umriss des Lichtkegels aus Quelle und Endkreis. */
private fun convexHull(points: List<Offset>): List<Offset> {
    val p = points.sortedWith(compareBy({ it.x }, { it.y }))
    if (p.size < 3) return p
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (pt in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), pt) <= 0) lower.removeAt(lower.size - 1); lower += pt }
    val upper = ArrayList<Offset>()
    for (pt in p.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), pt) <= 0) upper.removeAt(upper.size - 1); upper += pt }
    return lower.dropLast(1) + upper.dropLast(1)
}

// ================================================================== Einrichten
@Composable
private fun SetupPanel(
    store: Stage3DStore, cfg: View3D, fixtures: List<Fixture>, haze: Float, onHaze: (Float) -> Unit,
    selected: String?, onSelect: (String?) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxWidth().height(320.dp).background(Desk.Surface),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Desk.Panel).padding(10.dp)) {
                SetupSlider("Bühnenbreite", cfg.widthM, 4f..40f, "%.1f m") { store.setView(cfg.copy(widthM = it)) }
                SetupSlider("Dunst", haze, 0f..1f, "%.0f %%", factor = 100f, onChange = onHaze)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Alle:", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.align(Alignment.CenterVertically))
                    listOf(3f, 4f, 5f, 6f).forEach { h ->
                        SmallChip("${h.toInt()} m", false, { store.setRigs(fixtures.map { it.id }) { r -> r.copy(height = h) } })
                    }
                    SmallChip("hängend", false, { store.setRigs(fixtures.map { it.id }) { r -> r.copy(hanging = true) } })
                    SmallChip("stehend", false, { store.setRigs(fixtures.map { it.id }) { r -> r.copy(hanging = false) } })
                }
            }
        }
        items(fixtures, key = { it.id }) { f ->
            val r = f.rig
            val open = selected == f.id
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (open) Desk.Raised else Desk.Panel)
                    .clickableNoRipple { onSelect(if (open) null else f.id) }.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, style = MaterialTheme.typography.labelLarge.merge(Numeric), modifier = Modifier.width(40.dp))
                    Text(
                        "%.1f m · %.0f° · %s".format(r.height, r.yaw, if (r.hanging) "hängend" else "stehend") +
                            if (!f.moving) " · Neigung %.0f°".format(r.tilt) else " · Moving Head",
                        style = MaterialTheme.typography.bodySmall, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (open) {
                    SetupSlider("Höhe", r.height, 0.5f..12f, "%.1f m") { store.setRig(f.id, r.copy(height = it)) }
                    SetupSlider("Ausrichtung", r.yaw, -180f..180f, "%.0f°") { store.setRig(f.id, r.copy(yaw = it)) }
                    if (!f.moving) SetupSlider("Neigung", r.tilt, 0f..90f, "%.0f°") { store.setRig(f.id, r.copy(tilt = it)) }
                    SetupSlider("Abstrahlwinkel", r.beam, 3f..60f, "%.0f°") { store.setRig(f.id, r.copy(beam = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallChip("hängend", r.hanging, { store.setRig(f.id, r.copy(hanging = true)) })
                        SmallChip("stehend", !r.hanging, { store.setRig(f.id, r.copy(hanging = false)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String,
    factor: Float = 1f, onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(104.dp))
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, Modifier.weight(1f).height(32.dp), valueRange = range)
        Text(fmt.format(value * factor), style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.TextDim, modifier = Modifier.width(56.dp).padding(start = 6.dp))
    }
}
