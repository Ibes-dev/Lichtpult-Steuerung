package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
 * Vertikaler Fader mit relativer Bedienung: Berührung springt NICHT auf den
 * Touch-Punkt, sondern bewegt den Wert ab der aktuellen Position (bühnensicher).
 */
@Composable
fun VerticalFader(
    value: Float,
    onChange: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) local else value
    val track = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = {
                    local = currentValue
                    dragging = true
                },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false }
            ) { change, dragAmount ->
                change.consume()
                val travel = (size.height - 24.dp.toPx()).coerceAtLeast(1f)
                local = (local - dragAmount / travel).coerceIn(0f, 1f)
                currentOnChange(local)
            }
        }
    ) {
        val capH = 24.dp.toPx()
        val trackW = 10.dp.toPx()
        val travel = size.height - capH
        val cx = size.width / 2f
        drawRoundRect(
            color = track,
            topLeft = Offset(cx - trackW / 2, 0f),
            size = Size(trackW, size.height),
            cornerRadius = CornerRadius(trackW / 2)
        )
        val capTop = travel * (1f - shown)
        drawRoundRect(
            color = accent.copy(alpha = 0.55f),
            topLeft = Offset(cx - trackW / 2, capTop + capH / 2),
            size = Size(trackW, size.height - capTop - capH / 2),
            cornerRadius = CornerRadius(trackW / 2)
        )
        drawRoundRect(
            color = if (dragging) accent else Color(0xFFE0E0E0),
            topLeft = Offset(4.dp.toPx(), capTop),
            size = Size(size.width - 8.dp.toPx(), capH),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
    }
}

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
