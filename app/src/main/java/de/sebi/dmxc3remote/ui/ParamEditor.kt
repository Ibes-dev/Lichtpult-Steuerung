package de.sebi.dmxc3remote.ui

import LumosProtobuf.CommonTypes.ObjectData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * Editor für einen generischen Parameter.
 * Zahlenparameter (auch mit Einheit wie Phase in ° oder Frequenz in Hz) bekommen Regler bzw. Eingabe
 * und – wenn [onFan] gesetzt ist – einen Fächer-Modus "Start Operator Ende".
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
    val label = p.label + (p.desc.unit.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    val fan = v?.let { de.sebi.dmxc3remote.net.decodeFan(it) }
    val numeric = fan != null || p.isNumeric()
    if (numeric && onNumber != null) {
        var fanOn by remember(p.desc.name, fan != null) { mutableStateOf(fan != null) }
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        v?.let { de.sebi.dmxc3remote.net.paramText(it) }?.ifBlank { "–" } ?: "–",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fan != null) Desk.Amber else Desk.TextDim
                    )
                }
                if (onFan != null) FilterChip(
                    selected = fanOn,
                    onClick = {
                        // Fächer ausschalten = wieder ein Einzelwert (Startwert des Fächers)
                        if (fanOn && fan != null) onNumber(fan.from ?: 0.0)
                        fanOn = !fanOn
                    },
                    label = { Text("Fächern") }
                )
            }
            if (fanOn && onFan != null) FanParamRow(p, fan, operators, onFan)
            else NumberRow(p, onNumber)
        }
        return
    }
    ParamEditorBody(p, label, onSet)
}

/** Einzelwert: Regler, wenn DMXControl Grenzen nennt, sonst Eingabefeld. */
@Composable
private fun NumberRow(p: DevParam, onNumber: (Double) -> Unit) {
    val cur = p.value?.let { de.sebi.dmxc3remote.net.paramNumber(it) } ?: 0.0
    val lo = if (p.desc.hasLowerBound()) p.desc.lowerBound.asDouble() else null
    val hi = if (p.desc.hasUpperBound()) p.desc.upperBound.asDouble() else null
    if (lo != null && hi != null && hi > lo) {
        var local by remember(p.desc.name) { mutableFloatStateOf(cur.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(local.coerceIn(lo.toFloat(), hi.toFloat()), { local = it; onNumber(it.toDouble()) },
                Modifier.weight(1f), valueRange = lo.toFloat()..hi.toFloat())
            Text("%.2f".format(local), style = MaterialTheme.typography.labelMedium.merge(Numeric), modifier = Modifier.width(52.dp))
        }
    } else {
        var t by remember(p.desc.name, cur) { mutableStateOf(java.math.BigDecimal(cur).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactSearch(t, { t = it }, "Wert", Modifier.weight(1f))
            TextButton(onClick = { t.replace(',', '.').toDoubleOrNull()?.let(onNumber) }) { Text("Setzen") }
        }
    }
}

/** Start / Ende / Verlauf für einen Effektparameter. */
@Composable
private fun FanParamRow(p: DevParam, current: de.sebi.dmxc3remote.net.FanInfo?, operators: List<String>, onFan: (Double, Double, String) -> Unit) {
    val single = p.value?.let { de.sebi.dmxc3remote.net.paramNumber(it) }
    fun fmt(d: Double?) = d?.let { java.math.BigDecimal(it).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() } ?: "0"
    var from by remember(p.desc.name, current) { mutableStateOf(fmt(current?.from ?: single)) }
    var to by remember(p.desc.name, current) { mutableStateOf(fmt(current?.to ?: single)) }
    val ops = sortedOperators(operators)
    var op by remember(p.desc.name, current) { mutableStateOf(current?.op?.takeIf { it in ops } ?: ops.first()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactSearch(from, { from = it }, "Start", Modifier.weight(1f))
            Text(op, style = MaterialTheme.typography.titleMedium, color = Desk.Amber)
            CompactSearch(to, { to = it }, "Ende", Modifier.weight(1f))
            TextButton(onClick = {
                val a = from.replace(',', '.').toDoubleOrNull(); val b = to.replace(',', '.').toDoubleOrNull()
                if (a != null && b != null) onFan(a, b, op)
            }) { Text("Setzen") }
        }
        if (ops.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ops) { o -> FilterChip(selected = op == o, onClick = { op = o }, label = { Text(o) }) }
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
