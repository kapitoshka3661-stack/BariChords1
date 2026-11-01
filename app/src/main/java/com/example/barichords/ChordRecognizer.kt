package com.example.barichords

import kotlin.math.*

enum class ChordType(val suffix: String, val intervals: IntArray, val penalty: Float) {
    MAJ("", intArrayOf(0, 4, 7), 0f),
    MIN("m", intArrayOf(0, 3, 7), 0f),
    DOM7("7", intArrayOf(0, 4, 7, 10), 0.05f),
    MIN7("m7", intArrayOf(0, 3, 7, 10), 0.05f),
    MAJ7("maj7", intArrayOf(0, 4, 7, 11), 0.06f)
}

val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

data class Chord(val root: Int, val type: ChordType) {
    fun transposed(semi: Int) = Chord(((root + semi) % 12 + 12) % 12, type)
    val name get() = NOTE_NAMES[root] + type.suffix
}

/** chord == null означає тишу / немає акорду */
data class ChordSegment(val startSec: Float, val endSec: Float, val chord: Chord?)

class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        for (i in 0 until n / 2) {
            val a = -2.0 * PI * i / n
            cosT[i] = cos(a).toFloat(); sinT[i] = sin(a).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val tr = re[j + half] * cosT[k] - im[j + half] * sinT[k]
                    val ti = re[j + half] * sinT[k] + im[j + half] * cosT[k]
                    re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

object ChordRecognizer {
    private const val N = 8192      // розмір вікна FFT (~370 мс при 22050 Гц)
    private const val HOP = 2048    // крок (~93 мс)
    private const val BETA = 10f    // "різкість" емісій у Viterbi
    private const val P_STAY = 0.9  // ймовірність залишитись на тому ж акорді

    private val types = ChordType.values()
    private val S = 12 * types.size + 1          // + стан "N" (тиша)
    private val NO_CHORD = S - 1

    /** Центровані й нормовані шаблони акордів (для кореляції Пірсона). */
    private val templates: Array<FloatArray> = Array(S - 1) { s ->
        val root = s % 12
        val type = types[s / 12]
        val t = FloatArray(12)
        type.intervals.forEachIndexed { idx, iv -> t[(root + iv) % 12] = if (idx == 0) 1.2f else 1f }
        center(t)
        t
    }

    private fun center(v: FloatArray) {
        val m = v.average().toFloat()
        var nrm = 0f
        for (i in v.indices) { v[i] -= m; nrm += v[i] * v[i] }
        nrm = sqrt(nrm)
        if (nrm > 1e-6f) for (i in v.indices) v[i] /= nrm
    }

    fun analyze(pcm: FloatArray, sr: Int, onProgress: (Float) -> Unit = {}): List<ChordSegment> {
        val frames = (pcm.size - N) / HOP + 1
        if (frames <= 0) return emptyList()

        val fft = Fft(N)
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }

        // відповідність біну FFT -> клас висоти (0=C ... 11=B), лише чіткі півтони 82..2000 Гц
        val binPc = IntArray(N / 2) { -1 }
        for (k in 1 until N / 2) {
            val f = k * sr.toDouble() / N
            if (f in 82.0..2000.0) {
                val midi = 69 + 12 * log2(f / 440.0)
                val r = midi.roundToInt()
                if (abs(midi - r) <= 0.4) binPc[k] = ((r % 12) + 12) % 12
            }
        }

        val chroma = FloatArray(frames * 12)
        val rms = FloatArray(frames)
        val re = FloatArray(N); val im = FloatArray(N)
        for (fr in 0 until frames) {
            val off = fr * HOP
            var e = 0f
            for (i in 0 until N) { val x = pcm[off + i]; e += x * x; re[i] = x * window[i]; im[i] = 0f }
            rms[fr] = sqrt(e / N)
            fft.transform(re, im)
            for (k in 1 until N / 2) {
                val pc = binPc[k]
                if (pc < 0) continue
                val a = sqrt(re[k] * re[k] + im[k] * im[k]) / (N / 4f)
                chroma[fr * 12 + pc] += ln(1f + 100f * a)
            }
            if (fr % 50 == 0) onProgress(fr.toFloat() / frames * 0.8f)
        }

        // згладжування по часу (±2 кадри) + центрування/нормування
        val sm = FloatArray(frames * 12)
        for (fr in 0 until frames) {
            val a = max(0, fr - 2); val b = min(frames - 1, fr + 2)
            val v = FloatArray(12)
            for (j in a..b) for (p in 0 until 12) v[p] += chroma[j * 12 + p]
            center(v)
            System.arraycopy(v, 0, sm, fr * 12, 12)
        }

        val maxRms = rms.max()
        val silence = max(0.001f, maxRms * 0.03f)

        // Viterbi
        val logStay = ln(P_STAY).toFloat()
        val logSwitch = ln((1 - P_STAY) / (S - 1)).toFloat()
        val stayFlag = BooleanArray(frames * S)
        val bestPrev = IntArray(frames)
        var delta = FloatArray(S)
        var next = FloatArray(S)

        fun emission(fr: Int, s: Int): Float {
            val silent = rms[fr] < silence
            if (s == NO_CHORD) return BETA * (if (silent) 1f else 0.2f)
            if (silent) return -BETA
            var dot = 0f
            val t = templates[s]
            for (p in 0 until 12) dot += sm[fr * 12 + p] * t[p]
            return BETA * (dot - types[s / 12].penalty)
        }

        for (s in 0 until S) delta[s] = emission(0, s)
        for (fr in 1 until frames) {
            var bi = 0
            for (s in 1 until S) if (delta[s] > delta[bi]) bi = s
            bestPrev[fr - 1] = bi
            val sw = delta[bi] + logSwitch
            for (s in 0 until S) {
                val st = delta[s] + logStay
                val stay = st >= sw
                stayFlag[fr * S + s] = stay
                next[s] = max(st, sw) + emission(fr, s)
            }
            val tmp = delta; delta = next; next = tmp
            if (fr % 100 == 0) onProgress(0.8f + fr.toFloat() / frames * 0.2f)
        }

        var state = 0
        for (s in 1 until S) if (delta[s] > delta[state]) state = s
        val path = IntArray(frames)
        for (fr in frames - 1 downTo 0) {
            path[fr] = state
            if (fr > 0 && !stayFlag[fr * S + state]) state = bestPrev[fr - 1]
        }

        // у сегменти
        val segs = ArrayList<ChordSegment>()
        fun timeOf(fr: Int) = if (fr == 0) 0f else (fr * HOP + N / 2).toFloat() / sr
        val total = pcm.size.toFloat() / sr
        var start = 0
        for (fr in 1..frames) {
            if (fr == frames || path[fr] != path[start]) {
                val s = path[start]
                val chord = if (s == NO_CHORD) null else Chord(s % 12, types[s / 12])
                segs.add(ChordSegment(timeOf(start), if (fr == frames) total else timeOf(fr), chord))
                start = fr
            }
        }
        onProgress(1f)
        return segs
    }
}
