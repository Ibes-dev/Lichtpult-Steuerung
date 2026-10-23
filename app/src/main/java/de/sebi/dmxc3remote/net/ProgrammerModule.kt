package de.sebi.dmxc3remote.net

import LumosProtobuf.Cue.FannedValueServiceTypes.EFannedValueType
import LumosProtobuf.Cue.FannedValueServiceTypes.FannedValuesRequest
import LumosProtobuf.Cuelist.CuelistServiceTypes.CueAddFromProgrammerRequest
import LumosProtobuf.DependencyTypes.DependencyData
import LumosProtobuf.DependencyTypes.DependencySolution
import LumosProtobuf.DeviceServiceTypes.DevicePropertyDescriptor
import LumosProtobuf.DeviceServiceTypes.EGroupHandling
import LumosProtobuf.DeviceServiceTypes.FannedPropertyValue
import LumosProtobuf.ParameterServiceTypes.GenericParameterData
import LumosProtobuf.ParameterServiceTypes.GetParametersRequest
import LumosProtobuf.ParameterServiceTypes.SetTestParameterValueRequest
import LumosProtobuf.PresetServiceCRUDTypes.PresetCreationRequest
import LumosProtobuf.PresetServiceCRUDTypes.PresetMergeAppendRequest
import LumosProtobuf.PresetServiceCRUDTypes.PresetReplaceSubtractRequest
import LumosProtobuf.PresetServiceCRUDTypes.PresetReplaceSubtractResponse
import LumosProtobuf.PresetServiceTypes.EditPresetInProgrammerRequest
import LumosProtobuf.ProgrammerServiceTypes.CueStateRequest
import LumosProtobuf.ProgrammerServiceTypes.DeleteProgrammerValueRequest
import LumosProtobuf.ProgrammerServiceTypes.EOutputMode
import LumosProtobuf.ProgrammerServiceTypes.EViewMode
import LumosProtobuf.ProgrammerServiceTypes.EditCueInProgrammerRequest
import LumosProtobuf.ProgrammerServiceTypes.GroupState
import LumosProtobuf.ProgrammerServiceTypes.ProgrammerChangeMessage
import LumosProtobuf.ProgrammerServiceTypes.ProgrammerFilterPredicate
import LumosProtobuf.ProgrammerServiceTypes.SetProgrammerOutputRequest
import LumosProtobuf.ProgrammerServiceTypes.SetProgrammerValueRequest
import LumosProtobuf.ProgrammerServiceTypes.UpdateOriginOfProgrammerRequest
import LumosProtobuf.ProgrammerServiceTypes.UpdateOriginOfProgrammerResponse
import LumosProtobufClient.CuelistClientGrpcKt.CuelistClientCoroutineStub
import LumosProtobufClient.FannedValueClientGrpcKt.FannedValueClientCoroutineStub
import LumosProtobufClient.ParameterClientGrpcKt.ParameterClientCoroutineStub
import LumosProtobufClient.PresetClientGrpcKt.PresetClientCoroutineStub
import LumosProtobufClient.ProgrammerClientGrpcKt.ProgrammerClientCoroutineStub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Woher der Programmer-Inhalt stammt (nur App-seitig gemerkt, für die Anzeige). */
sealed interface ProgOrigin {
    val label: String
    data class Cue(val cuelistId: String, val cueId: String, override val label: String) : ProgOrigin
    data class Preset(val presetId: String, override val label: String) : ProgOrigin
}

/**
 * Offene Rückfrage von DMXControl beim Speichern (Abhängigkeiten, z. B. "Preset wird in Cues verwendet").
 * [solutions]: je Abhängigkeit die gewählte Lösung; [retry] speichert mit diesen Lösungen erneut.
 */
data class PendingDependencies(val title: String, val data: DependencyData, val retry: suspend (DependencyData) -> Unit)

