package de.sebi.dmxc3remote.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Eine eingehende MIDI-Nachricht: Note (N) oder Control Change (C). */
data class MidiMsg(val kind: Char, val channel: Int, val number: Int, val value: Int) {
    /** Zuordnungsschlüssel, so im Softdesk-Element gespeichert, z. B. "N:0:56". */
    val key get() = "$kind:$channel:$number"
    val pressed get() = kind == 'N' && value > 0
}

/**
 * USB-/Bluetooth-MIDI über android.media.midi. Öffnet alle angeschlossenen Geräte,
 * liefert Noten/CC an [onMessage] (Hauptthread) und sendet LED-Rückmeldungen an alle Geräte.
 */
class MidiHub(context: Context) {
    private val mm: MidiManager? = context.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val devices = HashMap<Int, MidiDevice>()
    private val inputs = HashMap<Int, MutableList<MidiInputPort>>()

    private val _names = MutableStateFlow<List<String>>(emptyList())
    val names: StateFlow<List<String>> = _names.asStateFlow()

    /** Element-ID, für die gerade MIDI gelernt wird. */
    val learning = MutableStateFlow<String?>(null)

    /** Zuletzt gelernt: Element-ID -> Schlüssel. */
    val learned = MutableStateFlow<Pair<String, String>?>(null)

    private val _last = MutableStateFlow<MidiMsg?>(null)
    val last: StateFlow<MidiMsg?> = _last.asStateFlow()

    var onMessage: ((MidiMsg) -> Unit)? = null

    val available get() = mm != null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = open(info)
        override fun onDeviceRemoved(info: MidiDeviceInfo) = close(info.id)
    }

    @Suppress("DEPRECATION")
    fun start() {
        val m = mm ?: return
        m.registerDeviceCallback(callback, main)
        val list = if (Build.VERSION.SDK_INT >= 33) m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        else m.devices.toList()
        list.forEach { open(it) }
    }

    fun stop() {
        mm?.unregisterDeviceCallback(callback)
        devices.keys.toList().forEach { close(it) }
    }

    private fun name(info: MidiDeviceInfo) =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI ${info.id}"

    private fun open(info: MidiDeviceInfo) {
        if (devices.containsKey(info.id)) return
        mm?.openDevice(info, { dev ->
            if (dev == null) return@openDevice
            devices[info.id] = dev
            // Output-Ports des Geräts = Daten zur App
            for (p in 0 until info.outputPortCount) dev.openOutputPort(p)?.connect(Parser { msg -> main.post { dispatch(msg) } })
            // Input-Ports des Geräts = Daten von der App (LEDs)
            val ins = (0 until info.inputPortCount).mapNotNull { dev.openInputPort(it) }.toMutableList()
            inputs[info.id] = ins
            _names.value = devices.keys.mapNotNull { id -> devices[id]?.info?.let(::name) }
        }, main)
    }

    private fun close(id: Int) {
        inputs.remove(id)?.forEach { runCatching { it.close() } }
        devices.remove(id)?.let { runCatching { it.close() } }
        _names.value = devices.values.map { name(it.info) }
    }

    private fun dispatch(msg: MidiMsg) {
        _last.value = msg
        val target = learning.value
        if (target != null && (msg.kind == 'C' || msg.pressed)) {
            learning.value = null
            learned.value = target to msg.key
            return
        }
        onMessage?.invoke(msg)
    }

    /** Sendet Note-On (Velocity 0 = aus) bzw. CC an alle Geräte, z. B. für LED-Farben. */
    fun send(key: String, value: Int) {
        val parts = key.split(':')
        if (parts.size != 3) return
        val ch = parts[1].toIntOrNull()?.coerceIn(0, 15) ?: return
        val num = parts[2].toIntOrNull()?.coerceIn(0, 127) ?: return
        val status = if (parts[0] == "N") 0x90 or ch else 0xB0 or ch
        val bytes = byteArrayOf(status.toByte(), num.toByte(), value.coerceIn(0, 127).toByte())
        inputs.values.flatten().forEach { runCatching { it.send(bytes, 0, bytes.size) } }
    }

    /** Minimaler Byte-Stream-Parser mit Running Status. */
    private class Parser(val out: (MidiMsg) -> Unit) : MidiReceiver() {
        private var status = 0
        private val data = IntArray(2)
        private var count = 0

        override fun onSend(msg: ByteArray, offset: Int, count0: Int, timestamp: Long) {
            for (i in offset until offset + count0) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue                       // Realtime ignorieren
                if (b and 0x80 != 0) { status = if (b < 0xF0) b else 0; count = 0; continue }
                if (status == 0) continue
                data[count++] = b
                val type = status and 0xF0
                val need = if (type == 0xC0 || type == 0xD0) 1 else 2
                if (count < need) continue
                count = 0
                val ch = status and 0x0F
                when (type) {
                    0x90 -> out(MidiMsg('N', ch, data[0], data[1]))
                    0x80 -> out(MidiMsg('N', ch, data[0], 0))
                    0xB0 -> out(MidiMsg('C', ch, data[0], data[1]))
                }
            }
        }
    }
}
