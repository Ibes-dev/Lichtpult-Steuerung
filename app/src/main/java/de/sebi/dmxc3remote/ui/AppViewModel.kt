package de.sebi.dmxc3remote.ui

import LumosProtobuf.Master.MasterServiceCRUDTypes.MasterActionRequest
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebi.dmxc3remote.net.ClientIdentity
import de.sebi.dmxc3remote.net.DiscoveredUmbra
import de.sebi.dmxc3remote.net.Prefs
import de.sebi.dmxc3remote.net.UmbraSession
import de.sebi.dmxc3remote.net.UmbraTarget
import de.sebi.dmxc3remote.net.umbraDiscovery
import io.grpc.Status
import io.grpc.StatusException
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

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _session = MutableStateFlow<UmbraSession?>(null)
    val session: StateFlow<UmbraSession?> = _session.asStateFlow()

    private val _discovered = MutableStateFlow<Map<String, DiscoveredUmbra>>(emptyMap())
    val discovered: StateFlow<Map<String, DiscoveredUmbra>> = _discovered.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var sessionJob: Job? = null
    private var discoveryJob: Job? = null

    init {
        startDiscovery()
    }

    private fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            launch {
                // Einträge, die > 15 s keinen Broadcast mehr gesendet haben, ausblenden
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
        stopDiscovery()

        sessionJob = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                _conn.value = ConnState.Connecting(target, attempt)
                val s = UmbraSession(
                    target,
                    ClientIdentity(prefs.clientName, prefs.runtimeId, prefs.username, prefs.password)
                )
                var failure: String
                try {
                    s.run {
                        _session.value = s
                        _conn.value = ConnState.Connected(target)
                        attempt = 0
                    }
                    failure = "Verbindung beendet"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.describe()
                } finally {
                    _session.value = null
                    s.close()
                }
                val wait = minOf(10, 2 * maxOf(1, attempt))
                for (sec in wait downTo 1) {
                    _conn.value = ConnState.Failed(target, failure, sec)
                    delay(1_000)
                }
            }
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        _conn.value = ConnState.Idle
        startDiscovery()
    }

    fun setFader(executorId: String, value: Float) {
        _session.value?.setFader(executorId, value.toDouble())
    }

    fun setButton(executorId: String, button: Int, pressed: Boolean) {
        val s = _session.value ?: return
        viewModelScope.launch {
            try {
                s.setButton(executorId, button, pressed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = e.describe()
            }
        }
    }

    fun masterAction(masterId: String, action: MasterActionRequest.EAction) {
        val s = _session.value ?: return
        viewModelScope.launch {
            try {
                s.masterAction(masterId, action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = e.describe()
            }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    private fun Throwable.describe(): String = when (this) {
        is StatusException -> when (status.code) {
            Status.Code.UNAVAILABLE -> "Umbra nicht erreichbar (${status.description ?: "UNAVAILABLE"})"
            Status.Code.PERMISSION_DENIED -> "Zugriff verweigert – NetID/Freigabe prüfen"
            Status.Code.UNAUTHENTICATED -> "Nicht authentifiziert – Sitzung ungültig"
            Status.Code.DEADLINE_EXCEEDED -> "Zeitüberschreitung"
            else -> "${status.code}: ${status.description ?: ""}"
        }
        else -> message ?: javaClass.simpleName
    }
}
