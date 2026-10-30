package de.sebi.dmxc3remote.net

import LumosProtobuf.Audio.AudioServiceTypes.ControlPlayerThreadRequest
import LumosProtobuf.Audio.AudioServiceTypes.PlayerThreadDescriptor
import LumosProtobuf.CommonTypes.EChangeType
import LumosProtobuf.CommonTypes.ENullableBool
import LumosProtobuf.Cuelist.CuelistGroupServiceTypes.CuelistGroupDescriptor
import LumosProtobuf.Cuelist.CuelistServiceTypes.AllCuelistActionRequest
import LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest
import LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistDescriptor
import LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistProgressStateChangeMessage
import LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistProgressStateChangeRequest
import LumosProtobuf.Cuelist.CuelistServiceTypes.SetCuelistValueRequest
import LumosProtobuf.DeviceServiceTypes.GlobalFunctionRequestResponse
import LumosProtobuf.Executor.ExecutorServiceCRUDTypes.SetExecutorValuesRequest
import LumosProtobuf.Executor.ExecutorServiceTypes.ExecutorDescriptor
import LumosProtobuf.Executor.ExecutorServiceTypes.ExecutorPageDescriptor
import LumosProtobuf.Macro.MacroServiceCRUDTypes.MacroSetButtonStateRequest
import LumosProtobuf.Macro.MacroServiceCRUDTypes.MacroSetFaderStateRequest
import LumosProtobuf.Macro.MacroServiceTypes.MacroDescriptor
import LumosProtobuf.MainSwitchServiceTypes.MainSwitchData
import LumosProtobuf.MainSwitchServiceTypes.MainSwitchSetRequest
import LumosProtobuf.Master.MasterServiceCRUDTypes.MasterActionRequest
import LumosProtobuf.Master.MasterServiceCRUDTypes.SetMasterValueRequest
import LumosProtobuf.Master.MasterServiceTypes.MasterDescriptor
import LumosProtobuf.Master.MasterServiceTypes.MasterButtonDescriptor
import LumosProtobuf.Master.MasterServiceTypes.MasterFaderDescriptor
import LumosProtobuf.Timecode.TimecodeServiceCRUDTypes.ETimecodeAction
import LumosProtobuf.Timecode.TimecodeServiceCRUDTypes.TimecodeActionRequest
import LumosProtobuf.Timecode.TimecodeServiceTypes.TimecodeDescriptor
import LumosProtobufClient.AudioClientGrpcKt.AudioClientCoroutineStub
import LumosProtobufClient.CuelistClientGrpcKt.CuelistClientCoroutineStub
import LumosProtobufClient.CuelistGroupClientGrpcKt.CuelistGroupClientCoroutineStub
import LumosProtobufClient.DeviceClientGrpcKt.DeviceClientCoroutineStub
import LumosProtobufClient.ExecutorClientGrpcKt.ExecutorClientCoroutineStub
import LumosProtobufClient.MacroClientGrpcKt.MacroClientCoroutineStub
import LumosProtobufClient.MainSwitchClientGrpcKt.MainSwitchClientCoroutineStub
import LumosProtobufClient.MasterClientGrpcKt.MasterClientCoroutineStub
import LumosProtobufClient.TimecodeClientGrpcKt.TimecodeClientCoroutineStub
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Einheitliche Behandlung von Added/Changed/Removed. */
internal fun <T> MutableStateFlow<Map<String, T>>.applyChange(
    type: EChangeType,
    id: String,
    hasData: Boolean,
    data: () -> T,
    idOf: (T) -> String
) {
    when (type) {
        EChangeType.Added, EChangeType.Changed -> if (hasData) {
            val d = data()
            update { it + (idOf(d).ifBlank { id } to d) }
        }
        EChangeType.Removed -> {
            val key = id.ifBlank { if (hasData) idOf(data()) else "" }
            if (key.isNotBlank()) update { it - key }
        }
        else -> Unit
    }
}

// ============================================================== Executoren
class ExecutorModule(s: UmbraSession) : Module(s, "Executoren") {
    private val stub = ExecutorClientCoroutineStub(s.channel)

    private val _items = MutableStateFlow<Map<String, ExecutorDescriptor>>(emptyMap())
    val items: StateFlow<Map<String, ExecutorDescriptor>> = _items.asStateFlow()
    private val _pages = MutableStateFlow<Map<String, ExecutorPageDescriptor>>(emptyMap())
    val pages: StateFlow<Map<String, ExecutorPageDescriptor>> = _pages.asStateFlow()

