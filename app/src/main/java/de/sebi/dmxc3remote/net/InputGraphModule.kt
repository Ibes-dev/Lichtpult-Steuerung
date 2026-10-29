package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.ObjectData
import LumosProtobuf.CommonTypes.GetMultipleRequest
import LumosProtobuf.Input.InputServiceCRUDTypes.ConnectionSetCreationRequest
import LumosProtobuf.Input.InputServiceCRUDTypes.ConnectionSetDeletionRequest
import LumosProtobuf.Input.InputServiceCRUDTypes.ConnectionSetModifyRequest
import LumosProtobuf.Input.InputServiceCRUDTypes.PortCreationOrReplaceRequest
import LumosProtobuf.Input.InputServiceTypes.BankDescriptor
import LumosProtobuf.Input.InputServiceTypes.ConnectionSetDescriptor
import LumosProtobuf.Input.InputServiceTypes.ENodeType
import LumosProtobuf.Input.InputServiceTypes.GraphNodeIDData
import LumosProtobuf.Input.InputServiceTypes.GraphNodePortIDData
import LumosProtobuf.Input.InputServiceTypes.InputElementDescriptor
import LumosProtobuf.Input.InputServiceTypes.NodeMetadataData
import LumosProtobuf.ParameterServiceTypes.GetParametersRequest
import LumosProtobuf.ParameterServiceTypes.SetTestParameterValueRequest
import LumosProtobuf.ValueTypes.PointData
import LumosProtobufClient.InputClientGrpcKt.InputClientCoroutineStub
import LumosProtobufClient.ParameterClientGrpcKt.ParameterClientCoroutineStub
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Verbindung zwischen zwei Anschlüssen (Ausgang → Eingang). */
data class GraphEdge(val from: GraphNodePortIDData, val to: GraphNodePortIDData) {
    val key get() = "${portKey(from)}>${portKey(to)}"
}

fun portKey(p: GraphNodePortIDData) = "${p.nodeId.nodeId}/${p.portId}"

/**
 * Verbindungen eines Sets aus den Anschlüssen: jede Verbindung steht bei beiden Enden in
 * connectedPorts und wird hier nur einmal geliefert, immer als Ausgang → Eingang.
 */
fun graphEdges(set: ConnectionSetDescriptor): List<GraphEdge> {
    val out = LinkedHashMap<String, GraphEdge>()
    set.nodesList.forEach { n -> n.portsList.forEach { p ->
        p.connectedPortsList.forEach { o ->
            val e = if (p.isInput) GraphEdge(o, p.id) else GraphEdge(p.id, o)
            out[e.key] = e
        }
    } }
    return out.values.toList()
}

/** Anwählbares Element für neue Ein-/Ausgänge (aus GetInputSources/Sinks) bzw. Baustein (GetAvailableNodes). */
data class GraphCatalogItem(val id: String, val name: String, val category: String)

/**
 * Eingangszuweisung (DMXControl "Input Assignment"): Bänke, Verbindungs-Sets mit ihrem Graphen,
 * Live-Werte der Anschlüsse, Verbinden/Lösen, Bausteine hinzufügen/entfernen.
 *
 * Für Aktiv-Schalter und Baustein-Werte gibt es keine eigenen Aufrufe; sie laufen – soweit
 * DMXControl sie anbietet – über die Parameter-Schnittstelle. Welche Typnamen dort gelten, ist nicht
 * dokumentiert; die App probiert und protokolliert (Diagnose).
 */
class InputGraphModule(s: UmbraSession) : Module(s, "Eingangszuweisung") {
    private val stub = InputClientCoroutineStub(s.channel)
    private val par = ParameterClientCoroutineStub(s.channel)

    private val _banks = MutableStateFlow<List<BankDescriptor>>(emptyList())
    val banks: StateFlow<List<BankDescriptor>> = _banks.asStateFlow()

    private val _sets = MutableStateFlow<Map<String, ConnectionSetDescriptor>>(emptyMap())
    val sets: StateFlow<Map<String, ConnectionSetDescriptor>> = _sets.asStateFlow()

