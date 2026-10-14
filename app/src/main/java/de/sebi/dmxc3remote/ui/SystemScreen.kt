package de.sebi.dmxc3remote.ui

import LumosProtobuf.ProjectServiceTypes.EProjectState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.UmbraSession

@Composable
private fun Section(title: String, error: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            ModuleError(error)
            content()
        }
    }
}

@Composable
fun SystemScreen(s: UmbraSession) {
    var section by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Übersicht", "DMX-Monitor", "Hardware").forEachIndexed { i, t ->
                if (i == section) Button(onClick = {}) { Text(t) } else OutlinedButton(onClick = { section = i }) { Text(t) }
            }
        }
        when (section) {
            0 -> Overview(s)
            1 -> DmxMonitor(s)
            else -> Hardware(s)
        }
    }
}

@Composable
private fun Overview(s: UmbraSession) {
    val state by s.project.state.collectAsStateWithLifecycle()
    val dirty by s.project.dirty.collectAsStateWithLifecycle()
    val saving by s.project.saving.collectAsStateWithLifecycle()
    val projErr by s.project.error.collectAsStateWithLifecycle()
    val switches by s.mainSwitches.items.collectAsStateWithLifecycle()
    val swErr by s.mainSwitches.error.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Section("Projekt", projErr) {
            Text(
                "Status: " + when (state) {
                    EProjectState.EProjectState_LOADED -> "geladen"
                    EProjectState.EProjectState_LOADING -> "lädt …"
                    EProjectState.EProjectState_CLOSING -> "schließt …"
                    EProjectState.EProjectState_CLOSED -> "geschlossen"
                    else -> "unbekannt"
                } + if (dirty) " · ungespeicherte Änderungen" else ""
            )
            Button(onClick = s.project::save, enabled = saving == null) { Text(saving ?: "Projekt speichern") }
        }
        Section("Hauptschalter", swErr) {
            if (switches.isEmpty()) Text("Keine", style = MaterialTheme.typography.bodySmall)
            switches.values.sortedBy { it.name }.forEach { sw ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sw.name, modifier = Modifier.weight(1f))
                    Switch(checked = sw.enabledState, onCheckedChange = { s.mainSwitches.set(sw.mainSwitchId, it) })
                }
            }
        }
        Section("Modulstatus") {
            s.modules.forEach { m ->
                val e by m.error.collectAsStateWithLifecycle()
                Row {
                    Text(m.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(
                        e ?: "OK",
                        color = if (e == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text("App-Version ${UmbraSession.APP_VERSION}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DmxMonitor(s: UmbraSession) {
    val m = s.dmx
    val universe by m.universe.collectAsStateWithLifecycle()
    val values by m.values.collectAsStateWithLifecycle()
    val err by m.error.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        m.setActive(true)
        onDispose { m.setActive(false) }
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        ModuleError(err)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { m.setUniverse(universe - 1) }, enabled = universe > 1) { Text("◀") }
            Text("Universum $universe", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { m.setUniverse(universe + 1) }) { Text("▶") }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            (0 until 512).chunked(16).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { ch ->
                        val v = values[ch]
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).padding(1.dp)
                                .background(Color(0xFFFFB300).copy(alpha = 0.1f + 0.9f * v / 255f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (v > 0) "$v" else "${ch + 1}",
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                color = if (v > 128) Color.Black else MaterialTheme.colorScheme.onSurface.copy(alpha = if (v > 0) 1f else 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hardware(s: UmbraSession) {
    val m = s.system
    val err by m.error.collectAsStateWithLifecycle()
    val interfaces by m.interfaces.collectAsStateWithLifecycle()
    val plugins by m.plugins.collectAsStateWithLifecycle()
    val rdm by m.rdm.collectAsStateWithLifecycle()
    val power by m.power.collectAsStateWithLifecycle()
    val amps by m.amps.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModuleError(err)
        TextButton(onClick = m::reload) { Text("Neu laden") }
        Section("DMX-Interfaces") {
            if (interfaces.isEmpty()) Text("Keine", style = MaterialTheme.typography.bodySmall)
            interfaces.values.forEach { st ->
                val d = st.meta.interfaceData
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${d.vendor} ${d.model}".trim().ifBlank { d.interfaceId })
                        Text(
                            listOfNotNull(
                                d.serialNr.ifBlank { null }?.let { "SN $it" },
                                if (st.meta.available) "verfügbar" else "nicht verfügbar",
                                st.working?.let { if (it) "arbeitet" else "Fehler" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = st.enabled == true, onCheckedChange = { m.setInterfaceEnabled(d.interfaceId, it) })
                }
            }
        }
        Section("Plugins") {
            plugins.sortedBy { it.first.name }.forEach { (p, en) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name)
                        Text(p.type.name.removePrefix("EPluginType_"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (en != null) Switch(checked = en, onCheckedChange = { m.setPluginEnabled(p.id, it) })
                }
            }
        }
        Section("RDM") {
            OutlinedButton(onClick = m::rdmDiscovery) { Text("Discovery starten") }
            rdm.forEach { d ->
                Text(
                    "${d.uid}  ·  Adr. ${d.info.dmx512StartAddress}  ·  ${d.info.dmx512Footprint} Kan." +
                        if (!d.present) "  (offline)" else "",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Section("Stromkreise") {
            if (power.isEmpty()) Text("Keine", style = MaterialTheme.typography.bodySmall)
            power.sortedBy { it.number }.forEach { p ->
                val a = amps[p.id]
                Text(
                    "${p.number} ${p.name}: ${a?.let { "%.1f A".format(it) } ?: "–"} / ${p.possibleAmps} A",
                    color = if (a != null && p.possibleAmps > 0 && a > p.possibleAmps * 0.9) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
