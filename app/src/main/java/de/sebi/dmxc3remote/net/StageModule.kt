package de.sebi.dmxc3remote.net

import LumosProtobuf.DeviceServiceTypes.DeviceDescriptor
import LumosProtobuf.DeviceServiceTypes.DevicePropertyDescriptor
import LumosProtobuf.DeviceServiceTypes.DevicePropertyValue
import LumosProtobuf.DeviceServiceTypes.DevicePropertyValueBulkRequest
import LumosProtobuf.DeviceServiceTypes.DevicePropertyValueRequest
import LumosProtobuf.DeviceServiceTypes.DevicePropertyValueResponse
import LumosProtobuf.DeviceServiceTypes.EValueType
import LumosProtobuf.DeviceServiceTypes.GlobalFunctionRequestResponse
import LumosProtobuf.DeviceServiceTypes.HighlightData
import LumosProtobuf.StageViewServiceCRUDTypes.StageViewCreationRequest
import LumosProtobuf.StageViewServiceCRUDTypes.StageViewDeletionRequest
import LumosProtobuf.StageViewServiceCRUDTypes.StageViewDeviceGroupModifyData
import LumosProtobuf.StageViewServiceCRUDTypes.StageViewDeviceModifyData
import LumosProtobuf.StageViewServiceCRUDTypes.StageViewModifyRequest
import LumosProtobuf.StageViewServiceTypes.StageViewDescriptor
import LumosProtobuf.ValueTypes.PointData
import LumosProtobufClient.DeviceClientGrpcKt.DeviceClientCoroutineStub
import LumosProtobufClient.StageViewClientGrpcKt.StageViewClientCoroutineStub
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Aktuelle Ausgabe eines Geräts für die Bühnendarstellung. */
data class LiveOut(val intensity: Float?, val r: Float, val g: Float, val b: Float, val hasColor: Boolean)

/** Bühnenansichten (StageView) mit Gerätepositionen, Live-Ausgabe, Highlight und Bearbeitung. */
class StageModule(s: UmbraSession) : Module(s, "Bühnenansicht") {
    private val stub = StageViewClientCoroutineStub(s.channel)
    private val devStub = DeviceClientCoroutineStub(s.channel)

    private val _views = MutableStateFlow<Map<String, StageViewDescriptor>>(emptyMap())
    val views: StateFlow<Map<String, StageViewDescriptor>> = _views.asStateFlow()

    private val _devices = MutableStateFlow<Map<String, DeviceDescriptor>>(emptyMap())
    val devices: StateFlow<Map<String, DeviceDescriptor>> = _devices.asStateFlow()

    private val _live = MutableStateFlow<Map<String, LiveOut>>(emptyMap())
    val live: StateFlow<Map<String, LiveOut>> = _live.asStateFlow()

    private val _highlight = MutableStateFlow(false)
    val highlight: StateFlow<Boolean> = _highlight.asStateFlow()

    /** Live-Abfrage nur, solange die Bühne sichtbar ist und Live eingeschaltet ist. */
    private val liveFor = MutableStateFlow<List<String>>(emptyList())

    fun setLiveDevices(ids: List<String>) {
        liveFor.value = ids
    }

    override suspend fun load() {
        _views.value = stub.dl(15).getStageViews(s.getMultiple(), s.headers).stageViewsList.associateBy { it.id }
        _devices.value = runCatching {
            devStub.dl(20).getDevices(s.getMultiple(), s.headers).devicesList
                .flatMap { listOf(it) + it.subdevicesList }.associateBy { it.id }
        }.getOrDefault(emptyMap())
    }

    override suspend fun stream() = coroutineScope {
        launch { livePoll() }
        stub.receiveStageViewChanges(s.getRequest(), s.headers).collect { m ->
            if (m.changeType == LumosProtobuf.CommonTypes.EChangeType.Changed && !m.hasStageViewData() && m.stageViewId.isNotBlank()) {
                reloadView(m.stageViewId)
            } else {
                _views.applyChange(m.changeType, m.stageViewId, m.hasStageViewData(), { m.stageViewData }, { it.id })
            }
        }
    }

    private suspend fun reloadView(id: String) {
        stub.dl(10).getStageViews(s.getMultiple(listOf(id)), s.headers).stageViewsList
            .forEach { v -> _views.update { it + (v.id to v) } }
    }

    // ---------------------------------------------------------------- Live-Ausgabe
    /** Dimmer- und Farbeigenschaft des ersten Beams (Heuristik über Namen und Wertetyp). */
    private fun liveProps(d: DeviceDescriptor): Pair<DevicePropertyDescriptor?, DevicePropertyDescriptor?> {
        val props = d.beamsList.flatMap { it.propertiesList }
        val dimmer = props.firstOrNull { p ->
            p.kind() == PropKind.DOUBLE && listOf(p.id, p.name, p.displayName).any {
                it.contains("dimmer", true) || it.contains("intensity", true) || it.contains("helligkeit", true)
            }
        }
        val color = props.firstOrNull { it.kind() == PropKind.COLOR }
        return dimmer to color
    }