    override suspend fun load() {
        _items.value = stub.dl(10).getExecutors(s.getMultiple(), s.headers).executorsList.associateBy { it.id }
        _pages.value = stub.dl(10).getExecutorPages(s.getMultiple(), s.headers).executorPagesList.associateBy { it.id }
    }

    override suspend fun stream() = coroutineScope {
        launch {
            stub.receiveExecutorPageChanges(s.getRequest(), s.headers).collect { m ->
                _pages.applyChange(m.changeType, m.executorPageId, m.hasExecutorPageData(), { m.executorPageData }, { it.id })
            }
        }
        stub.receiveExecutorChanges(s.getRequest(), s.headers).collect { m ->
            _items.applyChange(m.changeType, m.executorId, m.hasExecutorData(), { m.executorData }, { it.id })
        }
    }

    /** Executor-Seite im UserContext wählen (wirkt auf DMXC3-GUI, Hardware-Pulte usw.). */
    fun selectPage(pageId: String) = act("Seite wählen") {
        s.userStub.dl().selectExecutorPage(s.getMultiple(listOf(pageId)), s.headers).requireOk("SelectExecutorPage")
    }

    fun setFader(id: String, value: Double) = s.throttler.submit("exec:$id") {
        send(id) { it.setFaderAbsolut(value.coerceIn(0.0, 1.0)) }
    }

    fun setButton(id: String, button: Int, pressed: Boolean) = act("Executor-Taste") {
        val v = if (pressed) ENullableBool.True else ENullableBool.False
        send(id) {
            when (button) {
                1 -> it.setButton1(v)
                2 -> it.setButton2(v)
                3 -> it.setButton3(v)
                else -> it.setButton4(v)
            }
        }
    }

    private suspend fun send(id: String, apply: (SetExecutorValuesRequest.Builder) -> SetExecutorValuesRequest.Builder) {
        val req = apply(
            SetExecutorValuesRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setExecutorId(id)
                .setUserContextId(s.userContextId)
        ).build()
        stub.dl(3).setExecutorValues(req, s.headers).requireOk("SetExecutorValues")
    }
}

// ============================================================== Master
class MasterModule(s: UmbraSession) : Module(s, "Master") {
    private val stub = MasterClientCoroutineStub(s.channel)
    private val _items = MutableStateFlow<Map<String, MasterDescriptor>>(emptyMap())
    val items: StateFlow<Map<String, MasterDescriptor>> = _items.asStateFlow()

    override suspend fun load() {
        _items.value = stub.dl(10).getMasters(s.getMultiple(), s.headers).mastersList.associateBy { it.masterId }
    }

    override suspend fun stream() {
        stub.receiveMasterChanges(s.getRequest(), s.headers).collect { m ->
            _items.applyChange(m.changeType, m.masterId, m.hasMasterData(), { m.masterData }, { it.masterId })
        }
    }

    fun action(id: String, action: MasterActionRequest.EAction) = act("Master $action") {
        stub.dl(3).masterAction(
            MasterActionRequest.newBuilder().setRequestId(s.nextRequestId()).setMasterId(id).setAction(action).build(),
            s.headers
        ).requireOk("MasterAction")
    }

    /**
     * Welcher Eintrag der faders-Map den Hauptfader meint und ob DMXControl "absolut" oder "increment"
     * erwartet, ist nicht dokumentiert. DMXControl bestätigt Befehle auch dann, wenn es sie nicht umsetzt.
     * Deshalb: Varianten der Reihe nach senden, am gemeldeten Masterwert prüfen, ob er sich bewegt,
     * und die funktionierende Variante merken.
     */
    private data class FaderWay(val name: String, val increment: Boolean, val label: String)

    @Volatile private var faderWay: FaderWay? = null
    @Volatile private var probing = false

    private fun valueOf(m: MasterDescriptor?): Double? = when (m?.masterValueCase) {
        MasterDescriptor.MasterValueCase.INTENSITY -> m.intensity
        MasterDescriptor.MasterValueCase.PARAMETER -> m.parameter
        MasterDescriptor.MasterValueCase.SPEED -> m.speed
        else -> null
    }

