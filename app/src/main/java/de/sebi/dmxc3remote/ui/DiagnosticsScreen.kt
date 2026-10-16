package de.sebi.dmxc3remote.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.Diag
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.describe
import kotlinx.coroutines.launch

/**
 * Diagnose: Laufzeit, Status je Bereich, API-Tests für die offenen Annahmen,
 * Aufrufprotokoll mit optionalen Rohdaten, Export als Text.
 */
@Composable
fun DiagnosticsScreen(s: UmbraSession) {
    val rtt by Diag.rttMs.collectAsStateWithLifecycle()
    val calls by Diag.calls.collectAsStateWithLifecycle()
    val raw by Diag.captureRaw.collectAsStateWithLifecycle()
    val results = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var running by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    fun test(name: String, block: suspend () -> String) {
        if (running) return
        running = true
        scope.launch {
            val r = try { block() } catch (e: Exception) { "$name: ${e.describe()}" }
            results.add(0, "${Diag.ts()}  $r")
            Diag.note(r)
            running = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Section("Verbindung") {
                Text("Server: ${s.target.host}:${s.target.port}")
                Text("Ping-Laufzeit: ${rtt?.let { "$it ms" } ?: "wird gemessen"}", style = MaterialTheme.typography.bodyLarge.merge(Numeric))
                Text("Runtime-ID: ${s.runtimeId}", style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
            }
        }
        item {
            Section("Bereiche") {
                s.modules.forEach { m ->
                    val e by m.error.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (e == null) StatusPill("Bereit", Desk.Green)
                        else Text(e ?: "", color = Desk.Red, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Section("API-Tests") {
                Text(
                    "Klären die offenen Annahmen. Jeder Test hat 30 s Zeit; Ergebnisse stehen unten und im Export.",
                    style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                )
                OutlinedButton(enabled = !running, onClick = { test("Intervall") { s.beat.testInterval500() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Beat: 500 ms senden und zurücklesen")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = !running, onClick = { test("Learn") { s.beat.testLearn(0) } }, modifier = Modifier.weight(1f)) { Text("Learn Unix-ms") }
                    OutlinedButton(enabled = !running, onClick = { test("Learn") { s.beat.testLearn(1) } }, modifier = Modifier.weight(1f)) { Text("Learn .NET") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = !running, onClick = { test("Sync") { s.beat.testSync(0) } }, modifier = Modifier.weight(1f)) { Text("Sync Unix-ms") }
                    OutlinedButton(enabled = !running, onClick = { test("Sync") { s.beat.testSync(1) } }, modifier = Modifier.weight(1f)) { Text("Sync .NET") }
                }
                OutlinedButton(enabled = !running, onClick = {
                    test("Master") {
                        s.masters.items.value.values.joinToString("; ") { m ->
                            "${m.displayName.ifBlank { m.name }}: faders=" + m.fadersMap.entries.joinToString(",") { (k, v) -> "$k(name=${v.name})" }
                        }.ifBlank { "Keine Master" }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Master: Fader-Schlüssel anzeigen") }
                results.forEach { r -> Text(r, style = MaterialTheme.typography.bodySmall.merge(Numeric)) }
            }
        }
        item {
            Section("Aufrufe", actions = {
                Text("Rohdaten", style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.padding(end = 6.dp))
                Switch(checked = raw, onCheckedChange = { Diag.captureRaw.value = it })
            }) {
                OutlinedButton(onClick = {
                    val text = Diag.report("DMXC3 Remote ${UmbraSession.APP_VERSION}, Server ${s.target.host}:${s.target.port}\n" + results.joinToString("\n"))
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "DMXC3 Remote Diagnose")
                                .putExtra(Intent.EXTRA_TEXT, text),
                            "Diagnose teilen"
                        )
                    )
                }) { Text("Protokoll teilen") }
            }
        }
        items(calls.take(150)) { c ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Desk.Panel).padding(8.dp)
            ) {
                Row {
                    Text(Diag.ts(c.time), style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.TextDim, modifier = Modifier.width(92.dp))
                    Text(c.method, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${c.ms} ms", style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.TextDim)
                }
                Text(c.status, style = MaterialTheme.typography.labelSmall, color = if (c.status == "OK") Desk.Green else Desk.Red, maxLines = 2)
                if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
