package com.vtlo.acid303.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Monophonic wobble bass: the voice of dubstep and drum and bass.
 *
 * Two slightly detuned round oscillators (wavetables that morph sine, triangle, rounded square and rounded
 * saw) feed a resonant 24 dB low-pass whose cutoff is swept by a tempo-synced LFO, with an unfiltered sine
 * sub-oscillator an octave below. Everything is smooth by construction (no hard edges), so there is no
 * aliasing to oversample away; the filter is a zero-delay-feedback state-variable pair that stays stable
 * while it is modulated at audio rate.
 *
 * The LFO runs at [lfoInc] cycles per sample, set by the engine from the studio tempo and the rate division;
 * [resetLfo] restarts it on the beat. At phase 0 the filter is fully open.
 *
 * Shared knobs: [cutoff] is the open position of the filter, [resonance] its peak, [decay] the release of
 * a note, [accent] how much an accented note is louder and brighter. [envMod] and [waveform] are unused.
 */
class WobbleVoice(private val fs: Double) : SynthVoice {
    override var cutoff = 0.55
    override var resonance = 0.45
    override var envMod = 0.0
    override var decay = 0.35
    override var accent = 0.7
    override var waveform = 0.0
    override var tuneSemitones = 0.0

    /** 0 sine, 1/3 triangle, 2/3 rounded square, 1 rounded saw; in between they blend. */
    var shape = 0.5
    /** Level of the sine sub-oscillator. */
    var sub = 0.5
    /** Glide time of a slid note. */
    var glide = 0.15
    /** How far the LFO closes the filter: 0 none, 1 six octaves. */
    var lfoDepth = 0.7
    /** LFO waveform, see [LFO_SHAPES]. */
    var lfoShape = 0
    /** LFO speed in cycles per sample. */
    var lfoInc = 0.0

    /** LFO position in cycles, 0..1. */
    var lfoPhase = 0.0
    private var squareSmooth = 1.0
    private val squareCoeff = 1.0 - exp(-1.0 / (0.003 * fs))

    private var phase1 = 0.0
    private var phase2 = 0.0
    private var subPhase = 0.0
    private var pitch = 36.0
    private var target = 36.0
    private var gate = false
    private var accented = false
    private var env = 0.0
    private val attackCoeff = 1.0 - exp(-1.0 / (0.003 * fs))

    // Two state-variable stages (24 dB).
    private var a1 = 0.0
    private var a2 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0

    fun resetLfo() {
        lfoPhase = 0.0
    }

    override fun noteOn(note: Int, accented: Boolean, legato: Boolean) {
        target = note.toDouble()
        this.accented = accented
        if (legato && gate) return
        pitch = target
        // Every note starts with the oscillators in phase: a consistent attack, and the detune beats only build up slowly.
        phase1 = 0.0; phase2 = 0.0; subPhase = 0.0
        gate = true
    }

    override fun noteOff() {
        gate = false
    }

    override fun reset() {
        gate = false
        env = 0.0
        a1 = 0.0; a2 = 0.0; b1 = 0.0; b2 = 0.0
        squareSmooth = 1.0
    }

    /** The LFO at the current phase: 1 fully open, 0 fully closed. */
    private fun lfo(): Double {
        val p = lfoPhase
        return when (lfoShape) {
            0 -> 0.5 + 0.5 * cos(2.0 * PI * p)
            1 -> abs(2.0 * p - 1.0)
            2 -> {
                squareSmooth += ((if (p < 0.5) 1.0 else 0.0) - squareSmooth) * squareCoeff
                squareSmooth
            }
            3 -> 1.0 - p
            else -> p
        }
    }

