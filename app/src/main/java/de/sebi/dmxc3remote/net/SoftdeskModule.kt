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
 * Jedes Element wird als Eingang und/oder Ausgang im Baum der DMXC3-Eingangszuweisung
 * registriert und dort wie ein Hardware-Controller verdrahtet.
 *
 * UNVERIFIZIERT (keine öffentliche Referenzimplementierung):
 *  - Registrierung per Added, Werte per Changed + sourceValue
 *  - additionalSupportedIoTypes erweitert die verbindbaren Typen in der Eingangszuweisung
 */
class SoftdeskModule(s: UmbraSession, private val store: SoftdeskStore) : Module(s, "Softdesk") {
    private val stub = InputClientCoroutineStub(s.channel)

    private val _feedback = MutableStateFlow<Map<String, ObjectData>>(emptyMap())
    val feedback: StateFlow<Map<String, ObjectData>> = _feedback.asStateFlow()

    private val _registered = MutableStateFlow(0)
    val registered: StateFlow<Int> = _registered.asStateFlow()

    private val _serverCounts = MutableStateFlow<Pair<Int, Int>?>(null)
    val serverCounts: StateFlow<Pair<Int, Int>?> = _serverCounts.asStateFlow()

    /** Rohdaten der Rückkanäle (ungefiltert), für die Diagnose im Softdesk. */
    private val _diag = MutableStateFlow<List<String>>(emptyList())
    val diag: StateFlow<List<String>> = _diag.asStateFlow()
    private val _diagCounts = MutableStateFlow(DiagCounts())
    val diagCounts: StateFlow<DiagCounts> = _diagCounts.asStateFlow()

    data class DiagCounts(val proxyMsgs: Int = 0, val sinkMsgs: Int = 0, val matched: Int = 0)

    private fun log(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
        _diag.update { (listOf("$t  $line") + it).take(60) }
    }

    private val prefix get() = "DMXC3Remote.${s.runtimeId}."
    fun proxyId(e: SdElement) = "$prefix${e.id}"
    fun sinkId(e: SdElement) = "$prefix${e.id}.out"
    /**
     * Zuordnung einer empfangenen Sink-ID zum Element. Tolerant, weil das ID-Format im
     * Rückkanal nicht dokumentiert ist: exakte Proxy-ID, mit/ohne ".out", oder nur die Element-ID.
     */
    private fun elementIdOf(id: String): String? {
        val known = active.values.map { it.second.second.id }.toSet()
        val cand = id.removePrefix(prefix).removeSuffix(".out")
        if (cand in known) return cand
        return known.firstOrNull { id.endsWith(it) || id.endsWith("$it.out") || id.contains(it) }
    }

    /** Was die Anmeldung beeinflusst. Position/Farbe ändern nichts am Proxy. */
    private data class Sig(
        val deskId: String, val deskName: String, val name: String, val type: SdType,
        val sendIo: SdIo, val fbIo: SdIo, val min: Double, val max: Double
    ) {
        /** Typänderung erfordert Ab- und Neuanmeldung. */
        fun hard(o: Sig) = type != o.type || sendIo != o.sendIo || fbIo != o.fbIo
    }

    private fun sig(d: Softdesk, e: SdElement) = Sig(d.id, d.name, e.name, e.type, sourceIo(e), e.fbIo, e.min, e.max)

    private val active = HashMap<String, Pair<Sig, Pair<Softdesk, SdElement>>>()

    private fun sourceIo(e: SdElement) = if (e.type == SdType.FADER) SdIo.NUMERIC else e.sendIo

    private fun SdIo.wk() = when (this) {
        SdIo.BOOL -> EWellKnownInputType.BOOL
        SdIo.NUMERIC -> EWellKnownInputType.NUMERIC
        SdIo.STRING -> EWellKnownInputType.STRING
    }

    private fun descriptor(d: Softdesk, e: SdElement, id: String, io: SdIo): InputElementDescriptor {
        val info = IoInformationDescriptor.newBuilder()
            .setAutoGraphIoType(io.wk())
            .addAllAdditionalSupportedIoTypes(SdIo.entries.filter { it != io }.map { it.wk() })
        if (io == SdIo.NUMERIC) {
            info.setMin(ObjectData.newBuilder().setDoubleValue(e.min)).setMax(ObjectData.newBuilder().setDoubleValue(e.max))
        }
        return InputElementDescriptor.newBuilder()
            .setId(id)
            .setDisplayName(e.name)
            .setCategory(
                ParameterCategory.newBuilder()
                    .setId("DMXC3Remote.${s.runtimeId}")
                    .setName("Android: ${s.clientName}")
                    .setSubCategory(ParameterCategory.newBuilder().setId("DMXC3Remote.${s.runtimeId}.${d.id}").setName(d.name))
            )
            .setIoInfo(info)
            .build()
    }