    /** Live-Werte je Anschluss ("Knoten/Anschluss" → Wert) des gerade beobachteten Sets. */
    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    private val _workers = MutableStateFlow<List<GraphCatalogItem>>(emptyList())
    val workers: StateFlow<List<GraphCatalogItem>> = _workers.asStateFlow()
    private val _sources = MutableStateFlow<List<GraphCatalogItem>>(emptyList())
    val sources: StateFlow<List<GraphCatalogItem>> = _sources.asStateFlow()
    private val _sinks = MutableStateFlow<List<GraphCatalogItem>>(emptyList())
    val sinks: StateFlow<List<GraphCatalogItem>> = _sinks.asStateFlow()

    /** Parameter eines Bausteins (nodeId → Parameter), soweit DMXControl sie liefert. */
    private val _nodeParams = MutableStateFlow<Map<String, List<DevParam>>>(emptyMap())
    val nodeParams: StateFlow<Map<String, List<DevParam>>> = _nodeParams.asStateFlow()

    private val watched = MutableStateFlow<String?>(null)
    fun watch(setId: String?) { watched.value = setId; if (setId == null) _values.value = emptyMap() }

    private fun catPath(c: LumosProtobuf.ParameterServiceTypes.ParameterCategory?): String {
        val parts = ArrayList<String>(); var x = c
        while (x != null && x.name.isNotBlank()) { parts += x.name; x = if (x.hasSubCategory()) x.subCategory else null }
        return parts.joinToString(" › ")
    }
    private fun item(e: InputElementDescriptor) = GraphCatalogItem(e.id, e.displayName.ifBlank { e.id }, catPath(if (e.hasCategory()) e.category else null).ifBlank { "Weitere" })

    override suspend fun load() {
        _banks.value = stub.dl(10).getBanks(s.getMultiple(), s.headers).banksList.sortedBy { it.number }
        reloadSets(emptyList())
        _workers.value = runCatching {
            stub.dl(10).getAvailableNodes(s.getRequest(), s.headers).nodesList
                .filter { it.type == ENodeType.WORKER || it.type == ENodeType.UNKNOWN }
                .map { GraphCatalogItem(it.typeId, it.name.ifBlank { it.typeId }, catPath(if (it.hasCategory()) it.category else null).ifBlank { "Bausteine" }) }
                .sortedWith(compareBy({ it.category }, { it.name }))
        }.getOrDefault(emptyList())
        _sources.value = runCatching { stub.dl(10).getInputSources(s.getMultiple(), s.headers).sourcesList.map { item(it.element) } }.getOrDefault(emptyList())
        _sinks.value = runCatching { stub.dl(10).getInputSinks(s.getMultiple(), s.headers).sinksList.map { item(it.element) } }.getOrDefault(emptyList())
        val st = _sets.value.values.joinToString { "${it.name}: Zustand ${it.state}, ${it.nodesCount} Knoten${if (it.containsNodes) "" else " (ohne Knotendaten)"}" }
        Diag.note("Eingangszuweisung: ${_banks.value.size} Bänke, ${_sets.value.size} Sets ($st), ${_workers.value.size} Bausteintypen")
    }

    /** Sets laden (leer = alle); Sets ohne Knoten werden einzeln mit Knoten nachgeladen. */
    private suspend fun reloadSets(ids: List<String>) {
        val r = stub.dl(15).getConnectionSets(GetMultipleRequest.newBuilder().setRequestId(s.nextRequestId()).addAllIdFilter(ids).build(), s.headers)
        val got = r.connectionSetsList.associateBy { it.id }
        _sets.update { if (ids.isEmpty()) got else it + got }
    }

    suspend fun reloadSet(id: String) = runCatching { reloadSets(listOf(id)) }

