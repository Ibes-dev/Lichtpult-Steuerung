package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.DMXAddressData
import LumosProtobuf.CommonTypes.ObjectData
import LumosProtobuf.DeviceServiceTypes.DeviceDescriptor
import LumosProtobuf.ParameterServiceTypes.GenericParameterData
import LumosProtobuf.ParameterServiceTypes.GetParametersRequest
import LumosProtobuf.ParameterServiceTypes.SetTestParameterValueRequest
import LumosProtobufClient.DeviceClientGrpcKt.DeviceClientCoroutineStub
import LumosProtobufClient.ParameterClientGrpcKt.ParameterClientCoroutineStub
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Ein Geräteparameter mit aktuellem Wert. */
data class DevParam(val desc: GenericParameterData, val value: ObjectData?) {
    val label get() = desc.displayName.ifBlank { desc.name }
}

/** DMX-Adresse (Universum 1-n, Kanal 1-512), dazu Kanalanzahl, wenn bekannt. */
data class DmxAddr(val universe: Int, val address: Int, val count: Int?) {
    override fun toString() = "$universe.${address.toString().padStart(3, '0')}" + (count?.let { " (+$it)" } ?: "")
}

/**
 * Geräteliste mit DMX-Adressen. Adressen stehen nicht im DeviceDescriptor, sondern in den
 * Geräteparametern (ParameterClient.GetParameters, type "Device"). UNVERIFIZIERT:
 *  - Name des Adressparameters (gesucht wird nach "address"/"adresse")
 *  - Kodierung als DMXAddressData im fallbackProtobuf oder als Zahl (absolute Adresse)
 *  - Ändern per SetParameterValue mit derselben Kodierung
 */
class DeviceListModule(s: UmbraSession) : Module(s, "Geräteliste") {
    private val devStub = DeviceClientCoroutineStub(s.channel)
    private val parStub = ParameterClientCoroutineStub(s.channel)

    private val _devices = MutableStateFlow<List<DeviceDescriptor>>(emptyList())
    val devices: StateFlow<List<DeviceDescriptor>> = _devices.asStateFlow()

    /** Parameter je Geräte-ID (lazy geladen). */
    private val _params = MutableStateFlow<Map<String, List<DevParam>>>(emptyMap())
    val params: StateFlow<Map<String, List<DevParam>>> = _params.asStateFlow()

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val active = MutableStateFlow(false)
    fun setActive(on: Boolean) { active.value = on }

    override suspend fun load() {
        _devices.value = devStub.dl(20).getDevices(s.getMultiple(), s.headers).devicesList
            .flatMap { listOf(it) + it.subdevicesList }
            .sortedWith(compareBy({ it.number }, { it.name }))
        _params.value = emptyMap()
    }

    /** Parameter erst laden, wenn die Liste sichtbar ist; dann Gerät für Gerät. */
    override suspend fun stream() {
        while (true) {
            if (!active.value) { kotlinx.coroutines.delay(500); continue }
            val todo = _devices.value.filter { it.id !in _params.value }
            if (todo.isEmpty()) { kotlinx.coroutines.delay(1_000); continue }
            todo.forEachIndexed { i, d ->
                if (!active.value) return@forEachIndexed
                _progress.value = "Adressen laden ${i + 1}/${todo.size}"
                runCatching { readParams(d.id) }
            }
            _progress.value = null
        }
    }

    suspend fun readParams(id: String) {
        val r = parStub.dl(10).getParameters(
            GetParametersRequest.newBuilder().setType("Device").setId(id).setIncludingValues(true)
                .setUserContextId(s.userContextId).build(),
            s.headers
        )
        val list = r.parametersList.map { p -> DevParam(p, r.parameterValuesMap[p.name]) }
        _params.update { it + (id to list) }
    }

    fun addressParam(id: String): DevParam? = _params.value[id]?.firstOrNull { p ->
        val n = (p.desc.name + " " + p.desc.displayName).lowercase()
        ("address" in n || "adresse" in n) && p.value != null
    }

    fun addressOf(id: String): DmxAddr? {
        val v = addressParam(id)?.value ?: return null
        return decodeAddress(v)
    }