/** Wie mehrere Gruppen beim Fächern behandelt werden (EGroupHandling). */
enum class GroupMode(val label: String, val proto: EGroupHandling) {
    CONCAT("Gruppen hintereinander", EGroupHandling.CONCAT_GROUPS),
    AS_DEVICE("Gruppe wie ein Gerät", EGroupHandling.GROUP_IS_DEVICE),
    PARALLEL("Gruppen parallel", EGroupHandling.PARALLEL_GROUPS)
}

/**
 * Programmer-Arbeitsbereich: Inhalt (GetProgrammerState), Herkunft, Live/Blind, Cues und Presets
 * laden und zurückspeichern, Fächern mit Operator, Effektparameter.
 */
class ProgrammerModule(s: UmbraSession) : Module(s, "Programmer") {
    private val prog = ProgrammerClientCoroutineStub(s.channel)
    private val presetStub = PresetClientCoroutineStub(s.channel)
    private val cuelistStub = CuelistClientCoroutineStub(s.channel)
    private val fanStub = FannedValueClientCoroutineStub(s.channel)
    private val parStub = ParameterClientCoroutineStub(s.channel)

    private val _content = MutableStateFlow<List<GroupState>>(emptyList())
    /** Alles, was gerade im Programmer steht (eine Zeile je Gruppe und Eigenschaft). */
    val content: StateFlow<List<GroupState>> = _content.asStateFlow()

    private val _origin = MutableStateFlow<ProgOrigin?>(null)
    val origin: StateFlow<ProgOrigin?> = _origin.asStateFlow()

    private val _blind = MutableStateFlow(false)
    /** true = Programmer wirkt nicht auf die Ausgabe (EOutputMode.HIDDEN). */
    val blind: StateFlow<Boolean> = _blind.asStateFlow()

    private val _operators = MutableStateFlow<List<String>>(emptyList())
    /** Fächer-Operatoren laut DMXControl (GetFannedValues OPERATOR), z. B. linear, Sinus … */
    val operators: StateFlow<List<String>> = _operators.asStateFlow()

    private val _pending = MutableStateFlow<PendingDependencies?>(null)
    val pending: StateFlow<PendingDependencies?> = _pending.asStateFlow()

    /** Effekt-Parameter je Effekt-Instanz im Programmer. */
    private val _effectParams = MutableStateFlow<Map<String, List<DevParam>>>(emptyMap())
    val effectParams: StateFlow<Map<String, List<DevParam>>> = _effectParams.asStateFlow()

    private fun all() = ProgrammerFilterPredicate.newBuilder().setUserContextId(s.userContextId).build()

    override suspend fun load() {
        refresh()
        _operators.value = runCatching {
            fanStub.dl(5).getFannedValues(
                FannedValuesRequest.newBuilder().setRequestId(s.nextRequestId()).setType(EFannedValueType.OPERATOR).build(),
                s.headers
            ).resultsList
        }.getOrDefault(emptyList())
    }

    suspend fun refresh() {
        val r = prog.dl(5).getProgrammerState(
            CueStateRequest.newBuilder().setRequestId(s.nextRequestId()).setUserContextId(s.userContextId)
                .setViewMode(EViewMode.VALUE).build(),
            s.headers
        )
        _content.value = r.groupStatesList
        if (r.groupStatesCount == 0) _origin.value = null
    }

    /** Zähler für "Inhalt neu lesen"; wird von Änderungsmeldungen und eigenen Befehlen erhöht. */
    private val dirty = MutableStateFlow(0)

    /** Nach eigenen Programmer-Befehlen aufrufen, damit "Inhalt" sofort stimmt. */
    fun touch() = dirty.update { it + 1 }

