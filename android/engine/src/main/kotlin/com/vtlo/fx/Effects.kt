package com.vtlo.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

private val TWO_PI = 2.0 * PI

private fun pct(v: Float) = "${Math.round(v * 100)}"

/** Mono delay line with cubic (Hermite) interpolation. */
internal class DelayLine(maxDelaySamples: Int) {
    private val size = maxDelaySamples + 8
    private val buf = FloatArray(size)
    private var w = 0

    fun write(x: Float) {
        buf[w] = x
        if (++w == size) w = 0
    }

    /** The sample written [d] samples ago (d >= 2, fractional). */
    fun read(d: Float): Float {
        val dd = d.coerceIn(2f, (size - 4).toFloat())
        val pos = w - 1 - dd
        val i = floor(pos).toInt()
        val t = pos - i
        val ym1 = at(i - 1)
        val y0 = at(i)
        val y1 = at(i + 1)
        val y2 = at(i + 2)
        val c1 = 0.5f * (y1 - ym1)
        val c2 = ym1 - 2.5f * y0 + 2f * y1 - 0.5f * y2
        val c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1)
        return ((c3 * t + c2) * t + c1) * t + y0
    }

    private fun at(i: Int): Float {
        var j = i % size
        if (j < 0) j += size
        return buf[j]
    }

    fun clear() {
        buf.fill(0f)
        w = 0
    }
}

private fun smoothing(fs: Int, seconds: Float) = 1f - exp(-1f / (seconds * fs))

// ─── Flanger ─────────────────────────────────────────────────

/** Swept comb filter: a short modulated delay mixed with the dry signal, with feedback. */
class Flanger(fs: Int) : Effect(
    FxId.FLANGER,
    listOf(
        FxParam("rate", "RATE", 0.35f) { "%.2f Hz".format(rateHz(it)) },
        FxParam("depth", "DEPTH", 0.6f, format = ::pct),
        FxParam("feedback", "FEEDBACK", 0.5f, format = ::pct),
        FxParam("mix", "MIX", 0.6f, format = ::pct),
    ),
    fs,
) {
    private val dl = DelayLine((0.008 * fs).toInt())
    private val dr = DelayLine((0.008 * fs).toInt())
    private var phase = 0.0
    private var mixS = Float.NaN
    private val ms = smoothing(fs, 0.01f)

    override fun reset() {
        dl.clear(); dr.clear(); phase = 0.0; mixS = Float.NaN
    }

    override fun process(buf: FloatArray, frames: Int, bpm: Float) {
        val inc = TWO_PI * rateHz(p(0)) / fs
        val depth = p(1)
        val centre = 0.4f + 2.2f * depth // ms
        val swing = 0.35f + 1.8f * depth
        val fb = p(2) * 0.85f
        val mix = p(3)
        if (mixS.isNaN()) mixS = mix
        val msToSamples = fs / 1000f
        var i = 0
        for (f in 0 until frames) {
            mixS += (mix - mixS) * ms
            val lfoL = sin(phase).toFloat()
            val lfoR = sin(phase + PI / 2).toFloat()
            phase += inc
            if (phase > TWO_PI) phase -= TWO_PI
            val xl = buf[i]
            val xr = buf[i + 1]
            val wl = dl.read((centre + swing * lfoL) * msToSamples)
            val wr = dr.read((centre + swing * lfoR) * msToSamples)
            dl.write(xl + fb * wl)
            dr.write(xr + fb * wr)
            val norm = 1f / (1f + 0.5f * mixS)
            buf[i] = (xl + mixS * wl) * norm
            buf[i + 1] = (xr + mixS * wr) * norm
            i += 2
        }
    }

    private companion object {
        fun rateHz(v: Float) = 0.05f * 100f.pow(v)
    }
}

// ─── Chorus ──────────────────────────────────────────────────

