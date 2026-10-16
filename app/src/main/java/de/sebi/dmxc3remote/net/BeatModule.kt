package de.sebi.dmxc3remote.net

import LumosProtobuf.BeatServiceTypes.ManualBeatFactorRequestResponse
import LumosProtobuf.BeatServiceTypes.ManualBeatIntervalRequestResponse
import LumosProtobuf.BeatServiceTypes.ManualBeatLearnRequest
import LumosProtobuf.BeatServiceTypes.ManualBeatSyncRequest
import LumosProtobufClient.BeatClientGrpcKt.BeatClientCoroutineStub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tap-Tempo: Periode und Phase per linearer Regression (kleinste Quadrate) über die letzten
 * bis zu 8 Taps. Intervalle mit > 25 % Abweichung vom Median werden verworfen, nach 2 s Pause
 * beginnt eine neue Serie. Zeitstempel kommen vom Finger-Down (MotionEvent-Zeit), nicht vom Loslassen.
 */
class TapTempo {
    private val taps = ArrayList<Long>()
    private var outliersInRow = 0

    data class Result(val periodMs: Double, val anchorMs: Long, val taps: Int)

    fun reset() {
        taps.clear(); outliersInRow = 0
    }

    fun add(t: Long): Result? {
        if (taps.isNotEmpty() && t - taps.last() > 2_000) reset()
        if (taps.size >= 3) {
            val iv = taps.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
            val median = iv[iv.size / 2]
            val d = (t - taps.last()).toDouble()
            if (abs(d - median) > 0.25 * median) {
                // einzelner Ausreißer wird ignoriert, zwei in Folge = Tempowechsel
                if (++outliersInRow < 2) return null
                val last = taps.last(); reset(); taps += last
            }
        }
        outliersInRow = 0
        taps += t
        while (taps.size > 8) taps.removeAt(0)
        if (taps.size < 2) return null
        // y = a + b*x mit x = Tap-Index
        val n = taps.size
        val x = (0 until n).map { it.toDouble() }
        val y = taps.map { (it - taps[0]).toDouble() }
        val mx = x.average(); val my = y.average()
        val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        val b = if (sxx > 0) sxy / sxx else (y.last() - y.first())
        val a = my - b * mx
        val anchor = taps[0] + (a + b * (n - 1)).roundToInt()
        return Result(b, anchor, n)
    }
}

fun roundPeriod(periodMs: Double, rounding: Int): Double {
    if (rounding == 0 || periodMs <= 0) return periodMs
    val bpm = 60_000.0 / periodMs
    val r = if (rounding == 1) bpm.roundToInt().toDouble() else (bpm * 2).roundToInt() / 2.0
    return 60_000.0 / r
}

/** Zeitwert für Learn/Sync. UNVERIFIZIERT, welche Basis der Server erwartet; per Diagnose testbar. */
fun beatTime(unixMs: Long, base: Int): Long = when (base) {
    1 -> (unixMs + 62_135_596_800_000L) * 10_000L   // .NET DateTime.Ticks (UTC)
    else -> unixMs
}

class BeatModule(s: UmbraSession) : Module(s, "Beat") {
    private val stub = BeatClientCoroutineStub(s.channel)

    /** UNVERIFIZIERT: Intervall in ms (per Diagnose "500 ms senden" prüfbar). */
    private val _intervalMs = MutableStateFlow(0)
    val intervalMs: StateFlow<Int> = _intervalMs.asStateFlow()
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _factor = MutableStateFlow(1.0)
    val factor: StateFlow<Double> = _factor.asStateFlow()
    private val _beat = MutableStateFlow(0L)
    val beat: StateFlow<Long> = _beat.asStateFlow()

    /** Lokale Zeit (Unix-ms) des letzten Server-Beats, für Phasen-Nudge. */
    @Volatile var lastBeatAt: Long = 0L
        private set

    /** Lokales Tempo-Modell aus Taps/Mikrofon (für Metronom und Phasenanzeige). */
    private val _localModel = MutableStateFlow<TapTempo.Result?>(null)
    val localModel: StateFlow<TapTempo.Result?> = _localModel.asStateFlow()

    private val tapper = TapTempo()

    override suspend fun load() {
        val r = stub.dl(5).getManualBeat(s.getRequest(), s.headers)
        _intervalMs.value = r.interval
        _enabled.value = r.enabled
        _factor.value = stub.dl(5).getManualBeatFactor(s.getRequest(), s.headers).factor
    }

    override suspend fun stream() {
        stub.receiveManualBeatChanges(s.getRequest(), s.headers).collect { m ->
            if (m.beat != _beat.value) lastBeatAt = System.currentTimeMillis()
            _beat.value = m.beat
            _intervalMs.value = m.interval
            _factor.value = m.factor
        }
    }

    /**
     * Tap mit Zeitstempel des Finger-Down (Unix-ms).
     * mode 0: App rechnet und setzt per SetManualBeat; mode 1: Server lernt (LearnManualBeat).
     */
    fun tap(downUnixMs: Long, mode: Int, timeBase: Int, rounding: Int) {
        if (mode == 1) {
            act("Beat lernen") {
                stub.dl(30).learnManualBeat(
                    ManualBeatLearnRequest.newBuilder()
                        .setRequestId(s.nextRequestId())
                        .setRuntimeId(s.runtimeId)
                        .setTime(beatTime(downUnixMs, timeBase))
                        .setResetFactor(false)
                        .build(),
                    s.headers
                ).requireOk("LearnManualBeat")
            }
            return
        }
        val r = tapper.add(downUnixMs) ?: return
        _localModel.value = r
        val period = roundPeriod(r.periodMs, rounding)
        val interval = period.roundToInt()
        if (interval !in 150..6_000) return
        _intervalMs.value = interval
        // bewusst NICHT über den Fader-Throttler: sofort senden
        act("Tempo setzen") { sendInterval(interval, true) }
    }

