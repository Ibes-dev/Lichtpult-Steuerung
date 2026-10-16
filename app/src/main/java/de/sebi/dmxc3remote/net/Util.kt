package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.ConfirmedResponse
import LumosProtobuf.CommonTypes.ObjectData
import LumosProtobuf.CommonTypes.TranslateableData
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.AbstractStub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Unary-Calls immer mit Deadline. */
fun <S : AbstractStub<S>> S.dl(seconds: Long = 5): S = withDeadlineAfter(seconds, TimeUnit.SECONDS)

/** TranslateableData -> lesbarer Text ({0}, {1} … durch Parameter ersetzt). */
fun TranslateableData.text(): String {
    var s = formatString
    parametersList.forEachIndexed { i, p ->
        val v = when {
            p.hasTranslateable() -> p.translateable.text()
            p.hasObjectData() -> p.objectData.text()
            else -> ""
        }
        s = s.replace("{$i}", v)
    }
    return s
}

fun ObjectData.text(): String = when (valueCase) {
    ObjectData.ValueCase.STRINGVALUE -> stringValue
    ObjectData.ValueCase.DOUBLEVALUE -> doubleValue.toString()
    ObjectData.ValueCase.FLOATVALUE -> floatValue.toString()
    ObjectData.ValueCase.INTVALUE -> intValue.toString()
    ObjectData.ValueCase.LONGVALUE -> longValue.toString()
    ObjectData.ValueCase.UINTVALUE -> uintValue.toString()
    ObjectData.ValueCase.ULONGVALUE -> ulongValue.toString()
    ObjectData.ValueCase.BOOLVALUE -> boolValue.toString()
    ObjectData.ValueCase.SHORTVALUE -> shortValue.toString()
    ObjectData.ValueCase.SBYTEVALUE -> sbyteValue.toString()
    ObjectData.ValueCase.USHORTVALUE -> ushortValue.toString()
    ObjectData.ValueCase.BYTEVALUE -> byteValue.toString()
    ObjectData.ValueCase.ARRAYVALUE -> arrayValue.valuesList.joinToString(", ") { it.text() }
    else -> fallback()?.text ?: ""
}

/** Ergebnis der Auswertung eines nicht-primitiven Werts (Enum, Objekt). */
data class FallbackValue(val typeName: String, val number: Int?, val text: String)

/** Bekannte DMXC3-Enums, die als Ausgang vorkommen (Namen aus den Protos v3.3.2). */
private val KNOWN_ENUMS: Map<String, List<String>> = mapOf(
    "SceneListState" to listOf("Stopped", "Paused", "Running")
)

/**
 * Nicht-primitive Werte (z. B. der Cuelist-Status als Enum) kommen nicht als Text, sondern in
 * fallbackNet (.NET-Serialisierung) bzw. fallbackProtobuf mit Typname in fallbackType_AQ/protobufType_AQ.
 * UNVERIFIZIERT, heuristisch:
 *  - fallbackNet (BinaryFormatter): ein Enum steht als Int32 direkt vor dem Abschluss-Byte 0x0B
 *  - fallbackProtobuf: erstes Varint-Feld
 * Bekannte Enums werden in ihren Namen übersetzt (z. B. 2 -> "Running"), sonst "Typ=Zahl".
 */
fun ObjectData.fallback(): FallbackValue? {
    if (valueCase != ObjectData.ValueCase.VALUE_NOT_SET) return null
    val typeAq = fallbackTypeAQ.ifBlank { protobufTypeAQ }
    val typeName = typeAq.substringBefore(',').substringAfterLast('.').substringAfterLast('+')
    val bytes = when (fallbackCase) {
        ObjectData.FallbackCase.FALLBACKNET -> fallbackNet.toByteArray()
        ObjectData.FallbackCase.FALLBACKPROTOBUF -> fallbackProtobuf.toByteArray()
        else -> return if (typeName.isBlank()) null else FallbackValue(typeName, null, typeName)
    }
    val number: Int? = when (fallbackCase) {
        ObjectData.FallbackCase.FALLBACKNET ->
            if (bytes.size >= 5 && bytes.last() == 0x0B.toByte()) {
                val i = bytes.size - 5
                (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
            } else null
        else -> readFirstVarint(bytes)
    }
    val known = KNOWN_ENUMS.entries.firstOrNull { typeName.contains(it.key, ignoreCase = true) }?.value
    val text = when {
        number != null && known != null && number in known.indices -> known[number]
        number != null -> "$typeName=$number"
        else -> "$typeName (${bytes.size} Byte)"
    }
    return FallbackValue(typeName, number, text)
}

/** Erstes Feld einer Protobuf-Nachricht, falls es ein Varint ist (Wire-Type 0). */
private fun readFirstVarint(b: ByteArray): Int? {
    if (b.isEmpty() || (b[0].toInt() and 0x07) != 0) return null
    var result = 0L
    var shift = 0
    var i = 1
    while (i < b.size && shift < 35) {
        val v = b[i].toInt() and 0xFF
        result = result or ((v and 0x7F).toLong() shl shift)
        if (v and 0x80 == 0) return result.toInt()
        shift += 7
        i++
    }
    return null
}

fun ConfirmedResponse.requireOk(what: String) {
    if (!ok) throw UmbraException("$what: ${message.text().ifBlank { "abgelehnt" }}")
}

fun Throwable.describe(): String = when (this) {
    is StatusException -> when (status.code) {
        Status.Code.UNAVAILABLE -> "Umbra nicht erreichbar (${status.description ?: "UNAVAILABLE"})"
        Status.Code.PERMISSION_DENIED -> "Zugriff verweigert – NetID/Freigabe prüfen"
        Status.Code.UNAUTHENTICATED -> "Nicht authentifiziert – Sitzung ungültig"
        Status.Code.DEADLINE_EXCEEDED -> "Zeitüberschreitung"
        Status.Code.UNIMPLEMENTED -> "Vom Server nicht unterstützt"
        else -> "${status.code}: ${status.description ?: ""}"
    }
    else -> "${javaClass.simpleName}: ${message ?: ""}".trimEnd(' ', ':')
}

/**
 * Drosselt hochfrequente Werte (Fader): pro Schlüssel wird nur die jeweils
 * letzte Aktion ausgeführt, max. alle 25 ms.
 */
class Throttler {
    private val pending = ConcurrentHashMap<String, suspend () -> Unit>()

    fun submit(key: String, action: suspend () -> Unit) {
        pending[key] = action
    }

    suspend fun run(onError: (Throwable) -> Unit): Nothing {
        while (true) {
            delay(25)
            for (key in pending.keys.toList()) {
                val action = pending.remove(key) ?: continue
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Throwable) {
                    onError(e)
                }
            }
        }
    }
}
