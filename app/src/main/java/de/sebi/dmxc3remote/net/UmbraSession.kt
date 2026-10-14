package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.GetMultipleRequest
import LumosProtobuf.CommonTypes.GetRequest
import LumosProtobuf.UmbraServiceTypes.ClientInfo
import LumosProtobuf.UmbraServiceTypes.ClientProgramInfo
import LumosProtobuf.UmbraServiceTypes.EClientType
import LumosProtobuf.UmbraServiceTypes.PingPong
import LumosProtobuf.UmbraServiceTypes.ProgramInfo
import LumosProtobuf.UmbraServiceTypes.ReadyToWorkState
import LumosProtobuf.UmbraServiceTypes.UmbraClientReadyToWorkNotification
import LumosProtobuf.UmbraServiceTypes.UmbraLoginRequest
import LumosProtobuf.UmbraServiceTypes.UmbraLoginResponse
import LumosProtobuf.UmbraServiceTypes.UmbraLogoffRequest
import LumosProtobuf.User.UserServiceTypes.UserContextDescriptor
import LumosProtobuf.User.UserServiceTypes.UserContextRequest
import LumosProtobuf.User.UserServiceTypes.UserContextResponse
import LumosProtobufClient.UserClientGrpcKt.UserClientCoroutineStub
import LumosProtobufCommon.ClientServiceGrpcKt.ClientServiceCoroutineStub
import LumosProtobufCommon.ConnectedClientServiceGrpcKt.ConnectedClientServiceCoroutineStub
import android.os.Build
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class UmbraException(message: String) : IOException(message)

data class ClientIdentity(
    val clientName: String,
    val runtimeId: String,
    val username: String,
    val password: String
)

/** Meldung für die UI (Snackbar/Konsole). */
data class UiEvent(val text: String, val isError: Boolean, val time: Long = System.currentTimeMillis())

/**
 * Basis für alle Funktionsbereiche. load() holt den Ausgangszustand, stream() hält
 * die Änderungs-Streams offen. Fehler eines Moduls beenden NICHT die Sitzung,
 * das Modul wird mit Backoff neu gestartet (UNIMPLEMENTED -> dauerhaft deaktiviert).
 */
abstract class Module(protected val s: UmbraSession, val title: String) {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    open suspend fun load() {}
    open suspend fun stream() {
        kotlinx.coroutines.awaitCancellation()
    }

    internal suspend fun supervise() {
        var attempt = 0
        while (true) {
            try {
                load()
                _error.value = null
                attempt = 0
                stream()
                throw UmbraException("Stream beendet")
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                _error.value = e.describe()
                if (e is StatusException && e.status.code == Status.Code.UNIMPLEMENTED) return
                delay(minOf(30_000L, 2_000L shl minOf(attempt++, 4)))
            }
        }
    }

    /** Aktion aus der UI: Fehler als Event melden statt die Sitzung zu beenden. */
    protected fun act(what: String, block: suspend () -> Unit) = s.act(what, block)
}

/**
 * Sitzung gegen den Umbra-Broker (verifiziert gegen UmbraConnectionClient.cs und
 * das offizielle Companion-Modul):
 *  Login -> Header "SessionID" -> ReportReadyToWork -> Ping-Stream -> UserClient.Bind.
 *  Alle Kernel-Services laufen über denselben Umbra-Endpoint.
 */
