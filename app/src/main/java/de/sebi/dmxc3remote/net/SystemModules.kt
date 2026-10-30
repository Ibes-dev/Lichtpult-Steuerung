package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.ENullableBool
import LumosProtobuf.CommonTypes.GetRequest
import LumosProtobuf.DMXInterfaceManagementTypes.DMXInterfaceStateChangedMessage
import LumosProtobuf.DMXInterfaceManagementTypes.EnableDisableDMXInterfaceRequest
import LumosProtobuf.DMXInterfaceManagementTypes.LumosExtendedDMXInterfaceMetadata
import LumosProtobuf.DMXServiceTypes.GetDMXRequest
import LumosProtobuf.ElectricityServiceTypes.PowerSourceDescriptor
import LumosProtobuf.PluginManagementTypes.EnableDisablePluginRequestResponse
import LumosProtobuf.PluginManagementTypes.GetPluginsRequest
import LumosProtobuf.PluginManagementTypes.PluginMetadata
import LumosProtobuf.ProjectServiceTypes.EProjectState
import LumosProtobuf.ProjectServiceTypes.ESaveProjectVersionIncrement
import LumosProtobuf.ProjectServiceTypes.ProjectChangeStatusResponse
import LumosProtobuf.ProjectServiceTypes.ProjectDirtyRequest
import LumosProtobuf.ProjectServiceTypes.SaveProjectVersionRequest
import LumosProtobuf.RDM.RDMServiceTypes.RDMDeviceDescriptor
import LumosProtobufClient.DMXClientGrpcKt.DMXClientCoroutineStub
import LumosProtobufClient.DMXInterfaceManagementClientGrpcKt.DMXInterfaceManagementClientCoroutineStub
import LumosProtobufClient.ElectricityClientGrpcKt.ElectricityClientCoroutineStub
import LumosProtobufClient.PluginManagementClientGrpcKt.PluginManagementClientCoroutineStub
import LumosProtobufClient.ProjectClientGrpcKt.ProjectClientCoroutineStub
import LumosProtobufClient.RDMClientGrpcKt.RDMClientCoroutineStub
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ============================================================== Projekt
class ProjectModule(s: UmbraSession) : Module(s, "Projekt") {
    private val stub = ProjectClientCoroutineStub(s.channel)
    private val _state = MutableStateFlow<EProjectState?>(null)
    val state: StateFlow<EProjectState?> = _state.asStateFlow()
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    private val _saving = MutableStateFlow<String?>(null)
    val saving: StateFlow<String?> = _saving.asStateFlow()

    /** true, sobald nach dem Verbinden ein Projekt (neu) geladen wurde -> Sitzung muss neu synchronisieren. */
    private val _reloaded = MutableStateFlow(false)
    val reloaded: StateFlow<Boolean> = _reloaded.asStateFlow()

    private var lastLoaded: Boolean? = null
    private var sawLoading = false

    private suspend fun query() {
        val r = stub.dl(5).getProjectState(s.getRequest(), s.headers)
        _state.value = r.state
        val loaded = r.isLoaded && r.state == EProjectState.EProjectState_LOADED
        if (r.state == EProjectState.EProjectState_LOADING || r.state == EProjectState.EProjectState_CLOSING) sawLoading = true
        // Wechsel "kein Projekt -> Projekt" oder "Laden beobachtet -> geladen" = neues Projekt
        if (loaded && (lastLoaded == false || sawLoading)) _reloaded.value = true
        if (loaded) sawLoading = false
        lastLoaded = loaded
        _dirty.value = stub.dl(5).getProjectDirty(
            ProjectDirtyRequest.newBuilder().setRequestId(s.nextRequestId()).setQuickResponse(true).build(),
            s.headers
        ).dirty
    }

    override suspend fun load() = query()

