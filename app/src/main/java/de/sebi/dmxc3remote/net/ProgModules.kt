package de.sebi.dmxc3remote.net

import LumosProtobuf.CommandLineServiceTypes.CommandLineHistoryItem
import LumosProtobuf.CommandLineServiceTypes.ExecuteOrModifyCommandLineCommandRequest
import LumosProtobuf.CommandLineServiceTypes.ExecuteOrModifyCommandLineCommandResponse
import LumosProtobuf.CommonTypes.EChangeType
import LumosProtobuf.DeviceServiceTypes.DeviceGroupDescriptor
import LumosProtobuf.DeviceServiceTypes.DevicePropertyDescriptor
import LumosProtobuf.DeviceServiceTypes.DevicePropertyValue
import LumosProtobuf.LogServiceTypes.ELogLevel
import LumosProtobuf.PresetServiceTypes.InvokePresetRequest
import LumosProtobuf.PresetServiceTypes.PresetDescriptor
import LumosProtobuf.ProgrammerServiceTypes.DeleteProgrammerValueRequest
import LumosProtobuf.ProgrammerServiceTypes.SetProgrammerValueRequest
import LumosProtobuf.ValueTypes.LumosBooleanData
import LumosProtobuf.ValueTypes.LumosColorData
import LumosProtobuf.ValueTypes.LumosPositionData
import LumosProtobufClient.CommandLineClientGrpcKt.CommandLineClientCoroutineStub
import LumosProtobufClient.DeviceClientGrpcKt.DeviceClientCoroutineStub
import LumosProtobufClient.LogClientGrpcKt.LogClientCoroutineStub
import LumosProtobufClient.PresetClientGrpcKt.PresetClientCoroutineStub
import LumosProtobufClient.ProgrammerClientGrpcKt.ProgrammerClientCoroutineStub
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ============================================================== Kommandozeile
class CommandLineModule(s: UmbraSession) : Module(s, "Kommandozeile") {
    private val stub = CommandLineClientCoroutineStub(s.channel)
    private val _current = MutableStateFlow("")
    val current: StateFlow<String> = _current.asStateFlow()
    private val _history = MutableStateFlow<List<CommandLineHistoryItem>>(emptyList())
    val history: StateFlow<List<CommandLineHistoryItem>> = _history.asStateFlow()

    private fun mine(ctx: String) = ctx.isBlank() || ctx == s.userContextId

    override suspend fun load() {
        _current.value = stub.dl(5).getCurrentCommandLine(s.getRequest(), s.headers).currentCommandLine
        _history.value = stub.dl(5).getCommandLineHistory(s.getRequest(), s.headers).historyList
    }

    override suspend fun stream() = coroutineScope {
        launch {
            stub.receiveCommandLineHistoryChanges(s.getRequest(), s.headers).collect { m ->
                if (mine(m.userContextId)) _history.value = m.newHistoryList
            }
        }
        stub.receiveCurrentCommandLineChanges(s.getRequest(), s.headers).collect { m ->
            if (mine(m.userContextId)) _current.value = m.newCurrentCommandLine
        }
    }

    fun execute(command: String) = act("Befehl") {
        val r = stub.dl(10).executeOrModifyCommandLineCommand(
            ExecuteOrModifyCommandLineCommandRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setUserContextId(s.userContextId)
                .setTakethis(command)
                .setExecutionType(ExecuteOrModifyCommandLineCommandRequest.EExecutionType.Run)
                .build(),
            s.headers
        )
        when (r.resultCase) {
            ExecuteOrModifyCommandLineCommandResponse.ResultCase.ERROR ->
                s.emit("Befehl: ${r.error.message.text()}", isError = true)
            ExecuteOrModifyCommandLineCommandResponse.ResultCase.RESPONSE ->
                r.response.text().takeIf { it.isNotBlank() }?.let { s.emit(it) }
            else -> Unit
        }
        _current.value = r.currentCommandLine
    }
}

// ============================================================== Benachrichtigungen
class NotificationModule(s: UmbraSession) : Module(s, "Meldungen") {
    private val stub = LogClientCoroutineStub(s.channel)
    private val _items = MutableStateFlow<List<UiEvent>>(emptyList())
    val items: StateFlow<List<UiEvent>> = _items.asStateFlow()

