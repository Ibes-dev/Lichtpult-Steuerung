package de.sebi.dmxc3remote.ui

import LumosProtobuf.CommonTypes.ObjectData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.sebi.dmxc3remote.net.DevParam
import de.sebi.dmxc3remote.net.asDouble
import de.sebi.dmxc3remote.net.caption
import de.sebi.dmxc3remote.net.text

/** Parameter mit bekanntem, einfachem Werttyp (Zahl, Ja/Nein, Text, Auswahlliste) – nur solche werden angezeigt. */
fun DevParam.isEditable(): Boolean {
    if (desc.enumValuesCount > 0) return true
    return when (value?.valueCase) {
        ObjectData.ValueCase.BOOLVALUE, ObjectData.ValueCase.STRINGVALUE,
        ObjectData.ValueCase.DOUBLEVALUE, ObjectData.ValueCase.FLOATVALUE,
        ObjectData.ValueCase.INTVALUE, ObjectData.ValueCase.LONGVALUE,
        ObjectData.ValueCase.UINTVALUE, ObjectData.ValueCase.ULONGVALUE,
        ObjectData.ValueCase.SHORTVALUE, ObjectData.ValueCase.USHORTVALUE,
        ObjectData.ValueCase.BYTEVALUE, ObjectData.ValueCase.SBYTEVALUE -> true
        else -> false
    }
}

/**
 * Zahlenparameter laut Typangabe von DMXControl (parameterValueType_AQ, z. B. "System.Double").
 * Nur diese lassen sich fächern – nicht Auswahl-, Ja/Nein- oder Modus-Parameter wie "Load Mode".
 */
fun DevParam.isNumeric(): Boolean {
    if (desc.enumValuesCount > 0) return false
    if (value?.valueCase == ObjectData.ValueCase.BOOLVALUE) return false
    val aq = desc.parameterValueTypeAQ.substringBefore(',').substringAfterLast('.').lowercase()
    // .NET-Zahlentypen
    if (aq in setOf("double", "single", "decimal", "int16", "int32", "int64", "uint16", "uint32", "uint64", "byte", "sbyte")) return true
    // Einheiten-Typen von DMXControl (Phase in °, Frequenz in Hz, Prozent …) kommen als eigener Typ
    if (listOf("angle", "degree", "frequency", "hertz", "percent", "time", "duration", "speed", "value", "factor").any { it in aq }) return true
    // Sonst am Wert erkennen: Zahl (ggf. mit Einheit) oder gefächert "a > b"
    val t = value?.text()?.trim().orEmpty()
    if (Regex("""^-?\d+([.,]\d+)?\s*\S{0,3}$""").matches(t)) return true
    if (Regex("""^-?\d+([.,]\d+)?\s*\S+\s*-?\d+([.,]\d+)?$""").matches(t)) return true
    if (desc.hasLowerBound() && desc.lowerBound.asDouble() != null && desc.hasUpperBound() && desc.upperBound.asDouble() != null) return true
    return false
}

