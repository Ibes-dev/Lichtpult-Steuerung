package de.sebi.dmxc3remote.ui

import LumosProtobuf.ProjectServiceTypes.EProjectState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
fun SystemScreen(s: UmbraSession, vm: AppViewModel) {
    var section by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Projekt", "DMX-Ausgabe", "Hardware", "Steuerung", "Bedienfeld", "Info").forEachIndexed { i, t ->
                androidx.compose.material3.FilterChip(selected = i == section, onClick = { section = i }, label = { Text(t) })
            }
        }
        when (section) {
            0 -> Overview(s, vm)
            1 -> DmxMonitor(s)
            2 -> Hardware(s)
            3 -> ControlSettings(s, vm)
            4 -> SoftdeskSettings(vm)
            else -> AboutScreen()
        }
    }
}

@Composable
private fun Overview(s: UmbraSession, vm: AppViewModel) {
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
        Section("Design") {
            Text("Farbschema der App", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeskStyle.entries.forEach { st ->
                    val p = st.palette
                    Column(
                        Modifier.width(96.dp).clip(RoundedCornerShape(10.dp))
                            .border(2.dp, if (Desk.style == st) Desk.Amber else Desk.Line, RoundedCornerShape(10.dp))
                            .clickableNoRipple { vm.setDeskStyle(st) }
                    ) {
                        // kleine Vorschau: Fläche, Karte, Akzentfarben
                        Box(Modifier.fillMaxWidth().height(54.dp).background(p.surface).padding(6.dp)) {
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(p.panel).padding(5.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.BottomStart)) {
                                    listOf(p.amber, p.cyan, p.green, p.red).forEach { c -> Box(Modifier.size(10.dp).clip(CircleShape).background(c)) }
                                }
                                Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(2.dp)).background(p.text))
                            }
                        }
                        Text(st.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp), maxLines = 1)
                    }
                }
            }
        }
        Section("Projekt", error = projErr) {
            Text(
                when (state) {
                    EProjectState.EProjectState_LOADED -> if (dirty) "Projekt geladen, ungespeicherte Änderungen" else "Projekt geladen, alles gespeichert"
                    EProjectState.EProjectState_LOADING -> "Projekt wird geladen"
                    EProjectState.EProjectState_CLOSING -> "Projekt wird geschlossen"
                    EProjectState.EProjectState_CLOSED -> "Kein Projekt geöffnet"
                    else -> "Projektstatus unbekannt"
                }
            )
            Button(onClick = s.project::save, enabled = saving == null) { Text(saving ?: "Projekt speichern") }
        }
        Section("Hauptschalter", error = swErr) {
            if (switches.isEmpty()) Text("Keine Hauptschalter im Projekt.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
            switches.values.sortedBy { it.name }.forEach { sw ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sw.name, modifier = Modifier.weight(1f))
                    Switch(checked = sw.enabledState, onCheckedChange = { s.mainSwitches.set(sw.mainSwitchId, it) })
                }
            }
        }
        Section("Verbindung zu den Funktionsbereichen") {
            s.modules.forEach { m ->
                val e by m.error.collectAsStateWithLifecycle()
                Row {
                    Text(m.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (e == null) StatusPill("Bereit", Desk.Green) else Text(
                        e ?: "",
                        color = Desk.Red,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
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
    val info by m.info.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        m.setActive(true)
        onDispose { m.setActive(false) }
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        ModuleError(err)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { m.setUniverse(universe - 1) }, enabled = universe > 1) { Text("◀") }
            Text("Universum $universe", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
            TextButton(onClick = { m.setUniverse(universe + 1) }) { Text("▶") }
        }
        if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, modifier = Modifier.padding(horizontal = 8.dp))
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
    var commentFor by remember { mutableStateOf<String?>(null) }
    val plugins by m.plugins.collectAsStateWithLifecycle()
    val rdm by m.rdm.collectAsStateWithLifecycle()
    val power by m.power.collectAsStateWithLifecycle()
    val amps by m.amps.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModuleError(err)
        TextButton(onClick = m::reload) { Text("Hardware neu einlesen") }
        commentFor?.let { id ->
            val st = interfaces[id]
            val port = st?.meta?.interfaceData?.portMetadataList?.firstOrNull()?.port ?: 0
            RenameDialog("Kommentar (wie in DMXControl)", st?.comments?.get(port) ?: "", { c -> m.setComment(id, port, c); commentFor = null }) { commentFor = null }
        }
        Section("DMX-Interfaces") {
            if (interfaces.isEmpty()) Text("Keine Interfaces in Verwendung.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
            interfaces.values.forEach { st ->
                val d = st.meta.interfaceData
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickableNoRipple { commentFor = d.interfaceId }) {
                        Text("${d.vendor} ${d.model}".trim().ifBlank { d.interfaceId })
                        // Kommentar aus DMXControl, um gleiche Interfaces zu unterscheiden
                        if (st.comments.isNotEmpty()) Text(
                            st.comments.entries.sortedBy { it.key }.joinToString(" · ") { (port, c) ->
                                if (st.comments.size > 1 || d.portMetadataCount > 1) "Port ${port + 1}: $c" else c
                            },
                            style = MaterialTheme.typography.bodyMedium, color = Desk.Amber
                        )
                        Text(
                            listOfNotNull(
                                d.genericInstanceNumber.takeIf { d.genericInstanceNumberSet }?.let { "Instanz $it" },
                                d.serialNr.ifBlank { null }?.let { "Seriennr. $it" },
                                if (st.meta.available) "angeschlossen" else "nicht gefunden",
                                st.working?.let { if (it) "sendet" else "gestört" }
                            ).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall
                        )
                        // weitere Angaben aus DMXControl (z. B. IP-Adresse), falls vorhanden
                        if (st.meta.additionalMetadataCount > 0) Text(
                            st.meta.additionalMetadataMap.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                            style = MaterialTheme.typography.labelSmall, color = Desk.TextDim, maxLines = 2
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
            OutlinedButton(onClick = m::rdmDiscovery) { Text("RDM-Geräte suchen") }
            rdm.forEach { d ->
                Text(
                    "${d.uid}: Startadresse ${d.info.dmx512StartAddress}, ${d.info.dmx512Footprint} Kanäle" +
                        if (!d.present) ", nicht erreichbar" else "",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Section("Stromkreise") {
            if (power.isEmpty()) Text("Keine Stromkreise angelegt.", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
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

@Composable
private fun ControlSettings(s: UmbraSession, vm: AppViewModel) {
    val midiNames by vm.midi.names.collectAsStateWithLifecycle()
    val last by vm.midi.last.collectAsStateWithLifecycle()
    val volume by vm.volumeKeysGo.collectAsStateWithLifecycle()
    val compactCl by vm.cuelistCompact.collectAsStateWithLifecycle()
    val compactMs by vm.masterCompact.collectAsStateWithLifecycle()
    val view3d by vm.stage3d.view.collectAsStateWithLifecycle()
    val goId by vm.goCuelistId.collectAsStateWithLifecycle()
    val cuelists by s.cuelists.items.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Section("Darstellung") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cuelists kompakt anzeigen")
                    Text("Schlanke Zeilen statt Karten, sinnvoll bei vielen Cuelists", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                }
                Switch(checked = compactCl, onCheckedChange = vm::setCuelistCompact)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Master kompakt anzeigen")
                    Text("Nur Regler und Flash, mehr Master nebeneinander", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                }
                Switch(checked = compactMs, onCheckedChange = vm::setMasterCompact)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("3D-Bühne: StageView als Frontansicht")
                    Text(
                        if (view3d.front) "StageView von vorn gezeichnet: oben = hoch, Tiefe je Gerät einstellbar"
                        else "StageView von oben gezeichnet: oben = hinten, Höhe je Gerät einstellbar",
                        style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                    )
                }
                Switch(checked = view3d.front, onCheckedChange = { vm.stage3d.setView(view3d.copy(front = it)) })
            }
        }
        Section("Externe Tasten") {
            Text(
                "Fußtaster, Presenter und Medientasten (Bluetooth oder USB) lösen Go bzw. Zurück der Go-Cuelist aus: " +
                    "Bild ab / Pfeil rechts / Weiter / Play = Go, Bild auf / Pfeil links / Zurück = Zurück.",
                style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
            )
            Text(
                "Go-Cuelist: " + (cuelists[goId]?.name?.ifBlank { null } ?: if (goId.isBlank()) "keine (unter Cuelists den Stern antippen)" else "nicht gefunden"),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lautstärketasten verwenden")
                    Text("Lauter = Go, leiser = Zurück", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                }
                Switch(checked = volume, onCheckedChange = vm::setVolumeKeysGo)
            }
        }
        Section("Schnelleinstellungen") {
            Text(
                "Die Kachel \"Blackout\" lässt sich in den Android-Schnelleinstellungen hinzufügen (Bearbeiten-Stift). " +
                    "Sie schaltet Blackout, solange die App verbunden ist.",
                style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
            )
        }
        Section("MIDI-Controller") {
            if (!vm.midi.available) Text("Dieses Gerät unterstützt kein MIDI.", color = Desk.Red)
            if (midiNames.isEmpty()) Text("Kein Controller angeschlossen (USB-OTG oder Bluetooth-MIDI).", style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
            midiNames.forEach { Text(it) }
            Text(
                "Zuordnung im Bedienfeld: Element bearbeiten, \"MIDI lernen\", Taste oder Fader am Controller bewegen.",
                style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
            )
            last?.let { Text("Zuletzt empfangen: ${if (it.kind == 'N') "Note" else "CC"} ${it.number}, Kanal ${it.channel + 1}, Wert ${it.value}", style = MaterialTheme.typography.bodySmall.merge(Numeric)) }
        }
    }
}

/** Über die App: Version, Haftungsausschluss, Lizenzen. */
@Composable
private fun AboutScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Section(UmbraSession.APP_NAME) {
            Text("Version ${UmbraSession.APP_VERSION}")
            Text(
                "Inoffizielle Fernsteuerung für DMXControl 3 über dessen Netzwerkschnittstelle. " +
                    "Kein Produkt des DMXControl Projects e.V. und nicht von ihm unterstützt.",
                style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
            )
        }
        Section("Haftungsausschluss") {
            Text(
                "Nutzung auf eigene Verantwortung und ohne Gewähr. Die App schaltet Licht, löst Blackout aus und kann " +
                    "Bühnenansichten in DMXControl ändern oder löschen. Vor Auftritten testen. Die Verbindung zu " +
                    "DMXControl ist unverschlüsselt; nur in vertrauenswürdigen Netzen verwenden.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Section("Datenschutz") {
            Text(
                "Die App sendet Daten nur an den gewählten DMXControl-Rechner im lokalen Netz. Das Mikrofon wird nur " +
                    "bei eingeschalteter Tempo-/Stimmungserkennung genutzt und lokal ausgewertet; es werden keine " +
                    "Aufnahmen gespeichert oder übertragen, nur die berechneten Werte (z. B. BPM, Stimmung) an DMXControl.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Section("Lizenzen") {
            listOf(
                "Eigener Quellcode" to "CC0 1.0 (gemeinfrei)",
                "DMXControl3 Network Interface (Protokolldefinitionen)" to "Nutzung mit Zustimmung des DMXControl-Projektteams",
                "gRPC Java / gRPC Kotlin" to "Apache License 2.0",
                "Protocol Buffers (protobuf-javalite)" to "BSD 3-Clause",
                "Kotlin, kotlinx.coroutines" to "Apache License 2.0",
                "AndroidX, Jetpack Compose, Material 3, Material Icons" to "Apache License 2.0",
                "OkHttp (über grpc-okhttp)" to "Apache License 2.0",
                "Gradle Wrapper" to "Apache License 2.0"
            ).forEach { (name, lic) ->
                Column {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(lic, style = MaterialTheme.typography.bodySmall, color = Desk.TextDim)
                }
            }
            Text(
                "Lizenztexte: apache.org/licenses/LICENSE-2.0, opensource.org/license/bsd-3-clause, " +
                    "creativecommons.org/publicdomain/zero/1.0",
                style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
            )
        }
    }
}


/**
 * System → Bedienfeld: Anlässe. Ein Anlass legt fest, welche Bedienfelder sichtbar sind; die Wahl
 * liegt bewusst hier und nicht im Bedienfeld, damit die Show-Ansicht aufgeräumt bleibt.
 * Ausgeblendete Bedienfelder bleiben in DMXControl angemeldet.
 */
@Composable
private fun SoftdeskSettings(vm: AppViewModel) {
    val store = vm.softdesks
    val desks by store.desks.collectAsStateWithLifecycle()
    val occasions by store.occasions.collectAsStateWithLifecycle()
    val active by store.activeOccasion.collectAsStateWithLifecycle()
    var newOcc by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<de.sebi.dmxc3remote.net.Occasion?>(null) }
    var delete by remember { mutableStateOf<de.sebi.dmxc3remote.net.Occasion?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Aktueller Anlass") {
            Text(
                "Bestimmt, welche Bedienfelder im Bereich Bedienfeld zu sehen sind. Ausgeblendete bleiben in DMXControl " +
                    "angemeldet; ihre Verbindungen in der Zuweisung bleiben erhalten.",
                style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("Alle Bedienfelder", active == null, { store.setActiveOccasion(null) })
                occasions.forEach { o -> SmallChip(o.name, active == o.id, { store.setActiveOccasion(o.id) }) }
            }
        }
        Section("Anlässe") {
            if (occasions.isEmpty()) Text(
                "Noch keine Anlässe. Lege z. B. „Konzert“ oder „Fasnet“ an und wähle die passenden Bedienfelder.",
                style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
            )
            occasions.forEach { o ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Desk.Raised).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (active == o.id) Text("aktiv", style = MaterialTheme.typography.labelSmall, color = Desk.Green, modifier = Modifier.padding(end = 8.dp))
                        TextButton(onClick = { rename = o }) { Text("Umbenennen") }
                        TextButton(onClick = { delete = o }) { Text("Löschen", color = Desk.Red) }
                    }
                    Text("${o.desks.count { id -> desks.any { it.id == id } }} von ${desks.size} Bedienfeldern sichtbar",
                        style = MaterialTheme.typography.labelSmall, color = Desk.TextDim)
                    desks.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name.ifBlank { "Bedienfeld" }, modifier = Modifier.weight(1f))
                            Switch(checked = d.id in o.desks, onCheckedChange = { on ->
                                store.updateOccasion(o.copy(desks = if (on) o.desks + d.id else o.desks - d.id))
                            })
                        }
                    }
                }
            }
            OutlinedButton(onClick = { newOcc = true }) { Text("+ Anlass") }
        }
    }
    if (newOcc) RenameDialog("Neuer Anlass", "", { name -> store.addOccasion(name); newOcc = false }) { newOcc = false }
    rename?.let { o -> RenameDialog("Anlass umbenennen", o.name, { name -> store.updateOccasion(o.copy(name = name)); rename = null }) { rename = null } }
    delete?.let { o ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Anlass „${o.name}“ löschen?") },
            text = { Text("Die Bedienfelder selbst bleiben erhalten.") },
            confirmButton = { TextButton(onClick = { store.deleteOccasion(o.id); delete = null }) { Text("Löschen", color = Desk.Red) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Abbrechen") } }
        )
    }
}