    /** Programmer-Änderungen (auch vom PC) -> Inhalt neu lesen, gebündelt alle 300 ms. */
    override suspend fun stream() = kotlinx.coroutines.coroutineScope {
        launch {
            var seen = 0
            while (true) {
                delay(300)
                val d = dirty.value
                if (d != seen) { seen = d; runCatching { refresh() }; runCatching { s.devices.syncEffects() } }
            }
        }
        // Sicherheitsnetz: alle 3 s nachlesen, falls eine Änderung keine Meldung erzeugt
        launch {
            while (true) { delay(3_000); dirty.update { it + 1 } }
        }
        prog.receiveProgrammerChanges(s.getRequest(), s.headers).collect { m ->
            if (m.type == ProgrammerChangeMessage.EChangeType.OutputMode &&
                (m.userContextId.isBlank() || m.userContextId == s.userContextId)
            ) _blind.value = m.mode == EOutputMode.HIDDEN
            dirty.update { it + 1 }
        }
    }

    // ---------------------------------------------------------------- Ausgabe
    fun setBlind(on: Boolean) = act("Ausgabe") {
        prog.dl(3).setProgrammerOutput(
            SetProgrammerOutputRequest.newBuilder().setRequestId(s.nextRequestId()).setUserContextId(s.userContextId)
                .setMode(if (on) EOutputMode.HIDDEN else EOutputMode.ALL).build(),
            s.headers
        ).requireOk("Ausgabe")
        _blind.value = on
    }

    // ---------------------------------------------------------------- Laden
    fun loadCue(cuelistId: String, cueId: String, label: String, blind: Boolean) = act("Cue laden") {
        prog.dl(10).editCueInProgrammer(
            EditCueInProgrammerRequest.newBuilder().setRequestId(s.nextRequestId()).setUserContextId(s.userContextId)
                .setCuelistId(cuelistId).setCueId(cueId).setBlind(blind).build(),
            s.headers
        ).requireOk("Cue laden")
        _origin.value = ProgOrigin.Cue(cuelistId, cueId, label)
        refresh()
        s.emit("$label im Programmer")
    }

    fun loadPreset(presetId: String, label: String, blind: Boolean) = act("Preset laden") {
        presetStub.dl(10).editPresetInProgrammer(
            EditPresetInProgrammerRequest.newBuilder().setRequestId(s.nextRequestId()).setUserContextId(s.userContextId)
                .setPresetId(presetId).setBlind(blind).build(),
            s.headers
        ).requireOk("Preset laden")
        _origin.value = ProgOrigin.Preset(presetId, label)
        refresh()
        s.emit("Preset $label im Programmer")
    }

    // ---------------------------------------------------------------- Speichern
    /** Zurück in den geladenen Cue bzw. das geladene Preset (UpdateOriginOfProgrammer). */
    fun storeToOrigin(clearAfter: Boolean) = act("Speichern") {
        suspend fun attempt(deps: DependencyData?) {
            val b = UpdateOriginOfProgrammerRequest.newBuilder().setRequestId(s.nextRequestId())
                .setUserContextId(s.userContextId).setPredicate(all()).setClearProgrammer(clearAfter)
            if (deps != null) b.setDependencies(deps)
            val r = prog.dl(15).updateOriginOfProgrammer(b.build(), s.headers)
            when (r.responseCase) {
                UpdateOriginOfProgrammerResponse.ResponseCase.OK -> {
                    s.emit("Gespeichert in ${_origin.value?.label ?: "Ursprung"}")
                    if (clearAfter) _origin.value = null
                }
                UpdateOriginOfProgrammerResponse.ResponseCase.DEPENDENCIES ->
                    _pending.value = PendingDependencies("Speichern in ${_origin.value?.label ?: "Ursprung"}", r.dependencies) { attempt(it) }
                else -> throw UmbraException(r.error.ifBlank { "DMXControl hat das Speichern abgelehnt" })
            }
        }
        attempt(null)
    }

    /** Neuer Cue (ADD) bzw. Cue ersetzen/zusammenführen (REPLACE/MERGE an index). */
    fun storeToCue(cuelistId: String, type: CueAddFromProgrammerRequest.EAddType, index: Int, label: String) = act("Cue speichern") {
        cuelistStub.dl(10).addProgrammerCue(
            CueAddFromProgrammerRequest.newBuilder().setRequestId(s.nextRequestId()).setCuelistId(cuelistId)
                .setUserContextId(s.userContextId).setPredicate(all()).setType(type).setIndex(index).build(),
            s.headers
        ).requireOk("Cue speichern")
        s.emit(label)
    }

