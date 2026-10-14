package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.UmbraTarget

private val TABS = listOf("Executoren", "Softdesk", "Cuelists", "Master", "Macros", "Programmer", "Medien", "Konsole", "System")

@Composable
fun MainScreen(vm: AppViewModel, session: UmbraSession, target: UmbraTarget, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(session) {
        session.events.collect { ev -> snackbar.showSnackbar(ev.text) }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopBar(vm, session, target) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp) {
                TABS.forEachIndexed { i, t ->
                    Tab(selected = i == tab, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> ExecutorScreen(session)
                    1 -> SoftdeskScreen(session, vm.softdesks)
                    2 -> CuelistScreen(session)
                    3 -> MasterScreen(session)
                    4 -> MacroScreen(session)
                    5 -> ProgrammerScreen(session)
                    6 -> MediaScreen(session)
                    7 -> ConsoleScreen(session)
                    else -> SystemScreen(session)
                }
            }
        }
    }
}

@Composable
private fun TopBar(vm: AppViewModel, session: UmbraSession, target: UmbraTarget) {
    val blackout by session.globals.blackout.collectAsStateWithLifecycle()
    val freeze by session.globals.freeze.collectAsStateWithLifecycle()
    val dirty by session.project.dirty.collectAsStateWithLifecycle()
    val saving by session.project.saving.collectAsStateWithLifecycle()
    val beat by session.beat.beat.collectAsStateWithLifecycle()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Beat-LED
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (beat % 2L == 0L) MaterialTheme.colorScheme.secondary else Color.DarkGray)
        )
        Text(
            "  ${target.label}",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = session.project::save, enabled = saving == null) {
            Text(saving ?: if (dirty) "Speichern •" else "Speichern")
        }
        val bo = blackout == true
        Button(
            onClick = { session.globals.setBlackout(!bo) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (bo) Color(0xFFD32F2F) else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (bo) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) { Text("BLACKOUT") }
        val fr = freeze == true
        OutlinedButton(
            onClick = { session.globals.setFreeze(!fr) },
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (fr) MaterialTheme.colorScheme.secondary else Color.Transparent,
                contentColor = if (fr) Color.Black else MaterialTheme.colorScheme.secondary
            ),
            modifier = Modifier.padding(start = 4.dp)
        ) { Text("FREEZE") }
        TextButton(onClick = vm::disconnect) { Text("Trennen") }
    }
}
