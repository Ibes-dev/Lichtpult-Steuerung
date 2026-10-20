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
     * UNVERIFIZIERT: Welcher Eintrag der faders-Map den Hauptfader meint, ist nicht dokumentiert.
     * Es wird der erste Eintrag verwendet (Name aus dem Descriptor, sonst Map-Key).
     */
    fun setFader(master: MasterDescriptor, value: Double) = s.throttler.submit("master:${master.masterId}") {
        val entry = master.fadersMap.entries.firstOrNull()
        val name = if (entry == null) "" else entry.value.name.ifBlank { entry.key }
        stub.dl(3).setMasterValue(
            SetMasterValueRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setMasterId(master.masterId)
                .setUserContextId(s.userContextId)
                .setFader(
                    MasterFaderDescriptor.newBuilder()
                        .setMasterId(master.masterId)
                        .setName(name)
                        .setAbsolut(value.coerceIn(0.0, 1.0))
                        .build()
                )
                .build(),
            s.headers
        ).requireOk("SetMasterValue")
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
