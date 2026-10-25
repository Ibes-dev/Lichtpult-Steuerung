package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Einheitlicher Fader der App (Pult-Optik: Bahn, farbige Füllung, helle Kappe).
 * Relative Bedienung: Berührung springt NICHT auf den Touch-Punkt, sondern bewegt den Wert ab der
 * aktuellen Position (bühnensicher). Wert immer 0..1. Senkrecht oder waagerecht.
 */
@Composable
fun DeskFader(
    value: Float,
    onChange: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
    enabled: Boolean = true,
    onRelease: (() -> Unit)? = null
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnRelease by rememberUpdatedState(onRelease)
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    val shown = (if (dragging) local else value).coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier.then(if (!enabled) Modifier else Modifier.pointerInput(vertical) {
            val start: (Offset) -> Unit = { local = currentValue; dragging = true }
            val end: () -> Unit = { dragging = false; currentOnRelease?.invoke() }
            if (vertical) detectVerticalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = end) { change, d ->
                change.consume()
                val travel = (size.height - 24.dp.toPx()).coerceAtLeast(1f)
                local = (local - d / travel).coerceIn(0f, 1f)
                currentOnChange(local)
            } else detectHorizontalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = end) { change, d ->
                change.consume()
                val travel = (size.width - 24.dp.toPx()).coerceAtLeast(1f)
                local = (local + d / travel).coerceIn(0f, 1f)
                currentOnChange(local)
            }
        })
    ) {
        val cap = 24.dp.toPx()
        val trackW = 10.dp.toPx()
        val capColor = if (dragging) accent else Color(0xFFE0E0E0)
        if (vertical) {
            val travel = size.height - cap
            val cx = size.width / 2f
            drawRoundRect(track, Offset(cx - trackW / 2, 0f), Size(trackW, size.height), CornerRadius(trackW / 2))
            val capTop = travel * (1f - shown)
            drawRoundRect(accent.copy(alpha = 0.55f), Offset(cx - trackW / 2, capTop + cap / 2), Size(trackW, size.height - capTop - cap / 2), CornerRadius(trackW / 2))
            drawRoundRect(capColor, Offset(4.dp.toPx(), capTop), Size(size.width - 8.dp.toPx(), cap), CornerRadius(4.dp.toPx()))
        } else {
            val travel = size.width - cap
            val cy = size.height / 2f
            val capH = (size.height - 8.dp.toPx()).coerceAtMost(28.dp.toPx())
            drawRoundRect(track, Offset(0f, cy - trackW / 2), Size(size.width, trackW), CornerRadius(trackW / 2))
            val capLeft = travel * shown
            drawRoundRect(accent.copy(alpha = 0.55f), Offset(0f, cy - trackW / 2), Size(capLeft + cap / 2, trackW), CornerRadius(trackW / 2))
            drawRoundRect(capColor, Offset(capLeft, cy - capH / 2), Size(cap, capH), CornerRadius(4.dp.toPx()))
        }
    }
}

/** Waagerechter Fader für beliebige Wertebereiche (z. B. Programmer, Effektparameter). */
@Composable
fun DeskSlider(
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    accent: Color = Desk.Amber
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    DeskFader(
        ((value - range.start) / span).coerceIn(0f, 1f),
        { onChange(range.start + it * span) },
        accent,
        modifier.height(36.dp),
        vertical = false
    )
}

/** Bisheriger Name, unverändert nutzbar. */
@Composable
fun VerticalFader(value: Float, onChange: (Float) -> Unit, accent: Color, modifier: Modifier = Modifier) =
    DeskFader(value, onChange, accent, modifier, vertical = true)

/** Momentary-Taste: Drücken sendet true, Loslassen false (wie Companion press/release). */
@Composable
fun ExecButton(
    label: String,
    active: Boolean,
    accent: Color,
    onPress: (Boolean) -> Unit
) {
    val currentOnPress by rememberUpdatedState(onPress)
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        pressed -> accent
        active -> accent.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnPress(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            currentOnPress(false)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (pressed || active) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * XY-Pad für Pan/Tilt, Werte jeweils 0..1 (Abbildung auf den echten Bereich macht der Aufrufer).
 * Absolute Bedienung: Touch-Punkt = Position.
 */
@Composable
fun XYPad(
    x: Float,
    y: Float,
    onChange: (Float, Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        currentOnChange(
                            (o.x / size.width).coerceIn(0f, 1f),
                            (1f - o.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                ) { change, _ ->
                    change.consume()
                    currentOnChange(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    currentOnChange(
                        (o.x / size.width).coerceIn(0f, 1f),
                        (1f - o.y / size.height).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        val grid = Color.Gray.copy(alpha = 0.4f)
        for (i in 1..3) {
            val fx = size.width * i / 4f
            val fy = size.height * i / 4f
            drawLine(grid, Offset(fx, 0f), Offset(fx, size.height))
            drawLine(grid, Offset(0f, fy), Offset(size.width, fy))
        }
        val px = x * size.width
        val py = (1f - y) * size.height
        drawLine(accent.copy(alpha = 0.6f), Offset(px, 0f), Offset(px, size.height))
        drawLine(accent.copy(alpha = 0.6f), Offset(0f, py), Offset(size.width, py))
        drawCircle(accent, radius = 10.dp.toPx(), center = Offset(px, py))
    }
}

/** Klick ohne Ripple (für Farbfelder). */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) { detectTapGestures { onClick() } }