    private fun ways(master: MasterDescriptor, preferred: String = "Master"): List<FaderWay> {
        val names = buildList {
            master.fadersMap.forEach { (k, v) -> if (k.equals(preferred, true) || v.name.equals(preferred, true)) { add(v.name.ifBlank { k }); add(k) } }
            master.fadersMap.forEach { (k, v) -> add(k); if (v.name.isNotBlank()) add(v.name) }
            add(preferred)
            add("")
        }.distinct()
        return names.map { FaderWay(it, false, "absolut \"$it\"") } + names.map { FaderWay(it, true, "increment \"$it\"") }
    }

    private suspend fun send(master: MasterDescriptor, way: FaderWay, target: Double) {
        val cur = valueOf(_items.value[master.masterId]) ?: 0.0
        val f = MasterFaderDescriptor.newBuilder().setMasterId(master.masterId).setName(way.name)
        if (way.increment) f.setIncrement(target - cur) else f.setAbsolut(target)
        stub.dl(3).setMasterValue(
            SetMasterValueRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setMasterId(master.masterId)
                .setUserContextId(s.userContextId)
                .setFader(f)
                .build(),
            s.headers
        ).requireOk("SetMasterValue")
    }

    /**
     * Weiterer Regler eines Masters über seinen Namen (Pan, Tilt, Hue …), Wert 0..1.
     * UNVERIFIZIERT: Wertebereich der Nicht-Intensitäts-Regler.
     */
    fun setNamedFader(master: MasterDescriptor, name: String, value: Double) = s.throttler.submit("master:${master.masterId}:$name") {
        stub.dl(3).setMasterValue(
            SetMasterValueRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setMasterId(master.masterId)
                .setUserContextId(s.userContextId)
                .setFader(MasterFaderDescriptor.newBuilder().setMasterId(master.masterId).setName(name).setAbsolut(value.coerceIn(0.0, 1.0)))
                .build(),
            s.headers
        ).requireOk("SetMasterValue $name")
    }

    /**
     * Taste eines Masters (Flash, Stomp, Select, Fix, Max, Zero, Red …) wie eine Hardware-Taste:
     * Drücken = an, Loslassen = aus. DMXControl entscheidet, ob die Taste rastet.
     */
    fun button(master: MasterDescriptor, name: String, down: Boolean) = act("Master $name") {
        stub.dl(3).setMasterValue(
            SetMasterValueRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setMasterId(master.masterId)
                .setUserContextId(s.userContextId)
                .setButton(MasterButtonDescriptor.newBuilder().setMasterId(master.masterId).setName(name).setButtonValue(down))
                .build(),
            s.headers
        ).requireOk("Master $name")
    }

    fun setFader(master: MasterDescriptor, value: Double) = s.throttler.submit("master:${master.masterId}") {
        val target = value.coerceIn(0.0, 1.0)
        val known = faderWay
        if (known != null) { send(master, known, target); return@submit }
        if (probing) return@submit
        // Erkennung nur mit spürbarer Änderung, sonst ist "keine Wirkung" nicht von "schon dort" zu unterscheiden
        valueOf(_items.value[master.masterId])?.let { if (kotlin.math.abs(it - target) < 0.03) return@submit }
        probing = true
        try {
            Diag.note("Master ${master.name}: Fader-Einträge " + master.fadersMap.entries.joinToString { "${it.key}=\"${it.value.name}\"" }.ifBlank { "keine" })
            for (way in ways(master)) {
                val before = valueOf(_items.value[master.masterId])
                val r = runCatching { send(master, way, target) }
                if (r.isFailure) { Diag.note("Master-Fader ${way.label}: abgelehnt: ${r.exceptionOrNull()?.message}"); continue }
                // Rückmeldung über den Änderungs-Stream abwarten
                var moved = false
                for (i in 0 until 8) {
                    delay(60)
                    val now = valueOf(_items.value[master.masterId])
                    if (now != null && (before == null || kotlin.math.abs(now - before) > 0.005) && kotlin.math.abs(now - target) < 0.05) { moved = true; break }
                }
                if (!moved) {
                    // Stream meldet evtl. nicht: direkt nachfragen
                    runCatching { load() }
                    val now = valueOf(_items.value[master.masterId])
                    moved = now != null && kotlin.math.abs(now - target) < 0.05
                }
                Diag.note("Master-Fader ${way.label}: ${if (moved) "wirkt" else "keine Wirkung"}")
                if (moved) { faderWay = way; return@submit }
            }
            s.emit("Master: DMXControl setzt den Fader nicht (Details unter Diagnose)", isError = true)
        } finally {
            probing = false
        }
    }
}

