package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.EChangeType
import LumosProtobuf.CommonTypes.ObjectData
import LumosProtobuf.Input.InputServiceCRUDTypes.InputSourceProxyRequest
import LumosProtobuf.Input.InputServiceTypes.EFeedbackType
import LumosProtobuf.Input.InputServiceTypes.EWellKnownInputType
import LumosProtobuf.Input.InputServiceTypes.InputElementDescriptor
import LumosProtobuf.Input.InputServiceTypes.InputSourceDescriptor
import LumosProtobuf.Input.InputServiceTypes.IoInformationDescriptor
import LumosProtobuf.ParameterServiceTypes.ParameterCategory
import LumosProtobufClient.InputClientGrpcKt.InputClientCoroutineStub
import de.sebi.dmxc3remote.audio.AudioFeatures
import de.sebi.dmxc3remote.audio.MOODS
import de.sebi.dmxc3remote.audio.MicBeatDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Überträgt die Mikrofon-Analyse als Eingänge in die DMXControl-Eingangszuweisung
 * (Ordner "Android: <Client>" → "Mikrofon-Analyse"). Gesendet wird höchstens einmal pro Sekunde
 * und nur bei Änderung. IDs enthalten ".mic.", damit das Softdesk-Aufräumen sie nicht entfernt.
 */
class MicFeaturesModule(
    s: UmbraSession,
    private val mic: MicBeatDetector,
    private val enabled: StateFlow<Boolean>
) : Module(s, "Mikrofon an DMXControl") {
    private val stub = InputClientCoroutineStub(s.channel)

    private enum class Ch(val label: String, val io: EWellKnownInputType, val min: Double? = null, val max: Double? = null) {
        ENERGY("Energie", EWellKnownInputType.NUMERIC, 0.0, 1.0),
        BRIGHTNESS("Helligkeit", EWellKnownInputType.NUMERIC, 0.0, 1.0),
        AROUSAL("Aktivierung", EWellKnownInputType.NUMERIC, 0.0, 1.0),
        VALENCE("Valenz", EWellKnownInputType.NUMERIC, -1.0, 1.0),
        ONSETS("Anschläge pro Sekunde", EWellKnownInputType.NUMERIC, 0.0, 10.0),
        BPM("Tempo BPM", EWellKnownInputType.NUMERIC, 0.0, 250.0),
        MOOD("Stimmung", EWellKnownInputType.STRING),
        MOOD_INDEX("Stimmung Nr.", EWellKnownInputType.NUMERIC, 0.0, (MOODS.size - 1).toDouble()),
        KEY("Tonart", EWellKnownInputType.STRING),
        MAJOR("Dur", EWellKnownInputType.BOOL)
    }

    private val _registered = MutableStateFlow(false)
    val registered: StateFlow<Boolean> = _registered.asStateFlow()

    private val _lastSent = MutableStateFlow<AudioFeatures?>(null)
    val lastSent: StateFlow<AudioFeatures?> = _lastSent.asStateFlow()

    private fun id(c: Ch) = "DMXC3Remote.${s.runtimeId}.mic.${c.name.lowercase()}"

    private fun descriptor(c: Ch): InputElementDescriptor {
        val io = IoInformationDescriptor.newBuilder().setAutoGraphIoType(c.io)
        if (c.min != null && c.max != null) {
            io.setMin(ObjectData.newBuilder().setDoubleValue(c.min)).setMax(ObjectData.newBuilder().setDoubleValue(c.max))
        }
        return InputElementDescriptor.newBuilder()
            .setId(id(c))
            .setDisplayName("Mikro: ${c.label}")
            .setCategory(
                ParameterCategory.newBuilder()
                    .setId("DMXC3Remote.${s.runtimeId}")
                    .setName("Android: ${s.clientName}")
                    .setSubCategory(ParameterCategory.newBuilder().setId("DMXC3Remote.${s.runtimeId}.mic").setName("Mikrofon-Analyse"))
            )
            .setIoInfo(io)
            .build()
    }

    private suspend fun call(c: Ch, type: EChangeType, value: ObjectData? = null) {
        val b = InputSourceProxyRequest.newBuilder()
            .setRequestId(s.nextRequestId())
            .setInputSourceId(id(c))
            .setChangeType(type)
            .setUserContextId(s.userContextId)
            .setRuntimeId(s.runtimeId)
        if (type != EChangeType.Changed || value == null) {
            b.setSource(InputSourceDescriptor.newBuilder().setElement(descriptor(c)).setReceivesFeedback(EFeedbackType.NONE))
        }
        if (value != null) b.setSourceValue(value)
        stub.dl(5).createOrUpdateOrDeleteSourceProxy(b.build(), s.headers).requireOk("Mikro ${c.label}")
    }

    private suspend fun register() {
        Ch.entries.forEach { c ->
            try {
                call(c, EChangeType.Added)
            } catch (e: UmbraException) {
                call(c, EChangeType.Changed)
            }
        }
        _registered.value = true
    }

    private suspend fun unregister() {
        Ch.entries.forEach { c -> runCatching { call(c, EChangeType.Removed) } }
        _registered.value = false
    }

    private fun num(v: Double) = ObjectData.newBuilder().setDoubleValue(v).build()

    private fun values(f: AudioFeatures): Map<Ch, ObjectData> = mapOf(
        Ch.ENERGY to num(round2(f.energy)),
        Ch.BRIGHTNESS to num(round2(f.brightness)),
        Ch.AROUSAL to num(round2(f.arousal)),
        Ch.VALENCE to num(round2(f.valence)),
        Ch.ONSETS to num(round2(f.onsetRate)),
        Ch.BPM to num(f.bpm?.let { Math.round(it * 10) / 10.0 } ?: 0.0),
        Ch.MOOD to ObjectData.newBuilder().setStringValue(f.mood).build(),
        Ch.MOOD_INDEX to num(f.moodIndex.toDouble()),
        Ch.KEY to ObjectData.newBuilder().setStringValue(f.key).build(),
        Ch.MAJOR to ObjectData.newBuilder().setBoolValue(f.major).build()
    )

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    override suspend fun stream() {
        val sent = HashMap<Ch, ObjectData>()
        while (true) {
            // warten, bis Senden aktiviert ist und das Mikrofon läuft
            combine(enabled, mic.running) { e, r -> e && r }.first { it }
            if (!_registered.value) register()
            while (enabled.value && mic.running.value) {
                val f = mic.features.value
                if (f != null) {
                    values(f).forEach { (c, v) ->
                        if (sent[c] != v) {
                            call(c, EChangeType.Changed, v)
                            sent[c] = v
                        }
                    }
                    _lastSent.value = f
                }
                delay(1_000)
            }
            // Senden ausgeschaltet: Eingänge bleiben angemeldet (Verdrahtung bleibt gültig)
            sent.clear()
        }
    }

    fun removeFromDmxc() = act("Mikro-Eingänge entfernen") { unregister() }
}
