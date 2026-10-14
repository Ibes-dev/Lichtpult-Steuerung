package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.SdElement
import de.sebi.dmxc3remote.net.SdType
import de.sebi.dmxc3remote.net.Softdesk
import de.sebi.dmxc3remote.net.SoftdeskStore
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.asBool
import de.sebi.dmxc3remote.net.asDouble
import de.sebi.dmxc3remote.net.text

@Composable
fun SoftdeskScreen(s: UmbraSession, store: SoftdeskStore) {
    val desks by store.desks.collectAsStateWithLifecycle()
    val fb by s.softdesk.feedback.collectAsStateWithLifecycle()
    val registered by s.softdesk.registered.collectAsStateWithLifecycle()
    val err by s.softdesk.error.collectAsStateWithLifecycle()
    var deskId by rememberSaveable { mutableStateOf<String?>(null) }
    var edit by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SdElement?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deskDialog by remember { mutableStateOf(false) }
    val desk = desks.firstOrNull { it.id == deskId } ?: desks.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(desks, key = { it.id }) { d ->
                    FilterChip(selected = desk?.id == d.id, onClick = { deskId = d.id }, label = { Text(d.name) })
                }
            }
            Text("$registered reg.", style = MaterialTheme.typography.labelSmall)
            Switch(checked = edit, onCheckedChange = { edit = it }, modifier = Modifier.padding(start = 6.dp))
            Text("Edit", style = MaterialTheme.typography.labelSmall)
        }
        if (edit) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { deskId = store.addDesk("Softdesk ${desks.size + 1}") }) { Text("+ Softdesk") }
                if (desk != null) {
                    OutlinedButton(onClick = { deskDialog = true }) { Text("Softdesk …") }
                    OutlinedButton(onClick = { adding = true }) { Text("+ Element") }
                }
            }
        }
        if (desk == null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Noch kein Softdesk. Edit einschalten, Softdesk und Elemente anlegen. " +
                        "Die Elemente erscheinen danach in der DMXC3-Eingangszuweisung unter \"Android: ${s.clientName}\" " +
                        "und werden dort mit Funktionen verdrahtet.",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(desk.columns),
                Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(desk.elements, key = { it.id }, span = { GridItemSpan(it.span.coerceIn(1, desk.columns)) }) { e ->
                    Box {
                        SdControl(s, e, fb[e.id])
                        if (edit) {
                            Box(
                                Modifier.matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickableNoRipple { editing = e },
                                contentAlignment = Alignment.Center
                            ) { Text("✎ ${e.name}", maxLines = 2, textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
        }
    }

    if (adding && desk != null) {
        ElementDialog(null, onDismiss = { adding = false }) { t, name, span ->
            store.addElement(desk.id, t, name, span)
            adding = false
        }
    }
    val ed = editing
    if (ed != null && desk != null) {
        ElementDialog(
            ed,
            onDismiss = { editing = null },
            onDelete = { store.deleteElement(desk.id, ed.id); editing = null },
            onMove = { d -> store.moveElement(desk.id, ed.id, d) }
        ) { t, name, span ->
            store.updateElement(desk.id, ed.copy(type = t, name = name, span = span))
            editing = null
        }
    }
    if (deskDialog && desk != null) {
        DeskDialog(desk, onDismiss = { deskDialog = false }, onDelete = {
            store.deleteDesk(desk.id); deskId = null; deskDialog = false
        }) { name, cols ->
            store.renameDesk(desk.id, name, cols)
            deskDialog = false
        }
    }
}

private val SD_HEIGHT_SMALL = 72.dp
private val SD_HEIGHT_FADER = 260.dp

@Composable
private fun SdControl(s: UmbraSession, e: SdElement, fb: LumosProtobuf.CommonTypes.ObjectData?) {
    val accent = MaterialTheme.colorScheme.primary
    val frame = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
    when (e.type) {
        SdType.BUTTON -> Box(frame.height(SD_HEIGHT_SMALL)) {
            ExecButton(e.name, fb?.asBool() == true, accent) { p -> s.softdesk.press(e, p) }
        }
        SdType.TOGGLE -> {
            var on by remember(e.id) { mutableStateOf(false) }
            val lit = fb?.asBool() ?: on
            Box(frame.height(SD_HEIGHT_SMALL)) {
                ExecButton(e.name, lit, accent) { p ->
                    if (p) {
                        on = !lit
                        s.softdesk.press(e, on)
                    }
                }
            }
        }
        SdType.FADER -> {
            var local by remember(e.id) { mutableFloatStateOf(0f) }
            val value = fb?.asDouble()?.toFloat()?.coerceIn(0f, 1f) ?: local
            Column(
                frame.height(SD_HEIGHT_FADER).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Text(pct(value.toDouble()), style = MaterialTheme.typography.labelSmall)
                VerticalFader(value, { v -> local = v; s.softdesk.fader(e, v.toDouble()) }, accent, Modifier.fillMaxWidth().weight(1f))
            }
        }
        SdType.LED -> Row(frame.height(SD_HEIGHT_SMALL).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (fb?.asBool() == true) Color(0xFF66FF66) else Color(0xFF203020))
            )
            Text("  ${e.name}", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SdType.LABEL -> Column(frame.height(SD_HEIGHT_SMALL).padding(8.dp)) {
            Text(e.name, style = MaterialTheme.typography.labelSmall)
            Text(fb?.text() ?: "—", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ElementDialog(
    current: SdElement?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onMove: ((Int) -> Unit)? = null,
    onSave: (SdType, String, Int) -> Unit
) {
    var type by remember { mutableStateOf(current?.type ?: SdType.BUTTON) }
    var name by remember { mutableStateOf(current?.name ?: "") }
    var span by remember { mutableStateOf(current?.span ?: 1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Element hinzufügen" else "Element bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Typ", style = MaterialTheme.typography.labelMedium)
                SdType.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Breite: $span", modifier = Modifier.weight(1f))
                    TextButton(onClick = { span = (span - 1).coerceAtLeast(1) }) { Text("−") }
                    TextButton(onClick = { span = (span + 1).coerceAtMost(12) }) { Text("+") }
                }
                if (onMove != null) {
                    Row {
                        TextButton(onClick = { onMove(-1) }) { Text("◀ nach vorn") }
                        TextButton(onClick = { onMove(1) }) { Text("nach hinten ▶") }
                    }
                }
                if (current != null) {
                    Text(
                        "Typwechsel meldet das Element in DMXC3 ab und neu an; bestehende Verdrahtung kann verloren gehen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(type, name.trim(), span) }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}

@Composable
private fun DeskDialog(desk: Softdesk, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember { mutableStateOf(desk.name) }
    var cols by remember { mutableStateOf(desk.columns) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Softdesk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Spalten: $cols", modifier = Modifier.weight(1f))
                    TextButton(onClick = { cols = (cols - 1).coerceAtLeast(1) }) { Text("−") }
                    TextButton(onClick = { cols = (cols + 1).coerceAtMost(12) }) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), cols) }) { Text("Speichern") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}