// ============================================================== Macros
class MacroModule(s: UmbraSession) : Module(s, "Macros") {
    private val stub = MacroClientCoroutineStub(s.channel)
    private val _items = MutableStateFlow<Map<String, MacroDescriptor>>(emptyMap())
    val items: StateFlow<Map<String, MacroDescriptor>> = _items.asStateFlow()

    override suspend fun load() {
        _items.value = stub.dl(10).getMacros(s.getMultiple(), s.headers).macrosList.associateBy { it.id }
    }

    override suspend fun stream() {
        stub.receiveMacroChanges(s.getRequest(), s.headers).collect { m ->
            if (m.changeType == EChangeType.Changed && !m.hasMacroData() && m.macroId.isNotBlank()) {
                // Property-Änderung ohne Descriptor -> einzelnes Macro nachladen
                stub.dl(5).getMacros(s.getMultiple(listOf(m.macroId)), s.headers).macrosList
                    .forEach { d -> _items.update { it + (d.id to d) } }
            } else {
                _items.applyChange(m.changeType, m.macroId, m.hasMacroData(), { m.macroData }, { it.id })
            }
        }
    }

    fun setButton(macroId: String, number: Int, active: Boolean) = act("Macro-Taste") {
        stub.dl(3).setMacroButtonState(
            MacroSetButtonStateRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setMacroId(macroId)
                .setButtonNumber(number)
                .setActive(active)
                .build(),
            s.headers
        ).requireOk("SetMacroButtonState")
    }

    fun setFader(macroId: String, number: Int, value: Double) = s.throttler.submit("macro:$macroId:$number") {
        stub.dl(3).setMacroFaderState(
            MacroSetFaderStateRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setMacroId(macroId)
                .setFaderNumber(number)
                .setAbsolut(value.coerceIn(0.0, 1.0))
                .build(),
            s.headers
        ).requireOk("SetMacroFaderState")
    }
}

// ============================================================== MainSwitch
class MainSwitchModule(s: UmbraSession) : Module(s, "Hauptschalter") {
    private val stub = MainSwitchClientCoroutineStub(s.channel)
    private val _items = MutableStateFlow<Map<String, MainSwitchData>>(emptyMap())
    val items: StateFlow<Map<String, MainSwitchData>> = _items.asStateFlow()

    override suspend fun load() {
        _items.value = stub.dl(10).getMainSwitches(s.getMultiple(), s.headers).mainSwitchesList.associateBy { it.mainSwitchId }
    }

    override suspend fun stream() {
        stub.recieveMainSwitchChanges(s.getRequest(), s.headers).collect { m ->
            val cur = _items.value[m.mainSwitchId]
            if (cur == null) {
                load()
            } else {
                _items.update { it + (m.mainSwitchId to cur.toBuilder().setEnabledState(m.enabledState).build()) }
            }
        }
    }

    fun set(id: String, enabled: Boolean) = act("Hauptschalter") {
        stub.dl(3).setMainSwitch(
            MainSwitchSetRequest.newBuilder().setMainSwitchId(id).setEnabledState(enabled).build(),
            s.headers
        ).requireOk("SetMainSwitch")
    }
}

// ============================================================== Blackout / Freeze
class GlobalFunctionModule(s: UmbraSession) : Module(s, "Globale Funktionen") {
    private val stub = DeviceClientCoroutineStub(s.channel)
    private val _blackout = MutableStateFlow<Boolean?>(null)
    val blackout: StateFlow<Boolean?> = _blackout.asStateFlow()
    private val _freeze = MutableStateFlow<Boolean?>(null)
    val freeze: StateFlow<Boolean?> = _freeze.asStateFlow()

    private suspend fun query() {
        val bo = stub.dl(3).getSetGlobalFunction(
            GlobalFunctionRequestResponse.newBuilder().setRequestId(s.nextRequestId()).setSetValue(false).setBlackout(false).build(),
            s.headers
        )
        _blackout.value = bo.blackout
        val fr = stub.dl(3).getSetGlobalFunction(
            GlobalFunctionRequestResponse.newBuilder().setRequestId(s.nextRequestId()).setSetValue(false).setFreeze(false).build(),
            s.headers
        )
        _freeze.value = fr.freeze
    }

    override suspend fun load() = query()

