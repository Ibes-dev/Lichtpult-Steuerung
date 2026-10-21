package de.sebi.dmxc3remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Symbole für Effekte und Filter, gezeichnet nach dem Namen – angelehnt an die Darstellung
 * in DMXControl (Effects and Filters): Kurvenform für 1D-Effekte, Figur für 2D-Effekte.
 */
@Composable
fun EffectIcon(name: String, modifier: Modifier, color: Color) {
    val n = name.lowercase().replace(" ", "").replace("_", "")
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension / 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when {
            // ---------- 1D: Kurvenformen über zwei Perioden
            "bump" in n -> wave(color, stroke) { t -> abs(sin(2 * PI * t)) }
            "capacitor" in n -> wave(color, stroke) { t ->
                val x = (t * 2) % 1.0
                if (x < 0.5) 1 - exp(-10 * x) else exp(-10 * (x - 0.5))
            }
            "cardinal" in n || "sinc" in n -> wave(color, stroke) { t ->
                val x = (t - 0.5) * 6 * PI
                val v = if (abs(x) < 1e-6) 1.0 else sin(x) / x
                0.25 + 0.75 * v
            }
            "pow" in n -> wave(color, stroke) { t -> abs(sin(2 * PI * t)).pow(4) }
            "rect" in n -> wave(color, stroke, steps = 400) { t -> if ((t * 2) % 1.0 < 0.5) 1.0 else 0.0 }
            "saw" in n -> wave(color, stroke, steps = 400) { t -> (t * 2) % 1.0 }
            "swingup" in n -> wave(color, stroke) { t -> abs(sin(3 * PI * t)) * (0.55 + 0.45 * ((t * 3) % 1.0)) }
            "swing" in n -> wave(color, stroke) { t -> 0.5 + 0.5 * sin(4 * PI * t) * (0.55 + 0.45 * sin(6 * PI * t)) }
            "trapez" in n -> wave(color, stroke, steps = 400) { t ->
                val x = (t * 2) % 1.0
                when {
                    x < 0.2 -> x / 0.2
                    x < 0.5 -> 1.0
                    x < 0.7 -> 1 - (x - 0.5) / 0.2
                    else -> 0.0
                }
            }
            "triangle" in n || "dreieck" in n -> wave(color, stroke, steps = 400) { t ->
                val x = (t * 2) % 1.0
                if (x < 0.5) x * 2 else 2 - x * 2
            }
            "sin" in n -> wave(color, stroke) { t -> 0.5 + 0.5 * sin(4 * PI * t) }

            // ---------- 2D: Figuren
            "lissajous" in n -> curve(color, stroke) { a -> sin(3 * a + PI / 2) to sin(2 * a) }
            "epitroch" in n -> trochoid(color, stroke, R = 3.0, r = 1.0, d = 0.5, epi = true)
            "epizyk" in n || "epicyc" in n -> trochoid(color, stroke, R = 5.0, r = 1.0, d = 1.0, epi = true)
            "hypotroch" in n -> trochoid(color, stroke, R = 5.0, r = 3.0, d = 5.0, epi = false)
            "hypozyk" in n || "hypocyc" in n -> trochoid(color, stroke, R = 3.0, r = 1.0, d = 1.0, epi = false)
            "starpolygon" in n -> {
                star(color, stroke, points = 3, inner = false, rotation = -PI / 2)
                star(color, stroke, points = 3, inner = false, rotation = PI / 2)
            }
            "star" in n || "stern" in n -> star(color, stroke, points = 5, inner = true, rotation = -PI / 2)
            "polygon" in n -> polygon(color, stroke, 4, PI / 4)
            "ellipse" in n -> curve(color, stroke) { a -> cos(a) to 0.45 * sin(a) }
            "circle" in n || "kreis" in n -> curve(color, stroke) { a -> cos(a) to sin(a) }

            // ---------- Filter
            "pwmstatic" in n -> {
                bars(color); drawLine(color, Offset(size.width * 0.12f, size.height * 0.9f), Offset(size.width * 0.88f, size.height * 0.9f), stroke.width)
            }
            "pwm" in n -> bars(color)
            "chaser" in n -> chaser(color, stroke)
            "listswitch" in n || "list" in n -> listSwitch(color, stroke)
            "matrix" in n -> grid(color, stroke)
            "radix" in n -> {
                curve(color, stroke) { a -> cos(a) * 0.9 to sin(a) * 0.9 }
                radial(color, stroke)
            }
            else -> wave(color, stroke) { t -> 0.5 + 0.5 * sin(4 * PI * t) }
        }
    }
}

