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

    private companion object {
        const val KEY_RUNTIME = "runtimeId"
    }
}
