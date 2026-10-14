package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.EChangeType
import LumosProtobuf.CommonTypes.ObjectData
import LumosProtobuf.Input.InputServiceCRUDTypes.InputSinkProxyRequest
import LumosProtobuf.Input.InputServiceCRUDTypes.InputSourceProxyRequest
import LumosProtobuf.Input.InputServiceTypes.EFeedbackType
import LumosProtobuf.Input.InputServiceTypes.EWellKnownInputType
import LumosProtobuf.Input.InputServiceTypes.InputElementDescriptor
import LumosProtobuf.Input.InputServiceTypes.InputSinkDescriptor
import LumosProtobuf.Input.InputServiceTypes.InputSourceDescriptor
import LumosProtobuf.Input.InputServiceTypes.IoInformationDescriptor
import LumosProtobuf.ParameterServiceTypes.ParameterCategory
import LumosProtobufClient.InputClientGrpcKt.InputClientCoroutineStub
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Softdesk über Input-Proxies (InputClient.CreateOrUpdateOrDeleteSource/SinkProxy):
 * Jedes Element wird als Eingang (Source) und/oder Ausgang (Sink) im Baum der
 * DMXC3-Eingangszuweisung registriert und dort wie ein Hardware-Controller verdrahtet.
 * Werte gehen als SourceProxy-Changed hinaus, Feedback kommt über ReceiveSinkProxyChanges.
 *
 * UNVERIFIZIERT (keine öffentliche Referenzimplementierung):
 *  - Registrierung per changeType=Added, Werte per changeType=Changed + sourceValue
 *  - Source und Sink eines Elements mit derselben ID (gekoppeltes Feedback wie bei Motorfadern)
 *  - Ob der Server Proxies nach Verbindungsabbruch entfernt (App registriert bei jedem Verbinden neu)
 */
class SoftdeskModule(s: UmbraSession, private val store: SoftdeskStore) : Module(s, "Softdesk") {
    private val stub = InputClientCoroutineStub(s.channel)

    /** Feedback-Werte je Element-ID (ohne Präfix). */
    private val _feedback = MutableStateFlow<Map<String, ObjectData>>(emptyMap())
    val feedback: StateFlow<Map<String, ObjectData>> = _feedback.asStateFlow()

    private val _registered = MutableStateFlow(0)
    val registered: StateFlow<Int> = _registered.asStateFlow()

    /** Registrierter Stand je Element: Proxy-ID -> (Desk, Element). */
    private val active = HashMap<String, Pair<Softdesk, SdElement>>()

    fun proxyId(e: SdElement) = "DMXC3Remote.${s.runtimeId}.${e.id}"
    private fun elementIdOf(proxyId: String) = proxyId.substringAfterLast('.')

    private fun descriptor(d: Softdesk, e: SdElement): InputElementDescriptor {
        val io = IoInformationDescriptor.newBuilder()
        when (e.type) {
            SdType.FADER -> io.setAutoGraphIoType(EWellKnownInputType.NUMERIC)
                .setMin(ObjectData.newBuilder().setDoubleValue(0.0))
                .setMax(ObjectData.newBuilder().setDoubleValue(1.0))
            SdType.LABEL -> io.setAutoGraphIoType(EWellKnownInputType.STRING)
            else -> io.setAutoGraphIoType(EWellKnownInputType.BOOL)
        }
        return InputElementDescriptor.newBuilder()
            .setId(proxyId(e))
            .setDisplayName(e.name)
            .setCategory(
                ParameterCategory.newBuilder()
                    .setId("DMXC3Remote.${s.runtimeId}")
                    .setName("Android: ${s.clientName}")
                    .setSubCategory(ParameterCategory.newBuilder().setId("DMXC3Remote.${s.runtimeId}.${d.id}").setName(d.name))
            )
            .setIoInfo(io)
            .build()
    }

    private fun feedbackType(e: SdElement) = when (e.type) {
        SdType.FADER -> EFeedbackType.COUPLED
        SdType.BUTTON, SdType.TOGGLE -> EFeedbackType.INDEPENDENT
        else -> EFeedbackType.NONE
    }