    private fun feedbackType(e: SdElement) = when (e.type) {
        SdType.FADER -> EFeedbackType.COUPLED
        SdType.BUTTON -> EFeedbackType.INDEPENDENT
        else -> EFeedbackType.NONE
    }

    private suspend fun sourceCall(d: Softdesk, e: SdElement, type: EChangeType) {
        stub.dl(5).createOrUpdateOrDeleteSourceProxy(
            InputSourceProxyRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setInputSourceId(proxyId(e))
                .setChangeType(type)
                .setSource(
                    InputSourceDescriptor.newBuilder()
                        .setElement(descriptor(d, e, proxyId(e), sourceIo(e)))
                        .setReceivesFeedback(feedbackType(e))
                )
                .setUserContextId(s.userContextId)
                .setRuntimeId(s.runtimeId)
                .build(),
            s.headers
        ).requireOk("Eingang ${e.name} ($type)")
    }

    private suspend fun sinkCall(d: Softdesk, e: SdElement, type: EChangeType) {
        stub.dl(5).createOrUpdateOrDeleteSinkProxy(
            InputSinkProxyRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setInputSinkId(sinkId(e))
                .setChangeType(type)
                .setSink(
                    InputSinkDescriptor.newBuilder()
                        .setElement(descriptor(d, e, sinkId(e), e.fbIo))
                        .setProvidesFeedback(feedbackType(e))
                )
                .setUserContextId(s.userContextId)
                .setRuntimeId(s.runtimeId)
                .build(),
            s.headers
        ).requireOk("Ausgang ${e.name} ($type)")
    }

    private suspend fun upsert(name: String, call: suspend (EChangeType) -> Unit, isNew: Boolean) {
        if (!isNew) {
            call(EChangeType.Changed)
            return
        }
        try {
            call(EChangeType.Added)
        } catch (first: UmbraException) {
            try {
                call(EChangeType.Changed)
            } catch (second: UmbraException) {
                s.emit("$name: ${first.message} / ${second.message}", isError = true)
                throw second
            }
        }
    }

    private suspend fun register(d: Softdesk, e: SdElement, isNew: Boolean) {
        var failed: Throwable? = null
        if (e.type.hasSource) {
            runCatching { upsert("Eingang ${e.name}", { t -> sourceCall(d, e, t) }, isNew) }.onFailure { failed = it }
        }
        if (e.type.hasSink) {
            runCatching { upsert("Ausgang ${e.name}", { t -> sinkCall(d, e, t) }, isNew) }.onFailure { failed = it }
        }
        failed?.let { throw it }
    }

    private suspend fun unregister(d: Softdesk, e: SdElement) {
        if (e.type.hasSource) runCatching { sourceCall(d, e, EChangeType.Removed) }
        if (e.type.hasSink) runCatching { sinkCall(d, e, EChangeType.Removed) }
    }

    private suspend fun verify() {
        val src = stub.dl(10).getInputSources(s.getMultiple(), s.headers).sourcesList.count { it.element.id.startsWith(prefix) }
        val snk = stub.dl(10).getInputSinks(s.getMultiple(), s.headers).sinksList.count { it.element.id.startsWith(prefix) }
        _serverCounts.value = src to snk
    }

    private suspend fun sync(desks: List<Softdesk>) {
        val wanted = desks.flatMap { d ->
            d.elements.filter { it.type.hasSource || it.type.hasSink }.map { e -> proxyId(e) to (d to e) }
        }.toMap()
        for ((pid, old) in active.toMap()) {
            val now = wanted[pid]
            if (now == null || sig(now.first, now.second).hard(old.first)) {
                unregister(old.second.first, old.second.second)
                active.remove(pid)
            }
        }
        var errors = 0
        var changed = false
        for ((pid, de) in wanted) {
            val newSig = sig(de.first, de.second)
            val old = active[pid]
            if (old != null && old.first == newSig) {
                active[pid] = newSig to de
                continue
            }
            try {
                register(de.first, de.second, isNew = old == null)
                active[pid] = newSig to de
                changed = true
            } catch (e: UmbraException) {
                errors++
            }
        }
        if (errors > 0) s.emit("Softdesk: $errors Element(e) nicht angemeldet", isError = true)
        _registered.value = active.size
        if (changed || errors > 0 || _serverCounts.value == null) runCatching { verify() }.onFailure { _serverCounts.value = null }
    }

    override suspend fun load() {
        active.clear()
        sync(store.desks.value)
    }