    /**
     * Projektwechsel erkennen: Projekt-Änderungs-Stream als Auslöser (neue Version geladen),
     * zusätzlich Zustandsabfrage alle 2 s.
     */
    override suspend fun stream() = kotlinx.coroutines.coroutineScope {
        launch {
            runCatching {
                stub.receiveProjectChanges(s.getRequest(), s.headers).collect { m ->
                    // Laden einer Projektversion meldet sich als geänderte Version; Speichern auch.
                    // Darum nur kurz nachfragen statt sofort neu zu verbinden.
                    if (m.changedDataCase == LumosProtobuf.ProjectServiceTypes.ProjectChangedMessage.ChangedDataCase.VERSION) query()
                }
            }
        }
        while (true) {
            delay(2_000)
            query()
        }
    }

    fun save() = act("Projekt speichern") {
        _saving.value = "Speichern …"
        try {
            stub.saveProjectVersion(
                SaveProjectVersionRequest.newBuilder()
                    .setRequestId(s.nextRequestId())
                    .setIsAutosave(false)
                    .setResetDirty(ENullableBool.True)
                    .setIncrementVersion(ESaveProjectVersionIncrement.ESaveProjectVersionIncrement_None)
                    .build(),
                s.headers
            ).collect { r ->
                when (r.status) {
                    ProjectChangeStatusResponse.EStatus.WORKING ->
                        _saving.value = if (r.progressPercentSet) "Speichern ${r.progressPercent.toInt()} %" else "Speichern …"
                    ProjectChangeStatusResponse.EStatus.DONE -> {
                        val v = r.savedVersion
                        s.emit("Projekt gespeichert (v${v.major}.${v.minor}.${v.revision})")
                    }
                    ProjectChangeStatusResponse.EStatus.EXCEPTION,
                    ProjectChangeStatusResponse.EStatus.CLIENT_EXCEPTION,
                    ProjectChangeStatusResponse.EStatus.CLIENT_REJECT,
                    ProjectChangeStatusResponse.EStatus.CONCURRENT_IO ->
                        s.emit("Speichern fehlgeschlagen: ${r.status} ${r.exceptionMessage.text()}".trim(), isError = true)
                    else -> Unit
                }
            }
        } finally {
            _saving.value = null
            runCatching { query() }
        }
    }
}

// ============================================================== DMX-Monitor
class DmxModule(s: UmbraSession) : Module(s, "DMX-Monitor") {
    private val stub = DMXClientCoroutineStub(s.channel)
    private val _universe = MutableStateFlow(1)
    val universe: StateFlow<Int> = _universe.asStateFlow()
    private val _values = MutableStateFlow(IntArray(512))
    val values: StateFlow<IntArray> = _values.asStateFlow()
    private val active = MutableStateFlow(false)

    fun setActive(on: Boolean) {
        active.value = on
    }

    fun setUniverse(u: Int) {
        _universe.value = u.coerceIn(1, 1024)
        _values.value = IntArray(512)
    }

    /** Kurzinfo zur letzten Antwort (Diagnose im Monitor). */
    private val _info = MutableStateFlow("")
    val info: StateFlow<String> = _info.asStateFlow()

    /** 0 = dmxOutStart/Stop als absolute Kanäle (1-n), 1 = als Universen (1-n). Wird automatisch erkannt. */
    @Volatile private var mode = 0