/** Three detuned, slowly modulated voices; SPREAD offsets the left and right modulation. */
class Chorus(fs: Int) : Effect(
    FxId.CHORUS,
    listOf(
        FxParam("rate", "RATE", 0.3f) { "%.2f Hz".format(rateHz(it)) },
        FxParam("depth", "DEPTH", 0.5f, format = ::pct),
        FxParam("spread", "SPREAD", 0.7f, format = ::pct),
        FxParam("mix", "MIX", 0.5f, format = ::pct),
    ),
    fs,
) {
    private val dl = DelayLine((0.04 * fs).toInt())
    private val dr = DelayLine((0.04 * fs).toInt())
    private var phase = 0.0
    private var mixS = Float.NaN
    private val ms = smoothing(fs, 0.01f)

    override fun reset() {
        dl.clear(); dr.clear(); phase = 0.0; mixS = Float.NaN
    }

    override fun process(buf: FloatArray, frames: Int, bpm: Float) {
        val inc = TWO_PI * rateHz(p(0)) / fs
        val depthMs = 0.3f + p(1) * 4.5f
        val spread = p(2) * PI
        val mix = p(3)
        if (mixS.isNaN()) mixS = mix
        val msToSamples = fs / 1000f
        var i = 0
        for (f in 0 until frames) {
            mixS += (mix - mixS) * ms
            val xl = buf[i]
            val xr = buf[i + 1]
            dl.write(xl)
            dr.write(xr)
            var wl = 0f
            var wr = 0f
            for (v in 0 until 3) {
                val ph = phase + v * TWO_PI / 3
                val base = BASE_MS[v]
                wl += dl.read((base + depthMs * sin(ph).toFloat()) * msToSamples)
                wr += dr.read((base + depthMs * sin(ph + spread).toFloat()) * msToSamples)
            }
            phase += inc
            if (phase > TWO_PI) phase -= TWO_PI
            buf[i] = xl * (1f - 0.5f * mixS) + wl * (0.3f * mixS)
            buf[i + 1] = xr * (1f - 0.5f * mixS) + wr * (0.3f * mixS)
            i += 2
        }
    }

    private companion object {
        val BASE_MS = floatArrayOf(13f, 17f, 21f)
        fun rateHz(v: Float) = 0.1f * 40f.pow(v)
    }
}

// ─── Beat repeat ─────────────────────────────────────────────

/**
 * Captures the last slice of the mix when switched on and loops it, locked to the tempo
 * (the slice is a note division at the studio BPM). While looping the input is frozen out;
 * on release the live signal returns.
 */
class BeatRepeat(fs: Int) : Effect(
    FxId.BEAT_REPEAT,
    listOf(
        FxParam("grid", "GRID", 0.5f, detents = 5) { GRIDS[Math.round(it * 4)].label },
        FxParam("gate", "GATE", 1f, format = ::pct),
        FxParam("decay", "DECAY", 0.2f, format = ::pct),
        FxParam("mix", "MIX", 1f, format = ::pct),
    ),
    fs,
) {
    private class Grid(val label: String, val beats: Float)

    override val selfBypass: Boolean get() = true

    private val size = (3.2 * fs).toInt()
    private val ringL = FloatArray(size)
    private val ringR = FloatArray(size)
    private var w = 0
    private var active = false
    private var start = 0
    private var len = 1
    private var t = 0
    private var gain = 1f
    private var env = 0f
    private val xf = max(8, (0.002 * fs).toInt())
    private val envStep = smoothing(fs, 0.004f)

    override fun reset() {
        ringL.fill(0f); ringR.fill(0f)
        w = 0; active = false; env = 0f; t = 0; gain = 1f
    }

    override fun process(buf: FloatArray, frames: Int, bpm: Float) {
        if (on && !active) {
            val beat = fs * 60f / bpm.coerceIn(30f, 300f)
            len = (beat * GRIDS[Math.round(p(0) * 4)].beats).toInt().coerceIn(64, size - 1)
            start = ((w - len) % size + size) % size
            t = 0
            gain = 1f
            active = true
        }
        val gate = p(1).coerceIn(0.05f, 1f)
        val decay = p(2)
        val mix = p(3)
        val target = if (on) 1f else 0f
        var i = 0
        for (f in 0 until frames) {
            val inL = buf[i]
            val inR = buf[i + 1]
            if (!active) {
                ringL[w] = inL
                ringR[w] = inR
                if (++w == size) w = 0
                i += 2
                continue
            }
            env += (target - env) * envStep
            var idx = start + t
            if (idx >= size) idx -= size
            val gateLen = max(xf * 2, (gate * len).toInt())
            var g = gain
            if (t < xf) g *= t / xf.toFloat()
            if (t > gateLen - xf) g *= max(0f, (gateLen - t) / xf.toFloat())
            val m = mix * env
            buf[i] = inL * (1f - m) + ringL[idx] * g * m
            buf[i + 1] = inR * (1f - m) + ringR[idx] * g * m
            if (++t >= len) {
                t = 0
                gain *= 1f - decay * 0.35f
            }
            if (!on && env < 0.002f) {
                active = false
                env = 0f
            }
            i += 2
        }
    }

    private companion object {
        val GRIDS = listOf(Grid("1/2", 2f), Grid("1/4", 1f), Grid("1/8", 0.5f), Grid("1/16", 0.25f), Grid("1/32", 0.125f))
    }
}