/** 1D-Kurve: f(t) mit t, f ∈ [0, 1]. */
private fun DrawScope.wave(color: Color, stroke: Stroke, steps: Int = 120, f: (Double) -> Double) {
    val pad = size.minDimension * 0.12f
    val w = size.width - 2 * pad
    val h = size.height - 2 * pad
    val path = Path()
    for (i in 0..steps) {
        val t = i / steps.toDouble()
        val y = f(t).coerceIn(0.0, 1.0)
        val x = pad + (t * w).toFloat()
        val yy = pad + ((1 - y) * h).toFloat()
        if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
    }
    drawPath(path, color, style = stroke)
}

/** Parameterkurve, a ∈ [0, 2π], Werte ungefähr in [-1, 1]. */
private fun DrawScope.curve(color: Color, stroke: Stroke, steps: Int = 240, f: (Double) -> Pair<Double, Double>) {
    val pts = (0..steps).map { f(2 * PI * it / steps) }
    val m = pts.maxOf { maxOf(abs(it.first), abs(it.second)) }.takeIf { it > 0 } ?: 1.0
    val r = size.minDimension * 0.4f
    val c = Offset(size.width / 2, size.height / 2)
    val path = Path()
    pts.forEachIndexed { i, (x, y) ->
        val px = c.x + (x / m * r).toFloat()
        val py = c.y - (y / m * r).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.trochoid(color: Color, stroke: Stroke, R: Double, r: Double, d: Double, epi: Boolean) {
    // genug Umläufe, damit sich die Figur schließt
    val turns = (r / gcd(R, r)).toInt().coerceIn(1, 8)
    curve(color, stroke, steps = 360 * turns) { a0 ->
        val a = a0 * turns
        if (epi) ((R + r) * cos(a) - d * cos((R + r) / r * a)) to ((R + r) * sin(a) - d * sin((R + r) / r * a))
        else ((R - r) * cos(a) + d * cos((R - r) / r * a)) to ((R - r) * sin(a) - d * sin((R - r) / r * a))
    }
}

private fun gcd(a: Double, b: Double): Double = if (b < 1e-6) a else gcd(b, a - b * floor(a / b))

private fun DrawScope.polygon(color: Color, stroke: Stroke, corners: Int, rotation: Double) {
    val r = size.minDimension * 0.38f
    val c = Offset(size.width / 2, size.height / 2)
    val path = Path()
    for (i in 0..corners) {
        val a = rotation + 2 * PI * i / corners
        val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.star(color: Color, stroke: Stroke, points: Int, inner: Boolean, rotation: Double) {
    if (!inner) { polygon(color, stroke, points, rotation); return }
    val r = size.minDimension * 0.4f
    val ri = r * 0.42f
    val c = Offset(size.width / 2, size.height / 2)
    val path = Path()
    for (i in 0..points * 2) {
        val a = rotation + PI * i / points
        val rr = if (i % 2 == 0) r else ri
        val p = Offset(c.x + (rr * cos(a)).toFloat(), c.y + (rr * sin(a)).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.bars(color: Color) {
    val w = size.width * 0.14f
    val top = size.height * 0.18f
    val h = size.height * 0.64f
    drawRect(color, Offset(size.width * 0.28f, top), Size(w, h))
    drawRect(color, Offset(size.width * 0.58f, top), Size(w, h))
}

private fun DrawScope.chaser(color: Color, stroke: Stroke) {
    val r = size.minDimension * 0.09f
    val y = size.height / 2
    for (i in 0 until 4) {
        val x = size.width * (0.2f + i * 0.2f)
        if (i == 1) drawCircle(color, r, Offset(x, y)) else drawCircle(color, r, Offset(x, y), style = Stroke(stroke.width * 0.7f))
    }
}

private fun DrawScope.listSwitch(color: Color, stroke: Stroke) {
    for (i in 0 until 3) {
        val y = size.height * (0.3f + i * 0.2f)
        drawLine(color, Offset(size.width * 0.32f, y), Offset(size.width * 0.82f, y), stroke.width, StrokeCap.Round)
    }
    val p = Path().apply {
        moveTo(size.width * 0.14f, size.height * 0.42f)
        lineTo(size.width * 0.24f, size.height * 0.5f)
        lineTo(size.width * 0.14f, size.height * 0.58f)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.grid(color: Color, stroke: Stroke) {
    val s = size.minDimension * 0.18f
    for (i in 0 until 3) for (j in 0 until 3) {
        val o = Offset(size.width / 2 + (i - 1) * s * 1.3f - s / 2, size.height / 2 + (j - 1) * s * 1.3f - s / 2)
        if ((i + j) % 2 == 0) drawRect(color, o, Size(s, s)) else drawRect(color, o, Size(s, s), style = Stroke(stroke.width * 0.6f))
    }
}

private fun DrawScope.radial(color: Color, stroke: Stroke) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension * 0.36f
    for (i in 0 until 6) {
        val a = PI * i / 3
        drawLine(color, c, Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()), stroke.width * 0.6f)
    }
}