    private fun decodeAddress(v: ObjectData): DmxAddr? {
        // Zahl = absolute Adresse (1-basiert) über alle Universen
        v.asDouble()?.takeIf { v.valueCase != ObjectData.ValueCase.VALUE_NOT_SET && v.valueCase != ObjectData.ValueCase.BOOLVALUE }?.let { abs ->
            val a = abs.toInt().coerceAtLeast(1)
            return DmxAddr((a - 1) / 512 + 1, (a - 1) % 512 + 1, null)
        }
        if (v.fallbackCase == ObjectData.FallbackCase.FALLBACKPROTOBUF &&
            (v.protobufTypeAQ + v.fallbackTypeAQ).contains("DMXAddressData")
        ) {
            val d = runCatching { DMXAddressData.parseFrom(v.fallbackProtobuf) }.getOrNull() ?: return null
            val pair = d.addressesList.firstOrNull() ?: return null
            val min = pair.min; val max = pair.max
            val u = if (min.dmxUniverseSet) min.dmxUniverse else 1
            val count = if (pair.hasMax()) (max.dmxAddress - min.dmxAddress + 1).takeIf { it > 0 } else null
            return DmxAddr(u, min.dmxAddress, count)
        }
        return null
    }

    /** Adresse ändern, in derselben Kodierung wie gelesen. */
    fun setAddress(id: String, universe: Int, address: Int) = act("Adresse ändern") {
        val p = addressParam(id) ?: throw UmbraException("Kein Adressparameter gefunden")
        val old = p.value ?: throw UmbraException("Adresse unbekannt")
        val value: ObjectData = when {
            old.fallbackCase == ObjectData.FallbackCase.FALLBACKPROTOBUF -> {
                val d = DMXAddressData.parseFrom(old.fallbackProtobuf)
                val pair = d.addressesList.firstOrNull() ?: throw UmbraException("Adressdaten leer")
                val span = if (pair.hasMax()) pair.max.dmxAddress - pair.min.dmxAddress else 0
                val newPair = pair.toBuilder()
                    .setMin(pair.min.toBuilder().setDmxAddress(address).setDmxUniverse(universe).setDmxUniverseSet(true))
                    .setMax(pair.max.toBuilder().setDmxAddress(address + span).setDmxUniverse(universe).setDmxUniverseSet(true))
                val nd = d.toBuilder().setAddresses(0, newPair).build()
                old.toBuilder().setFallbackProtobuf(ByteString.copyFrom(nd.toByteArray())).build()
            }
            else -> ObjectData.newBuilder().setIntValue((universe - 1) * 512 + address).build()
        }
        setParam(id, p.desc, value)
    }

    /** Einfachen Parameter (Text, Zahl, Ja/Nein) setzen. */
    fun setSimple(id: String, p: DevParam, text: String) = act("Parameter ändern") {
        val old = p.value
        val v = ObjectData.newBuilder()
        when (old?.valueCase) {
            ObjectData.ValueCase.BOOLVALUE -> v.setBoolValue(text.equals("true", true) || text == "1" || text.equals("ja", true))
            ObjectData.ValueCase.INTVALUE -> v.setIntValue(text.trim().toInt())
            ObjectData.ValueCase.LONGVALUE -> v.setLongValue(text.trim().toLong())
            ObjectData.ValueCase.UINTVALUE -> v.setUintValue(text.trim().toInt())
            ObjectData.ValueCase.DOUBLEVALUE -> v.setDoubleValue(text.trim().replace(',', '.').toDouble())
            ObjectData.ValueCase.FLOATVALUE -> v.setFloatValue(text.trim().replace(',', '.').toFloat())
            else -> v.setStringValue(text)
        }
        setParam(id, p.desc, v.build())
    }

    private suspend fun setParam(id: String, desc: GenericParameterData, value: ObjectData) {
        parStub.dl(5).setParameterValue(
            SetTestParameterValueRequest.newBuilder()
                .setId(id).setParameter(desc).setUserContextId(s.userContextId).setValue(value).build(),
            s.headers
        ).requireOk("SetParameterValue ${desc.name}")
        readParams(id)
        s.emit("${desc.displayName.ifBlank { desc.name }} geändert")
    }
}
