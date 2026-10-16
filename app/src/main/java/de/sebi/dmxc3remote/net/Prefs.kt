package de.sebi.dmxc3remote.net

import android.content.Context
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dmxc3remote", Context.MODE_PRIVATE)

    /** Stabile Runtime-ID dieses Clients. Umbra identifiziert Clients darüber. */
    val runtimeId: String
        get() = sp.getString(KEY_RUNTIME, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_RUNTIME, it).apply()
        }

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", UmbraConstants.UMBRA_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    var lastLabel: String
        get() = sp.getString("lastLabel", "") ?: ""
        set(v) = sp.edit().putString("lastLabel", v).apply()

    var netId: String
        get() = sp.getString("netId", "") ?: ""
        set(v) = sp.edit().putString("netId", v).apply()

    var clientName: String
        get() = sp.getString("clientName", "Android Remote") ?: "Android Remote"
        set(v) = sp.edit().putString("clientName", v).apply()

    var username: String
        get() = sp.getString("username", "DMXCDefault") ?: "DMXCDefault"
        set(v) = sp.edit().putString("username", v).apply()

    var password: String
        get() = sp.getString("password", "DMXC3") ?: "DMXC3"
        set(v) = sp.edit().putString("password", v).apply()

    // ---------------------------------------------------------------- Verbindung
    var autoConnect: Boolean
        get() = sp.getBoolean("autoConnect", true)
        set(v) = sp.edit().putBoolean("autoConnect", v).apply()

    var profiles: List<ConnectionProfile>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("profiles", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConnectionProfile(o.getString("name"), o.getString("host"), o.optInt("port", UmbraConstants.UMBRA_PORT), o.optString("netId"))
            }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("profiles", org.json.JSONArray().apply {
            v.forEach { p ->
                put(org.json.JSONObject().apply { put("name", p.name); put("host", p.host); put("port", p.port); put("netId", p.netId) })
            }
        }.toString()).apply()

    // ---------------------------------------------------------------- externe Tasten
    /** Cuelist, die Fußtaster/Presenter/Lautstärketasten auslösen. */
    var goCuelistId: String
        get() = sp.getString("goCuelistId", "") ?: ""
        set(v) = sp.edit().putString("goCuelistId", v).apply()

    var volumeKeysGo: Boolean
        get() = sp.getBoolean("volumeKeysGo", false)
        set(v) = sp.edit().putBoolean("volumeKeysGo", v).apply()

    // ---------------------------------------------------------------- Beat
    /** 0 = App rechnet Tempo (SetManualBeat), 1 = Server lernt (LearnManualBeat je Tap). */
    var beatTapMode: Int
        get() = sp.getInt("beatTapMode", 0)
        set(v) = sp.edit().putInt("beatTapMode", v).apply()

    /** Zeitbasis für Learn/Sync: 0 = Unix-ms, 1 = .NET-Ticks (UNVERIFIZIERT, welche der Server erwartet). */
    var beatTimeBase: Int
        get() = sp.getInt("beatTimeBase", 0)
        set(v) = sp.edit().putInt("beatTimeBase", v).apply()

    /** 0 = keine Rundung, 1 = ganze BPM, 2 = halbe BPM. */
    var beatRounding: Int
        get() = sp.getInt("beatRounding", 0)
        set(v) = sp.edit().putInt("beatRounding", v).apply()

    var beatHaptic: Boolean
        get() = sp.getBoolean("beatHaptic", false)
        set(v) = sp.edit().putBoolean("beatHaptic", v).apply()

    var micAutoApply: Boolean
        get() = sp.getBoolean("micAutoApply", false)
        set(v) = sp.edit().putBoolean("micAutoApply", v).apply()

    private companion object {
        const val KEY_RUNTIME = "runtimeId"
    }
}

data class ConnectionProfile(val name: String, val host: String, val port: Int, val netId: String) {
    fun toTarget() = UmbraTarget(host, port, netId, name)
}