    /**
     * Polling nur bei sichtbarem Monitor (200 ms).
     * Laut Proto sind dmxOutStart/Stop 1-basiert ("1-n"); ob Kanal oder Universum gemeint ist, steht dort nicht.
     * Die App fragt zuerst Kanäle ((u-1)*512+1 … u*512) ab und schaltet auf Universen um,
     * wenn die Antwort nicht 512 Werte bzw. keine Kanalnummern enthält.
     */
    override suspend fun stream() {
        while (true) {
            if (!active.value) {
                delay(300)
                continue
            }
            val u = _universe.value
            val firstCh = (u - 1) * 512 + 1
            val (start, stop) = if (mode == 0) firstCh to firstCh + 511 else u to u
            val r = stub.dl(3).getDmxValues(
                GetDMXRequest.newBuilder()
                    .setRequestId(s.nextRequestId())
                    .setDmxOutStart(start)
                    .setDmxOutStop(stop)
                    .build(),
                s.headers
            )
            val bytes = r.dmxValues.toByteArray()
            val chans = r.dmxChannelsList
            val out = IntArray(512)
            when {
                chans.size == bytes.size && bytes.isNotEmpty() -> chans.forEachIndexed { i, ch ->
                    // Kanalnummern absolut (1-n) oder universumsbezogen
                    val idx = when {
                        ch in firstCh..firstCh + 511 -> ch - firstCh
                        ch in 1..512 -> ch - 1
                        ch in 0..511 -> ch
                        else -> -1
                    }
                    if (idx in 0 until 512) out[idx] = bytes[i].toInt() and 0xFF
                }
                bytes.size >= 512 -> {
                    // Universums-Block(e): das gewünschte Universum ist der erste Block der Antwort
                    for (i in 0 until 512) out[i] = bytes[i].toInt() and 0xFF
                }
                else -> for (i in bytes.indices.take(512)) out[i] = bytes[i].toInt() and 0xFF
            }
            _info.value = "Anfrage ${if (mode == 0) "Kanäle" else "Universum"} $start–$stop: " +
                "${bytes.size} Werte, ${chans.size} Kanalnummern" + (chans.firstOrNull()?.let { ", erste Nr. $it" } ?: "")
            // Automatische Erkennung: Kanalmodus liefert nichts Brauchbares -> Universumsmodus probieren
            if (mode == 0 && bytes.isEmpty()) mode = 1
            else if (mode == 1 && bytes.isEmpty()) mode = 0
            if (u == _universe.value) _values.value = out
            delay(200)
        }
    }
}

// ============================================================== System
data class InterfaceState(
    val meta: LumosExtendedDMXInterfaceMetadata, val enabled: Boolean?, val working: Boolean?,
    /** Kommentar je Port aus DMXControl (DMXInterfaceManagementClient.GetComment). */
    val comments: Map<Int, String> = emptyMap()
)

class SystemModule(s: UmbraSession) : Module(s, "System") {
    private val pluginStub = PluginManagementClientCoroutineStub(s.channel)
    private val ifStub = DMXInterfaceManagementClientCoroutineStub(s.channel)
    private val rdmStub = RDMClientCoroutineStub(s.channel)
    private val powerStub = ElectricityClientCoroutineStub(s.channel)

    private val _plugins = MutableStateFlow<List<Pair<PluginMetadata, Boolean?>>>(emptyList())
    val plugins: StateFlow<List<Pair<PluginMetadata, Boolean?>>> = _plugins.asStateFlow()
    private val _interfaces = MutableStateFlow<Map<String, InterfaceState>>(emptyMap())
    val interfaces: StateFlow<Map<String, InterfaceState>> = _interfaces.asStateFlow()
    private val _rdm = MutableStateFlow<List<RDMDeviceDescriptor>>(emptyList())
    val rdm: StateFlow<List<RDMDeviceDescriptor>> = _rdm.asStateFlow()
    private val _power = MutableStateFlow<List<PowerSourceDescriptor>>(emptyList())
    val power: StateFlow<List<PowerSourceDescriptor>> = _power.asStateFlow()
    private val _amps = MutableStateFlow<Map<String, Double>>(emptyMap())
    val amps: StateFlow<Map<String, Double>> = _amps.asStateFlow()

    override suspend fun load() {
        runCatching { loadPlugins() }
        runCatching { loadInterfaces() }
        runCatching { loadRdm() }
        runCatching {
            _power.value = powerStub.dl(10).getPowerSources(s.getMultiple(), s.headers).powerSourcesList
        }
    }

    private suspend fun loadPlugins() {
        val list = pluginStub.dl(10).getPlugins(
            GetPluginsRequest.newBuilder().setRequestId(s.nextRequestId()).build(), s.headers
        ).pluginsList
        _plugins.value = list.map { p ->
            p to runCatching {
                pluginStub.dl(3).isPluginEnabled(
                    EnableDisablePluginRequestResponse.newBuilder().setRequestId(s.nextRequestId()).setPluginId(p.id).build(),
                    s.headers
                ).enabled
            }.getOrNull()
        }
    }

