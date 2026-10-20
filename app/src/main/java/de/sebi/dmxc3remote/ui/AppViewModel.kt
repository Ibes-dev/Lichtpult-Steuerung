package de.sebi.dmxc3remote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebi.dmxc3remote.audio.MicBeatDetector
import de.sebi.dmxc3remote.midi.MidiHub
import de.sebi.dmxc3remote.midi.MidiMsg
import de.sebi.dmxc3remote.net.ClientIdentity
import de.sebi.dmxc3remote.net.ConnectionProfile
import de.sebi.dmxc3remote.net.Diag
import de.sebi.dmxc3remote.net.SdMode
import de.sebi.dmxc3remote.net.SdType
import de.sebi.dmxc3remote.net.isOn
import de.sebi.dmxc3remote.tile.SessionHolder
import kotlinx.coroutines.flow.collectLatest
import de.sebi.dmxc3remote.net.DiscoveredUmbra
import de.sebi.dmxc3remote.net.Prefs
import de.sebi.dmxc3remote.net.SoftdeskStore
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.UmbraTarget
import de.sebi.dmxc3remote.net.describe
import de.sebi.dmxc3remote.net.informClientExists
import de.sebi.dmxc3remote.net.umbraDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val target: UmbraTarget, val attempt: Int) : ConnState
    data class Connected(val target: UmbraTarget) : ConnState
    data class Failed(val target: UmbraTarget, val message: String, val retryInSec: Int) : ConnState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    val softdesks = SoftdeskStore(app)

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _session = MutableStateFlow<UmbraSession?>(null)
    val session: StateFlow<UmbraSession?> = _session.asStateFlow()

    private val _discovered = MutableStateFlow<Map<String, DiscoveredUmbra>>(emptyMap())
    val discovered: StateFlow<Map<String, DiscoveredUmbra>> = _discovered.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    /** Server, die eine Anmeldung abgelehnt haben -> dort "Freigabe anfragen" anbieten. */
    private val _needsAdmission = MutableStateFlow<Set<String>>(emptySet())
    val needsAdmission: StateFlow<Set<String>> = _needsAdmission.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var sessionJob: Job? = null
    private var discoveryJob: Job? = null

    /** Signal: Netz ist wieder da -> Wartezeit vor dem nächsten Verbindungsversuch abbrechen. */
    private val retryNow = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    val midi = MidiHub(app)
    val mic = MicBeatDetector()

    private val _profiles = MutableStateFlow(prefs.profiles)
    val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()

    /** Beat-Einstellungen (UI-reaktiv, persistent in Prefs). */
    val beatTapMode = MutableStateFlow(prefs.beatTapMode)
    val beatTimeBase = MutableStateFlow(prefs.beatTimeBase)
    val beatRounding = MutableStateFlow(prefs.beatRounding)
    val beatHaptic = MutableStateFlow(prefs.beatHaptic)
    val micAutoApply = MutableStateFlow(prefs.micAutoApply)
    val micToDmxc = MutableStateFlow(prefs.micToDmxc)
    val cuelistCompact = MutableStateFlow(prefs.cuelistCompact)
    val navOrder = MutableStateFlow(prefs.navOrder)
    val goCuelistId = MutableStateFlow(prefs.goCuelistId)
    val volumeKeysGo = MutableStateFlow(prefs.volumeKeysGo)
    val autoConnect = MutableStateFlow(prefs.autoConnect)

    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            retryNow.trySend(Unit)
        }
    }

    init {
        startDiscovery()
        runCatching {
            app.getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netCallback)
        }
        midi.onMessage = { m -> handleMidi(m) }
        midi.start()
        viewModelScope.launch { midi.learned.collect { l -> if (l != null) applyLearned(l.first, l.second) } }
        viewModelScope.launch { midiFeedbackLoop() }
        viewModelScope.launch { micLoop() }
        // Automatisch mit dem zuletzt genutzten Ziel verbinden
        if (prefs.autoConnect && prefs.host.isNotBlank()) {
            connect(UmbraTarget(prefs.host, prefs.port, prefs.netId, prefs.lastLabel.ifBlank { prefs.host }))
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) }
        midi.stop()
        mic.stop()
        SessionHolder.session.value = null
        super.onCleared()
    }

    // ---------------------------------------------------------------- Profile
    fun saveProfile(p: ConnectionProfile) {
        val list = _profiles.value.filterNot { it.name.equals(p.name, true) } + p
        prefs.profiles = list
        _profiles.value = list
    }

    fun deleteProfile(name: String) {
        val list = _profiles.value.filterNot { it.name == name }
        prefs.profiles = list
        _profiles.value = list
    }

    fun setAutoConnect(on: Boolean) { prefs.autoConnect = on; autoConnect.value = on }

    // ---------------------------------------------------------------- Einstellungen
    fun setBeatTapMode(v: Int) { prefs.beatTapMode = v; beatTapMode.value = v }
    fun setBeatTimeBase(v: Int) { prefs.beatTimeBase = v; beatTimeBase.value = v }
    fun setBeatRounding(v: Int) { prefs.beatRounding = v; beatRounding.value = v }
    fun setBeatHaptic(v: Boolean) { prefs.beatHaptic = v; beatHaptic.value = v }
    fun setMicAutoApply(v: Boolean) { prefs.micAutoApply = v; micAutoApply.value = v }
    fun setMicToDmxc(v: Boolean) { prefs.micToDmxc = v; micToDmxc.value = v }
    fun setCuelistCompact(v: Boolean) { prefs.cuelistCompact = v; cuelistCompact.value = v }
    fun setNavOrder(names: List<String>) { val v = names.joinToString(","); prefs.navOrder = v; navOrder.value = v }

    /** Mikrofon im ViewModel-Scope: läuft weiter, auch wenn der Medien-Tab verlassen wird. */
    fun startMic() = mic.start(viewModelScope)
    fun stopMic() = mic.stop()
    fun setGoCuelist(id: String) { prefs.goCuelistId = id; goCuelistId.value = id }
    fun setVolumeKeysGo(v: Boolean) { prefs.volumeKeysGo = v; volumeKeysGo.value = v }

    // ---------------------------------------------------------------- Externe Tasten
    /**
     * Fußtaster, Presenter, Medientasten, optional Lautstärketasten: Go/Back der Go-Cuelist.
     * Rückgabe true = Taste verbraucht.
     */
    fun hardwareKey(keyCode: Int): Boolean {
        val s = _session.value ?: return false
        val go = when (keyCode) {
            android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> true
            android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysGo.value) true else return false
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysGo.value) false else return false
            else -> return false
        }
        val id = goCuelistId.value
        if (id.isBlank() || s.cuelists.items.value[id] == null) {
            s.emit("Keine Go-Cuelist gewählt (Cuelists: Stern antippen)", isError = true)
            return true
        }
        s.cuelists.action(
            id,
            if (go) LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest.EAction.GO
            else LumosProtobuf.Cuelist.CuelistServiceTypes.CuelistActionRequest.EAction.GO_BACK
        )
        return true
    }

    // ---------------------------------------------------------------- MIDI
    private val midiLatched = HashMap<String, Boolean>()

    private fun findMidiElement(key: String) =
        softdesks.desks.value.asSequence().flatMap { it.elements.asSequence() }.firstOrNull { it.midi == key }

    private fun handleMidi(m: MidiMsg) {
        val s = _session.value ?: return
        val e = findMidiElement(m.key) ?: return
        when (e.type) {
            SdType.FADER -> s.softdesk.fader(e, m.value / 127.0)
            SdType.BUTTON -> {
                if (m.kind == 'C') {
                    s.softdesk.button(e, m.value >= 64)
                } else if (e.mode == SdMode.MOMENTARY) {
                    s.softdesk.button(e, m.pressed)
                } else if (m.pressed) {
                    val next = !(midiLatched[e.id] ?: false)
                    midiLatched[e.id] = next
                    s.softdesk.button(e, next)
                }
            }
            else -> Unit
        }
    }

    private fun applyLearned(elementId: String, key: String) {
        val desk = softdesks.desks.value.firstOrNull { d -> d.elements.any { it.id == elementId } } ?: return
        val e = desk.elements.first { it.id == elementId }
        softdesks.updateElement(desk.id, e.copy(midi = key))
    }

    /** LED-Rückmeldung an MIDI-Controller: pro Element mit Notenzuordnung An/Aus-Velocity senden. */
    private suspend fun midiFeedbackLoop() {
        val sent = HashMap<String, Int>()
        _session.collectLatest { s ->
            if (s == null) { sent.clear(); return@collectLatest }
            kotlinx.coroutines.flow.combine(s.softdesk.feedback, softdesks.rules, softdesks.desks) { fb, rules, desks ->
                Triple(fb, rules, desks)
            }.collect { (fb, rules, desks) ->
                desks.flatMap { it.elements }.filter { it.midi.startsWith("N:") && it.type.hasSink }.forEach { e ->
                    val v = fb[e.id] ?: return@forEach
                    val vel = if (v.isOn(rules, e.fbOn)) e.midiOn else e.midiOff
                    if (sent[e.midi] != vel) {
                        sent[e.midi] = vel
                        midi.send(e.midi, vel)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Mikrofon (Hybrid)
    /** Übernimmt stabile Mikrofon-Schätzungen automatisch als Tempo (Tap setzt dann nur die Phase). */
    private suspend fun micLoop() {
        var lastApplied = 0.0
        val recent = ArrayDeque<Double>()
        mic.bpm.collect { bpm ->
            if (bpm == null || !micAutoApply.value) return@collect
            if (mic.confidence.value < 0.3) return@collect
            recent.addLast(bpm); while (recent.size > 3) recent.removeFirst()
            val stable = recent.size == 3 && recent.maxOrNull()!! / recent.minOrNull()!! < 1.02
            if (stable && kotlin.math.abs(bpm - lastApplied) / bpm > 0.01) {
                lastApplied = bpm
                _session.value?.beat?.applyBpm(bpm, beatRounding.value)
            }
        }
    }

    private fun identity() = ClientIdentity(prefs.clientName, prefs.runtimeId, prefs.username, prefs.password)

    private fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            launch {
                while (isActive) {
                    delay(5_000)
                    val limit = System.currentTimeMillis() - 15_000
                    _discovered.update { m -> m.filterValues { it.seenAt >= limit } }
                }
            }
            try {
                umbraDiscovery(getApplication()).collect { d -> _discovered.update { it + (d.key to d) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = "Discovery: ${e.message}"
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun connect(target: UmbraTarget) {
        sessionJob?.cancel()
        prefs.host = target.host
        prefs.port = target.port
        prefs.netId = target.netId
        prefs.lastLabel = target.label
        stopDiscovery()

        sessionJob = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                _conn.value = ConnState.Connecting(target, attempt)
                val s = UmbraSession(target, identity(), softdesks, mic, micToDmxc)
                var failure: String
                try {
                    s.run {
                        _session.value = s
                        SessionHolder.session.value = s
                        _conn.value = ConnState.Connected(target)
                        attempt = 0
                    }
                    failure = "Verbindung beendet"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: de.sebi.dmxc3remote.net.ProjectReloadedException) {
                    // kein Fehler: sofort neu verbinden (finally räumt die alte Sitzung auf)
                    Diag.note("Projekt geladen, Sitzung wird neu aufgebaut")
                    attempt = 0
                    continue
                } catch (e: Throwable) {
                    failure = e.describe()
                    if (failure.contains("abgelehnt") || failure.contains("verweigert") || failure.contains("NetID")) {
                        _needsAdmission.update { it + target.host }
                    }
                } finally {
                    _session.value = null
                    SessionHolder.session.value = null
                    s.close()
                }
                Diag.note("Verbindung verloren: $failure")
                retryNow.tryReceive() // altes Signal verwerfen
                val wait = minOf(10, 2 * maxOf(1, attempt))
                for (sec in wait downTo 1) {
                    _conn.value = ConnState.Failed(target, failure, sec)
                    // Netz wieder da -> sofort neu versuchen
                    if (kotlinx.coroutines.withTimeoutOrNull(1_000) { retryNow.receive() } != null) break
                }
            }
        }
    }

    /** Manuell neu synchronisieren (Menü): Sitzung neu aufbauen. */
    fun resync() {
        val c = _conn.value
        val t = when (c) {
            is ConnState.Connected -> c.target
            is ConnState.Failed -> c.target
            is ConnState.Connecting -> c.target
            else -> null
        } ?: return
        connect(t)
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        _conn.value = ConnState.Idle
        startDiscovery()
    }

    /** Freigabe anfragen: Client erscheint in der DMXC3-Netzwerkverwaltung; zugewiesene NetID wird übernommen. */
    fun requestAdmission(d: DiscoveredUmbra) {
        viewModelScope.launch {
            try {
                val r = informClientExists(d.host, d.port, identity())
                r.clientName?.let { prefs.clientName = it }
                if (r.netId != null) {
                    _info.value = "Freigabe erhalten, NetID ${r.netId}"
                    _needsAdmission.update { it - d.host }
                    connect(UmbraTarget(d.host, d.port, r.netId, d.clientName.ifBlank { d.host }))
                } else {
                    _info.value = "Anfrage an ${r.umbraName?.takeIf { it != "DefaultUnknownServerName" } ?: d.host} gesendet. " +
                        "In DMXControl unter Netzwerk den Client \"${prefs.clientName}\" dem Netzwerk zuweisen und hier erneut anfragen."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = "Freigabe: ${e.describe()}"
            }
        }
    }

    fun clearError() {
        _lastError.value = null
        _info.value = null
    }
}
