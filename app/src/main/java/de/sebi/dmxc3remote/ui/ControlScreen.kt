package de.sebi.dmxc3remote.ui

import LumosProtobuf.Executor.ExecutorServiceTypes.ExecutorDescriptor
import LumosProtobuf.Master.MasterServiceCRUDTypes.MasterActionRequest
import LumosProtobuf.Master.MasterServiceTypes.EMasterType
import LumosProtobuf.Master.MasterServiceTypes.MasterDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.UmbraTarget
import kotlin.math.roundToInt

private const val ALL_PAGES = "__all__"

@Composable
fun ControlScreen(vm: AppViewModel, session: UmbraSession, target: UmbraTarget, modifier: Modifier = Modifier) {
    val executors by session.executors.collectAsStateWithLifecycle()
    val pages by session.pages.collectAsStateWithLifecycle()
    val masters by session.masters.collectAsStateWithLifecycle()
    val initialPage by session.initialPageId.collectAsStateWithLifecycle()
    val error by vm.lastError.collectAsStateWithLifecycle()

    var selectedPage by rememberSaveable { mutableStateOf<String?>(null) }
    val pageId = selectedPage ?: initialPage ?: ALL_PAGES

    val sortedPages = remember(pages) { pages.values.sortedBy { it.pageNumber } }
    val visible: List<ExecutorDescriptor> = remember(executors, pages, pageId) {
        val page = pages[pageId]
        if (page == null) {
            executors.values.sortedBy { it.number }
        } else {
            page.executorIdsList.mapNotNull { executors[it] }
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(target.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            masters.values
                .filter { it.type == EMasterType.GRAND }
                .sortedBy { it.number }
                .forEach { GrandMasterControls(it, vm) }
            TextButton(onClick = vm::disconnect) { Text("Trennen") }
        }
        error?.let {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f), maxLines = 2)
                TextButton(onClick = vm::clearError) { Text("OK") }
            }
        }

        val tabs = listOf(ALL_PAGES) + sortedPages.map { it.id }
        val selectedIndex = tabs.indexOf(pageId).coerceAtLeast(0)
        ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, id ->
                val label = if (id == ALL_PAGES) "Alle" else pages[id]?.let { p ->
                    "${p.pageNumber} ${p.name}".trim()
                } ?: id
                Tab(selected = i == selectedIndex, onClick = { selectedPage = id }, text = { Text(label, maxLines = 1) })
            }
        }

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Keine Executoren auf dieser Seite")
            }
        } else {
            LazyRow(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.id }) { ex ->
                    ExecutorStrip(
                        ex = ex,
                        onFader = { v -> vm.setFader(ex.id, v) },
                        onButton = { b, pressed -> vm.setButton(ex.id, b, pressed) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GrandMasterControls(m: MasterDescriptor, vm: AppViewModel) {
    val pct = (m.intensity * 100).roundToInt()
    Text("${m.displayName.ifBlank { m.name }} $pct%", style = MaterialTheme.typography.labelLarge)
    TextButton(onClick = { vm.masterAction(m.masterId, MasterActionRequest.EAction.BLACK) }) { Text("BLACK") }
    TextButton(onClick = { vm.masterAction(m.masterId, MasterActionRequest.EAction.FULL) }) { Text("FULL") }
}

@Composable
private fun ExecutorStrip(
    ex: ExecutorDescriptor,
    onFader: (Float) -> Unit,
    onButton: (Int, Boolean) -> Unit
) {
    val accent = ex.color.let { c ->
        if (!ex.hasColor() || (c.r == 0 && c.g == 0 && c.b == 0)) MaterialTheme.colorScheme.primary
        else Color(c.r, c.g, c.b)
    }
    Column(
        Modifier
            .width(96.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "${ex.number}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            ex.name.ifBlank { ex.faderDisplayName.ifBlank { "—" } },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(34.dp)
        )

        val labels = listOf(
            ex.button1DisplayName, ex.button2DisplayName, ex.button3DisplayName, ex.button4DisplayName
        )
        val keys = listOf(ex.button1Key, ex.button2Key, ex.button3Key, ex.button4Key)
        val active = listOf(ex.button1Active, ex.button2Active, ex.button3Active, ex.button4Active)
        val used = (0..3).filter { labels[it].isNotBlank() || keys[it].isNotBlank() }
            .ifEmpty { listOf(0) }
        used.forEach { i ->
            ExecButton(
                label = labels[i].ifBlank { keys[i].ifBlank { "B${i + 1}" } },
                active = active[i],
                accent = accent,
                onPress = { pressed -> onButton(i + 1, pressed) }
            )
        }

        Text(
            "${(ex.faderPosition * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium
        )
        VerticalFader(
            value = ex.faderPosition.toFloat().coerceIn(0f, 1f),
            onChange = onFader,
            accent = accent,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