    /** UNVERIFIZIERT: GetInterfaceEnabled erwartet die Interface-ID im requestId-Feld. */
    private suspend fun loadInterfaces() {
        val list = ifStub.dl(10).getUsedInterfaces(s.getMultiple(), s.headers).interfacesList
        _interfaces.value = list.associate { meta ->
            val id = meta.interfaceData.interfaceId
            val en = runCatching {
                ifStub.dl(3).getInterfaceEnabled(GetRequest.newBuilder().setRequestId(id).build(), s.headers).interfaceEnabled
            }.getOrNull()
            id to InterfaceState(meta, en, null, loadComments(meta))
        }
    }

    /** Kommentare aller Ports (ohne Port-Angaben: Port 0). Leere Kommentare werden ausgelassen. */
    private suspend fun loadComments(meta: LumosExtendedDMXInterfaceMetadata): Map<Int, String> {
        val id = meta.interfaceData.interfaceId
        val ports = meta.interfaceData.portMetadataList.map { it.port }.ifEmpty { listOf(0) }.distinct()
        return ports.mapNotNull { port ->
            runCatching {
                ifStub.dl(3).getComment(
                    LumosProtobuf.DMXInterfaceManagementTypes.GetSetDMXInterfaceCommentRequestResponse.newBuilder()
                        .setInterfaceId(id).setPort(port).build(), s.headers
                ).comment
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { port to it }
        }.toMap()
    }

    /** Kommentar eines Ports setzen (wie in DMXControl). */
    fun setComment(id: String, port: Int, comment: String) = act("Kommentar") {
        ifStub.dl(5).setComment(
            LumosProtobuf.DMXInterfaceManagementTypes.GetSetDMXInterfaceCommentRequestResponse.newBuilder()
                .setInterfaceId(id).setPort(port).setComment(comment).build(), s.headers
        ).requireOk("Kommentar")
        _interfaces.update { m -> m[id]?.let { st -> m + (id to st.copy(comments = st.comments + (port to comment))) } ?: m }
    }

    suspend fun loadRdm() {
        _rdm.value = rdmStub.dl(10).getRDMDevices(s.getMultiple(), s.headers).rdmDevicesList
    }

    override suspend fun stream() = coroutineScope {
        launch {
            runCatching {
                powerStub.receivePowerSourceConsumptionChanges(s.getRequest(), s.headers).collect { m ->
                    _amps.update { it + m.changedConsumptionAmpsMap }
                }
            }
        }
        launch {
            runCatching {
                rdmStub.receiveRDMDeviceChanges(s.getRequest(), s.headers).collect { loadRdm() }
            }
        }
        ifStub.receiveInterfaceStateChanges(s.getRequest(), s.headers).collect { m ->
            if (m.type != DMXInterfaceStateChangedMessage.EType.Interface) return@collect
            val cur = _interfaces.value[m.interfaceId] ?: return@collect
            val next = when (m.changeCase) {
                DMXInterfaceStateChangedMessage.ChangeCase.ENABLED -> cur.copy(enabled = m.enabled)
                DMXInterfaceStateChangedMessage.ChangeCase.WORKING -> cur.copy(working = m.working)
                else -> cur
            }
            _interfaces.update { it + (m.interfaceId to next) }
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) = act("Plugin") {
        pluginStub.dl(10).setPluginEnabled(
            EnableDisablePluginRequestResponse.newBuilder().setRequestId(s.nextRequestId()).setPluginId(id).setEnabled(enabled).build(),
            s.headers
        ).requireOk("SetPluginEnabled")
        loadPlugins()
    }

    fun setInterfaceEnabled(id: String, enabled: Boolean) = act("DMX-Interface") {
        val r = ifStub.dl(10).setInterfaceEnabled(
            EnableDisableDMXInterfaceRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setInterfaceId(id)
                .setEnabled(enabled)
                .setTarget(EnableDisableDMXInterfaceRequest.ETarget.INTERFACE)
                .build(),
            s.headers
        )
        _interfaces.update { m -> m[id]?.let { m + (id to it.copy(enabled = r.interfaceEnabled)) } ?: m }
    }

    fun rdmDiscovery() = act("RDM-Discovery") {
        rdmStub.dl(30).discovery(s.getRequest(), s.headers).requireOk("RDM-Discovery")
        loadRdm()
    }

    fun reload() = act("Neu laden") { load() }
}