    override suspend fun stream() {
        stub.receiveUserNotifications(s.getRequest(), s.headers).collect { n ->
            val targets = n.targetClientRuntimeIdsList
            if (targets.isNotEmpty() && s.runtimeId !in targets) return@collect
            val isError = n.level == ELogLevel.ELogLevel_ERROR || n.level == ELogLevel.ELogLevel_FATAL
            val sender = n.sender.text()
            val text = listOf(sender, n.message.text()).filter { it.isNotBlank() }.joinToString(": ")
            val ev = UiEvent(text, isError)
            _items.update { (listOf(ev) + it).take(200) }
            if (isError || n.level == ELogLevel.ELogLevel_WARNING) s.emit(text, isError = true)
        }
    }
}

// ============================================================== Gerätegruppen + Programmer
enum class PropKind { DOUBLE, COLOR, POSITION, BOOL, UNSUPPORTED }

fun DevicePropertyDescriptor.kind(): PropKind {
    val b = if (type.hasUpperBound()) type.upperBound else if (type.hasLowerBound()) type.lowerBound else null
    return when (b?.valueCase) {
        DevicePropertyValue.ValueCase.DOUBLEVALUE -> PropKind.DOUBLE
        DevicePropertyValue.ValueCase.COLOR -> PropKind.COLOR
        DevicePropertyValue.ValueCase.POSITION -> PropKind.POSITION
        DevicePropertyValue.ValueCase.BOOLVALUE -> PropKind.BOOL
        else -> when {
            type.boolType -> PropKind.BOOL
            else -> PropKind.UNSUPPORTED
        }
    }
}

/** Wertebereich aus PropertyTypeDescriptor (lower/upperBound), Fallback 0..1. */
fun DevicePropertyDescriptor.doubleRange(): ClosedFloatingPointRange<Double> {
    val lo = if (type.hasLowerBound() && type.lowerBound.valueCase == DevicePropertyValue.ValueCase.DOUBLEVALUE) type.lowerBound.doubleValue else 0.0
    val hi = if (type.hasUpperBound() && type.upperBound.valueCase == DevicePropertyValue.ValueCase.DOUBLEVALUE) type.upperBound.doubleValue else 1.0
    return if (hi > lo) lo..hi else 0.0..1.0
}

data class PanTiltRange(val pan: ClosedFloatingPointRange<Double>, val tilt: ClosedFloatingPointRange<Double>)

fun DevicePropertyDescriptor.positionRange(): PanTiltRange {
    val lo = if (type.hasLowerBound() && type.lowerBound.hasPosition()) type.lowerBound.position else null
    val hi = if (type.hasUpperBound() && type.upperBound.hasPosition()) type.upperBound.position else null
    fun r(a: Double?, b: Double?) = if (a != null && b != null && b > a) a..b else 0.0..1.0
    return PanTiltRange(r(lo?.pan, hi?.pan), r(lo?.tilt, hi?.tilt))
}

/** Obergrenze für RGB-Werte (1.0 oder 255 – UNVERIFIZIERT, aus upperBound abgeleitet). */
fun DevicePropertyDescriptor.colorMax(): Double {
    val hi = if (type.hasUpperBound() && type.upperBound.hasColor()) type.upperBound.color else null
    val m = listOfNotNull(hi?.r, hi?.g, hi?.b).maxOrNull() ?: 1.0
    return if (m > 1.0) m else 1.0
}

class DeviceModule(s: UmbraSession) : Module(s, "Gerätegruppen") {
    private val stub = DeviceClientCoroutineStub(s.channel)
    private val progStub = ProgrammerClientCoroutineStub(s.channel)
    private val _groups = MutableStateFlow<Map<String, DeviceGroupDescriptor>>(emptyMap())
    val groups: StateFlow<Map<String, DeviceGroupDescriptor>> = _groups.asStateFlow()

    override suspend fun load() {
        _groups.value = stub.dl(15).getDeviceGroups(s.getMultiple(), s.headers).deviceGroupsList
            .filter { !it.temporary }
            .associateBy { it.id }
    }

    override suspend fun stream() {
        stub.receiveDeviceGroupChanges(s.getRequest(), s.headers).collect { m ->
            if (m.changeType == EChangeType.Changed && !m.hasDeviceGroupData() && m.deviceGroupId.isNotBlank()) {
                stub.dl(5).getDeviceGroups(s.getMultiple(listOf(m.deviceGroupId)), s.headers).deviceGroupsList
                    .forEach { d -> _groups.update { it + (d.id to d) } }
            } else if (!(m.hasDeviceGroupData() && m.deviceGroupData.temporary)) {
                _groups.applyChange(m.changeType, m.deviceGroupId, m.hasDeviceGroupData(), { m.deviceGroupData }, { it.id })
            }
        }
    }

