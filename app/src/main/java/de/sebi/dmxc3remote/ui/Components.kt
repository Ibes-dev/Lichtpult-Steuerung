package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Tablet-Layout ab 600 dp Breite (Material-Breakpoint "medium"). */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** Fehler eines Moduls: knapp, mit Ursache, ohne die Bedienung zu verdecken. */
@Composable
fun ModuleError(text: String?) {
    if (text == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Desk.Red.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Desk.Red, modifier = Modifier.size(16.dp))
        Text(
            text,
            color = Desk.Text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Gruppe mit Überschrift; optionale Aktionen rechts neben dem Titel. */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Desk.Panel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            actions()
        }
        if (error != null) ModuleError(error)
        content()
    }
}

/** Kleiner Zustands-Hinweis (läuft, Pause, aus …). */
@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = 5.dp))
    }
}

/** Leerer Zustand mit Handlungsanweisung statt leerer Fläche. */
@Composable
fun EmptyHint(title: String, hint: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = Desk.TextDim, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Kanalzug mit "Scribble Strip" wie am Hardware-Pult: farbiger Kopf mit Nummer und Name
 * in der Farbe des Executors, darunter Tasten und Fader.
 */
@Composable
fun ChannelStrip(
    number: String,
    name: String,
    color: Color,
    width: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(Desk.Panel)
            .border(1.dp, Desk.Line, RoundedCornerShape(10.dp))
    ) {
        Column(
            Modifier.fillMaxWidth().background(color.copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(number, style = MaterialTheme.typography.labelSmall, color = Color.Black.copy(alpha = 0.7f), maxLines = 1)
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.Black,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

/**
 * Schlankes Suchfeld (40 dp hoch) statt des großen OutlinedTextField – spart auf dem Handy Platz.
 * Mit Lupe, Platzhalter und Löschen-Kreuz.
 */
@Composable
fun CompactSearch(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Desk.Raised)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            Icons.Filled.Search, contentDescription = null,
            tint = Desk.TextDim, modifier = Modifier.size(18.dp)
        )
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Desk.TextDim, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Desk.Text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Desk.Amber),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            androidx.compose.material3.IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Close, contentDescription = "Suche löschen",
                    tint = Desk.TextDim, modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Einfacher Umbenennen-Dialog. */
@Composable
fun RenameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = text.isNotBlank() && text != initial, onClick = { onConfirm(text.trim()) }) { Text("Umbenennen") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

/** Kleiner Auswahl-Chip (30 dp hoch), z. B. für Gerätegruppen. */
@Composable
fun SmallChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Desk.Amber else Desk.Raised)
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.Black else Desk.Text
        )
    }
}

/** Schlanke Reiter mit Unterstrich (36 dp hoch). */
@Composable
fun SmallTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(36.dp)) {
        labels.forEachIndexed { i, t ->
            Column(
                Modifier.weight(1f).fillMaxHeight().clickableNoRipple { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    t, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (i == selected) Desk.Amber else Desk.TextDim
                )
                Box(
                    Modifier.padding(top = 4.dp).height(2.dp).width(28.dp)
                        .background(if (i == selected) Desk.Amber else Color.Transparent)
                )
            }
        }
    }
}