    /** Kein Änderungs-Stream im Protokoll -> Polling alle 2 s. */
    override suspend fun stream() {
        while (true) {
            delay(2_000)
            query()
        }
    }

    fun setBlackout(on: Boolean) = act("Blackout") {
        val r = stub.dl(3).getSetGlobalFunction(
            GlobalFunctionRequestResponse.newBuilder().setRequestId(s.nextRequestId()).setSetValue(true).setBlackout(on).build(),
            s.headers
        )
        _blackout.value = r.blackout
    }

    fun setFreeze(on: Boolean) = act("Freeze") {
        val r = stub.dl(3).getSetGlobalFunction(
            GlobalFunctionRequestResponse.newBuilder().setRequestId(s.nextRequestId()).setSetValue(true).setFreeze(on).build(),
            s.headers
        )
        _freeze.value = r.freeze
    }
}

// ============================================================== Cuelists
class CuelistModule(s: UmbraSession) : Module(s, "Cuelists") {
    private val stub = CuelistClientCoroutineStub(s.channel)
    private val groupStub = CuelistGroupClientCoroutineStub(s.channel)
    private val _items = MutableStateFlow<Map<String, CuelistDescriptor>>(emptyMap())
    val items: StateFlow<Map<String, CuelistDescriptor>> = _items.asStateFlow()
    private val _groups = MutableStateFlow<Map<String, CuelistGroupDescriptor>>(emptyMap())
    val groups: StateFlow<Map<String, CuelistGroupDescriptor>> = _groups.asStateFlow()

    override suspend fun load() {
        _items.value = stub.dl(15).getCuelists(s.getMultiple(), s.headers).cuelistsList.associateBy { it.id }
        _groups.value = runCatching {
            groupStub.dl(10).getCuelistGroups(s.getMultiple(), s.headers).cuelistgroupsList.associateBy { it.id }
        }.getOrDefault(emptyMap())
    }

    /** Fortschritt je Cuelist (Cue-Index -> Zustand), aus ReceiveCuelistProgressChanges. */
    private val _progress = MutableStateFlow<Map<String, CuelistProgressStateChangeMessage>>(emptyMap())
    val progress: StateFlow<Map<String, CuelistProgressStateChangeMessage>> = _progress.asStateFlow()

    override suspend fun stream() = coroutineScope {
        // Bidi-Stream: angefragte Cuelist-IDs gehen hinaus, Fortschritt kommt zurück.
        // Bei neuen Cuelists wird die ID-Liste erneut gesendet.
        launch {
            runCatching {
                val requests = kotlinx.coroutines.flow.flow {
                    var last: Set<String> = emptySet()
                    _items.collect { m ->
                        if (m.keys != last) {
                            last = m.keys
                            emit(CuelistProgressStateChangeRequest.newBuilder().addAllCuelistIds(m.keys).build())
                        }
                    }
                }
                stub.receiveCuelistProgressChanges(requests, s.headers).collect { msg ->
                    _progress.update { it + (msg.cuelistId to msg) }
                }
            }
        }
        launch {
            runCatching {
                groupStub.receiveCuelistGroupChanges(s.getRequest(), s.headers).collect { m ->
                    _groups.applyChange(m.changeType, m.cuelistGroupId, m.hasCuelistGroupData(), { m.cuelistGroupData }, { it.id })
                }
            }
        }
        stub.receiveCuelistChanges(s.getRequest(), s.headers).collect { m ->
            _items.applyChange(m.changeType, m.cuelistId, m.hasCuelistData(), { m.cuelistData }, { it.id })
        }
    }

    fun action(id: String, action: CuelistActionRequest.EAction, index: Int = 0) = act("Cuelist $action") {
        stub.dl(3).cuelistAction(
            CuelistActionRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setCuelistId(id)
                .setAction(action)
                .setIndex(index)
                .build(),
            s.headers
        ).requireOk("CuelistAction")
    }

    /**
     * Index-Basis für GO_TO/LOAD. Das Proto sagt nur "index … e.g. GoTo". Möglich sind Listenposition
     * 0-basiert, 1-basiert oder das Feld cueIndex. Die App probiert der Reihe nach und merkt sich,
     * was DMXControl tatsächlich anspringt (Kontrolle über den gemeldeten aktuellen Cue).
     */
    @Volatile private var indexMode: Int? = null   // 0 = Position, 1 = Position+1, 2 = cueIndex

