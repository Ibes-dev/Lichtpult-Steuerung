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
    else -> ""
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
    else -> message ?: javaClass.simpleName
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
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }
}
