package de.sebi.dmxc3remote.net

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-weite Diagnose: protokolliert jeden gRPC-Aufruf (Methode, Status, Dauer) und optional
 * Rohdaten der Nachrichten; misst die Ping-Laufzeit zum Server.
 */
object Diag {
    data class Call(val time: Long, val method: String, val status: String, val ms: Long, val detail: String = "")

    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    val calls: StateFlow<List<Call>> = _calls.asStateFlow()

    private val _rttMs = MutableStateFlow<Long?>(null)
    val rttMs: StateFlow<Long?> = _rttMs.asStateFlow()

    /** Rohdaten von Anfragen/Antworten mitschreiben (kostet Leistung, nur zur Fehlersuche). */
    val captureRaw = MutableStateFlow(false)

    private val _notes = MutableStateFlow<List<String>>(emptyList())
    val notes: StateFlow<List<String>> = _notes.asStateFlow()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)
    fun ts(t: Long = System.currentTimeMillis()): String = fmt.format(Date(t))

    fun note(text: String) {
        _notes.update { (listOf("${ts()}  $text") + it).take(200) }
    }

    fun rtt(ms: Long) {
        _rttMs.value = ms
    }

    private fun add(c: Call) {
        _calls.update { (listOf(c) + it).take(300) }
    }

    private fun short(m: Any?): String = m.toString().replace(Regex("\\s+"), " ").take(400)

    /** Interceptor für den gRPC-Kanal. Streams werden beim Ende mit ihrer Gesamtdauer erfasst. */
    val interceptor: ClientInterceptor = object : ClientInterceptor {
        override fun <Q, R> interceptCall(
            method: MethodDescriptor<Q, R>,
            options: CallOptions,
            next: Channel
        ): ClientCall<Q, R> {
            val name = method.fullMethodName.substringAfterLast('/')
            val start = System.currentTimeMillis()
            var reqText = ""
            var respText = ""
            return object : ForwardingClientCall.SimpleForwardingClientCall<Q, R>(next.newCall(method, options)) {
                override fun sendMessage(message: Q) {
                    if (captureRaw.value && reqText.isEmpty()) reqText = short(message)
                    super.sendMessage(message)
                }

                override fun start(listener: ClientCall.Listener<R>, headers: Metadata) {
                    super.start(object : ForwardingClientCallListener.SimpleForwardingClientCallListener<R>(listener) {
                        override fun onMessage(message: R) {
                            if (captureRaw.value && respText.isEmpty()) respText = short(message)
                            super.onMessage(message)
                        }

                        override fun onClose(status: Status, trailers: Metadata) {
                            val st = if (status.isOk) "OK" else "${status.code}${status.description?.let { ": $it" } ?: ""}"
                            val detail = if (reqText.isEmpty() && respText.isEmpty()) "" else "→ $reqText\n← $respText"
                            add(Call(start, name, st, System.currentTimeMillis() - start, detail))
                            super.onClose(status, trailers)
                        }
                    }, headers)
                }
            }
        }
    }

    /** Text für den Export (Teilen). */
    fun report(extra: String = ""): String = buildString {
        if (extra.isNotBlank()) appendLine(extra)
        appendLine("Ping-Laufzeit: ${_rttMs.value?.let { "$it ms" } ?: "unbekannt"}")
        appendLine()
        appendLine("Hinweise:")
        _notes.value.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Aufrufe (neueste zuerst):")
        _calls.value.forEach { c ->
            appendLine("  ${ts(c.time)}  ${c.method}  ${c.status}  ${c.ms} ms")
            if (c.detail.isNotBlank()) c.detail.lines().forEach { appendLine("      $it") }
        }
    }
}