    override suspend fun stream() = coroutineScope {
        launch { runCatching { stub.receiveBankChanges(s.getRequest(), s.headers).collect { runCatching { _banks.value = stub.dl(10).getBanks(s.getMultiple(), s.headers).banksList.sortedBy { b -> b.number } } } } }
        launch { runCatching { stub.receiveConnectionSetChanges(s.getRequest(), s.headers).collect { m ->
            if (m.changeCase != LumosProtobuf.Input.InputServiceCRUDTypes.ConnectionSetChangedMessage.ChangeCase.CHANGE_NOT_SET)
                Diag.note("Eingangszuweisung Änderung: ${m.changeCase} = ${m.property}${m.parameter}${m.nodeParameter} (Knoten ${m.nodeId.nodeId.take(8)}, Wert ${m.newValue.text()})")
            if (m.hasSet() && m.set.containsNodes) _sets.update { it + (m.set.id to m.set) }
            else if (m.connectionSetId.isNotBlank()) reloadSet(m.connectionSetId)
        } } }
        // Live-Werte des geöffneten Sets
        var logged = false
        while (true) {
            val id = watched.value
            if (id == null) { delay(400); continue }
            val r = runCatching {
                stub.dl(3).getConnectionSetPreviewValues(
                    LumosProtobuf.CommonTypes.GetRequest.newBuilder().setRequestId(id).setUserContextId(s.userContextId).build(), s.headers)
            }.getOrNull()
            if (r != null && !r.notFound) {
                _values.value = r.valuesList.associate { portKey(it.portId) to it.value }
                if (!logged) { logged = true; Diag.note("Eingangszuweisung Live-Werte: ${r.valuesCount} Anschlüsse") }
            } else if (r != null && !logged) { logged = true; Diag.note("Eingangszuweisung Live-Werte: Set nicht gefunden (requestId = Set-ID?)") }
            delay(400)
        }
    }

    // ================================================================ Graph lesen
    fun edges(set: ConnectionSetDescriptor): List<GraphEdge> = graphEdges(set)

    // ================================================================ Bearbeiten
    private suspend fun modify(setId: String, f: (ConnectionSetModifyRequest.Builder) -> Unit): LumosProtobuf.Input.InputServiceCRUDTypes.ConnectionSetModifyResponse {
        val b = ConnectionSetModifyRequest.newBuilder().setRequestId(s.nextRequestId()).setConnectionSetId(setId)
        f(b)
        val r = stub.dl(5).modifyConnectionSet(b.build(), s.headers)
        if (r.connectionSetNotFound) throw UmbraException("Verbindungs-Set nicht gefunden")
        reloadSet(setId)
        return r
    }

    fun connect(setId: String, from: GraphNodePortIDData, to: GraphNodePortIDData) = act("Verbinden") {
        val r = modify(setId) { it.setEdgeToCreate(ConnectionSetModifyRequest.EdgeData.newBuilder().setFrom(from).setTo(to)) }
        if (!r.edgeCreated) throw UmbraException("DMXControl hat die Verbindung nicht angelegt (Typen passen evtl. nicht)")
    }

    /** Neues Element mit einem bestehenden Anschluss verbinden (erster passender Anschluss). */
    fun connectToNew(setId: String, from: LumosProtobuf.Input.InputServiceTypes.PortMetadataData, nodeId: GraphNodeIDData) = act("Verbinden") {
        reloadSet(setId)
        val n = _sets.value[setId]?.nodesList?.firstOrNull { it.id.nodeId == nodeId.nodeId } ?: return@act
        val target = n.portsList.firstOrNull { it.isInput == !from.isInput } ?: return@act
        val r = modify(setId) {
            it.setEdgeToCreate(ConnectionSetModifyRequest.EdgeData.newBuilder()
                .setFrom(if (from.isInput) target.id else from.id).setTo(if (from.isInput) from.id else target.id))
        }
        if (!r.edgeCreated) throw UmbraException("Neues Element angelegt, Verbindung aber nicht möglich")
    }

