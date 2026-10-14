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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
