package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.UmbraTarget

enum class Dest(val label: String, val icon: ImageVector) {
    EXEC("Executoren", Icons.Filled.Tune),
    CUE("Cuelists", Icons.Filled.FormatListNumbered),
    SOFT("Bedienfeld", Icons.Filled.Dashboard),
    PROG("Programmer", Icons.Filled.Palette),
    MASTER("Master", Icons.Filled.Brightness6),
    MACRO("Macros", Icons.Filled.Bolt),
    MEDIA("Beat & Medien", Icons.Filled.MusicNote),
    STAGE("Bühne", Icons.Filled.Theaters),
    STAGE3D("3D-Bühne", Icons.Filled.ViewInAr),
    DEVICES("Geräte", Icons.Filled.Lightbulb),
    CONSOLE("Befehle", Icons.Filled.Code),
    SYSTEM("System", Icons.Filled.Settings),
    DIAG("Diagnose", Icons.Filled.BugReport),
    MORE("Mehr", Icons.Filled.MoreHoriz)
}

private val ALL = Dest.entries.filter { it != Dest.MORE }

/** Gespeicherte Reihenfolge anwenden; neue Ziele (nach Updates) hinten anhängen. */
private fun orderOf(saved: String): List<Dest> {
    val names = saved.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val known = names.mapNotNull { n -> ALL.firstOrNull { it.name == n } }
    return known + ALL.filter { it !in known }
}

/** Auf dem Handy erscheinen die ersten vier unten, der Rest unter "Mehr". */
private const val PHONE_SLOTS = 4

@Composable
fun MainScreen(vm: AppViewModel, session: UmbraSession, target: UmbraTarget, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(session) {
        session.events.collect { ev -> snackbar.showSnackbar(ev.text) }
    }
    val orderStr by vm.navOrder.collectAsStateWithLifecycle()
    val order = remember(orderStr) { orderOf(orderStr) }
    val phonePrimary = remember(order) { order.take(PHONE_SLOTS) + Dest.MORE }
    var destName by rememberSaveable { mutableStateOf(order.first().name) }
    val dest = Dest.valueOf(destName)
    val wide = isWide()

    Box(modifier.background(Desk.Surface)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(vm, session, target)
            GlobalStrip(session)
            Row(Modifier.weight(1f)) {
                if (wide) {
                    NavigationRail(
                        containerColor = Desk.Panel,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
                    ) {
                        (order + Dest.MORE).forEach { d ->
                            NavigationRailItem(
                                selected = dest == d,
                                onClick = { destName = d.name },
                                icon = { Icon(d.icon, contentDescription = null) },
                                label = { Text(d.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                    VerticalDivider(color = Desk.Line)
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    Content(dest, vm, session, order) { destName = it.name }
                }
            }
            if (!wide) {
                NavigationBar(containerColor = Desk.Panel, windowInsets = WindowInsets(0, 0, 0, 0)) {
                    phonePrimary.forEach { d ->
                        val selected = dest == d || (d == Dest.MORE && dest !in phonePrimary)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { destName = d.name },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(d.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 16.dp else 80.dp))
    }
}

@Composable
private fun Content(dest: Dest, vm: AppViewModel, s: UmbraSession, order: List<Dest>, navigate: (Dest) -> Unit) {
    when (dest) {
        Dest.EXEC -> ExecutorScreen(s)
        Dest.CUE -> CuelistScreen(s, vm)
        Dest.SOFT -> SoftdeskScreen(s, vm.softdesks, vm.midi)
        Dest.PROG -> ProgrammerScreen(s)
        Dest.MASTER -> MasterScreen(s)
        Dest.MACRO -> MacroScreen(s)
        Dest.MEDIA -> MediaScreen(s, vm)
        Dest.STAGE -> StageScreen(s)
        Dest.STAGE3D -> Stage3DScreen(s, vm.stage3d)
        Dest.DEVICES -> DevicesScreen(s)
        Dest.CONSOLE -> ConsoleScreen(s)
        Dest.SYSTEM -> SystemScreen(s, vm)
        Dest.DIAG -> DiagnosticsScreen(s)
        Dest.MORE -> MoreScreen(vm, order, navigate)
    }
}

@Composable
private fun MoreScreen(vm: AppViewModel, order: List<Dest>, navigate: (Dest) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val wide = isWide() // @Composable: außerhalb des LazyColumn-DSL aufrufen
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(if (wide) order else order.drop(PHONE_SLOTS)) { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Desk.Panel)
                    .clickable { navigate(d) }
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(d.icon, contentDescription = null, tint = Desk.Amber)
                Text(d.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
            }
        }
        item {
            androidx.compose.material3.OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Reihenfolge der Leiste anpassen")
            }
        }
    }
    if (editing) NavOrderDialog(order, onDismiss = { editing = false }) { vm.setNavOrder(it.map { d -> d.name }); editing = false }
}

/** Reihenfolge per Pfeiltasten; die ersten vier stehen auf dem Handy in der unteren Leiste. */
@Composable
private fun NavOrderDialog(order: List<Dest>, onDismiss: () -> Unit, onSave: (List<Dest>) -> Unit) {
    var list by remember { mutableStateOf(order) }
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in list.indices) return
        list = list.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leiste anpassen") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Handy: die ersten $PHONE_SLOTS stehen unten in der Leiste, der Rest unter Mehr. Tablet: Reihenfolge der Seitenleiste.",
                    style = MaterialTheme.typography.bodySmall, color = Desk.TextDim
                )
                list.forEachIndexed { i, d ->
                    if (i == PHONE_SLOTS) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Desk.Amber)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (i < PHONE_SLOTS) Desk.Raised else Desk.Panel)
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(d.icon, contentDescription = null, tint = if (i < PHONE_SLOTS) Desk.Amber else Desk.TextDim, modifier = Modifier.size(20.dp))
                        Text(d.label, modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0) {
                            Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp, contentDescription = "nach oben")
                        }
                        IconButton(onClick = { move(i, 1) }, enabled = i < list.lastIndex) {
                            Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown, contentDescription = "nach unten")
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(list) }) { Text("Speichern") } },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = { list = ALL }) { Text("Standard") }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}