/** Neuer Wert im selben Typ wie der bisherige. */
private fun sameType(old: ObjectData?, d: Double): ObjectData = ObjectData.newBuilder().apply {
    when (old?.valueCase) {
        ObjectData.ValueCase.FLOATVALUE -> setFloatValue(d.toFloat())
        ObjectData.ValueCase.INTVALUE -> setIntValue(Math.round(d).toInt())
        ObjectData.ValueCase.LONGVALUE -> setLongValue(Math.round(d))
        ObjectData.ValueCase.UINTVALUE -> setUintValue(Math.round(d).toInt())
        ObjectData.ValueCase.ULONGVALUE -> setUlongValue(Math.round(d))
        ObjectData.ValueCase.SHORTVALUE -> setShortValue(Math.round(d).toInt())
        ObjectData.ValueCase.USHORTVALUE -> setUshortValue(Math.round(d).toInt())
        ObjectData.ValueCase.BYTEVALUE -> setByteValue(Math.round(d).toInt())
        ObjectData.ValueCase.SBYTEVALUE -> setSbyteValue(Math.round(d).toInt())
        ObjectData.ValueCase.DOUBLEVALUE, null -> setDoubleValue(d)
        // Einheiten-Typen (Phase, Frequenz …) liefert DMXControl als Text oder Sonderwert:
        // als Text senden, so wie man ihn in der Device Control eintippt
        else -> setStringValue(java.math.BigDecimal(d).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
    }
}.build()

/**
 * Editor für einen generischen Parameter (Effekte, Geräte):
 * Auswahlliste -> Chips, Ja/Nein -> Schalter, Zahl mit Grenzen -> Regler, sonst Eingabefeld.
 */
/** Operatoren für Fächer: der lineare Verlauf ">" zuerst. */
fun sortedOperators(ops: List<String>): List<String> =
    (listOf(">") + ops).distinct().sortedBy { if (it == ">") 0 else 1 }

/** Bereich und Vorgabe eines Effektparameters (wie in der Device Control von DMXControl). */
data class ParamSpec(val min: Float, val max: Float, val def: Float, val bool: Boolean = false, val fmt: String = "%.0f")

/**
 * Vorgaben nach Parametername, da DMXControl über die Schnittstelle keine Grenzen mitschickt:
 * Amplitude 0–100 (100), Phase 0–360° (0), Frequenz 0–5 Hz (0,1), "+/-"-Schalter aus.
 */
fun paramSpec(p: DevParam): ParamSpec {
    val n = (p.desc.name + " " + p.desc.displayName).lowercase()
    val aq = p.desc.parameterValueTypeAQ.lowercase()
    if ("boolean" in aq || n.trim().startsWith("+/-") || p.value?.valueCase == ObjectData.ValueCase.BOOLVALUE) return ParamSpec(0f, 1f, 0f, bool = true)
    val lo = if (p.desc.hasLowerBound()) p.desc.lowerBound.asDouble()?.toFloat() else null
    val hi = if (p.desc.hasUpperBound()) p.desc.upperBound.asDouble()?.toFloat() else null
    val byName = when {
        "phase" in n -> ParamSpec(0f, 360f, 0f, fmt = "%.0f°")
        "freq" in n -> ParamSpec(0f, 5f, 1f, fmt = "%.2f Hz")
        "amplitude" in n -> ParamSpec(0f, 100f, 100f)
        "time" in n || "zeit" in n -> ParamSpec(0f, 5f, 0.1f, fmt = "%.2f")
        "offset" in n -> ParamSpec(-100f, 100f, 0f)
        "rotation" in n || "angle" in n || "winkel" in n -> ParamSpec(0f, 360f, 0f, fmt = "%.0f°")
        "size" in n || "radius" in n || "width" in n || "height" in n || "größe" in n -> ParamSpec(0f, 100f, 50f)
        "count" in n || "anzahl" in n || "corner" in n || "ecken" in n -> ParamSpec(1f, 12f, 5f)
        else -> null
    }
    // Bekannte Parameter: Bereich wie in der Device Control (DMXControl erlaubt z. B. Phase bis 3600°,
    // das macht den Regler unbrauchbar grob). Unbekannte: Grenzen von DMXControl, sonst 0–100.
    return byName ?: if (lo != null && hi != null && hi > lo) ParamSpec(lo, hi, lo.coerceAtLeast(0f).coerceAtMost(hi), fmt = "%.2f")
    else ParamSpec(0f, 100f, 0f, fmt = "%.1f")
}

/**
 * Editor für einen Effektparameter.
 *  Schalter-Parameter: Schalter (Vorgabe aus).
 *  Zahlenparameter: Regler mit Vorgabewert; "Fächern" ist ausgeschaltet und öffnet Start · Verlauf · Ende.
 *  Alles andere (Auswahllisten wie Play Mode): Auswahl.
 */
@Composable
fun ParamEditor(
    p: DevParam,
    onSet: (ObjectData) -> Unit,
    operators: List<String> = emptyList(),
    onFan: ((from: Double, to: Double, op: String) -> Unit)? = null,
    onNumber: ((Double) -> Unit)? = null
) {
    val v = p.value
    val label = p.label
    val spec = paramSpec(p)
    if (spec.bool) {
        var on by remember(p.desc.name) { mutableStateOf(if (v?.valueCase == ObjectData.ValueCase.BOOLVALUE) v.boolValue else false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { on = it; onSet(ObjectData.newBuilder().setBoolValue(it).build()) })
        }
        return
    }
    val fan = v?.let { de.sebi.dmxc3remote.net.decodeFan(it) }
    val numeric = p.desc.enumValuesCount == 0 && (fan != null || p.isNumeric() || v == null || v.valueCase == ObjectData.ValueCase.VALUE_NOT_SET)
    if (numeric && onNumber != null) {
        var fanOn by remember(p.desc.name) { mutableStateOf(false) }
        var local by remember(p.desc.name) {
            mutableFloatStateOf((v?.let { de.sebi.dmxc3remote.net.paramNumber(it) }?.toFloat() ?: spec.def).coerceIn(spec.min, spec.max))
        }
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (fan != null && !fanOn) Text(fan.text, style = MaterialTheme.typography.labelSmall, color = Desk.Amber, modifier = Modifier.padding(end = 8.dp))
                if (onFan != null) SmallChip("Fächern", fanOn, {
                    // Fächer ausschalten = wieder Einzelwert
                    if (fanOn && fan != null) onNumber(local.toDouble())
                    fanOn = !fanOn
                })
            }
            if (fanOn && onFan != null) {
                FanParamRow(p, fan, operators, spec, local, onFan)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(local, { local = it; onNumber(it.toDouble()) }, Modifier.weight(1f).height(36.dp), valueRange = spec.min..spec.max)
                    Text(spec.fmt.format(local), style = MaterialTheme.typography.labelMedium.merge(Numeric), color = Desk.TextDim, modifier = Modifier.width(64.dp).padding(start = 6.dp))
                }
            }
        }
        return
    }
    ParamEditorBody(p, label, onSet)
}

