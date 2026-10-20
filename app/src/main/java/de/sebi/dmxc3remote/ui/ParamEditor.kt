package de.sebi.dmxc3remote.ui

import LumosProtobuf.CommonTypes.ObjectData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
        else -> setDoubleValue(d)
    }
}.build()

/**
 * Editor für einen generischen Parameter (Effekte, Geräte):
 * Auswahlliste -> Chips, Ja/Nein -> Schalter, Zahl mit Grenzen -> Regler, sonst Eingabefeld.
 */
@Composable
fun ParamEditor(p: DevParam, onSet: (ObjectData) -> Unit) {
    val v = p.value
    val label = p.label + (p.desc.unit.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
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