    fun disconnect(setId: String, e: GraphEdge) = act("Lösen") {
        val r = modify(setId) { it.setEdgeToDelete(ConnectionSetModifyRequest.EdgeData.newBuilder().setFrom(e.from).setTo(e.to)) }
        if (!r.edgeDeleted) throw UmbraException("DMXControl hat die Verbindung nicht gelöst")
    }

    fun deleteNode(setId: String, node: GraphNodeIDData) = act("Baustein entfernen") {
        val r = modify(setId) { it.setNodeToDelete(node) }
        if (!r.nodeDeleted) throw UmbraException("DMXControl hat den Baustein nicht entfernt")
    }

    /** Baustein (Worker) anlegen; liefert die neue Knoten-ID über [onCreated]. */
    fun addWorker(setId: String, typeId: String, x: Float, y: Float, onCreated: (GraphNodeIDData) -> Unit = {}) = act("Baustein anlegen") {
        val r = modify(setId) { it.setNodeToCreateTypeId(typeId).setPositionToAdd(PointData.newBuilder().setX(x).setY(y)) }
        if (!r.hasNodeCreated()) throw UmbraException("DMXControl hat den Baustein nicht angelegt")
        onCreated(r.nodeCreated)
    }

    /** Eingang (Quelle) oder Ausgang (Ziel) als Knoten anlegen. */
    fun addPort(setId: String, elementId: String, isSource: Boolean, x: Float, y: Float, onCreated: (GraphNodeIDData) -> Unit = {}) = act("Anschluss anlegen") {
        val b = PortCreationOrReplaceRequest.newBuilder().setRequestId(s.nextRequestId()).setConnectionSetId(setId)
            .setPositionToAdd(PointData.newBuilder().setX(x).setY(y))
        if (isSource) b.setSourceOrElementIdToCreate(elementId) else b.setSinkOrElementIdToCreate(elementId)
        val r = stub.dl(5).createOrReplacePort(b.build(), s.headers)
        if (r.connectionSetNotFound) throw UmbraException("Verbindungs-Set nicht gefunden")
        reloadSet(setId)
        if (r.hasCreationResult()) onCreated(r.creationResult.nodeId)
        else if (r.hasReplacementResult()) onCreated(r.replacementResult)
    }

    fun createSet(bankId: String, name: String) = act("Set anlegen") {
        val r = stub.dl(10).createConnectionSet(
            ConnectionSetCreationRequest.newBuilder().setRequestId(s.nextRequestId()).setTargetBankId(bankId).setConnectionSetNameTemplate(name).build(), s.headers)
        if (!r.ok) throw UmbraException(r.message.ifBlank { "Set nicht angelegt" })
        load()
    }

    fun deleteSet(id: String) = act("Set löschen") {
        stub.dl(10).deleteConnectionSet(ConnectionSetDeletionRequest.newBuilder().setRequestId(s.nextRequestId()).addConnectionSetIds(id).build(), s.headers).requireOk("Set löschen")
        _sets.update { it - id }
        _banks.value = runCatching { stub.dl(10).getBanks(s.getMultiple(), s.headers).banksList.sortedBy { it.number } }.getOrDefault(_banks.value)
    }

    // ================================================================ Parameter (Aktiv, Baustein-Werte)
    /** Funktionierender Parameter-Typname je Objektart, z. B. "set" → "ConnectionSet". */
    private val typeFor = HashMap<String, String>()

    /** Funktionierende Objekt-ID-Schreibweise je Objektart (Index in den Kandidaten). */
    private val idFor = HashMap<String, String>()

    /**
     * Parameter einer Objektart suchen: alle Kombinationen aus Typnamen und ID-Schreibweisen,
     * bis DMXControl Parameter liefert. Fehlermeldungen von DMXControl werden protokolliert –
     * sie verraten oft den erwarteten Typ.
     */
    /** Objektarten, für die DMXControl in dieser Sitzung keine Parameter liefert (nicht erneut durchprobieren). */
    private val unavailable = HashSet<String>()