    private suspend fun register(d: Softdesk, e: SdElement, type: EChangeType) {
        val desc = descriptor(d, e)
        if (e.type.hasSource) {
            stub.dl(5).createOrUpdateOrDeleteSourceProxy(
                InputSourceProxyRequest.newBuilder()
                    .setRequestId(s.nextRequestId())
                    .setInputSourceId(desc.id)
                    .setChangeType(type)
                    .setSource(InputSourceDescriptor.newBuilder().setElement(desc).setReceivesFeedback(feedbackType(e)))
                    .setUserContextId(s.userContextId)
                    .setRuntimeId(s.runtimeId)
                    .build(),
                s.headers
            ).requireOk("SourceProxy ${e.name}")
        }
        if (e.type.hasSink) {
            stub.dl(5).createOrUpdateOrDeleteSinkProxy(
                InputSinkProxyRequest.newBuilder()
                    .setRequestId(s.nextRequestId())
                    .setInputSinkId(desc.id)
                    .setChangeType(type)
                    .setSink(InputSinkDescriptor.newBuilder().setElement(desc).setProvidesFeedback(feedbackType(e)))
                    .setUserContextId(s.userContextId)
                    .setRuntimeId(s.runtimeId)
                    .build(),
                s.headers
            ).requireOk("SinkProxy ${e.name}")
        }
    }

    private suspend fun unregister(d: Softdesk, e: SdElement) = runCatching { register(d, e, EChangeType.Removed) }

    /** Abgleich lokaler Softdesks mit den registrierten Proxies. */
    private suspend fun sync(desks: List<Softdesk>) {
        val wanted = desks.flatMap { d -> d.elements.map { e -> proxyId(e) to (d to e) } }.toMap()
        // entfernte oder im Typ geänderte Elemente abmelden
        for ((pid, old) in active.toMap()) {
            val now = wanted[pid]
            if (now == null || now.second.type != old.second.type) {
                unregister(old.first, old.second)
                active.remove(pid)
            }
        }
        for ((pid, de) in wanted) {
            val old = active[pid]
            when {
                old == null -> {
                    try {
                        register(de.first, de.second, EChangeType.Added)
                    } catch (e: UmbraException) {
                        // existiert evtl. noch aus vorheriger Sitzung -> aktualisieren
                        register(de.first, de.second, EChangeType.Changed)
                    }
                    active[pid] = de
                }
                old != de -> {
                    register(de.first, de.second, EChangeType.Changed)
                    active[pid] = de
                }
            }
        }
        _registered.value = active.size
    }

    override suspend fun load() {
        active.clear()
        sync(store.desks.value)
    }

    override suspend fun stream() = coroutineScope {
        launch { store.desks.collect { sync(it) } }
        stub.receiveSinkProxyChanges(s.getRequest(), s.headers).collect { m ->
            val upd = m.changedValuesList
                .filter { it.inputSinkId.startsWith("DMXC3Remote.${s.runtimeId}.") }
                .associate { elementIdOf(it.inputSinkId) to it.newValue }
            if (upd.isNotEmpty()) _feedback.update { it + upd }
        }
    }

    private suspend fun sendValue(e: SdElement, v: ObjectData) {
        stub.dl(3).createOrUpdateOrDeleteSourceProxy(
            InputSourceProxyRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setInputSourceId(proxyId(e))
                .setChangeType(EChangeType.Changed)
                .setSourceValue(v)
                .setUserContextId(s.userContextId)
                .setRuntimeId(s.runtimeId)
                .build(),
            s.headers
        ).requireOk("Softdesk ${e.name}")
    }

    fun press(e: SdElement, on: Boolean) = act("Softdesk") {
        sendValue(e, ObjectData.newBuilder().setBoolValue(on).build())
    }

    fun fader(e: SdElement, v: Double) = s.throttler.submit("sd:${e.id}") {
        sendValue(e, ObjectData.newBuilder().setDoubleValue(v.coerceIn(0.0, 1.0)).build())
    }
}

fun ObjectData.asBool(): Boolean = when (valueCase) {
    ObjectData.ValueCase.BOOLVALUE -> boolValue
    ObjectData.ValueCase.DOUBLEVALUE -> doubleValue > 0.5
    ObjectData.ValueCase.FLOATVALUE -> floatValue > 0.5f
    ObjectData.ValueCase.INTVALUE -> intValue != 0
    ObjectData.ValueCase.STRINGVALUE -> stringValue.equals("true", true) || stringValue == "1"
    else -> text().toDoubleOrNull()?.let { it > 0.5 } ?: false
}

fun ObjectData.asDouble(): Double? = when (valueCase) {
    ObjectData.ValueCase.DOUBLEVALUE -> doubleValue
    ObjectData.ValueCase.FLOATVALUE -> floatValue.toDouble()
    ObjectData.ValueCase.BOOLVALUE -> if (boolValue) 1.0 else 0.0
    else -> text().toDoubleOrNull()
}
