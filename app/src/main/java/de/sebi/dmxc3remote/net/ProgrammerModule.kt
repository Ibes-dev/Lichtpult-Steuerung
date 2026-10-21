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
                if (d != seen) { seen = d; runCatching { refresh() } }
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
        delete(DeleteProgrammerValueRequest.EClearMode.CLEAR)
        _origin.value = null
    }

    /** Eine Eigenschaft einer Gruppe aus dem Programmer nehmen. */
    fun remove(groupId: String, propertyId: String) = act("Entfernen") {
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
    }

    fun loadEffectParams(attachableId: String) = act("Effektparameter") { readEffectParams(attachableId) }

    fun setEffectParam(attachableId: String, desc: GenericParameterData, value: LumosProtobuf.CommonTypes.ObjectData) =
        s.throttler.submit("eff:$attachableId:${desc.name}") {
            parStub.dl(5).setParameterValue(
                SetTestParameterValueRequest.newBuilder().setId(attachableId).setParameter(desc)
                    .setUserContextId(s.userContextId).setValue(value).build(),
                s.headers
            ).requireOk(desc.displayName.ifBlank { desc.name })
            _effectParams.update { m ->
                m + (attachableId to (m[attachableId] ?: emptyList()).map { if (it.desc.name == desc.name) it.copy(value = value) else it })
            }
        }
}
