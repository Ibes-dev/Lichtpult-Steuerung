package de.sebi.dmxc3remote.net

import LumosProtobuf.CommonTypes.EChangeType
import LumosProtobuf.CommonTypes.ENullableBool
import LumosProtobuf.CommonTypes.GetMultipleRequest
import LumosProtobuf.CommonTypes.GetRequest
import LumosProtobuf.Executor.ExecutorServiceCRUDTypes.SetExecutorValuesRequest
import LumosProtobuf.Executor.ExecutorServiceTypes.ExecutorDescriptor
import LumosProtobuf.Executor.ExecutorServiceTypes.ExecutorPageDescriptor
import LumosProtobuf.Master.MasterServiceCRUDTypes.MasterActionRequest
import LumosProtobuf.Master.MasterServiceTypes.MasterDescriptor
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
import LumosProtobuf.User.UserServiceTypes.UserContextRequest
import LumosProtobuf.User.UserServiceTypes.UserContextResponse
import LumosProtobufClient.ExecutorClientGrpcKt.ExecutorClientCoroutineStub
import LumosProtobufClient.MasterClientGrpcKt.MasterClientCoroutineStub
import LumosProtobufClient.UserClientGrpcKt.UserClientCoroutineStub
import LumosProtobufCommon.ClientServiceGrpcKt.ClientServiceCoroutineStub
import LumosProtobufCommon.ConnectedClientServiceGrpcKt.ConnectedClientServiceCoroutineStub
import android.os.Build
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class UmbraException(message: String) : IOException(message)

data class ClientIdentity(
    val clientName: String,
    val runtimeId: String,
    val username: String,
    val password: String
)

/**
 * Eine Sitzung gegen den Umbra-Broker von DMXControl 3.
 *
 * Ablauf (verifiziert gegen UmbraConnectionClient.cs und das offizielle Companion-Modul):
 *  1. ClientService.Login (ohne Header)          -> SessionID
 *  2. Header "SessionID" für alle weiteren Calls
 *  3. ConnectedClientService.ReportReadyToWork   (Pflicht, einmalig)
 *  4. ConnectedClientService.Ping (bidi-Stream)  (Pflicht, sonst Drop nach ~5 min)
 *  5. UserClient.Bind(username, passwordHash)    -> userContextId
 *  6. Kernel-Services (Executor, Master, ...) laufen über denselben Umbra-Endpoint.
 */