// ─── Vowel (original) ────────────────────────────────────────

/**
 * A talking filter: three resonant band-passes placed on the formants of the vowels
 * A, E, I, O, U. VOWEL picks a position along that scale; RATE and DEPTH sweep it with an
 * LFO (opposite phase left and right), so the mix seems to speak.
 */
class Vowel(fs: Int) : Effect(
    FxId.VOWEL,
    listOf(
        FxParam("vowel", "VOWEL", 0f) { NAMES[Math.round(it * 4)] },
        FxParam("rate", "RATE", 0.35f) { if (it < 0.01f) "OFF" else "%.2f Hz".format(rateHz(it)) },
        FxParam("depth", "DEPTH", 0.5f, format = ::pct),
        FxParam("mix", "MIX", 0.85f, format = ::pct),
    ),
    fs,
) {
    private val ic1 = FloatArray(6)
    private val ic2 = FloatArray(6)
    private val a1 = FloatArray(6)
    private val a2 = FloatArray(6)
    private val a3 = FloatArray(6)
    private var phase = 0.0
    private var mixS = Float.NaN
    private val ms = smoothing(fs, 0.01f)

    init {
        for (b in 0 until 6) updateBand(b, 0, 1000f)
    }

    override fun reset() {
        ic1.fill(0f); ic2.fill(0f); phase = 0.0; mixS = Float.NaN
    }

    private fun updateBand(slot: Int, band: Int, hz: Float) {
        val g = tan(PI * min(hz, fs * 0.45f) / fs).toFloat()
        val k = 1f / Q[band]
        val a = 1f / (1f + g * (g + k))
        a1[slot] = a
        a2[slot] = g * a
        a3[slot] = g * g * a
    }

    override fun process(buf: FloatArray, frames: Int, bpm: Float) {
        val rate = p(1)
        val inc = if (rate < 0.01f) 0.0 else TWO_PI * rateHz(rate) / fs
        val depth = p(2)
        val base = p(0) * 4f
        val mix = p(3)
        if (mixS.isNaN()) mixS = mix
        var f = 0
        while (f < frames) {
            val n = min(BLOCK, frames - f)
            // Update the formant coefficients once per block, per channel.
            for (ch in 0 until 2) {
                val lfo = if (inc == 0.0) 0f else sin(phase + ch * PI / 2).toFloat()
                val pos = (base + depth * 2f * lfo).coerceIn(0f, 4f)
                val i0 = min(pos.toInt(), 3)
                val fr = pos - i0
                for (b in 0 until 3) {
                    updateBand(ch * 3 + b, b, FORMANTS[i0][b] * (1f - fr) + FORMANTS[i0 + 1][b] * fr)
                }
            }
            phase += inc * n
            if (phase > TWO_PI) phase -= TWO_PI
            var idx = f * 2
            for (s in 0 until n) {
                mixS += (mix - mixS) * ms
                for (ch in 0 until 2) {
                    val x = buf[idx + ch]
                    var wet = 0f
                    for (b in 0 until 3) {
                        val k = ch * 3 + b
                        val v3 = x - ic2[k]
                        val v1 = a1[k] * ic1[k] + a2[k] * v3
                        val v2 = ic2[k] + a2[k] * ic1[k] + a3[k] * v3
                        ic1[k] = 2f * v1 - ic1[k]
                        ic2[k] = 2f * v2 - ic2[k]
                        wet += GAINS[b] * v1 / Q[b] // band-pass scaled to unity peak gain
                    }
                    buf[idx + ch] = x * (1f - mixS) + wet * (2.4f * mixS)
                }
                idx += 2
            }
            f += n
        }
    }

    private companion object {
        const val BLOCK = 16
        val NAMES = listOf("A", "E", "I", "O", "U")
        val FORMANTS = arrayOf(
            floatArrayOf(800f, 1150f, 2900f), // A
            floatArrayOf(400f, 1600f, 2700f), // E
            floatArrayOf(350f, 1700f, 2700f), // I
            floatArrayOf(450f, 800f, 2830f), // O
            floatArrayOf(325f, 700f, 2530f), // U
        )
        val Q = floatArrayOf(6f, 9f, 12f)
        val GAINS = floatArrayOf(1f, 0.8f, 0.5f)
        fun rateHz(v: Float) = 0.1f * 60f.pow(v)
    }
}