    private suspend fun params(kind: String, candidates: List<String>, id: String, idVariants: List<String> = listOf(id)): Pair<String, List<DevParam>>? {
        if (kind in unavailable) return null
        val types = (listOfNotNull(typeFor[kind]) + candidates).filter { it.isNotBlank() }.distinct()
        val ids = idVariants.distinct()
        val errors = LinkedHashSet<String>()
        for (t in types) for (oid in ids) {
            val r = runCatching {
                par.dl(5).getParameters(GetParametersRequest.newBuilder().setType(t).setId(oid).setIncludingValues(true).setUserContextId(s.userContextId).build(), s.headers)
            }
            val resp = r.getOrNull()
            if (resp == null) { r.exceptionOrNull()?.message?.let { errors += "$t: ${it.take(120)}" }; continue }
            if (resp.parametersCount > 0) {
                if (typeFor[kind] != t) Diag.note("Eingangszuweisung: Parameter für $kind = Typ \"$t\", ID-Form ${ids.indexOf(oid)} (${resp.parametersList.joinToString { it.name }})")
                typeFor[kind] = t; idFor[kind + id] = oid
                return t to resp.parametersList.map { DevParam(it, resp.parameterValuesMap[it.name]) }
            }
        }
        Diag.note("Eingangszuweisung: keine Parameter für $kind unter ${types.size} Typen × ${ids.size} ID-Formen" +
            if (errors.isNotEmpty()) "; Meldungen: " + errors.take(4).joinToString(" | ") else "")
        // Nur merken, wenn DMXControl ohne Fehler leer geantwortet hat (Schnittstelle bietet es nicht an)
        if (errors.isEmpty() && typeFor[kind] == null) unavailable += kind
        return null
    }

    /** Typnamen-Kandidaten für Bausteine der Eingangszuweisung. */
    private fun nodeTypes(node: NodeMetadataData) = listOf(
        "Node", "GraphNode", "InputNode", "InputGraphNode", "LumosGraphNode", "GraphNodeBase", "ConnectionSetNode", "InputAssignmentNode",
        "Worker", "InputWorker", "Source", "Sink", "InputSource", "InputSink",
        node.typeId, node.typeId.substringAfterLast('.'), node.typeId.substringAfterLast('.').removeSuffix("Node")
    )

    private fun nodeIds(node: NodeMetadataData) = listOf(
        node.id.nodeId, "${node.id.graphId}/${node.id.nodeId}", "${node.id.graphId}:${node.id.nodeId}",
        "${node.id.graphId}.${node.id.nodeId}", "${node.id.graphId}|${node.id.nodeId}"
    )

    private suspend fun setParam(type: String, id: String, p: DevParam, v: ObjectData) {
        par.dl(5).setParameterValue(SetTestParameterValueRequest.newBuilder().setId(id).setParameter(p.desc).setUserContextId(s.userContextId).setValue(v).build(), s.headers)
            .requireOk(p.label)
    }

    private fun activeParam(list: List<DevParam>) = list.firstOrNull { p ->
        val n = (p.desc.name + " " + p.desc.displayName).lowercase()
        ("active" in n || "aktiv" in n || "enabled" in n) && (p.value?.valueCase == ObjectData.ValueCase.BOOLVALUE || "boolean" in p.desc.parameterValueTypeAQ.lowercase())
    }

    /** Set ein-/ausschalten (Parameter "Active" des Sets). */
    fun setSetActive(id: String, on: Boolean) = act("Set aktiv") {
        val (t, list) = params("set", listOf("ConnectionSet", "InputConnectionSet", "Graph", "InputGraph", "LumosGraph"), id) ?: throw UmbraException("DMXControl bietet für Sets keinen Aktiv-Schalter über die Schnittstelle")
        val p = activeParam(list) ?: throw UmbraException("Kein Aktiv-Parameter für Sets gefunden")
        setParam(t, id, p, ObjectData.newBuilder().setBoolValue(on).build())
        reloadSet(id)
    }

