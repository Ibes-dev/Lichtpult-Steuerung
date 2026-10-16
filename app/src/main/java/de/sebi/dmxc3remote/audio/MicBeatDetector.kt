package de.sebi.dmxc3remote.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tempo-Schätzung aus dem Handy-Mikrofon.
 *  - 16 kHz mono, Blöcke à 256 Samples (16 ms, ~62,5 Blöcke/s)
 *  - Bassband 40–150 Hz per Biquad-Bandpass, Energie je Block
 *  - Onset-Kurve: positive Energiezunahme (Half-Wave-Rectified Flux)
 *  - Tempo: Autokorrelation der Onset-Kurve über 6 s, Suchbereich 70–180 BPM, parabolisch verfeinert
 * Liefert BPM, Konfidenz (0..1) und Pegel. Taugt als Vorschlag, den ein Tap bestätigt (Hybrid).
 */
/** Merkmale der Musik, ca. jede Sekunde aktualisiert. Alle 0..1-Werte relativ, nicht kalibriert. */
data class AudioFeatures(
    val energy: Double,        // Lautheit relativ zum gleitenden Maximum der letzten ~30 s
    val brightness: Double,    // spektraler Schwerpunkt, 0 = dumpf, 1 = hell
    val onsetRate: Double,     // Anschläge pro Sekunde
    val bpm: Double?,
    val key: String,           // z. B. "a-Moll", leer wenn unsicher
    val major: Boolean,
    val keyConfidence: Double,
    val arousal: Double,       // Aktivierung 0..1
    val valence: Double,       // Stimmungsrichtung -1 (dunkel) .. +1 (hell)
    val mood: String,
    val moodIndex: Int
)

/** Stimmungsklassen; Index wird als Zahl an DMXControl gesendet. */
val MOODS = listOf("Stille", "Melancholisch", "Entspannt", "Neutral", "Aggressiv", "Euphorisch")

class MicBeatDetector {
    private val _bpm = MutableStateFlow<Double?>(null)
    val bpm: StateFlow<Double?> = _bpm.asStateFlow()
    private val _confidence = MutableStateFlow(0.0)
    val confidence: StateFlow<Double> = _confidence.asStateFlow()
    private val _level = MutableStateFlow(0.0)
    val level: StateFlow<Double> = _level.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _features = MutableStateFlow<AudioFeatures?>(null)
    val features: StateFlow<AudioFeatures?> = _features.asStateFlow()

    /** Zeitpunkt (Unix-ms) des letzten erkannten Onsets, für die Phase im Hybrid-Modus. */
    @Volatile var lastOnsetAt: Long = 0L
        private set

    private var job: Job? = null

    private val sampleRate = 16_000
    private val hop = 256
    private val frameRate = sampleRate.toDouble() / hop
    private val window = (6 * frameRate).toInt()

    /** Biquad-Bandpass (RBJ, konstante 0-dB-Spitze). */
    private class Biquad(f0: Double, q: Double, fs: Double) {
        private val b0: Double; private val b2: Double; private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init {
            val w0 = 2 * PI * f0 / fs
            val alpha = kotlin.math.sin(w0) / (2 * q)
            val a0 = 1 + alpha
            b0 = alpha / a0; b2 = -alpha / a0
            a1 = -2 * kotlin.math.cos(w0) / a0; a2 = (1 - alpha) / a0
        }
        fun p(x: Double): Double {
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    @SuppressLint("MissingPermission") // Berechtigung wird in der UI vor dem Start angefragt
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _error.value = null
        job = scope.launch(Dispatchers.Default) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED.takeIf { android.os.Build.VERSION.SDK_INT >= 24 } ?: MediaRecorder.AudioSource.MIC,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, hop * 8)
                )
            } catch (e: Exception) {
                _error.value = "Mikrofon nicht verfügbar: ${e.message}"; return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                _error.value = "Mikrofon konnte nicht geöffnet werden"
                rec.release(); return@launch
            }
            val bp = Biquad(78.0, 0.7, sampleRate.toDouble()) // Mitte ~78 Hz, Band grob 40–150 Hz
            val buf = ShortArray(hop)
            val fa = FeatureAnalyzer(sampleRate)
            val onset = DoubleArray(window)
            var idx = 0
            var filled = 0
            var prevEnergy = 0.0
            var smooth = 0.0
            var framesSinceEstimate = 0
            rec.startRecording()
            _running.value = true
            try {
                while (isActive) {
                    val n = rec.read(buf, 0, hop)
                    if (n <= 0) continue
                    var e = 0.0
                    var peak = 0.0
                    for (i in 0 until n) {
                        val x = buf[i] / 32768.0
                        peak = max(peak, abs(x))
                        val y = bp.p(x)
                        e += y * y
                    }
                    fa.push(buf, n)
                    e = sqrt(e / n)
                    _level.value = peak
                    // Onset: Zunahme gegenüber geglätteter Energie
                    smooth = 0.9 * smooth + 0.1 * e
                    val o = max(0.0, e - max(prevEnergy, smooth))
                    prevEnergy = e
                    if (o > 0 && o > 0.6 * (onset.maxOrNull() ?: 0.0)) {
                        lastOnsetAt = System.currentTimeMillis()
                        fa.onset()
                    }
                    onset[idx] = o
                    idx = (idx + 1) % window
                    if (filled < window) filled++
                    if (++framesSinceEstimate >= frameRate.toInt()) {
                        framesSinceEstimate = 0
                        if (filled == window) estimate(onset, idx)
                        _features.value = fa.features(_bpm.value, peak)
                    }
                }
            } finally {
                rec.stop(); rec.release()
                _running.value = false
                _features.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun estimate(ring: DoubleArray, start: Int) {
        val n = ring.size
        val x = DoubleArray(n) { ring[(start + it) % n] }
        val mean = x.average()
        for (i in x.indices) x[i] -= mean
        val r0 = x.sumOf { it * it }
        if (r0 <= 1e-12) { _confidence.value = 0.0; return }
        val minLag = (60.0 / 180.0 * frameRate).toInt()
        val maxLag = (60.0 / 70.0 * frameRate).toInt() + 1
        val r = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var acc = 0.0
            for (i in 0 until n - lag) acc += x[i] * x[i + lag]
            r[lag] = acc
        }
        var best = minLag
        for (lag in minLag..maxLag) if (r[lag] > r[best]) best = lag
        // parabolische Interpolation um das Maximum
        val y0 = r[best - 1]; val y1 = r[best]; val y2 = r[best + 1]
        val denom = y0 - 2 * y1 + y2
        val shift = if (denom != 0.0) 0.5 * (y0 - y2) / denom else 0.0
        val lag = best + shift.coerceIn(-0.5, 0.5)
        val conf = (y1 / r0).coerceIn(0.0, 1.0)
        _confidence.value = conf
        _bpm.value = 60.0 * frameRate / lag
    }
}