    private fun applyFeedback(source: String, id: String, v: ObjectData) {
        val eid = elementIdOf(id)
        log("$source  $id = ${v.text().ifBlank { v.valueCase.name }}" + if (eid == null) "  (nicht zugeordnet)" else "")
        if (eid != null) {
            _feedback.update { it + (eid to v) }
            _diagCounts.update { it.copy(matched = it.matched + 1) }
        }
    }

    override suspend fun stream() = coroutineScope {
        launch { store.desks.collect { sync(it) } }
        // Kanal 1: Änderungen an Sinks (enthält newValue)
        launch {
            try {
                log("Sink-Änderungen: Stream geöffnet")
                stub.receiveInputSinkChanges(s.getRequest(), s.headers).collect { m ->
                    _diagCounts.update { it.copy(sinkMsgs = it.sinkMsgs + 1) }
                    if (m.hasNewValue()) applyFeedback("Sink", m.inputSinkId, m.newValue)
                }
                log("Sink-Änderungen: Stream beendet")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Sink-Änderungen: ${e.describe()}")
            }
        }
        // Kanal 2: Proxy-Werte. UNVERIFIZIERT: requestId = runtimeId, falls der Server darüber zuordnet.
        log("Proxy-Werte: Stream geöffnet")
        val req = LumosProtobuf.CommonTypes.GetRequest.newBuilder()
            .setRequestId(s.runtimeId)
            .setUserContextId(s.userContextId)
            .build()
        try {
            stub.receiveSinkProxyChanges(req, s.headers).collect { m ->
                _diagCounts.update { it.copy(proxyMsgs = it.proxyMsgs + 1) }
                m.changedValuesList.forEach { applyFeedback("Proxy", it.inputSinkId, it.newValue) }
            }
            log("Proxy-Werte: Stream beendet")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Proxy-Werte: ${e.describe()}")
            throw e
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

    /** Taste: sendet je nach Werttyp Bool, Zahl oder Text (konfigurierbare An/Aus-Werte). */
    fun button(e: SdElement, on: Boolean) = act("Softdesk") {
        val raw = if (on) e.onValue else e.offValue
        val v = when (e.sendIo) {
            SdIo.BOOL -> ObjectData.newBuilder().setBoolValue(on)
            SdIo.NUMERIC -> ObjectData.newBuilder().setDoubleValue(raw.replace(',', '.').toDoubleOrNull() ?: if (on) 1.0 else 0.0)
            SdIo.STRING -> ObjectData.newBuilder().setStringValue(raw)
        }.build()
        sendValue(e, v)
    }

    /** Fader: Position 0..1 wird auf min..max des Elements abgebildet. */
    fun fader(e: SdElement, pos: Double) = s.throttler.submit("sd:${e.id}") {
        val p = pos.coerceIn(0.0, 1.0)
        sendValue(e, ObjectData.newBuilder().setDoubleValue(e.min + p * (e.max - e.min)).build())
    }
}

// ------------------------------------------------------------------ Auswertung der Rückmeldung

fun ObjectData.asDouble(): Double? = when (valueCase) {
    ObjectData.ValueCase.DOUBLEVALUE -> doubleValue
    ObjectData.ValueCase.FLOATVALUE -> floatValue.toDouble()
    ObjectData.ValueCase.INTVALUE -> intValue.toDouble()
    ObjectData.ValueCase.LONGVALUE -> longValue.toDouble()
    ObjectData.ValueCase.UINTVALUE -> uintValue.toDouble()
    ObjectData.ValueCase.ULONGVALUE -> ulongValue.toDouble()
    ObjectData.ValueCase.SHORTVALUE -> shortValue.toDouble()
    ObjectData.ValueCase.SBYTEVALUE -> sbyteValue.toDouble()
    ObjectData.ValueCase.USHORTVALUE -> ushortValue.toDouble()
    ObjectData.ValueCase.BYTEVALUE -> byteValue.toDouble()
    ObjectData.ValueCase.BOOLVALUE -> if (boolValue) 1.0 else 0.0
    else -> text().trim().replace(',', '.').toDoubleOrNull()
}

/**
 * "An" nach Regeln: Bool direkt; Text, wenn er in der Liste steht (ohne Groß/Klein);
 * sonst Zahl über dem Schwellwert. Eigene Werte am Element ersetzen die globale Liste.
 */
fun ObjectData.isOn(rules: FeedbackRules, elementOn: String): Boolean {
    if (valueCase == ObjectData.ValueCase.BOOLVALUE) return boolValue
    val words = elementOn.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .ifEmpty { rules.onWords.map { it.trim().lowercase() } }
    val t = text().trim().lowercase()
    if (t in words) return true
    val num = asDouble() ?: return false
    // explizit aufgeführte Zahlen gelten exakt, sonst Schwellwert
    if (words.any { it.replace(',', '.').toDoubleOrNull() == num }) return true
    return num > rules.threshold
}
