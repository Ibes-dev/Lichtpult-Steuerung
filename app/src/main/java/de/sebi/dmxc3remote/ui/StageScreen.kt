package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.UmbraSession

/**
 * Bühnenansicht: Geräte und Gruppen an ihrer StageView-Position, Auswahl per Antippen.
 * Koordinaten werden auf die verfügbare Fläche skaliert (Seitenverhältnis bleibt).
 */
@Composable
fun StageScreen(s: UmbraSession) {
    val views by s.stage.views.collectAsStateWithLifecycle()
    val devices by s.stage.devices.collectAsStateWithLifecycle()
    val groups by s.devices.groups.collectAsStateWithLifecycle()
    val err by s.stage.error.collectAsStateWithLifecycle()
    var viewId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val sorted = remember(views) { views.values.sortedBy { if (it.orderIndexSet) it.orderIndex else Int.MAX_VALUE } }
    val view = views[viewId] ?: sorted.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(sorted, key = { it.id }) { v ->
                    FilterChip(selected = view?.id == v.id, onClick = { viewId = v.id }, label = { Text(v.name.ifBlank { "Ansicht" }) })
                }
            }
            OutlinedButton(onClick = { selected = emptySet(); s.devices.selectIds(emptyList()) }, modifier = Modifier.padding(end = 10.dp)) {
                Text("Auswahl leeren")
            }
        }
        if (view == null) {
            EmptyHint("Keine Bühnenansicht", "In DMXControl ist keine StageView angelegt.")
            return@Column
        }
        Text(
            if (selected.isEmpty()) "Geräte antippen, um sie auszuwählen. Die Auswahl gilt auch im Programmer."
            else "${selected.size} ausgewählt",
            style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(horizontal = 10.dp)
        )
        data class Item(val id: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val group: Boolean)
        val items = remember(view, devices, groups) {
            view.devicesList.map { d ->
                val dev = devices[d.deviceId]
                Item(d.deviceId, dev?.let { "${it.number}" } ?: "?", d.position.x, d.position.y,
                    d.size.width.toFloat().takeIf { it > 0 } ?: 40f, d.size.height.toFloat().takeIf { it > 0 } ?: 40f, false)
            } + view.deviceGroupsList.map { g ->
                Item(g.deviceGroupId, groups[g.deviceGroupId]?.name?.take(10) ?: "Gruppe", g.position.x, g.position.y,
                    g.size.width.toFloat().takeIf { it > 0 } ?: 60f, g.size.height.toFloat().takeIf { it > 0 } ?: 30f, true)
            }
        }
        if (items.isEmpty()) {
            EmptyHint("Leere Ansicht", "Diese StageView enthält keine Geräte.")
            return@Column
        }
        val minX = items.minOf { it.x }; val minY = items.minOf { it.y }
        val maxX = items.maxOf { it.x + it.w }; val maxY = items.maxOf { it.y + it.h }
        BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Desk.Panel)) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            val pad = 16f
            val scale = minOf((wPx - 2 * pad) / (maxX - minX).coerceAtLeast(1f), (hPx - 2 * pad) / (maxY - minY).coerceAtLeast(1f))
            fun rectOf(i: Item) = Rect(
                Offset(pad + (i.x - minX) * scale, pad + (i.y - minY) * scale),
                Size(i.w * scale, i.h * scale)
            )
            Canvas(
                Modifier.fillMaxSize().pointerInput(items, scale) {
                    detectTapGestures { pos ->
                        val hit = items.lastOrNull { rectOf(it).inflate(6f).contains(pos) } ?: return@detectTapGestures
                        selected = if (hit.id in selected) selected - hit.id else selected + hit.id
                        s.devices.selectIds(selected.toList())
                    }
                }
            ) {
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 11.dp.toPx()
                }
                items.sortedBy { !it.group }.forEach { i ->
                    val r = rectOf(i)
                    val sel = i.id in selected
                    drawRoundRect(
                        color = when {
                            sel -> Desk.Amber
                            i.group -> Color(0xFF3A3F6B)
                            else -> Desk.Raised
                        },
                        topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(6f)
                    )
                    drawRoundRect(Desk.Line, r.topLeft, r.size, CornerRadius(6f), style = Stroke(1.5f))
                    paint.color = if (sel) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    drawContext.canvas.nativeCanvas.drawText(i.label, r.center.x, r.center.y + paint.textSize / 3, paint)
                }
            }
        }
    }
}