// ─── Warble (original) ───────────────────────────────────────

/**
 * Worn tape: slow wow and fast flutter bend the pitch through a short modulated delay, a
 * random drift keeps it from repeating, and HEAT adds saturation and rolls off the highs.
 */
class Warble(fs: Int) : Effect(
    FxId.WARBLE,
    listOf(
        FxParam("wow", "WOW", 0.4f, format = ::pct),
        FxParam("flutter", "FLUTTER", 0.3f, format = ::pct),
        FxParam("heat", "HEAT", 0.35f, format = ::pct),
        FxParam("mix", "MIX", 1f, format = ::pct),
    ),
    fs,
) {
    private val dl = DelayLine((0.03 * fs).toInt())
    private val dr = DelayLine((0.03 * fs).toInt())
    private var wowPh = 0.0
    private var wow2Ph = 0.0
    private var flPh = 0.0
    private var fl2Ph = 0.0
    private var drift = 0f
    private var rng = 0x2545F491
    private var lpL = 0f
    private var lpR = 0f
    private var mixS = Float.NaN
    private val ms = smoothing(fs, 0.01f)

    override fun reset() {
        dl.clear(); dr.clear(); lpL = 0f; lpR = 0f; drift = 0f; mixS = Float.NaN
    }

    private fun noise(): Float {
        rng = rng xor (rng shl 13); rng = rng xor (rng ushr 17); rng = rng xor (rng shl 5)
        return rng / 2147483648f
    }

    override fun process(buf: FloatArray, frames: Int, bpm: Float) {
        val wow = p(0)
        val flutter = p(1)
        val heat = p(2)
        val mix = p(3)
        if (mixS.isNaN()) mixS = mix
        val wowAmp = 2.6f * wow
        val flAmp = 0.2f * flutter
        val driftAmp = 9f * wow
        val drive = 1f + heat * 6f
        val comp = 1f / (1f + 0.35f * (drive - 1f))
        val fc = 14000f - heat * 10500f
        val lp = exp(-TWO_PI * fc / fs).toFloat()
        val msToSamples = fs / 1000f
        val incWow = TWO_PI * 0.6 / fs
        val incWow2 = TWO_PI * 0.23 / fs
        val incFl = TWO_PI * 7.3 / fs
        val incFl2 = TWO_PI * 11.7 / fs
        var i = 0
        for (f in 0 until frames) {
            mixS += (mix - mixS) * ms
            drift += (noise() - drift) * 0.0002f
            val common = wowAmp * (0.8f * sin(wowPh).toFloat() + 0.2f * sin(wow2Ph).toFloat()) + driftAmp * drift
            val modL = common + flAmp * (sin(flPh).toFloat() + 0.4f * sin(fl2Ph).toFloat())
            val modR = common * 0.9f + flAmp * (sin(flPh + 1.1).toFloat() + 0.4f * sin(fl2Ph + 0.7).toFloat())
            wowPh += incWow; wow2Ph += incWow2; flPh += incFl; fl2Ph += incFl2
            if (wowPh > TWO_PI) wowPh -= TWO_PI
            if (wow2Ph > TWO_PI) wow2Ph -= TWO_PI
            if (flPh > TWO_PI) flPh -= TWO_PI
            if (fl2Ph > TWO_PI) fl2Ph -= TWO_PI
            val xl = buf[i]
            val xr = buf[i + 1]
            dl.write(xl)
            dr.write(xr)
            var wl = dl.read((BASE_MS + modL) * msToSamples)
            var wr = dr.read((BASE_MS + modR) * msToSamples)
            wl = tanh(drive * wl) * comp
            wr = tanh(drive * wr) * comp
            lpL = (1f - lp) * wl + lp * lpL
            lpR = (1f - lp) * wr + lp * lpR
            buf[i] = xl * (1f - mixS) + lpL * mixS
            buf[i + 1] = xr * (1f - mixS) + lpR * mixS
            i += 2
        }
    }

    private companion object {
        const val BASE_MS = 7f
    }
}