    override fun render(): Double {
        // Pitch glide (only slid notes move; others snap), then the oscillators.
        val glideS = 0.004 + glide * 0.45
        pitch += (target - pitch) * (1.0 - exp(-1.0 / (glideS * fs)))
        val freq = 440.0 * 2.0.pow((pitch + tuneSemitones - 69.0) / 12.0)
        val inc = (freq / fs).coerceAtMost(0.45)
        val o1 = wave(phase1)
        val o2 = wave(phase2)
        phase1 += inc; if (phase1 >= 1.0) phase1 -= 1.0
        phase2 += inc * DETUNE; if (phase2 >= 1.0) phase2 -= 1.0
        val osc = (o1 + 0.5 * o2) * (1.0 / 1.5)

        // The swept filter.
        val u = lfo()
        lfoPhase += lfoInc
        if (lfoPhase >= 1.0) lfoPhase -= 1.0
        val accentOct = if (accented) 0.8 * accent else 0.0
        val octaves = -lfoDepth * 6.0 * (1.0 - u) + accentOct
        val fc = (BASE_MIN_HZ * (BASE_MAX_HZ / BASE_MIN_HZ).pow(cutoff) * 2.0.pow(octaves)).coerceIn(12.0, 0.45 * fs)
        val g = tan(PI * fc / fs)
        val k = 1.5 - 1.42 * resonance
        val x1 = svf(osc, g, k, true)
        val y = svf(x1, g, 1.2, false)

        // Sub-oscillator, unfiltered, so the low end holds while the filter wobbles.
        val subOut = sin(2.0 * PI * subPhase) * sub * 0.8
        subPhase += inc * 0.5; if (subPhase >= 1.0) subPhase -= 1.0

        // Amplitude: a short attack, a release set by the knob.
        env = if (gate) env + (1.0 - env) * attackCoeff else env * exp(-1.0 / ((0.02 * 60.0.pow(decay)) * fs))
        val gain = if (accented) 1.0 + 0.6 * accent else 1.0
        return (tanh(y * 1.2) + subOut) * env * gain * OUTPUT_GAIN
    }

    private fun svf(v0: Double, g: Double, k: Double, first: Boolean): Double {
        val c1 = if (first) a1 else b1
        val c2 = if (first) a2 else b2
        val a = 1.0 / (1.0 + g * (g + k))
        val v3 = v0 - c2
        val v1 = a * c1 + g * a * v3
        val v2 = c2 + g * v1
        if (first) {
            a1 = 2.0 * v1 - c1; a2 = 2.0 * v2 - c2
        } else {
            b1 = 2.0 * v1 - c1; b2 = 2.0 * v2 - c2
        }
        return v2
    }

    private fun wave(phase: Double): Double {
        val pos = (shape.coerceIn(0.0, 1.0) * 3.0).coerceAtMost(2.9999)
        val i = pos.toInt()
        val f = pos - i
        val x = phase * TABLE_SIZE
        val j = x.toInt()
        val t = x - j
        val j1 = (j + 1) and (TABLE_SIZE - 1)
        val a = TABLES[i][j] + (TABLES[i][j1] - TABLES[i][j]) * t
        val b = TABLES[i + 1][j] + (TABLES[i + 1][j1] - TABLES[i + 1][j]) * t
        return a + (b - a) * f
    }

    companion object {
        const val TABLE_SIZE = 2048
        private const val DETUNE = 1.0046 // 8 cents
        private const val BASE_MIN_HZ = 80.0
        private const val BASE_MAX_HZ = 14000.0
        private const val OUTPUT_GAIN = 0.5

        val LFO_SHAPES = listOf("SINE", "TRI", "SQR", "SAW ↓", "SAW ↑")
        val SHAPES = listOf("SINE", "TRI", "ROUND SQ", "ROUND SAW")

        /** Cycles of the LFO per quarter note for each rate division, slowest first. */
        val RATE_CYCLES = doubleArrayOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0)
        val RATE_LABELS = listOf("1 BAR", "1/2", "1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T", "1/32")

        private val TABLES: Array<DoubleArray> = arrayOf(
            DoubleArray(TABLE_SIZE) { sin(2.0 * PI * it / TABLE_SIZE) },
            DoubleArray(TABLE_SIZE) { asin(sin(2.0 * PI * it / TABLE_SIZE)) * 2.0 / PI },
            normalized(DoubleArray(TABLE_SIZE) { tanh(3.0 * sin(2.0 * PI * it / TABLE_SIZE)) }),
            normalized(DoubleArray(TABLE_SIZE) { i ->
                // Twelve harmonics with a Lanczos taper: a saw that is already rounded off.
                var s = 0.0
                for (n in 1..12) {
                    val sigma = sin(PI * n / 13.0) / (PI * n / 13.0)
                    s += sigma * sin(2.0 * PI * n * i / TABLE_SIZE) / n
                }
                s
            }),
        )

        private fun normalized(a: DoubleArray): DoubleArray {
            val peak = a.maxOf { abs(it) }
            return DoubleArray(a.size) { a[it] / peak }
        }
    }
}