class UmbraSession(
    val target: UmbraTarget,
    private val identity: ClientIdentity
) {
    val channel: ManagedChannel = OkHttpChannelBuilder
        .forAddress(target.host, target.port)
        .usePlaintext() // Kestrel Protocols=Http2 ohne TLS -> h2c prior knowledge
        .maxInboundMessageSize(UmbraConstants.MAX_MESSAGE_SIZE)
        .keepAliveTime(20, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .build()

    /**
     * Pro Aufruf frische Metadata mit SessionID. grpc-java verändert die übergebene
     * Metadata beim Call-Start (Timeout-, Encoding-Header) und Metadata ist nicht
     * thread-sicher -> eine gemeinsam genutzte Instanz ist bei vielen parallelen Calls riskant.
     */
    val headers: Metadata
        get() = Metadata().also { m -> sessionId?.let { m.put(SESSION_KEY, it) } }
    val runtimeId get() = identity.runtimeId
    private val requestCounter = AtomicLong()

    private val clientStub = ClientServiceCoroutineStub(channel)
    private val connectedStub = ConnectedClientServiceCoroutineStub(channel)
    val userStub = UserClientCoroutineStub(channel)

    @Volatile
    private var sessionId: String? = null
    var userContextId: String = ""
        private set

    private val _userContext = MutableStateFlow<UserContextDescriptor?>(null)
    val userContext: StateFlow<UserContextDescriptor?> = _userContext.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val throttler = Throttler()

    private var actScope: kotlinx.coroutines.CoroutineScope? = null

    // ------------------------------------------------------------------ Module
    val executors = ExecutorModule(this)
    val masters = MasterModule(this)
    val macros = MacroModule(this)
    val mainSwitches = MainSwitchModule(this)
    val globals = GlobalFunctionModule(this)
    val cuelists = CuelistModule(this)
    val beat = BeatModule(this)
    val audio = AudioModule(this)
    val timecodes = TimecodeModule(this)
    val commandLine = CommandLineModule(this)
    val notifications = NotificationModule(this)
    val devices = DeviceModule(this)
    val presets = PresetModule(this)
    val project = ProjectModule(this)
    val dmx = DmxModule(this)
    val system = SystemModule(this)

    val modules: List<Module> = listOf(
        executors, masters, macros, mainSwitches, globals, cuelists, beat, audio,
        timecodes, commandLine, notifications, devices, presets, project, dmx, system
    )

    fun nextRequestId() = requestCounter.incrementAndGet().toString()

    fun getMultiple(ids: List<String> = emptyList()): GetMultipleRequest = GetMultipleRequest.newBuilder()
        .setRequestId(nextRequestId())
        .setUserContextId(userContextId)
        .addAllIdFilter(ids)
        .build()

    fun getRequest(): GetRequest = GetRequest.newBuilder()
        .setRequestId(nextRequestId())
        .setUserContextId(userContextId)
        .build()

    fun emit(text: String, isError: Boolean = false) {
        _events.tryEmit(UiEvent(text, isError))
    }

    /** Startet eine UI-Aktion im Sitzungs-Scope, Fehler -> Event. */
    fun act(what: String, block: suspend () -> Unit) {
        val scope = actScope ?: return
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                emit("$what: ${e.describe()}", isError = true)
            }
        }
    }

    private fun programInfo(): ClientProgramInfo = buildProgramInfo(identity, target.netId)

    /** Baut die Sitzung auf und blockiert, solange sie lebt. Nur Login/Bind/Ping sind fatal. */
    suspend fun run(onReady: () -> Unit): Unit = coroutineScope {
        actScope = this
        login()
        reportReadyToWork()
        bindUser()
        onReady()

        launch { throttler.run { e -> emit(e.describe(), isError = true) } }
        launch { userContextChanges() }
        modules.forEach { m -> launch { m.supervise() } }
        pingLoop()
    }

    private suspend fun login() {
        val resp = clientStub.dl(3).login(UmbraLoginRequest.newBuilder().setClient(programInfo()).build())
        when (resp.returnCode) {
            UmbraLoginResponse.EReturnCode.NoError,
            UmbraLoginResponse.EReturnCode.AlreadyLoggedIn -> Unit
            else -> throw UmbraException("Login abgelehnt: ${resp.returnCode} ${resp.message}".trim())
        }
        if (resp.sessionId.isBlank()) throw UmbraException("Login ohne SessionID")
        sessionId = resp.sessionId
    }

    private suspend fun reportReadyToWork() {
        connectedStub.dl(3).reportReadyToWork(
            UmbraClientReadyToWorkNotification.newBuilder()
                .setState(ReadyToWorkState.newBuilder().setReadyToWork(true).setReceiveAndDisplayMessages(true).build())
                .build(),
            headers
        ).requireOk("ReportReadyToWork")
    }

    private suspend fun bindUser() {
        val resp = userStub.dl(3).bind(
            UserContextRequest.newBuilder()
                .setRequestId(nextRequestId())
                .setUsername(identity.username)
                .setPasswordHash(PasswordHash.dmxc(identity.password))
                .build(),
            headers
        )
        when (resp.resultCase) {
            UserContextResponse.ResultCase.USERCONTEXTID -> userContextId = resp.userContextId
            UserContextResponse.ResultCase.BINDERROR ->
                throw UmbraException("Benutzer-Bind fehlgeschlagen: ${resp.bindError}")
            UserContextResponse.ResultCase.ERROR ->
                throw UmbraException("Benutzer-Bind fehlgeschlagen: ${resp.error.text()}")
            else -> throw UmbraException("Benutzer-Bind: leere Antwort")
        }
        if (resp.hasUserContextData()) _userContext.value = resp.userContextData
    }

    private suspend fun userContextChanges() {
        while (true) {
            try {
                userStub.receiveUserContextChanges(getRequest(), headers).collect { m ->
                    if (m.hasUserContextData() &&
                        (m.userContextData.id.isBlank() || m.userContextData.id == userContextId)
                    ) {
                        _userContext.value = m.userContextData
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StatusException) {
                if (e.status.code == Status.Code.UNIMPLEMENTED) return
            }
            delay(5_000)
        }
    }

    /** Ping-Pong wie UmbraConnectionClient.OpenPing: eigener Ping alle 5 s, fremde beantworten. */
    private suspend fun pingLoop(): Nothing = coroutineScope {
        val outgoing = Channel<PingPong>(Channel.UNLIMITED)
        val sender = launch {
            var counter = 0L
            while (isActive) {
                outgoing.send(
                    PingPong.newBuilder()
                        .setRequestCounter(++counter)
                        .setClientname(identity.clientName)
                        .build()
                )
                delay(5_000)
            }
        }
        connectedStub.ping(outgoing.receiveAsFlow(), headers).collect { pong ->
            if (pong.clientname != identity.clientName) {
                outgoing.send(pong.toBuilder().setResponder(identity.clientName).build())
            }
        }
        sender.cancel()
        throw UmbraException("Ping-Stream vom Server beendet")
    }

    /** Best-effort Logoff + Kanal schließen. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        actScope = null
        if (sessionId != null) {
            withTimeoutOrNull(1_500) {
                try {
                    clientStub.logoff(UmbraLogoffRequest.newBuilder().setClient(programInfo()).build(), headers)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        sessionId = null
        channel.shutdownNow()
    }

    companion object {
        const val APP_VERSION = "0.2.0"

        fun buildProgramInfo(identity: ClientIdentity, netId: String): ClientProgramInfo {
            val clientInfo = ClientInfo.newBuilder()
                .setHostname(Build.MODEL ?: "Android")
                .addAllIps(localIpv4())
                .setType(EClientType.ExternalTool)
                .setClientname(identity.clientName)
                .setRuntimeid(identity.runtimeId)
                .setNetworkid(netId)
                .setClientCapabilities(0)
                .build()
            val program = ProgramInfo.newBuilder()
                .setProgramName("DMXC3 Remote (Android)")
                .setVendor("private")
                .setProgramVersion(APP_VERSION)
                .setBuildDate(System.currentTimeMillis())
                .build()
            return ClientProgramInfo.newBuilder()
                .setProgramInfo(program)
                .setClientInfo(clientInfo)
                .setClientTimestampUTC(System.currentTimeMillis())
                .build()
        }

        private val SESSION_KEY: Metadata.Key<String> =
            Metadata.Key.of("sessionid", Metadata.ASCII_STRING_MARSHALLER)

        fun localIpv4(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