@Composable
private fun TopBar(vm: AppViewModel, session: UmbraSession, target: UmbraTarget) {
    val dirty by session.project.dirty.collectAsStateWithLifecycle()
    val saving by session.project.saving.collectAsStateWithLifecycle()
    val beat by session.beat.beat.collectAsStateWithLifecycle()
    val interval by session.beat.intervalMs.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().background(Desk.Panel).padding(start = 14.dp, end = 4.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Desk.Green))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(target.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                saving ?: if (dirty) "Ungespeicherte Änderungen" else "Verbunden",
                style = MaterialTheme.typography.labelSmall,
                color = if (dirty) Desk.Amber else Desk.TextDim,
                maxLines = 1
            )
        }
        // Takt-Anzeige: blinkt im Beat, darunter BPM
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(if (beat % 2L == 0L) Desk.Cyan else Desk.Raised)
            )
            Text(
                if (interval > 0) "${(60_000.0 / interval).toInt()} BPM" else "– BPM",
                style = MaterialTheme.typography.labelMedium.merge(Numeric),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        IconButton(onClick = session.project::save, enabled = saving == null) {
            Icon(Icons.Filled.Save, contentDescription = "Projekt speichern", tint = if (dirty) Desk.Amber else Desk.TextDim)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menü") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Projekt speichern") }, onClick = { menu = false; session.project.save() })
                DropdownMenuItem(text = { Text("Neu synchronisieren") }, onClick = { menu = false; vm.resync() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Verbindung trennen") }, onClick = { menu = false; vm.disconnect() })
            }
        }
    }
}

/** Globale Not-/Showfunktionen, immer sichtbar und groß genug für den Daumen. */
@Composable
private fun GlobalStrip(session: UmbraSession) {
    val blackout by session.globals.blackout.collectAsStateWithLifecycle()
    val freeze by session.globals.freeze.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().background(Desk.Surface).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlobalToggle("Blackout", blackout == true, Desk.Red, Modifier.weight(1f)) { session.globals.setBlackout(blackout != true) }
        GlobalToggle("Freeze", freeze == true, Desk.Cyan, Modifier.weight(1f)) { session.globals.setFreeze(freeze != true) }
    }
}

@Composable
private fun GlobalToggle(label: String, on: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) color else Desk.Raised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (on) "$label aktiv" else label,
            style = MaterialTheme.typography.labelLarge,
            color = if (on) Color.Black else Desk.Text
        )
    }
}