    fun presetMerge(presetId: String, append: Boolean, name: String) = act("Preset") {
        presetStub.dl(10).mergeAppendProgrammer(
            PresetMergeAppendRequest.newBuilder().setRequestId(s.nextRequestId()).setUserContextId(s.userContextId)
                .setPresetId(presetId).setAppend(append).setPredicate(all()).build(),
            s.headers
        ).requireOk("Preset")
        s.emit(if (append) "An $name angehängt" else "In $name zusammengeführt")
    }

    fun presetReplace(presetId: String, subtract: Boolean, name: String) = act("Preset") {
        suspend fun attempt(deps: DependencyData?) {
            val b = PresetReplaceSubtractRequest.newBuilder().setRequestId(s.nextRequestId()).setUserContextId(s.userContextId)
                .setPresetId(presetId).setSubtract(subtract).setPredicate(all())
            if (deps != null) b.setDependencies(deps)
            val r = presetStub.dl(15).replaceSubtractProgrammer(b.build(), s.headers)
            when (r.responseCase) {
                PresetReplaceSubtractResponse.ResponseCase.PROCESSEDPRESETID -> s.emit(if (subtract) "Aus $name entfernt" else "$name ersetzt")
                PresetReplaceSubtractResponse.ResponseCase.DEPENDENCIES ->
                    _pending.value = PendingDependencies("$name ${if (subtract) "abziehen" else "ersetzen"}", r.dependencies) { attempt(it) }
                else -> throw UmbraException(r.error.ifBlank { "DMXControl hat das Preset nicht geändert" })
            }
        }
        attempt(null)
    }

    fun createPreset(name: String, propertyPreset: Boolean) = act("Preset anlegen") {
        presetStub.dl(10).createPreset(
            PresetCreationRequest.newBuilder().setRequestId(s.nextRequestId()).setUserContextId(s.userContextId)
                .setPredicate(all()).setPropertyPreset(propertyPreset).setPresetNameTemplate(name).build(),
            s.headers
        ).requireOk("Preset anlegen")
        s.emit("Preset \"$name\" angelegt")
    }

    /** Rückfrage beantworten: gewählte Lösungen eintragen und erneut speichern. */
    fun resolvePending(choice: Map<String, DependencySolution>) {
        val p = _pending.value ?: return
        _pending.value = null
        act("Speichern") { p.retry(applySolutions(p.data, choice)) }
    }

    fun cancelPending() { _pending.value = null }

    private fun applySolutions(d: DependencyData, choice: Map<String, DependencySolution>): DependencyData =
        d.toBuilder()
            .clearDependencies()
            .addAllDependencies(d.dependenciesList.map { o ->
                val sol = choice[o.id] ?: o.solution.takeIf { o.hasSolution() } ?: o.possibleSolutionsList.firstOrNull()
                if (sol != null) o.toBuilder().setSolution(sol).build() else o
            })
            .clearChildren()
            .addAllChildren(d.childrenList.map { applySolutions(it, choice) })
            .build()

    // ---------------------------------------------------------------- Bearbeiten
    fun undo() = act("Rückgängig") { delete(DeleteProgrammerValueRequest.EClearMode.UNDO) }
    fun clear() = act("Programmer leeren") {
        // Über die Schnittstelle angelegte Effekte entfernt CLEAR nicht zuverlässig (bleiben als "Geist"
        // in DMXControl stehen) – deshalb vorher einzeln löschen.
        s.devices.deleteAllEffects()
        delete(DeleteProgrammerValueRequest.EClearMode.CLEAR)
        _origin.value = null
        _effectParams.value = emptyMap()
    }

    /** Eine Eigenschaft einer Gruppe aus dem Programmer nehmen. */
    fun remove(groupId: String, propertyId: String) = act("Entfernen") {
        s.devices.deleteEffects(groupId, propertyId)
        delete(DeleteProgrammerValueRequest.EClearMode.DELETE) { it.setGroupId(groupId).addPropertyIds(propertyId) }
    }