    /** Bank ein- oder ausschalten. */
    fun setBankActive(id: String, on: Boolean = true) = act(if (on) "Bank aktivieren" else "Bank deaktivieren") {
        val (t, list) = params("bank", listOf("Bank", "InputBank", "ConnectionSetBank", "InputAssignmentBank"), id) ?: throw UmbraException("DMXControl bietet das Umschalten der Bank nicht über die Schnittstelle")
        val p = activeParam(list) ?: throw UmbraException("Kein Aktiv-Parameter für Bänke gefunden")
        setParam(t, id, p, ObjectData.newBuilder().setBoolValue(on).build())
        _banks.value = stub.dl(10).getBanks(s.getMultiple(), s.headers).banksList.sortedBy { it.number }
    }

    /** Werte eines Bausteins laden. Kandidaten: allgemeine Typnamen und der Bausteintyp selbst. */
    fun loadNodeParams(node: NodeMetadataData) = act("Baustein-Werte") {
        val id = node.id.nodeId
        val r = params("node", nodeTypes(node), id, nodeIds(node))
        _nodeParams.update { it + (id to (r?.second ?: emptyList())) }
    }

    /**
     * Baustein verschieben: Parameter "Position" des Bausteins (in DMXControl sichtbar als "518, -69").
     * Die Kodierung des Werts ist nicht dokumentiert – die App spiegelt das gelesene Format bzw.
     * probiert PointData und Text "x, y" und prüft am neu geladenen Set, ob die Position stimmt.
     */
    fun moveNode(setId: String, node: NodeMetadataData, x: Float, y: Float) = act("Baustein verschieben") {
        val id = node.id.nodeId
        val (t, list) = params("node", nodeTypes(node), id, nodeIds(node))
            ?: throw UmbraException("DMXControl stellt Position und Werte der Bausteine über die Schnittstelle nicht bereit")
        val p = list.firstOrNull { it.desc.name.equals("Position", true) || it.desc.displayName.equals("Position", true) }
            ?: throw UmbraException("Kein Positions-Parameter gefunden (${list.joinToString { it.desc.name }})")
        val xi = Math.round(x); val yi = Math.round(y)
        val point = PointData.newBuilder().setX(xi.toFloat()).setY(yi.toFloat()).build()
        val cur = p.value
        val candidates = buildList {
            if (cur != null && cur.fallbackCase == ObjectData.FallbackCase.FALLBACKPROTOBUF)
                add("Vorlage" to cur.toBuilder().setFallbackProtobuf(point.toByteString()).build())
            add("Text" to ObjectData.newBuilder().setStringValue("$xi, $yi").build())
            add("PointData" to ObjectData.newBuilder().setFallbackProtobuf(point.toByteString()).setProtobufTypeAQ("LumosProtobuf.PointData").build())
        }
        for ((name, v) in candidates) {
            val r = runCatching { setParam(t, idFor["node$id"] ?: id, p, v) }
            if (r.isFailure) { Diag.note("Baustein verschieben ($name): abgelehnt ${r.exceptionOrNull()?.message}"); continue }
            reloadSet(setId)
            val now = _sets.value[setId]?.nodesList?.firstOrNull { it.id.nodeId == id }?.position
            Diag.note("Baustein verschieben ($name): soll $xi, $yi – ist ${now?.x}, ${now?.y}")
            if (now != null && kotlin.math.abs(now.x - xi) < 2 && kotlin.math.abs(now.y - yi) < 2) return@act
        }
        throw UmbraException("DMXControl hat die neue Position nicht übernommen (Details unter Diagnose)")
    }

    fun setNodeParam(node: NodeMetadataData, p: DevParam, v: ObjectData) = s.throttler.submit("node:${node.id.nodeId}:${p.desc.name}") {
        val t = typeFor["node"] ?: return@submit
        setParam(t, idFor["node${node.id.nodeId}"] ?: node.id.nodeId, p, v)
        _nodeParams.update { m -> m + (node.id.nodeId to (m[node.id.nodeId] ?: emptyList()).map { if (it.desc.name == p.desc.name) it.copy(value = v) else it }) }
    }
}