    fun select(groupIds: List<String>) = act("Auswahl") {
        s.userStub.dl().selectDevicesAndGroups(s.getMultiple(groupIds), s.headers).requireOk("SelectDevicesAndGroups")
    }

    private suspend fun setValue(groupId: String, propertyId: String, value: DevicePropertyValue) {
        progStub.dl(3).setProgrammerValue(
            SetProgrammerValueRequest.newBuilder()
                .setGroupId(groupId)
                .setPropertyId(propertyId)
                .setUserContextId(s.userContextId)
                .setDpv(value)
                .build(),
            s.headers
        )
    }

    private fun dpv(groupId: String, propertyId: String) =
        DevicePropertyValue.newBuilder().setDeviceId(groupId).setPropertyId(propertyId)

    fun setDouble(groupId: String, propertyId: String, v: Double) = s.throttler.submit("prog:$groupId:$propertyId") {
        setValue(groupId, propertyId, dpv(groupId, propertyId).setDoubleValue(v).build())
    }

    fun setColor(groupId: String, propertyId: String, r: Double, g: Double, b: Double) =
        s.throttler.submit("prog:$groupId:$propertyId") {
            setValue(
                groupId, propertyId,
                dpv(groupId, propertyId).setColor(
                    LumosColorData.newBuilder().setR(r).setG(g).setB(b).setColorSet(LumosColorData.EColorSet.RGB).build()
                ).build()
            )
        }

    fun setPosition(groupId: String, propertyId: String, pan: Double, tilt: Double) =
        s.throttler.submit("prog:$groupId:$propertyId") {
            setValue(
                groupId, propertyId,
                dpv(groupId, propertyId).setPosition(
                    LumosPositionData.newBuilder().setPan(pan).setTilt(tilt).setPanset(true).setTiltset(true).build()
                ).build()
            )
        }

    fun setBool(groupId: String, propertyId: String, v: Boolean) = act("Programmer") {
        setValue(groupId, propertyId, dpv(groupId, propertyId).setBoolValue(LumosBooleanData.newBuilder().setValue(v).build()).build())
    }

    fun clearProgrammer() = act("Programmer leeren") {
        progStub.dl(5).deleteProgrammerValue(
            DeleteProgrammerValueRequest.newBuilder()
                .setRequestId(s.nextRequestId())
                .setUserContextId(s.userContextId)
                .setMode(DeleteProgrammerValueRequest.EClearMode.CLEAR)
                .build(),
            s.headers
        ).requireOk("Programmer leeren")
    }
}

// ============================================================== Presets
class PresetModule(s: UmbraSession) : Module(s, "Presets") {
    private val stub = PresetClientCoroutineStub(s.channel)
    private val _items = MutableStateFlow<Map<String, PresetDescriptor>>(emptyMap())
    val items: StateFlow<Map<String, PresetDescriptor>> = _items.asStateFlow()

    override suspend fun load() {
        _items.value = stub.dl(15).getPresets(s.getMultiple(), s.headers).presetsList.associateBy { it.presetId }
    }

    override suspend fun stream() {
        stub.receivePresetChanges(s.getRequest(), s.headers).collect { m ->
            if (m.changeType == EChangeType.Changed && !m.hasPresetData() && m.presetId.isNotBlank()) {
                stub.dl(5).getPresets(s.getMultiple(listOf(m.presetId)), s.headers).presetsList
                    .forEach { d -> _items.update { it + (d.presetId to d) } }
            } else {
                _items.applyChange(m.changeType, m.presetId, m.hasPresetData(), { m.presetData }, { it.presetId })
            }
        }
    }

    /** Preset aufrufen; mit Gruppe gezielt, ohne Gruppe auf alle enthaltenen Geräte (forceInvokeAll). */
    fun invoke(presetId: String, groupId: String?) = act("Preset") {
        val b = InvokePresetRequest.newBuilder()
            .setRequestId(s.nextRequestId())
            .setUserContextId(s.userContextId)
            .setPresetId(presetId)
        if (groupId != null) b.setDeviceGroupId(groupId) else b.setForceInvokeAll(true)
        stub.dl(5).invokePreset(b.build(), s.headers).requireOk("InvokePreset")
    }
}