    private suspend fun delete(
        mode: DeleteProgrammerValueRequest.EClearMode,
        f: (DeleteProgrammerValueRequest.Builder) -> DeleteProgrammerValueRequest.Builder = { it }
    ) {
        prog.dl(5).deleteProgrammerValue(
            f(DeleteProgrammerValueRequest.newBuilder().setRequestId(s.nextRequestId()).setUserContextId(s.userContextId).setMode(mode)).build(),
            s.headers
        ).requireOk("Programmer")
        refresh()
    }

    // ---------------------------------------------------------------- Fächern
    /** Fächer mit Operator und Start-/Endwert; Werte im Bereich der Eigenschaft. */
    fun setFan(groupId: String, p: DevicePropertyDescriptor, operator: String, from: Double, to: Double) =
        s.throttler.submit("fan:$groupId:${p.id}") {
            prog.dl(3).setProgrammerValue(
                SetProgrammerValueRequest.newBuilder().setGroupId(groupId).setPropertyId(p.id).setUserContextId(s.userContextId)
                    .setFpv(
                        FannedPropertyValue.newBuilder().setOperator(operator).setPropertyTypeAQ(p.devicePropertyTypeAQ)
                            .addFannedValues(LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.newBuilder()
                                .setDeviceId(groupId).setPropertyId(p.id).setDoubleValue(from))
                            .addFannedValues(LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.newBuilder()
                                .setDeviceId(groupId).setPropertyId(p.id).setDoubleValue(to))
                    ).build(),
                s.headers
            )
            touch()
        }

    fun setGroupMode(groupId: String, propertyId: String, mode: GroupMode) = act("Gruppenverhalten") {
        prog.dl(3).setProgrammerValue(
            SetProgrammerValueRequest.newBuilder().setGroupId(groupId).setPropertyId(propertyId).setUserContextId(s.userContextId)
                .setGroupHandling(mode.proto).build(),
            s.headers
        )
    }

    /** Fächer-Gruppengröße: n Geräte bekommen jeweils denselben Wert (1 = jedes Gerät eigener Wert). */
    fun setFanGroupSize(groupId: String, propertyId: String, size: Int) = act("Gruppengröße") {
        prog.dl(3).setProgrammerValue(
            SetProgrammerValueRequest.newBuilder().setGroupId(groupId).setPropertyId(propertyId).setUserContextId(s.userContextId)
                .setFanningGroupSize(size.coerceAtLeast(1)).build(),
            s.headers
        )
    }

    // ---------------------------------------------------------------- Effektparameter
    /** Parameter einer Effekt-Instanz im Programmer (ParameterClient, type "Attachable", mit UserContext). */
    suspend fun readEffectParams(attachableId: String) {
        val r = parStub.dl(10).getParameters(
            GetParametersRequest.newBuilder().setType("Attachable").setId(attachableId).setIncludingValues(true)
                .setUserContextId(s.userContextId).build(),
            s.headers
        )
        _effectParams.update { it + (attachableId to r.parametersList.map { p -> DevParam(p, r.parameterValuesMap[p.name]) }) }
        if (loggedEffects.add(attachableId)) {
            Diag.note("Effektparameter: " + r.parametersList.joinToString("; ") { p ->
                val v = r.parameterValuesMap[p.name]
                "${p.name} [${p.parameterValueTypeAQ.substringBefore(',')}] ${v?.valueCase}=\"${v?.text().orEmpty()}\""
            })
        }
    }

    private val loggedEffects = java.util.Collections.synchronizedSet(HashSet<String>())

    fun loadEffectParams(attachableId: String) = act("Effektparameter") { readEffectParams(attachableId) }

