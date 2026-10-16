package de.sebi.dmxc3remote.net

import LumosProtobuf.DeviceServiceTypes.DeviceDescriptor
import LumosProtobuf.StageViewServiceTypes.StageViewDescriptor
import LumosProtobufClient.DeviceClientGrpcKt.DeviceClientCoroutineStub
import LumosProtobufClient.StageViewClientGrpcKt.StageViewClientCoroutineStub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bühnenansichten (StageView) mit Gerätepositionen; Gerätenamen aus GetDevices. */
class StageModule(s: UmbraSession) : Module(s, "Bühnenansicht") {
    private val stub = StageViewClientCoroutineStub(s.channel)
    private val devStub = DeviceClientCoroutineStub(s.channel)

    private val _views = MutableStateFlow<Map<String, StageViewDescriptor>>(emptyMap())
    val views: StateFlow<Map<String, StageViewDescriptor>> = _views.asStateFlow()

    private val _devices = MutableStateFlow<Map<String, DeviceDescriptor>>(emptyMap())
    val devices: StateFlow<Map<String, DeviceDescriptor>> = _devices.asStateFlow()

    override suspend fun load() {
        _views.value = stub.dl(15).getStageViews(s.getMultiple(), s.headers).stageViewsList.associateBy { it.id }
        _devices.value = runCatching {
            devStub.dl(20).getDevices(s.getMultiple(), s.headers).devicesList.flatMap { listOf(it) + it.subdevicesList }.associateBy { it.id }
        }.getOrDefault(emptyMap())
    }

    override suspend fun stream() {
        stub.receiveStageViewChanges(s.getRequest(), s.headers).collect { m ->
            if (m.changeType == LumosProtobuf.CommonTypes.EChangeType.Changed && !m.hasStageViewData() && m.stageViewId.isNotBlank()) {
                stub.dl(10).getStageViews(s.getMultiple(listOf(m.stageViewId)), s.headers).stageViewsList
                    .forEach { v -> _views.value = _views.value + (v.id to v) }
            } else {
                _views.applyChange(m.changeType, m.stageViewId, m.hasStageViewData(), { m.stageViewData }, { it.id })
            }
        }
    }
}
