package de.sebi.dmxc3remote.ui

import LumosProtobuf.CommonTypes.ObjectData
import LumosProtobuf.DeviceServiceTypes.DeviceDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.DevParam
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.text

/** Geräteliste mit DMX-Adressen; Antippen öffnet die Geräteparameter zum Bearbeiten. */
@Composable
fun DevicesScreen(s: UmbraSession) {
    val m = s.deviceList
    val devices by m.devices.collectAsStateWithLifecycle()
    val params by m.params.collectAsStateWithLifecycle()
    val progress by m.progress.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        m.setActive(true)
        onDispose { m.setActive(false) }
    }
    val q = query.trim().lowercase()
    val visible = remember(devices, q, params) {
        devices.filter { d ->
            q.isEmpty() || d.name.lowercase().contains(q) || d.number.toString() == q ||
                d.model.lowercase().contains(q) || m.addressOf(d.id)?.toString()?.startsWith(q) == true
        }
    }

    Column(Modifier.fillMaxSize()) {
        ModuleError(err)
        OutlinedTextField(
            query, { query = it }, singleLine = true,
            placeholder = { Text("Suchen: Name, Nummer, Modell oder Adresse (z. B. 1.025)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
        )
        Text(
            progress ?: "${devices.size} Geräte",
            style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(horizontal = 10.dp)
        )
        // Kopfzeile
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Nr.", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.width(44.dp))
            Text("Name / Modell", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.weight(1f))
            Text("Adresse", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.width(96.dp))
        }
        if (devices.isEmpty()) {
            EmptyHint("Keine Geräte", "Im geladenen Projekt sind keine Geräte angelegt.")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(visible, key = { it.id }) { d -> DeviceRow(d, m.addressOf(d.id)?.toString(), d.id in params) { openId = d.id } }
            }
        }
    }
    val od = openId?.let { id -> devices.firstOrNull { it.id == id } }
    if (od != null) DeviceDialog(s, od) { openId = null }
}

@Composable
private fun DeviceRow(d: DeviceDescriptor, addr: String?, loaded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp)).background(Desk.Panel)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${d.number}", style = MaterialTheme.typography.labelLarge.merge(Numeric), modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name.ifBlank { "Gerät ${d.number}" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(d.vendor, d.model, d.mode).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { d.type },
                style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            addr ?: if (loaded) "–" else "…",
            style = MaterialTheme.typography.bodyMedium.merge(Numeric),
            color = if (addr != null) Desk.Amber else Desk.TextDim,
            modifier = Modifier.width(96.dp)
        )
    }
}

@Composable
private fun DeviceDialog(s: UmbraSession, d: DeviceDescriptor, onClose: () -> Unit) {
    val m = s.deviceList
    val params by m.params.collectAsStateWithLifecycle()
    val list = params[d.id] ?: emptyList()
    val addr = m.addressOf(d.id)
    var uni by remember(d.id, addr) { mutableStateOf(addr?.universe?.toString() ?: "1") }
    var ch by remember(d.id, addr) { mutableStateOf(addr?.address?.toString() ?: "1") }
    var edit by remember { mutableStateOf<DevParam?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${d.number} ${d.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(listOf(d.vendor, d.model, d.mode).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                Text("DMX-Adresse", style = MaterialTheme.typography.titleSmall, color = Desk.Amber)
                if (m.addressParam(d.id) == null) {
                    Text(
                        if (params.containsKey(d.id)) "Kein Adressparameter gefunden." else "Parameter werden geladen …",
                        style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(uni, { uni = it.filter(Char::isDigit) }, label = { Text("Universum") }, singleLine = true,
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(ch, { ch = it.filter(Char::isDigit) }, label = { Text("Kanal") }, singleLine = true,
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    TextButton(
                        enabled = (uni.toIntOrNull() ?: 0) >= 1 && (ch.toIntOrNull() ?: 0) in 1..512,
                        onClick = { m.setAddress(d.id, uni.toInt(), ch.toInt()) }
                    ) { Text("Adresse übernehmen") }
                    Text(
                        "Ungeprüft, ob DMXControl die Änderung so annimmt; danach wird die Adresse neu gelesen. Überschneidungen prüft die App nicht.",
                        style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                    )
                }
                if (list.isNotEmpty()) {
                    Text("Parameter", style = MaterialTheme.typography.titleSmall, color = Desk.Amber)
                    list.sortedBy { it.label }.forEach { p ->
                        val simple = p.value?.valueCase?.let { it != ObjectData.ValueCase.VALUE_NOT_SET && it != ObjectData.ValueCase.ARRAYVALUE } == true
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .then(if (simple) Modifier.clickable { edit = p } else Modifier)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                p.value?.text()?.ifBlank { "–" } ?: "–",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (simple) Desk.Text else Desk.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(140.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } }
    )
    val e = edit
    if (e != null) {
        var t by remember(e) { mutableStateOf(e.value?.text() ?: "") }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(e.label) },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { m.setSimple(d.id, e, t); edit = null }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Abbrechen") } }
        )
    }
}