    // ================================================================ Effektparameter schreiben
    /**
     * Wie ein Zahlenwert bzw. ein Fächer für einen Parametertyp gesendet werden muss, wird einmal
     * je Typ (parameterValueType_AQ) ermittelt: senden, zurücklesen, prüfen. Danach direkt.
     *  Zahl:   0 = doubleValue, 1 = Text ("90")
     *  Fächer: 0 = FannedPropertyValue (operator + parameterValues) im fallbackProtobuf,
     *          1 = Text ("180 > 0", Schreibweise der Device Control)
     */
    private val numberMode = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val fanMode = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun typeKey(desc: GenericParameterData) = desc.parameterValueTypeAQ.substringBefore(',').ifBlank { desc.name }

    private fun numText(d: Double) =
        java.math.BigDecimal(d).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * Wert im Format der Vorlage: Hat DMXControl den Parameter als FannedPropertyValue geliefert,
     * wird genau dieses Objekt mit neuen Stützwerten zurückgeschickt (Typangaben unverändert).
     */
    private fun mirrored(template: LumosProtobuf.CommonTypes.ObjectData?, op: String, values: List<Double>): LumosProtobuf.CommonTypes.ObjectData? {
        val t = template ?: return null
        val f = fpvOf(t) ?: return null
        // Belegt durch Diagnose: DMXControl (NumericFannedValue) liest die Stützwerte aus fannedValues
        // als Zahlen; Texte in parameterValues werden als 0 gelesen.
        val dpvs = values.map { LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.newBuilder().setDoubleValue(it).build() }
        val nf = f.toBuilder().clearParameterValues().clearFannedValues().addAllFannedValues(dpvs)
            .setOperator(if (values.size >= 2) op else f.operator).build()
        return t.toBuilder().setFallbackProtobuf(nf.toByteString()).build()
    }

    private fun numberValue(mode: Int, d: Double) = LumosProtobuf.CommonTypes.ObjectData.newBuilder().apply {
        if (mode == 0) setDoubleValue(d) else setStringValue(numText(d))
    }.build()

    private fun fanValue(mode: Int, desc: GenericParameterData, op: String, from: Double, to: Double): LumosProtobuf.CommonTypes.ObjectData =
        if (mode == 0) {
            val fpv = FannedPropertyValue.newBuilder().setOperator(op).setUiValueType(desc.valueType)
                .addParameterValues(numText(from)).addParameterValues(numText(to)).build()
            LumosProtobuf.CommonTypes.ObjectData.newBuilder()
                .setFallbackProtobuf(fpv.toByteString())
                .setProtobufTypeAQ("LumosProtobuf.FannedPropertyValue")
                .build()
        } else LumosProtobuf.CommonTypes.ObjectData.newBuilder().setStringValue("${numText(from)} $op ${numText(to)}").build()

    private suspend fun sendParam(attachableId: String, desc: GenericParameterData, v: LumosProtobuf.CommonTypes.ObjectData) {
        parStub.dl(5).setParameterValue(
            SetTestParameterValueRequest.newBuilder().setId(attachableId).setParameter(desc)
                .setUserContextId(s.userContextId).setValue(v).build(),
            s.headers
        ).requireOk(desc.displayName.ifBlank { desc.name })
    }

    private fun currentOf(attachableId: String, desc: GenericParameterData) =
        _effectParams.value[attachableId]?.firstOrNull { it.desc.name == desc.name }?.value

