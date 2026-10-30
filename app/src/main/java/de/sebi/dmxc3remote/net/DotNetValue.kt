package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.ObjectData
import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * .NET-Werte, die DMXControl als BinaryFormatter-Daten im Feld fallbackNet überträgt.
 * Aufbau belegt durch den Mitschnitt der Windows-Oberfläche (Eingangszuweisung, 30.10.2026):
 *
 *  System.Drawing.Point:  <Kopf>  x:int32 LE  y:int32 LE  0x0B
 *  MultiTypeValue:        <Kopf>  <Wert-Datensatz>  0x0A 0x0A 0x0A  0x0B
 *     Wert-Datensatz = 0x08 <Typcode> <Wert>  (Boolean=1, Double=6, Int32=8) oder 0x0A (null)
 */
object DotNetValue {
    /** Kopf eines Point, so wie ihn die Windows-Oberfläche sendet (System.Drawing 4.0). */
    private val POINT_HEAD = hex(
        "0001000000ffffffff01000000000000000c020000005153797374656d2e44726177696e672c2056657273696f6e3d342e302e302e302c2043756c747572653d6e65757472616c2c205075626c69634b6579546f6b656e3d6230336635663766313164353061336105010000001453797374656d2e44726177696e672e506f696e74020000000178017900000808020000 00".replace(" ", "")
    )
    private const val POINT_AQ = "System.Drawing.Point, System.Drawing, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a"

    /** Ende des Kopfs eines MultiTypeValue: Mitgliedstypen + Bibliotheks-ID. */
    private val MULTI_HEAD_END = hex("020701010202000000")
    private val MULTI_TAIL = hex("0a0a0a0b")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun le(b: ByteArray, at: Int, n: Int) = ByteBuffer.wrap(b, at, n).order(ByteOrder.LITTLE_ENDIAN)

    fun isPoint(v: ObjectData?) = v != null && v.fallbackTypeAQ.startsWith("System.Drawing.Point")
    fun isMulti(v: ObjectData?) = v != null && v.fallbackTypeAQ.contains("MultiTypeValue")

    fun parsePoint(v: ObjectData?): Pair<Int, Int>? {
        if (!isPoint(v) || v!!.fallbackCase != ObjectData.FallbackCase.FALLBACKNET) return null
        val b = v.fallbackNet.toByteArray()
        if (b.size < 9 || b.last() != 0x0B.toByte()) return null
        return le(b, b.size - 9, 4).int to le(b, b.size - 5, 4).int
    }

    fun buildPoint(x: Int, y: Int): ObjectData {
        val body = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(y).put(0x0B).array()
        return ObjectData.newBuilder().setFallbackTypeAQ(POINT_AQ).setFallbackNet(ByteString.copyFrom(POINT_HEAD + body)).build()
    }

    /** Wert in einem MultiTypeValue: Boolean, Int32, Double, String oder null. */
    fun parseMulti(v: ObjectData?): Any? {
        if (!isMulti(v) || v!!.fallbackCase != ObjectData.FallbackCase.FALLBACKNET) return null
        val b = v.fallbackNet.toByteArray()
        val i = indexOf(b, MULTI_HEAD_END).takeIf { it >= 0 }?.plus(MULTI_HEAD_END.size) ?: return null
        if (i >= b.size) return null
        return when (b[i].toInt()) {
            0x08 -> if (i + 2 >= b.size) null else when (b[i + 1].toInt()) {
                1 -> b[i + 2] != 0.toByte()
                6 -> le(b, i + 2, 8).double
                8 -> le(b, i + 2, 4).int
                else -> null
            }
            // BinaryObjectString: 0x06, Objekt-ID (int32), Länge (7-Bit-kodiert), UTF-8
            0x06 -> {
                var k = i + 5; var len = 0; var shift = 0
                while (k < b.size) { val c = b[k++].toInt() and 0xFF; len = len or ((c and 0x7F) shl shift); shift += 7; if (c < 0x80) break }
                if (k + len > b.size) null else String(b, k, len, Charsets.UTF_8)
            }
            else -> null
        }
    }

    /** Anzeige wie in der Windows-Oberfläche: True/False, Zahlen mit Punkt, Text unverändert. */
    fun multiText(v: ObjectData?): String = when (val x = parseMulti(v)) {
        is Boolean -> if (x) "True" else "False"
        is Double -> java.math.BigDecimal(x).stripTrailingZeros().toPlainString()
        null -> ""
        else -> x.toString()
    }

    /**
     * Eingabetext in den Wert eines MultiTypeValue: True/False → Ein/Aus, ganze Zahl → Int32,
     * Kommazahl → Double, sonst Text. So frei wie in der Windows-Oberfläche.
     */
    fun multiFromText(t: String): Any {
        val s = t.trim()
        return when {
            s.equals("true", true) -> true
            s.equals("false", true) -> false
            s.matches(Regex("-?\\d{1,9}")) -> s.toInt()
            s.replace(',', '.').toDoubleOrNull() != null && s.any { it == '.' || it == ',' } -> s.replace(',', '.').toDouble()
            else -> t
        }
    }

    /** Neuer MultiTypeValue auf Basis des gelesenen (Kopf und Typangaben unverändert). */
    fun buildMulti(template: ObjectData, value: Any): ObjectData? {
        val b = template.fallbackNet.toByteArray()
        val i = indexOf(b, MULTI_HEAD_END).takeIf { it >= 0 }?.plus(MULTI_HEAD_END.size) ?: return null
        val rec = when (value) {
            is Boolean -> byteArrayOf(0x08, 1, (if (value) 1 else 0).toByte())
            is Int -> byteArrayOf(0x08, 8) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
            is Double -> byteArrayOf(0x08, 6) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
            is String -> {
                // Objekt-ID 3: 1 = MultiTypeValue, 2 = Bibliothek (siehe Kopf)
                val u = value.toByteArray(Charsets.UTF_8)
                val len = ArrayList<Byte>(); var n = u.size
                do { var c = n and 0x7F; n = n ushr 7; if (n != 0) c = c or 0x80; len += c.toByte() } while (n != 0)
                byteArrayOf(0x06, 3, 0, 0, 0) + len.toByteArray() + u
            }
            else -> return null
        }
        return template.toBuilder().setFallbackNet(ByteString.copyFrom(b.copyOfRange(0, i) + rec + MULTI_TAIL)).build()
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }
        return -1
    }
}
