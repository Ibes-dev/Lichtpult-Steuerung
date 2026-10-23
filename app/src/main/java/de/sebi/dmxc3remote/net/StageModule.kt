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
data class LiveOut(
    val intensity: Float?, val r: Float, val g: Float, val b: Float, val hasColor: Boolean,
    /** Pan/Tilt in Grad relativ zur Mitte des Gerätebereichs (null = kein Moving Head). */
    val pan: Double? = null, val tilt: Double? = null,
    /** Zoom 0..1 innerhalb des Gerätebereichs. */
    val zoom: Float? = null,
    /** Gobo eingesetzt (nicht "offen") und dessen Name. */
    val gobo: String? = null,
    /** Strobe aktiv (Geschwindigkeit > 0). */
    val strobe: Float? = null
)

/** Für die Live-Abfrage gefundene Eigenschaften eines Geräts. */
private data class LiveProps(
    val dimmer: DevicePropertyDescriptor?, val color: DevicePropertyDescriptor?,
    val position: DevicePropertyDescriptor?, val zoom: DevicePropertyDescriptor?,
    val gobo: DevicePropertyDescriptor?, val strobe: DevicePropertyDescriptor?
)

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

    /** Zusätzlich Position, Zoom, Gobo und Strobe abfragen (3D-Ansicht). */
    @Volatile private var extended = false

    fun setLiveDevices(ids: List<String>, extended: Boolean = false) {
        this.extended = extended
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
    private fun words(p: DevicePropertyDescriptor) = listOf(p.id, p.name, p.displayName, p.type.name).joinToString(" ").lowercase()

    /** Relevante Eigenschaften des Geräts (alle Beams, erste passende je Art). */
    private fun liveProps(d: DeviceDescriptor): LiveProps {
        val props = d.beamsList.flatMap { it.propertiesList }
        return LiveProps(
            dimmer = props.firstOrNull { p -> p.kind() == PropKind.DOUBLE && words(p).let { "dimmer" in it || "intensity" in it || "helligkeit" in it } },
            color = props.firstOrNull { it.kind() == PropKind.COLOR },
            position = props.firstOrNull { it.kind() == PropKind.POSITION },
            zoom = props.firstOrNull { p -> (p.kind() == PropKind.DOUBLE) && "zoom" in words(p) },
            gobo = props.firstOrNull { p -> "gobo" in words(p) },
            strobe = props.firstOrNull { p -> p.kind() == PropKind.STROBE || ("strobe" in words(p) && p.kind() == PropKind.DOUBLE) }
        )
    }

    private enum class What { DIMMER, COLOR, POSITION, ZOOM, GOBO, STROBE }

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
            val t0 = System.currentTimeMillis()
            val ext = extended
            val reqs = ArrayList<DevicePropertyValueRequest>()
            val map = HashMap<Int, Triple<String, What, DevicePropertyDescriptor>>()
            ids.forEach { id ->
                val d = _devices.value[id] ?: return@forEach
                val lp = liveProps(d)
                val wanted = buildList {
                    lp.dimmer?.let { add(What.DIMMER to it) }
                    lp.color?.let { add(What.COLOR to it) }
                    if (ext) {
                        lp.position?.let { add(What.POSITION to it) }
                        lp.zoom?.let { add(What.ZOOM to it) }
                        lp.gobo?.let { add(What.GOBO to it) }
                        lp.strobe?.let { add(What.STROBE to it) }
                    }
                }
                wanted.forEach { (w, p) ->
                    map[reqs.size] = Triple(id, w, p)
                    reqs += DevicePropertyValueRequest.newBuilder()
                        .setDeviceOrGroupId(id).setPropertyId(p.id).setType(EValueType.CURRENT_OUTPUT).build()
                }
            }
            if (reqs.isNotEmpty()) {
                val resp = runCatching {
                    devStub.dl(5).getDevicePropertiesCurrentValues(
                        DevicePropertyValueBulkRequest.newBuilder().setRequestId(s.nextRequestId()).addAllRequests(reqs).build(),
                        s.headers
                    )
                }.getOrNull()
                if (resp != null) {
                    val next = HashMap(_live.value)
                    val cases = HashMap<String, Int>()
                    resp.responsesList.forEachIndexed { i, r ->
                        val v = dpvOf(r)
                        val caseName = v?.valueCase?.name ?: r.pvCase.name
                        cases[caseName] = (cases[caseName] ?: 0) + 1
                        if (v == null) return@forEachIndexed
                        val byId = map.values.firstOrNull { it.first == v.deviceId && it.third.id == v.propertyId }
                        val (id, what, p) = byId ?: map[i] ?: return@forEachIndexed
                        val cur = next[id] ?: LiveOut(null, 1f, 1f, 1f, false)
                        next[id] = when (what) {
                            What.COLOR -> if (v.valueCase == DevicePropertyValue.ValueCase.COLOR)
                                colorOf(v.color, p)?.let { (cr, cg, cb) -> cur.copy(r = cr, g = cg, b = cb, hasColor = true) } ?: cur else cur
                            What.DIMMER -> if (v.valueCase == DevicePropertyValue.ValueCase.DOUBLEVALUE) {
                                val rg = p.doubleRange()
                                cur.copy(intensity = ((v.doubleValue - rg.start) / (rg.endInclusive - rg.start)).toFloat().coerceIn(0f, 1f))
                            } else cur
                            What.POSITION -> if (v.valueCase == DevicePropertyValue.ValueCase.POSITION) {
                                val rg = p.positionRange()
                                cur.copy(
                                    pan = v.position.pan - (rg.pan.start + rg.pan.endInclusive) / 2,
                                    tilt = v.position.tilt - (rg.tilt.start + rg.tilt.endInclusive) / 2
                                )
                            } else cur
                            What.ZOOM -> if (v.valueCase == DevicePropertyValue.ValueCase.DOUBLEVALUE) {
                                val rg = p.doubleRange()
                                cur.copy(zoom = ((v.doubleValue - rg.start) / (rg.endInclusive - rg.start)).toFloat().coerceIn(0f, 1f))
                            } else cur
                            What.GOBO -> when (v.valueCase) {
                                DevicePropertyValue.ValueCase.GOBO -> {
                                    val g = v.gobo.gobovaluesMap.values.firstOrNull()
                                    cur.copy(gobo = if (g == null || g.open) null else g.caption.ifBlank { g.goboname.ifBlank { "Gobo" } })
                                }
                                else -> cur
                            }
                            What.STROBE -> when (v.valueCase) {
                                DevicePropertyValue.ValueCase.STROBE -> cur.copy(strobe = v.strobe.speed.toFloat().takeIf { it > 0f })
                                DevicePropertyValue.ValueCase.DOUBLEVALUE -> cur.copy(strobe = v.doubleValue.toFloat().takeIf { it > 0f })
                                else -> cur
                            }
                        }
                    }
                    _live.value = next
                    val sig = ids.hashCode() * 31 + if (ext) 1 else 0
                    if (sig != loggedFor) {
                        loggedFor = sig
                        Diag.note("Bühne live: ${reqs.size} Abfragen, Antworten: " + cases.entries.joinToString { "${it.key}=${it.value}" })
                    }
                }
            }
            // 3D: so schnell, wie DMXControl antwortet, höchstens ~16 Abfragen pro Sekunde;
            // 2D-Bühne: 400 ms reichen für Farbe und Helligkeit
            val took = System.currentTimeMillis() - t0
            delay(if (ext) (60 - took).coerceAtLeast(5) else 400)
        }
    }

    private var loggedFor = 0

    /**
     * Anzeigefarbe aus LumosColorData: erste nicht schwarze Quelle aus
     * Visualisierungsfarbe, berechneter Farbe, RGB-Werten oder gewähltem Farbrad-Eintrag.
     */
    private fun colorOf(c: LumosProtobuf.ValueTypes.LumosColorData, p: DevicePropertyDescriptor): Triple<Float, Float, Float>? {
        fun cd(x: LumosProtobuf.ValueTypes.ColorData) = Triple(x.r / 255f, x.g / 255f, x.b / 255f)
        val candidates = ArrayList<Triple<Float, Float, Float>>()
        if (c.hasVisualizationColor()) candidates += cd(c.visualizationColor)
        if (c.hasCalculatedColor()) candidates += cd(c.calculatedColor)
        val mx = p.colorMax().toFloat()
        candidates += Triple((c.r / mx).toFloat(), (c.g / mx).toFloat(), (c.b / mx).toFloat())
        c.colorwheelMap.values.firstOrNull { it.hasColor() }?.let { candidates += cd(it.color) }
        return candidates.firstOrNull { it.first + it.second + it.third > 0.02f }
            ?: candidates.firstOrNull()?.takeIf { c.hasVisualizationColor() || c.hasCalculatedColor() }
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
