package de.sebi.dmxc3remote.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stimmungsmerkmale ohne Machine Learning (Heuristik, bewusst einfach und erklärbar):
 *  - Energie: RMS relativ zu einem langsam abfallenden Maximum (gleicht Mikrofon-Pegel grob aus)
 *  - Helligkeit: spektraler Schwerpunkt (FFT 2048) zwischen 300 und 3000 Hz normiert
 *  - Tonart/Dur-Moll: Chroma-Vektor (65 Hz–2 kHz) mit ~8 s Gedächtnis, Korrelation mit
 *    Krumhansl-Kessler-Profilen für 24 Tonarten
 *  - Aktivierung (Arousal) aus Energie, Anschlagsdichte und Tempo; Valenz aus Dur/Moll und Helligkeit
 *  - Stimmung = Quadrant aus Arousal/Valenz, plus "Stille" bei sehr niedrigem Pegel
 */
class FeatureAnalyzer(private val sampleRate: Int) {
    private val n = 2048
    private val ring = FloatArray(n)
    private var pos = 0
    private var sinceFft = 0

    private val chroma = DoubleArray(12)
    private var energyMax = 1e-4
    private var energy = 0.0
    private var centroidNorm = 0.5
    private val onsets = ArrayDeque<Long>()

    private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

    fun push(buf: ShortArray, count: Int) {
        var e = 0.0
        for (i in 0 until count) {
            val x = buf[i] / 32768f
            ring[pos] = x; pos = (pos + 1) % n
            e += x * x
        }
        val rms = sqrt(e / count)
        // gleitendes Maximum mit ~30 s Abfall
        energyMax = max(rms, energyMax * 0.9995)
        energy = 0.95 * energy + 0.05 * (rms / energyMax).coerceIn(0.0, 1.0)
        sinceFft += count
        if (sinceFft >= n / 2) {
            sinceFft = 0
            spectrum()
        }
    }

    fun onset() {
        val now = System.currentTimeMillis()
        onsets.addLast(now)
        while (onsets.isNotEmpty() && now - onsets.first() > 5_000) onsets.removeFirst()
    }

    private fun spectrum() {
        val re = DoubleArray(n) { ring[(pos + it) % n] * window[it] }
        val im = DoubleArray(n)
        fft(re, im)
        val binHz = sampleRate.toDouble() / n
        var num = 0.0; var den = 0.0
        val frame = DoubleArray(12)
        for (k in 1 until n / 2) {
            val f = k * binHz
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            num += f * mag; den += mag
            if (f in 65.0..2000.0) {
                val midi = 69 + 12 * ln(f / 440.0) / ln(2.0)
                val pc = ((midi.roundToInt() % 12) + 12) % 12
                frame[pc] += mag
            }
        }
        if (den > 0) {
            val c = num / den
            centroidNorm = 0.9 * centroidNorm + 0.1 * ((c - 300) / 2700).coerceIn(0.0, 1.0)
        }
        // Chroma mit ~8 s Gedächtnis (ein Spektrum alle 64 ms)
        val sum = frame.sum()
        if (sum > 0) for (i in 0 until 12) chroma[i] = 0.992 * chroma[i] + 0.008 * frame[i] / sum
    }

    private fun corr(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) {
            val x = a[i] - ma; val y = b[i] - mb
            sab += x * y; saa += x * x; sbb += y * y
        }
        return if (saa > 0 && sbb > 0) sab / sqrt(saa * sbb) else 0.0
    }

    fun features(bpm: Double?, peak: Double): AudioFeatures {
        // Tonart
        var best = -2.0; var second = -2.0; var bestKey = 0; var bestMajor = true
        for (root in 0 until 12) for (major in listOf(true, false)) {
            val prof = if (major) MAJOR else MINOR
            val rotated = DoubleArray(12) { prof[(it - root + 12) % 12] }
            val c = corr(chroma, rotated)
            if (c > best) { second = best; best = c; bestKey = root; bestMajor = major }
            else if (c > second) second = c
        }
        val keyConf = ((best - second) * 4).coerceIn(0.0, 1.0) * best.coerceIn(0.0, 1.0)
        val key = if (best > 0.3) NAMES[bestKey].let { if (bestMajor) "$it-Dur" else "${it.lowercase()}-Moll" } else ""

        val onsetRate = onsets.size / 5.0
        val tempoTerm = bpm?.let { ((it - 70) / 110).coerceIn(0.0, 1.0) } ?: 0.5
        val arousal = (0.5 * energy + 0.3 * (onsetRate / 4).coerceIn(0.0, 1.0) + 0.2 * tempoTerm).coerceIn(0.0, 1.0)
        val modeTerm = if (key.isEmpty()) 0.0 else (if (bestMajor) 1.0 else -1.0) * keyConf
        val valence = (0.6 * modeTerm + 0.4 * (centroidNorm * 2 - 1)).coerceIn(-1.0, 1.0)

        val moodIndex = when {
            peak < 0.01 && energy < 0.05 -> 0
            arousal < 0.4 && valence < -0.1 -> 1
            arousal < 0.4 -> 2
            arousal < 0.6 && kotlin.math.abs(valence) <= 0.2 -> 3
            valence < 0 -> 4
            else -> 5
        }
        return AudioFeatures(
            energy = energy, brightness = centroidNorm, onsetRate = onsetRate, bpm = bpm,
            key = key, major = bestMajor, keyConfidence = keyConf,
            arousal = arousal, valence = valence, mood = MOODS[moodIndex], moodIndex = moodIndex
        )
    }

    companion object {
        val NAMES = listOf("C", "Cis", "D", "Es", "E", "F", "Fis", "G", "As", "A", "B", "H")
        // Krumhansl-Kessler-Profile
        val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** In-place Radix-2-FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar; im[i + k] += ai
                        val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