    /**
     * Abfrage per GetDevicePropertiesCurrentValues (Typ CURRENT_OUTPUT) alle 400 ms als Sammelabfrage.
     * Der Stream ReceiveDevicePropertyChanges wird nicht genutzt: das ID-Format der Anmeldung ist undokumentiert.
     */
    private suspend fun livePoll() {
        while (true) {
            val ids = liveFor.value
            if (ids.isEmpty()) {
                delay(500); continue
            }
            val reqs = ArrayList<DevicePropertyValueRequest>()
            val map = HashMap<Int, Triple<String, Boolean, DevicePropertyDescriptor>>() // Index -> (Gerät, istFarbe, Eigenschaft)
            ids.forEach { id ->
                val d = _devices.value[id] ?: return@forEach
                val (dim, col) = liveProps(d)
                listOfNotNull(dim?.let { it to false }, col?.let { it to true }).forEach { (p, isColor) ->
                    map[reqs.size] = Triple(id, isColor, p)
                    reqs += DevicePropertyValueRequest.newBuilder()
                        .setDeviceOrGroupId(id).setPropertyId(p.id).setType(EValueType.CURRENT_OUTPUT).build()
                }
            }
            if (reqs.isNotEmpty()) {
                val resp = devStub.dl(5).getDevicePropertiesCurrentValues(
                    DevicePropertyValueBulkRequest.newBuilder().setRequestId(s.nextRequestId()).addAllRequests(reqs).build(),
                    s.headers
                )
                val next = HashMap(_live.value)
                resp.responsesList.forEachIndexed { i, r ->
                    val (id, isColor, p) = map[i] ?: return@forEachIndexed
                    val v = dpvOf(r) ?: return@forEachIndexed
                    val cur = next[id] ?: LiveOut(null, 1f, 1f, 1f, false)
                    next[id] = if (isColor && v.valueCase == DevicePropertyValue.ValueCase.COLOR) {
                        val c = v.color
                        if (c.hasVisualizationColor()) cur.copy(r = c.visualizationColor.r / 255f, g = c.visualizationColor.g / 255f, b = c.visualizationColor.b / 255f, hasColor = true)
                        else if (c.hasCalculatedColor()) cur.copy(r = c.calculatedColor.r / 255f, g = c.calculatedColor.g / 255f, b = c.calculatedColor.b / 255f, hasColor = true)
                        else {
                            val mx = p.colorMax().toFloat()
                            cur.copy(r = (c.r / mx).toFloat(), g = (c.g / mx).toFloat(), b = (c.b / mx).toFloat(), hasColor = true)
                        }
                    } else if (!isColor && v.valueCase == DevicePropertyValue.ValueCase.DOUBLEVALUE) {
                        val rg = p.doubleRange()
                        cur.copy(intensity = ((v.doubleValue - rg.start) / (rg.endInclusive - rg.start)).toFloat().coerceIn(0f, 1f))
                    } else cur
                }
                _live.value = next
            }
            delay(400)
        }
    }

    private fun dpvOf(r: DevicePropertyValueResponse): DevicePropertyValue? = when (r.pvCase) {
        DevicePropertyValueResponse.PvCase.DPV -> r.dpv
        DevicePropertyValueResponse.PvCase.PROPERTYVALUE -> if (r.propertyValue.hasDpv()) r.propertyValue.dpv else null
        else -> null
    }

    // ---------------------------------------------------------------- Highlight
    /** UNVERIFIZIERT: Highlight über GetSetGlobalFunction für die aktuelle Auswahl des UserContexts. */
    fun setHighlight(on: Boolean) = act("Highlight") {
        val r = devStub.dl(3).getSetGlobalFunction(
            GlobalFunctionRequestResponse.newBuilder()
                .setRequestId(s.nextRequestId())
                .setSetValue(true)
                .setHighlight(HighlightData.newBuilder().setUserContextId(s.userContextId).setEnabled(on))
                .build(),
            s.headers
        )
        _highlight.value = if (r.hasHighlight() && r.highlight.valueCase == HighlightData.ValueCase.ENABLED) r.highlight.enabled else on
    }

    // ---------------------------------------------------------------- Bearbeiten
    fun moveDevice(viewId: String, deviceId: String, x: Float, y: Float) = act("Gerät verschieben") {
        stub.dl(5).modifyStageView(
            StageViewModifyRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setStageViewId(viewId)
                .setType(StageViewModifyRequest.EModType.DEVICE)
                .setDevice(
                    StageViewDeviceModifyData.newBuilder()
                        .setType(StageViewDeviceModifyData.EModType.POSITION)
                        .setDeviceId(deviceId)
                        .setPosition(PointData.newBuilder().setX(x).setY(y))
                )
                .build(),
            s.headers
        ).requireOk("Position")
        reloadView(viewId)
    }

    fun moveGroup(viewId: String, groupId: String, x: Float, y: Float) = act("Gruppe verschieben") {
        stub.dl(5).modifyStageView(
            StageViewModifyRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setStageViewId(viewId)
                .setType(StageViewModifyRequest.EModType.DEVICE_GROUP)
                .setDeviceGroup(
                    StageViewDeviceGroupModifyData.newBuilder()
                        .setType(StageViewDeviceGroupModifyData.EModType.POSITION)
                        .setDeviceGroupId(groupId)
                        .setPosition(PointData.newBuilder().setX(x).setY(y))
                )
                .build(),
            s.headers
        ).requireOk("Position")
        reloadView(viewId)
    }

    fun createView(copyFrom: String?, name: String) = act("Ansicht anlegen") {
        val b = StageViewCreationRequest.newBuilder().setRequestId(s.nextRequestId()).setStageViewNameTemplate(name)
        if (copyFrom != null) b.setCopyFromStageView(copyFrom)
        stub.dl(10).createStageView(b.build(), s.headers).requireOk("CreateStageView")
        load()
    }

    fun deleteView(id: String) = act("Ansicht löschen") {
        val r = stub.dl(10).deleteStageView(
            StageViewDeletionRequest.newBuilder().setRequestId(s.nextRequestId()).setStageViewId(id).build(), s.headers
        )
        if (r.error.isNotBlank()) throw UmbraException(r.error)
        _views.update { it - id }
    }
}
