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

    private suspend fun query() {
        _state.value = stub.dl(5).getProjectState(s.getRequest(), s.headers).state
        _dirty.value = stub.dl(5).getProjectDirty(
            ProjectDirtyRequest.newBuilder().setRequestId(s.nextRequestId()).setQuickResponse(true).build(),
            s.headers
        ).dirty
    }

    override suspend fun load() = query()

    /** Kein Push-Stream für Clients ohne Projektdaten -> Polling alle 5 s. */
    override suspend fun stream() {
        while (true) {
            delay(5_000)
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

    /**
     * Polling nur bei sichtbarem Monitor (200 ms).
     * UNVERIFIZIERT: dmxOutStart/Stop als absolute, 0-basierte Kanalnummern über alle Universen.
     */
    override suspend fun stream() {
        while (true) {
            if (!active.value) {
                delay(300)
                continue
            }
            val u = _universe.value
            val start = (u - 1) * 512
            val r = stub.dl(3).getDmxValues(
                GetDMXRequest.newBuilder()
                    .setRequestId(s.nextRequestId())
                    .setDmxOutStart(start)
                    .setDmxOutStop(start + 511)
                    .build(),
                s.headers
            )
            val bytes = r.dmxValues.toByteArray()
            val out = IntArray(512)
            if (r.dmxChannelsCount == bytes.size && bytes.isNotEmpty()) {
                r.dmxChannelsList.forEachIndexed { i, ch ->
                    val idx = ch - start
                    if (idx in 0 until 512) out[idx] = bytes[i].toInt() and 0xFF
                }
            } else {
                for (i in 0 until minOf(512, bytes.size)) out[i] = bytes[i].toInt() and 0xFF
            }
            if (u == _universe.value) _values.value = out
            delay(200)
        }
    }
}

// ============================================================== System
data class InterfaceState(val meta: LumosExtendedDMXInterfaceMetadata, val enabled: Boolean?, val working: Boolean?)

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
            id to InterfaceState(meta, en, null)
        }
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