    private fun indexFor(mode: Int, pos: Int, cueIndex: Int) = when (mode) {
        0 -> pos
        1 -> pos + 1
        else -> cueIndex
    }

    // ================================================================ Cues bearbeiten
    private val cueStub by lazy { LumosProtobufClient.CueClientGrpcKt.CueClientCoroutineStub(s.channel) }

    /**
     * "parentContainerId" ist nicht dokumentiert: Cue-eigener Wert, Cuelist-ID oder leer. Die App
     * probiert der Reihe nach, prüft das Ergebnis an der neu geladenen Cuelist und merkt sich,
     * was wirkt.
     */
    @Volatile private var containerMode: Int? = null

    private fun containers(cuelistId: String, cueId: String): List<Pair<Int, String>> {
        val cue = _items.value[cuelistId]?.cuesList?.firstOrNull { it.cueId == cueId }
        val all = listOf(0 to (cue?.parentContainerId ?: ""), 1 to cuelistId, 2 to "")
            .filter { it.first != 0 || it.second.isNotBlank() }.distinctBy { it.second }
        return containerMode?.let { m -> all.sortedBy { if (it.first == m) 0 else 1 } } ?: all
    }

    private suspend fun cueOp(
        what: String, cuelistId: String, cueId: String,
        send: suspend (container: String) -> LumosProtobuf.CommonTypes.ConfirmedResponse,
        check: (LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistDescriptor?) -> Boolean
    ) {
        var last = ""
        for ((mode, container) in containers(cuelistId, cueId)) {
            val r = runCatching { send(container) }.getOrNull()
            if (r == null || !r.ok) { last = r?.message?.text().orEmpty(); Diag.note("$what (Container $mode): abgelehnt $last"); continue }
            delay(250)
            runCatching { load() }
            if (check(_items.value[cuelistId])) { containerMode = mode; return }
            Diag.note("$what (Container $mode): angenommen, aber ohne Wirkung")
        }
        throw UmbraException("$what: DMXControl hat es nicht übernommen" + (last.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
    }

    /** Cue umbenennen (CueClient.SetCueValue, Feld name). */
    fun renameCue(cuelistId: String, cueId: String, name: String) = act("Cue umbenennen") {
        cueOp("Cue umbenennen", cuelistId, cueId,
            { c ->
                cueStub.dl(5).setCueValue(
                    LumosProtobuf.Cue.CueServiceTypes.SetCueValueRequest.newBuilder()
                        .setRequestId(s.nextRequestId()).setCueId(cueId).setParentContainerId(c).setName(name).build(),
                    s.headers
                )
            },
            { cl -> cl?.cuesList?.firstOrNull { it.cueId == cueId }?.name == name })
        s.emit("Cue umbenannt in \"$name\"")
    }

    /** Cue löschen (CueClient.DeleteCue). */
    fun deleteCue(cuelistId: String, cueId: String, label: String) = act("Cue löschen") {
        cueOp("Cue löschen", cuelistId, cueId,
            { c ->
                cueStub.dl(5).deleteCue(
                    LumosProtobuf.Cue.CueServiceTypes.CueDeletionRequest.newBuilder()
                        .setRequestId(s.nextRequestId()).setParentContainerId(c).setCueId(cueId).build(),
                    s.headers
                )
            },
            { cl -> cl != null && cl.cuesList.none { it.cueId == cueId } })
        s.emit("$label gelöscht")
    }

    /** Cue an eine andere Position schieben (CueClient.MoveCue, Ziel = Position in der Liste). */
    fun moveCue(cuelistId: String, cueId: String, targetPos: Int) = act("Cue verschieben") {
        cueOp("Cue verschieben", cuelistId, cueId,
            { c ->
                cueStub.dl(5).moveCue(
                    LumosProtobuf.Cue.CueServiceTypes.CueMoveRequest.newBuilder()
                        .setRequestId(s.nextRequestId()).setParentContainerId(c).setCueId(cueId).setTargetIndex(targetPos).build(),
                    s.headers
                )
            },
            { cl -> cl?.cuesList?.sortedBy { it.cueIndex }?.indexOfFirst { it.cueId == cueId } == targetPos })
    }

    /**
     * Cuelist umbenennen. Die Cuelist-Schnittstelle hat dafür keinen eigenen Aufruf; der Name wird
     * über die allgemeine Parameter-Schnittstelle gesetzt (type "Cuelist", Parameter "Name").
     */
    fun renameCuelist(cuelistId: String, name: String) = act("Cuelist umbenennen") {
        val par = LumosProtobufClient.ParameterClientGrpcKt.ParameterClientCoroutineStub(s.channel)
        val r = par.dl(10).getParameters(
            LumosProtobuf.ParameterServiceTypes.GetParametersRequest.newBuilder()
                .setType("Cuelist").setId(cuelistId).setIncludingValues(true).setUserContextId(s.userContextId).build(),
            s.headers
        )
        val p = r.parametersList.firstOrNull { it.name.equals("Name", true) }
            ?: r.parametersList.firstOrNull { it.name.contains("name", true) || it.displayName.contains("name", true) }
            ?: throw UmbraException("DMXControl bietet für Cuelists keinen Namensparameter an")
        par.dl(5).setParameterValue(
            LumosProtobuf.ParameterServiceTypes.SetTestParameterValueRequest.newBuilder()
                .setId(cuelistId).setParameter(p).setUserContextId(s.userContextId)
                .setValue(LumosProtobuf.CommonTypes.ObjectData.newBuilder().setStringValue(name)).build(),
            s.headers
        ).requireOk("Cuelist umbenennen")
        delay(250)
        runCatching { load() }
        if (_items.value[cuelistId]?.name != name) throw UmbraException("Cuelist umbenennen: DMXControl hat den Namen nicht übernommen")
        s.emit("Cuelist umbenannt in \"$name\"")
    }

    /** Index für Aktionen, die einen Cue adressieren (gleiche Basis wie Go To, sobald erkannt). */
    fun indexOf(id: String, pos: Int): Int {
        val cue = cuesOf(id).getOrNull(pos) ?: return pos
        return indexFor(indexMode ?: 0, pos, cue.cueIndex)
    }

    /** Liste der Cues in der Reihenfolge, wie DMXControl sie führt. */
    fun cuesOf(id: String) = _items.value[id]?.cuesList?.sortedBy { it.cueIndex } ?: emptyList()

    /** Zu einem Cue springen (GO_TO). pos = Position in [cuesOf]. */
    fun goTo(id: String, pos: Int) = act("Go To") {
        val cue = cuesOf(id).getOrNull(pos) ?: return@act
        val known = indexMode
        val modes = if (known != null) listOf(known) else listOf(0, 2, 1).distinctBy { indexFor(it, pos, cue.cueIndex) }
        for (mode in modes) {
            stub.dl(3).cuelistAction(
                CuelistActionRequest.newBuilder()
                    .setRequestId(s.nextRequestId())
                    .setCuelistId(id)
                    .setAction(CuelistActionRequest.EAction.GO_TO)
                    .setIndex(indexFor(mode, pos, cue.cueIndex))
                    .build(),
                s.headers
            ).requireOk("Go To")
            if (known != null) return@act
            // Rückmeldung abwarten: steht die Cuelist jetzt auf dem gewünschten Cue?
            delay(700)
            val now = _items.value[id]
            if (now != null && (now.index == cue.cueIndex || now.index == pos)) {
                indexMode = mode
                Diag.note("Go To: Index-Basis erkannt = ${listOf("Position", "Position+1", "cueIndex")[mode]}")
                return@act
            }
        }
        s.emit("Go To: DMXControl hat den Cue nicht angesprungen", isError = true)
    }

    /** Cue als nächsten laden, ohne ihn auszuführen (LOAD). Gleiche Index-Basis wie Go To. */
    fun load(id: String, pos: Int) {
        val cue = cuesOf(id).getOrNull(pos) ?: return
        action(id, CuelistActionRequest.EAction.LOAD, indexFor(indexMode ?: 0, pos, cue.cueIndex))
    }

    /** Neue Cuelist anlegen, optional als Kopie einer bestehenden (CuelistClient.CreateCuelist). */
    fun createCuelist(name: String, copyFrom: String? = null) = act("Cuelist anlegen") {
        val b = LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistCreationRequest.newBuilder()
            .setRequestId(s.nextRequestId()).setCuelistNameTemplate(name)
        if (copyFrom != null) b.setCopyFromCuelist(copyFrom)
        stub.dl(10).createCuelist(b.build(), s.headers).requireOk("Cuelist anlegen")
        load()
        s.emit(if (copyFrom != null) "Kopie \"$name\" angelegt" else "Cuelist \"$name\" angelegt")
    }

    fun stopAll() = act("Alle Cuelists stoppen") {
        stub.dl(5).allCuelistAction(
            AllCuelistActionRequest.newBuilder().setRequestId(s.nextRequestId()).setAction(AllCuelistActionRequest.EAction.STOP).build(),
            s.headers
        )
    }

    fun setIntensity(id: String, value: Double) = s.throttler.submit("cuelist:$id") {
        stub.dl(3).setCuelistValue(
            SetCuelistValueRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setCuelistId(id)
                .setIntensity(value.coerceIn(0.0, 1.0))
                .build(),
            s.headers
        ).requireOk("SetCuelistValue")
    }
}

// ============================================================== Audio
class AudioModule(s: UmbraSession) : Module(s, "Audio") {
    private val stub = AudioClientCoroutineStub(s.channel)
    private val _items = MutableStateFlow<Map<String, PlayerThreadDescriptor>>(emptyMap())
    val items: StateFlow<Map<String, PlayerThreadDescriptor>> = _items.asStateFlow()

    override suspend fun load() {
        _items.value = stub.dl(10).getPlayerThreads(s.getMultiple(), s.headers).playerThreadsList.associateBy { it.id }
    }

    override suspend fun stream() {
        stub.receivePlayerThreadChanges(s.getRequest(), s.headers).collect { m ->
            _items.applyChange(m.changeType, m.playerThreadId, m.hasPlayerThreadData(), { m.playerThreadData }, { it.id })
        }
    }

    fun control(id: String, mode: ControlPlayerThreadRequest.EPlayMode) = act("Audio $mode") {
        stub.dl(3).controlPlayerThread(
            ControlPlayerThreadRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setPlayerThreadId(id)
                .setPlayMode(mode)
                .build(),
            s.headers
        ).requireOk("ControlPlayerThread")
    }

    /** UNVERIFIZIERT: Lautstärke 0.0–1.0 angenommen. */
    fun setVolume(id: String, volume: Double) = s.throttler.submit("audio:$id") {
        stub.dl(3).controlPlayerThread(
            ControlPlayerThreadRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setPlayerThreadId(id)
                .setPlayMode(ControlPlayerThreadRequest.EPlayMode.None)
                .setVolume(volume.coerceIn(0.0, 1.0))
                .setVolumeSet(true)
                .build(),
            s.headers
        ).requireOk("ControlPlayerThread")
    }
}

// ============================================================== Timecode
class TimecodeModule(s: UmbraSession) : Module(s, "Timecode") {
    private val stub = TimecodeClientCoroutineStub(s.channel)
    private val _items = MutableStateFlow<Map<String, TimecodeDescriptor>>(emptyMap())
    val items: StateFlow<Map<String, TimecodeDescriptor>> = _items.asStateFlow()

    /** Laufzeit je Timecode-ID (Einheit laut Server, vermutlich ms). */
    private val _elapsed = MutableStateFlow<Map<String, Long>>(emptyMap())
    val elapsed: StateFlow<Map<String, Long>> = _elapsed.asStateFlow()
    private val _state = MutableStateFlow<Map<String, Int>>(emptyMap())
    val state: StateFlow<Map<String, Int>> = _state.asStateFlow()

    override suspend fun load() {
        val list = stub.dl(10).getTimecodes(s.getMultiple(), s.headers).timecodesList
        _items.value = list.associateBy { it.id }
        _elapsed.value = list.associate { it.id to it.timeElapsed }
        _state.value = list.associate { it.id to it.timecodeState }
    }

    override suspend fun stream() = coroutineScope {
        launch {
            stub.receiveTimecodeStateChanges(s.getRequest(), s.headers).collect { m ->
                _elapsed.update { it + (m.id to m.timeElapsed) }
                _state.update { it + (m.id to m.state) }
            }
        }
        stub.receiveTimecodeChanges(s.getRequest(), s.headers).collect { m ->
            _items.applyChange(m.changeType, m.timecodeId, m.hasTimecodeData(), { m.timecodeData }, { it.id })
        }
    }

    fun action(id: String, action: ETimecodeAction) = act("Timecode $action") {
        stub.dl(3).timecodeAction(
            TimecodeActionRequest.newBuilder().setRequestId(s.nextRequestId()).setTimecodeId(id).setAction(action).build(),
            s.headers
        ).requireOk("TimecodeAction")
    }
}
