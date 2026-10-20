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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Takt der App als Eingänge in der Eingangszuweisung ("Android: <Client>" → "Takt"):
 *  - Takt: Tap      Ein/Aus, kurzer Impuls bei jedem Tippen auf die Tap-Fläche
 *  - Takt: Beat     BEAT (zählt mit jedem Schlag des manuellen Takts von DMXControl)
 *  - Takt: Impuls   Ein/Aus, kurzer Impuls auf jedem Schlag
 *  - Takt: BPM      Zahl, aktuelles Tempo
 * IDs enthalten ".beat.", damit das Softdesk-Aufräumen sie nicht entfernt.
 */
class BeatInputModule(s: UmbraSession) : Module(s, "Takt an DMXControl") {
    private val stub = InputClientCoroutineStub(s.channel)

    private enum class Ch(val label: String, val io: EWellKnownInputType, val min: Double? = null, val max: Double? = null) {
        TAP("Tap", EWellKnownInputType.BOOL),
        BEAT("Beat", EWellKnownInputType.BEAT),
        PULSE("Impuls", EWellKnownInputType.BOOL),
        BPM("BPM", EWellKnownInputType.NUMERIC, 0.0, 400.0)
    }

    /** Tap-Ereignisse aus der UI. */
    private val taps = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    fun tapped() { taps.tryEmit(Unit) }

    private fun id(c: Ch) = "DMXC3Remote.${s.runtimeId}.beat.${c.name.lowercase()}"

    private fun descriptor(c: Ch): InputElementDescriptor {
        val io = IoInformationDescriptor.newBuilder().setAutoGraphIoType(c.io)
        if (c.min != null && c.max != null) {
            io.setMin(ObjectData.newBuilder().setDoubleValue(c.min)).setMax(ObjectData.newBuilder().setDoubleValue(c.max))
        }
        return InputElementDescriptor.newBuilder()
            .setId(id(c))
            .setDisplayName("Takt: ${c.label}")
            .setCategory(
                ParameterCategory.newBuilder()
                    .setId("DMXC3Remote.${s.runtimeId}")
                    .setName("Android: ${s.clientName}")
                    .setSubCategory(ParameterCategory.newBuilder().setId("DMXC3Remote.${s.runtimeId}.beat").setName("Takt"))
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
        if (value == null) b.setSource(InputSourceDescriptor.newBuilder().setElement(descriptor(c)).setReceivesFeedback(EFeedbackType.NONE))
        else b.setSourceValue(value)
        stub.dl(5).createOrUpdateOrDeleteSourceProxy(b.build(), s.headers).requireOk("Takt ${c.label}")
    }

    private suspend fun send(c: Ch, v: ObjectData) = runCatching { call(c, EChangeType.Changed, v) }

    private fun bool(b: Boolean) = ObjectData.newBuilder().setBoolValue(b).build()

    override suspend fun load() {
        Ch.entries.forEach { c ->
            try { call(c, EChangeType.Added) } catch (e: UmbraException) { call(c, EChangeType.Changed) }
        }
    }

    override suspend fun stream() = coroutineScope {
        // Tap-Impulse
        launch {
            taps.collect {
                send(Ch.TAP, bool(true))
                kotlinx.coroutines.delay(50)
                send(Ch.TAP, bool(false))
            }
        }
        // BPM bei Änderung
        launch {
            s.beat.intervalMs.collect { iv ->
                if (iv > 0) send(Ch.BPM, ObjectData.newBuilder().setDoubleValue(Math.round(600_000.0 / iv) / 10.0).build())
            }
        }
        // Schläge des manuellen Takts (Stream von DMXControl)
        s.beat.beat.collect { n ->
            if (n <= 0) return@collect
            send(Ch.BEAT, ObjectData.newBuilder().setUlongValue(n).build())
            send(Ch.PULSE, bool(true))
            kotlinx.coroutines.delay(40)
            send(Ch.PULSE, bool(false))
        }
    }
}