// ─── Ghost (original) ────────────────────────────────────────

/**
 * Reverse echoes: the signal is read backwards in overlapping windowed grains, and fed back
 * so the reversed repeats are themselves reversed again, haunting the mix.
 */
class Ghost(fs: Int) : Effect(
    FxId.GHOST,
    listOf(
        FxParam("time", "TIME", 0.45f) { "${Math.round(timeSec(it) * 1000)}ms" },
        FxParam("feedback", "FEEDBACK", 0.45f, format = ::pct),
        FxParam("tone", "TONE", 0.6f) { "%.1fk".format(toneHz(it) / 1000f) },
        FxParam("mix", "MIX", 0.5f, format = ::pct),
    ),
    fs,
) {
    private val size = (2.3 * fs).toInt()
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var w = 0
    private val gt = IntArray(2)
    private val gstart = IntArray(2)
    private val glen = IntArray(2)
    private var initialised = false
    private var lpL = 0f
    private var lpR = 0f
    private var mixS = Float.NaN
    private val ms = smoothing(fs, 0.01f)

    override fun reset() {
        bufL.fill(0f); bufR.fill(0f); w = 0; initialised = false; lpL = 0f; lpR = 0f; mixS = Float.NaN
    }

    private fun wrap(i: Int): Int {
        var j = i % size
        if (j < 0) j += size
        return j
    }

    override fun process(buf: FloatArray, frames: Int, bpm: Float) {
        val len = (timeSec(p(0)) * fs).toInt().coerceIn(256, size / 2 - 8)
        val fb = p(1) * 0.85f
        val lp = exp(-TWO_PI * toneHz(p(2)) / fs).toFloat()
        val mix = p(3)
        if (mixS.isNaN()) mixS = mix
        if (!initialised) {
            initialised = true
            for (g in 0 until 2) glen[g] = len
            gt[0] = 0; gstart[0] = wrap(w - 1)
            gt[1] = len / 2; gstart[1] = wrap(w - 1 - len / 2)
        }
        var i = 0
        for (f in 0 until frames) {
            mixS += (mix - mixS) * ms
            var wl = 0f
            var wr = 0f
            for (g in 0 until 2) {
                val s = sin(PI * gt[g] / glen[g]).toFloat()
                val win = s * s
                val idx = wrap(gstart[g] - gt[g])
                wl += bufL[idx] * win
                wr += bufR[idx] * win
            }
            lpL = (1f - lp) * wl + lp * lpL
            lpR = (1f - lp) * wr + lp * lpR
            val xl = buf[i]
            val xr = buf[i + 1]
            bufL[w] = xl + fb * lpL
            bufR[w] = xr + fb * lpR
            if (++w == size) w = 0
            for (g in 0 until 2) {
                if (++gt[g] >= glen[g]) {
                    gt[g] = 0
                    glen[g] = len
                    gstart[g] = wrap(w - 1)
                }
            }
            buf[i] = xl * (1f - 0.5f * mixS) + lpL * mixS
            buf[i + 1] = xr * (1f - 0.5f * mixS) + lpR * mixS
            i += 2
        }
    }

    private companion object {
        fun timeSec(v: Float) = 0.12f * 7.5f.pow(v)
        fun toneHz(v: Float) = 1200f * 10f.pow(v)
    }
}