    /** Externe Tempoquelle (Mikrofon): Tempo setzen, Phase unverändert. */
    fun applyBpm(bpm: Double, rounding: Int) {
        val period = roundPeriod(60_000.0 / bpm, rounding)
        _localModel.value = TapTempo.Result(period, System.currentTimeMillis(), 0)
        setInterval(period.roundToInt())
    }

    /** Phase setzen: der Beat liegt auf dem übergebenen Zeitpunkt. 30 s Deadline (Timeout-Ursache ungeklärt). */
    fun syncAt(unixMs: Long, timeBase: Int) = act("Beat sync") {
        stub.dl(30).syncManualBeat(
            ManualBeatSyncRequest.newBuilder().setRequestId(s.nextRequestId()).setTime(beatTime(unixMs, timeBase)).build(),
            s.headers
        ).requireOk("SyncManualBeat")
    }

    /** Phasen-Nudge relativ zum zuletzt empfangenen Server-Beat. */
    fun nudgePhase(deltaMs: Long, timeBase: Int) {
        val ref = if (lastBeatAt > 0) lastBeatAt else System.currentTimeMillis()
        syncAt(ref + deltaMs, timeBase)
    }

    fun nudgeBpm(delta: Double) {
        val cur = _intervalMs.value.takeIf { it > 0 } ?: return
        val bpm = (60_000.0 / cur + delta).coerceIn(10.0, 400.0)
        setInterval((60_000.0 / bpm).roundToInt())
    }

    fun multiply(f: Double) {
        val cur = _intervalMs.value.takeIf { it > 0 } ?: return
        setInterval((cur / f).roundToInt())
    }

    fun setBpm(bpm: Double) = setInterval((60_000.0 / bpm.coerceIn(10.0, 400.0)).roundToInt())

    fun setInterval(interval: Int) {
        if (interval !in 150..6_000) return
        _intervalMs.value = interval
        act("Tempo setzen") { sendInterval(interval, true) }
    }

    private suspend fun sendInterval(interval: Int, enabled: Boolean) {
        stub.dl(5).setManualBeat(
            ManualBeatIntervalRequestResponse.newBuilder()
                .setRequestId(s.nextRequestId())
                .setInterval(interval)
                .setEnabled(enabled)
                .setEnabledSet(true)
                .build(),
            s.headers
        ).requireOk("SetManualBeat")
    }

    fun setEnabled(on: Boolean) = act("Beat an/aus") {
        sendInterval(_intervalMs.value.takeIf { it > 0 } ?: 500, on)
        _enabled.value = on
    }

    fun setFactor(f: Double) = act("Beat-Faktor") {
        stub.dl(3).setManualBeatFactor(
            ManualBeatFactorRequestResponse.newBuilder().setRequestId(s.nextRequestId()).setFactor(f).build(),
            s.headers
        ).requireOk("SetManualBeatFactor")
        _factor.value = f
    }

    // ---------------------------------------------------------------- API-Tests (Diagnose)

    /** Sendet 500 ms und liest nach 1,5 s zurück, was der Server meldet. */
    suspend fun testInterval500(): String {
        sendInterval(500, true)
        kotlinx.coroutines.delay(1_500)
        val r = stub.dl(5).getManualBeat(s.getRequest(), s.headers)
        return "Gesendet: 500. Server meldet interval=${r.interval}, enabled=${r.enabled}. " +
            "Zeigt die DMXControl-GUI 120 BPM, ist die Einheit ms."
    }

    /** Ein Learn- bzw. Sync-Aufruf mit 30 s Deadline und Zeitmessung. */
    suspend fun testLearn(timeBase: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val r = stub.dl(30).learnManualBeat(
                ManualBeatLearnRequest.newBuilder().setRequestId(s.nextRequestId()).setRuntimeId(s.runtimeId)
                    .setTime(beatTime(t0, timeBase)).setResetFactor(false).build(),
                s.headers
            )
            "Learn (${if (timeBase == 1) ".NET-Ticks" else "Unix-ms"}): ok=${r.ok} nach ${System.currentTimeMillis() - t0} ms ${r.message.text()}"
        } catch (e: Exception) {
            "Learn (${if (timeBase == 1) ".NET-Ticks" else "Unix-ms"}): ${e.describe()} nach ${System.currentTimeMillis() - t0} ms"
        }
    }

    suspend fun testSync(timeBase: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            val r = stub.dl(30).syncManualBeat(
                ManualBeatSyncRequest.newBuilder().setRequestId(s.nextRequestId()).setTime(beatTime(t0, timeBase)).build(),
                s.headers
            )
            "Sync (${if (timeBase == 1) ".NET-Ticks" else "Unix-ms"}): ok=${r.ok} nach ${System.currentTimeMillis() - t0} ms ${r.message.text()}"
        } catch (e: Exception) {
            "Sync (${if (timeBase == 1) ".NET-Ticks" else "Unix-ms"}): ${e.describe()} nach ${System.currentTimeMillis() - t0} ms"
        }
    }
}