class UmbraSession(
    val target: UmbraTarget,
    private val identity: ClientIdentity
) {
    private val channel: ManagedChannel = OkHttpChannelBuilder
        .forAddress(target.host, target.port)
        .usePlaintext() // Umbra: Kestrel, Protocols=Http2, ohne TLS -> h2c prior knowledge
        .maxInboundMessageSize(UmbraConstants.MAX_MESSAGE_SIZE)
        .keepAliveTime(20, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .build()

    private val headers = Metadata()
    private val requestCounter = AtomicLong()

    private val clientStub = ClientServiceCoroutineStub(channel)
    private val connectedStub = ConnectedClientServiceCoroutineStub(channel)
    private val userStub = UserClientCoroutineStub(channel)
    private val executorStub = ExecutorClientCoroutineStub(channel)
    private val masterStub = MasterClientCoroutineStub(channel)

    private var sessionId: String? = null
    private var userContextId: String = ""

    private val _executors = MutableStateFlow<Map<String, ExecutorDescriptor>>(emptyMap())
    val executors: StateFlow<Map<String, ExecutorDescriptor>> = _executors.asStateFlow()

    private val _pages = MutableStateFlow<Map<String, ExecutorPageDescriptor>>(emptyMap())
    val pages: StateFlow<Map<String, ExecutorPageDescriptor>> = _pages.asStateFlow()

    private val _masters = MutableStateFlow<Map<String, MasterDescriptor>>(emptyMap())
    val masters: StateFlow<Map<String, MasterDescriptor>> = _masters.asStateFlow()

    private val _initialPageId = MutableStateFlow<String?>(null)
    val initialPageId: StateFlow<String?> = _initialPageId.asStateFlow()

    /** Letzter Faderwert je Executor, wird vom Sender-Loop gedrosselt abgearbeitet. */
    private val pendingFader = ConcurrentHashMap<String, Double>()

    private fun nextRequestId() = requestCounter.incrementAndGet().toString()

    private fun programInfo(): ClientProgramInfo {
        val clientInfo = ClientInfo.newBuilder()
            .setHostname(Build.MODEL ?: "Android")
            .addAllIps(localIpv4())
            .setType(EClientType.ExternalTool)
            .setClientname(identity.clientName)
            .setRuntimeid(identity.runtimeId)
            .setNetworkid(target.netId)
            .setClientCapabilities(0)
            .build()
        val program = ProgramInfo.newBuilder()
            .setProgramName("DMXC3 Remote (Android)")
            .setVendor("private")
            .setProgramVersion("0.1.0")
            .setBuildDate(System.currentTimeMillis())
            .build()
        return ClientProgramInfo.newBuilder()
            .setProgramInfo(program)
            .setClientInfo(clientInfo)
            .setClientTimestampUTC(System.currentTimeMillis())
            .build()
    }

    /**
     * Baut die Sitzung auf und blockiert, solange sie lebt.
     * Wirft eine Exception, sobald ein Pflicht-Stream abbricht.
     */
    suspend fun run(onReady: () -> Unit) = coroutineScope {
        login()
        reportReadyToWork()
        bindUser()
        loadInitialState()
        onReady()

        launch { pingLoop() }
        launch { faderSender() }
        launch { executorChanges() }
        launch { executorPageChanges() }
        launch { masterChanges() }
    }

    private suspend fun login() {
        val resp = clientStub.withDeadlineAfter(3, TimeUnit.SECONDS)
            .login(UmbraLoginRequest.newBuilder().setClient(programInfo()).build())
        when (resp.returnCode) {
            UmbraLoginResponse.EReturnCode.NoError,
            UmbraLoginResponse.EReturnCode.AlreadyLoggedIn -> Unit
            else -> throw UmbraException("Login abgelehnt: ${resp.returnCode} ${resp.message}".trim())
        }
        if (resp.sessionId.isBlank()) throw UmbraException("Login ohne SessionID")
        sessionId = resp.sessionId
        headers.put(SESSION_KEY, resp.sessionId)
    }

    private suspend fun reportReadyToWork() {
        val resp = connectedStub.withDeadlineAfter(3, TimeUnit.SECONDS).reportReadyToWork(
            UmbraClientReadyToWorkNotification.newBuilder()
                .setState(ReadyToWorkState.newBuilder().setReadyToWork(true).build())
                .build(),
            headers
        )
        if (!resp.ok) throw UmbraException("ReportReadyToWork abgelehnt: ${resp.message.formatString}")
    }

    private suspend fun bindUser() {
        val resp = userStub.withDeadlineAfter(3, TimeUnit.SECONDS).bind(
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
                throw UmbraException("Benutzer-Bind fehlgeschlagen: ${resp.error.formatString}")
            else -> throw UmbraException("Benutzer-Bind: leere Antwort")
        }
        if (resp.hasUserContextData()) {
            _initialPageId.value = resp.userContextData.selectedExecutorPage.ifBlank { null }
        }
    }

    private fun getMultiple() = GetMultipleRequest.newBuilder()
        .setRequestId(nextRequestId())
        .setUserContextId(userContextId)
        .build()

    private fun getRequest() = GetRequest.newBuilder()
        .setRequestId(nextRequestId())
        .setUserContextId(userContextId)
        .build()

    private suspend fun loadInitialState() {
        val ex = executorStub.withDeadlineAfter(10, TimeUnit.SECONDS).getExecutors(getMultiple(), headers)
        _executors.value = ex.executorsList.associateBy { it.id }

        val pg = executorStub.withDeadlineAfter(10, TimeUnit.SECONDS).getExecutorPages(getMultiple(), headers)
        _pages.value = pg.executorPagesList.associateBy { it.id }

        val ms = masterStub.withDeadlineAfter(10, TimeUnit.SECONDS).getMasters(getMultiple(), headers)
        _masters.value = ms.mastersList.associateBy { it.masterId }
    }

    /** Ping-Pong wie in UmbraConnectionClient.OpenPing: eigener Ping alle 5 s, fremde Pings beantworten. */
    private suspend fun pingLoop() = coroutineScope {
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

    private suspend fun faderSender() {
        while (true) {
            delay(25)
            for (id in pendingFader.keys.toList()) {
                val value = pendingFader.remove(id) ?: continue
                try {
                    sendExecutor(id) { it.setFaderAbsolut(value) }
                } catch (_: UmbraException) {
                    // Server lehnt Wert ab (z. B. Executor leer) – Sitzung bleibt bestehen.
                }
            }
        }
    }

    private suspend fun executorChanges() {
        executorStub.receiveExecutorChanges(getRequest(), headers).collect { m ->
            when (m.changeType) {
                EChangeType.Added, EChangeType.Changed ->
                    if (m.hasExecutorData()) _executors.update { it + (m.executorData.id to m.executorData) }
                EChangeType.Removed -> {
                    val id = m.executorId.ifBlank { m.executorData.id }
                    _executors.update { it - id }
                }
                else -> Unit
            }
        }
        throw UmbraException("Executor-Stream beendet")
    }

    private suspend fun executorPageChanges() {
        executorStub.receiveExecutorPageChanges(getRequest(), headers).collect { m ->
            when (m.changeType) {
                EChangeType.Added, EChangeType.Changed ->
                    if (m.hasExecutorPageData()) _pages.update { it + (m.executorPageData.id to m.executorPageData) }
                EChangeType.Removed -> {
                    val id = m.executorPageId.ifBlank { m.executorPageData.id }
                    _pages.update { it - id }
                }
                else -> Unit
            }
        }
        throw UmbraException("ExecutorPage-Stream beendet")
    }

    private suspend fun masterChanges() {
        masterStub.receiveMasterChanges(getRequest(), headers).collect { m ->
            when (m.changeType) {
                EChangeType.Added, EChangeType.Changed ->
                    if (m.hasMasterData()) _masters.update { it + (m.masterData.masterId to m.masterData) }
                EChangeType.Removed -> {
                    val id = m.masterId.ifBlank { m.masterData.masterId }
                    _masters.update { it - id }
                }
                else -> Unit
            }
        }
        throw UmbraException("Master-Stream beendet")
    }

    // ---------------------------------------------------------------- Aktionen

    /** Fader 0.0 … 1.0 (Companion sendet value/100). Gedrosselt auf max. 40 Calls/s je Executor. */
    fun setFader(executorId: String, value: Double) {
        pendingFader[executorId] = value.coerceIn(0.0, 1.0)
    }

    /** Taste 1..4 drücken (true) bzw. loslassen (false). */
    suspend fun setButton(executorId: String, button: Int, pressed: Boolean) {
        val v = if (pressed) ENullableBool.True else ENullableBool.False
        sendExecutor(executorId) {
            when (button) {
                1 -> it.setButton1(v)
                2 -> it.setButton2(v)
                3 -> it.setButton3(v)
                4 -> it.setButton4(v)
                else -> it
            }
        }
    }

    suspend fun masterAction(masterId: String, action: MasterActionRequest.EAction) {
        val resp = masterStub.withDeadlineAfter(3, TimeUnit.SECONDS).masterAction(
            MasterActionRequest.newBuilder()
                .setRequestId(nextRequestId())
                .setMasterId(masterId)
                .setAction(action)
                .build(),
            headers
        )
        if (!resp.ok) throw UmbraException("MasterAction $action: ${resp.message.formatString}")
    }

    private suspend fun sendExecutor(
        executorId: String,
        apply: (SetExecutorValuesRequest.Builder) -> SetExecutorValuesRequest.Builder
    ) {
        val req = apply(
            SetExecutorValuesRequest.newBuilder()
                .setRequestId(nextRequestId())
                .setExecutorId(executorId)
                .setUserContextId(userContextId)
        ).build()
        val resp = executorStub.withDeadlineAfter(3, TimeUnit.SECONDS).setExecutorValues(req, headers)
        if (!resp.ok) throw UmbraException("SetExecutorValues: ${resp.message.formatString}")
    }

    /** Best-effort Logoff + Kanal schließen. */
    suspend fun close() = withContext(NonCancellable) {
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

    private companion object {
        val SESSION_KEY: Metadata.Key<String> =
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