    /**
     * Zahlenwert setzen (Regler/Eingabe). Zuerst als Zahl; lehnt DMXControl das ab, als Text ("90").
     * Die angenommene Variante wird je Parametertyp gemerkt. Ein Fehler erscheint nur, wenn
     * DMXControl beide Varianten ablehnt – nicht, weil sich der Wert nicht zurücklesen lässt.
     */
    fun setEffectNumber(attachableId: String, desc: GenericParameterData, d: Double) =
        s.throttler.submit("eff:$attachableId:${desc.name}") {
            val template = currentOf(attachableId, desc)
            // 1) im Format von DMXControl (gespiegelte Vorlage)
            mirrored(template, "", listOf(d))?.let { v ->
                val r = runCatching { sendParam(attachableId, desc, v) }
                if (r.isSuccess) { storeLocal(attachableId, desc, v); return@submit }
                Diag.note("Effektparameter ${desc.name}: Vorlagenformat abgelehnt: ${r.exceptionOrNull()?.message}")
            }
            // 2) als Zahl bzw. Text
            val key = typeKey(desc)
            val modes = numberMode[key]?.let { listOf(it) } ?: listOf(0, 1)
            var last: Throwable? = null
            for (mode in modes) {
                val v = numberValue(mode, d)
                val r = runCatching { sendParam(attachableId, desc, v) }
                if (r.isSuccess) { numberMode[key] = mode; storeLocal(attachableId, desc, v); return@submit }
                last = r.exceptionOrNull()
            }
            Diag.note("Effektparameter ${desc.name}: Wert $d abgelehnt: ${last?.message}")
            s.emit("${desc.displayName.ifBlank { desc.name }}: ${last?.message ?: "abgelehnt"}", isError = true)
        }

    /** Gesendeten Wert als aktuellen Wert merken (für Vorlage und Anzeige). */
    private fun storeLocal(attachableId: String, desc: GenericParameterData, v: LumosProtobuf.CommonTypes.ObjectData) {
        _effectParams.update { m ->
            m + (attachableId to (m[attachableId] ?: emptyList()).map { if (it.desc.name == desc.name) it.copy(value = v) else it })
        }
    }

    /**
     * Effektparameter fächern, z. B. Phase von 180° bis 0° über die Geräte.
     * DMXControl prüft den Fächer vorab (IsValidParameteredFannedValue) und zeigt ihn in der
     * Device Control als "Start Operator Ende". Gesendet wird zuerst als FannedPropertyValue,
     * sonst als Text; Erfolg wird per Rücklesen geprüft und je Typ gemerkt.
     */
    fun setEffectParamFanned(attachableId: String, desc: GenericParameterData, from: Double, to: Double, op: String) = act("Effekt fächern") {
        val o = op.trim().ifBlank { ">" }
        // Vorlage nötig: der Parameter so, wie DMXControl ihn führt (NumericFannedValue)
        if (currentOf(attachableId, desc)?.let { fpvOf(it) } == null) runCatching { readEffectParams(attachableId) }
        val template = currentOf(attachableId, desc)
        val v = mirrored(template, o, listOf(from, to))
            ?: throw UmbraException("${desc.displayName.ifBlank { desc.name }} lässt sich nicht fächern")
        sendParam(attachableId, desc, v)
        runCatching { readEffectParams(attachableId) }
        val back = currentOf(attachableId, desc)
        val fan = back?.let { decodeFan(it) }
        Diag.note("Effekt fächern ${desc.name}: gesendet ${numText(from)} $o ${numText(to)}, gelesen \"${back?.let { paramText(it) }}\"")
        // DMXControl führt "a < b" als "b > a" – Reihenfolge der Werte daher egal
        val ok = fan != null && fan.from != null && fan.to != null &&
            setOf(Math.round(fan.from!! * 100), Math.round(fan.to!! * 100)) == setOf(Math.round(from * 100), Math.round(to * 100))
        if (!ok) throw UmbraException("DMXControl führt den Fächer anders: ${fan?.text ?: back?.let { paramText(it) } ?: "leer"}")
        s.emit("${desc.displayName.ifBlank { desc.name }}: ${fan!!.text}")
    }

    /** Fächer zurücknehmen: wieder ein einzelner Wert. */
    fun clearEffectFan(attachableId: String, desc: GenericParameterData, value: Double) = setEffectNumber(attachableId, desc, value)

    fun setEffectParam(attachableId: String, desc: GenericParameterData, value: LumosProtobuf.CommonTypes.ObjectData) =
        s.throttler.submit("eff:$attachableId:${desc.name}") {
            sendParam(attachableId, desc, value)
            _effectParams.update { m ->
                m + (attachableId to (m[attachableId] ?: emptyList()).map { if (it.desc.name == desc.name) it.copy(value = value) else it })
            }
        }
}