/** Start · Verlauf · Ende für einen Effektparameter, mit zwei Reglern im Bereich des Parameters. */
@Composable
private fun FanParamRow(
    p: DevParam, current: de.sebi.dmxc3remote.net.FanInfo?, operators: List<String>,
    spec: ParamSpec, single: Float, onFan: (Double, Double, String) -> Unit
) {
    var from by remember(p.desc.name) { mutableFloatStateOf((current?.from?.toFloat() ?: single).coerceIn(spec.min, spec.max)) }
    var to by remember(p.desc.name) { mutableFloatStateOf((current?.to?.toFloat() ?: if (single == spec.max) spec.min else spec.max).coerceIn(spec.min, spec.max)) }
    val ops = sortedOperators(operators)
    var op by remember(p.desc.name) { mutableStateOf(current?.op?.takeIf { it in ops } ?: ops.first()) }
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        listOf("Start" to from, "Ende" to to).forEachIndexed { i, (n, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, style = MaterialTheme.typography.labelMedium, color = Desk.TextDim, modifier = Modifier.width(44.dp))
                Slider(value, { if (i == 0) from = it else to = it }, Modifier.weight(1f).height(32.dp), valueRange = spec.min..spec.max)
                Text(spec.fmt.format(value), style = MaterialTheme.typography.labelSmall.merge(Numeric), color = Desk.TextDim, modifier = Modifier.width(64.dp).padding(start = 6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ops) { o -> SmallChip(o, op == o, { op = o }) }
            }
            TextButton(onClick = { onFan(from.toDouble(), to.toDouble(), op) }) { Text("Setzen") }
        }
    }
}

@Composable
private fun ParamEditorBody(p: DevParam, label: String, onSet: (ObjectData) -> Unit) {
    val v = p.value
    when {
        p.desc.enumValuesCount > 0 -> Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            val current = v?.text()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(p.desc.enumValuesList) { e ->
                    val obj = if (e.hasObj()) e.obj else null
                    val caption = obj?.text() ?: if (e.hasIenum()) e.ienum.caption() else "?"
                    FilterChip(
                        selected = caption == current,
                        onClick = { if (obj != null) onSet(obj) },
                        label = { Text(caption) }
                    )
                }
            }
        }
        v?.valueCase == ObjectData.ValueCase.BOOLVALUE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = v.boolValue, onCheckedChange = { onSet(ObjectData.newBuilder().setBoolValue(it).build()) })
        }
        v?.valueCase == ObjectData.ValueCase.STRINGVALUE -> {
            var t by remember(p.desc.name) { mutableStateOf(v.stringValue) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(t, { t = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSet(ObjectData.newBuilder().setStringValue(t).build()) }) { Text("Setzen") }
            }
        }
        else -> {
            val cur = v?.asDouble() ?: 0.0
            val lo = if (p.desc.hasLowerBound()) p.desc.lowerBound.asDouble() else null
            val hi = if (p.desc.hasUpperBound()) p.desc.upperBound.asDouble() else null
            if (lo != null && hi != null && hi > lo) {
                var local by remember(p.desc.name) { mutableFloatStateOf(cur.toFloat()) }
                Column {
                    Row {
                        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text("%.2f".format(local), style = MaterialTheme.typography.labelMedium.merge(Numeric))
                    }
                    Slider(
                        local.coerceIn(lo.toFloat(), hi.toFloat()),
                        { local = it; onSet(sameType(v, it.toDouble())) },
                        valueRange = lo.toFloat()..hi.toFloat()
                    )
                }
            } else {
                var t by remember(p.desc.name) { mutableStateOf(v?.text() ?: "") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        t, { t = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    TextButton(onClick = { t.replace(',', '.').toDoubleOrNull()?.let { onSet(sameType(v, it)) } }) { Text("Setzen") }
                }
            }
        }
    }
}
