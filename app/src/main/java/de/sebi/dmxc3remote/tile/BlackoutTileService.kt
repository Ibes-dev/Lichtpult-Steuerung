package de.sebi.dmxc3remote.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import de.sebi.dmxc3remote.net.UmbraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Aktuelle Sitzung für Komponenten außerhalb der Activity (Schnelleinstellungen). */
object SessionHolder {
    val session = MutableStateFlow<UmbraSession?>(null)
}

/**
 * Kachel in den Schnelleinstellungen: Blackout umschalten, ohne die App zu öffnen.
 * Funktioniert, solange der App-Prozess mit aktiver Verbindung läuft; sonst ist die Kachel inaktiv.
 */
class BlackoutTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { sc ->
            sc.launch {
                SessionHolder.session.collect { s ->
                    if (s == null) {
                        render(null)
                    } else {
                        s.globals.blackout.collect { render(it) }
                    }
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val s = SessionHolder.session.value ?: return
        s.globals.setBlackout(s.globals.blackout.value != true)
    }

    private fun render(blackout: Boolean?) {
        val t = qsTile ?: return
        t.label = "Blackout"
        t.state = when (blackout) {
            null -> Tile.STATE_UNAVAILABLE
            true -> Tile.STATE_ACTIVE
            false -> Tile.STATE_INACTIVE
        }
        t.updateTile()
    }
}
