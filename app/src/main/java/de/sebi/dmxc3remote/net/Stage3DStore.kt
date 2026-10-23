package de.sebi.dmxc3remote.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Einbau eines Scheinwerfers für die 3D-Ansicht (lokal gespeichert, nicht in DMXControl). */
data class Rig3D(
    /** Höhe über dem Boden in Metern. */
    val height: Float = 4f,
    /** Ausrichtung (Drehung um die Hochachse) in Grad, 0 = zur Bühnenvorderkante. */
    val yaw: Float = 0f,
    /** Neigung feststehender Scheinwerfer in Grad, 0 = senkrecht nach unten bzw. oben. */
    val tilt: Float = 30f,
    /** true = hängend (strahlt nach unten), false = stehend (strahlt nach oben). */
    val hanging: Boolean = true,
    /** Abstrahlwinkel, wenn das Gerät keinen Zoom meldet. */
    val beam: Float = 25f
)

/** Einstellungen einer Ansicht: welche StageView, wie breit die Bühne in Metern ist. */
data class View3D(val stageViewId: String? = null, val widthM: Float = 10f)

/** Speichert Höhe/Ausrichtung je Gerät und die gewählte StageView (SharedPreferences, JSON). */
class Stage3DStore(context: Context) {
    private val sp = context.getSharedPreferences("stage3d", Context.MODE_PRIVATE)

    private val _rigs = MutableStateFlow(loadRigs())
    val rigs: StateFlow<Map<String, Rig3D>> = _rigs.asStateFlow()

    private val _view = MutableStateFlow(loadView())
    val view: StateFlow<View3D> = _view.asStateFlow()

    private fun loadRigs(): Map<String, Rig3D> = runCatching {
        val o = JSONObject(sp.getString("rigs", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val r = o.getJSONObject(k)
            Rig3D(
                r.optDouble("h", 4.0).toFloat(), r.optDouble("yaw", 0.0).toFloat(), r.optDouble("tilt", 30.0).toFloat(),
                r.optBoolean("hang", true), r.optDouble("beam", 25.0).toFloat()
            )
        }
    }.getOrDefault(emptyMap())

    private fun loadView() = View3D(sp.getString("view", null), sp.getFloat("widthM", 10f))

    fun rig(id: String) = _rigs.value[id] ?: Rig3D()

    fun setRig(id: String, r: Rig3D) = saveRigs(_rigs.value + (id to r))

    fun setRigs(ids: Collection<String>, f: (Rig3D) -> Rig3D) = saveRigs(_rigs.value + ids.associateWith { f(rig(it)) })

    private fun saveRigs(m: Map<String, Rig3D>) {
        val o = JSONObject()
        m.forEach { (k, r) ->
            o.put(k, JSONObject().put("h", r.height.toDouble()).put("yaw", r.yaw.toDouble()).put("tilt", r.tilt.toDouble())
                .put("hang", r.hanging).put("beam", r.beam.toDouble()))
        }
        sp.edit().putString("rigs", o.toString()).apply()
        _rigs.value = m
    }

    fun setView(v: View3D) {
        sp.edit().putString("view", v.stageViewId).putFloat("widthM", v.widthM).apply()
        _view.value = v
    }
}