/** Gefächerter Parameterwert: Operator und Stützwerte, z. B. ">" mit [180, 0]. */
data class FanInfo(val op: String, val values: List<String>) {
    /** Anzeige mit höchstens zwei Nachkommastellen (DMXControl liefert z. B. 176.55006408691406). */
    val text get() = values.joinToString(" $op ") { v ->
        v.replace(',', '.').toDoubleOrNull()?.let {
            java.math.BigDecimal(it).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        } ?: v
    }
    val from get() = values.firstOrNull()?.replace(',', '.')?.toDoubleOrNull()
    val to get() = values.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
}


/**
 * DMXControl führt Effektparameter als "parametrierte Fächerwerte": ein FannedPropertyValue im
 * Protobuf-Anhang des ObjectData, mit einem Stützwert (Einzelwert, z. B. Phase 0) oder mehreren
 * (Fächer, z. B. 0 > 180). Liefert das Objekt, wenn der Anhang so lesbar ist.
 */
fun fpvOf(v: LumosProtobuf.CommonTypes.ObjectData): FannedPropertyValue? {
    if (v.fallbackCase != LumosProtobuf.CommonTypes.ObjectData.FallbackCase.FALLBACKPROTOBUF) return null
    val f = runCatching { FannedPropertyValue.parseFrom(v.fallbackProtobuf) }.getOrNull() ?: return null
    return f.takeIf { it.parameterValuesCount > 0 || it.fannedValuesCount > 0 || it.operator.isNotBlank() }
}

/** Stützwerte: Zahlen aus fannedValues haben Vorrang, sonst die Texte aus parameterValues. */
private fun FannedPropertyValue.values(): List<String> =
    fannedValuesList.mapNotNull { if (it.valueCase == LumosProtobuf.DeviceServiceTypes.DevicePropertyValue.ValueCase.DOUBLEVALUE) it.doubleValue.toString() else null }
        .ifEmpty { parameterValuesList.filter { it.isNotBlank() } }

private val FAN_TEXT = Regex("""^\s*(-?\d+(?:[.,]\d+)?)\s*([^\d\s.,-][^\d\s]*)\s*(-?\d+(?:[.,]\d+)?)\s*$""")

/** Fächer aus einem Parameterwert lesen (mind. zwei Stützwerte bzw. Text "a > b"). */
fun decodeFan(v: LumosProtobuf.CommonTypes.ObjectData): FanInfo? {
    fpvOf(v)?.let { f -> val vals = f.values(); if (vals.size >= 2) return FanInfo(f.operator.ifBlank { ">" }, vals) }
    if (v.valueCase == LumosProtobuf.CommonTypes.ObjectData.ValueCase.STRINGVALUE) {
        FAN_TEXT.matchEntire(v.stringValue)?.let { m -> return FanInfo(m.groupValues[2], listOf(m.groupValues[1], m.groupValues[3])) }
    }
    return null
}

/** Zahl aus einem Parameterwert: primitive Zahl, Einzelwert im FannedPropertyValue oder Text "90". */
fun paramNumber(v: LumosProtobuf.CommonTypes.ObjectData): Double? {
    v.asDouble()?.let { return it }
    fpvOf(v)?.values()?.takeIf { it.size == 1 }?.first()?.replace(',', '.')?.toDoubleOrNull()?.let { return it }
    if (v.valueCase == LumosProtobuf.CommonTypes.ObjectData.ValueCase.STRINGVALUE)
        return Regex("""^\s*(-?\d+(?:[.,]\d+)?)""").find(v.stringValue)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
    return null
}

/** Lesbarer Parameterwert: Fächer, Zahl oder Text. */
fun paramText(v: LumosProtobuf.CommonTypes.ObjectData): String =
    decodeFan(v)?.text ?: paramNumber(v)?.let {
        java.math.BigDecimal(it).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    } ?: v.text()

