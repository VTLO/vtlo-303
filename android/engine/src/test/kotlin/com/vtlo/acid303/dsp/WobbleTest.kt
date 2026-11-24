package com.vtlo.acid303.dsp

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WobbleTest {
    private val fs = 48000

    private fun engine(bpm: Float = 150f, preset: Int = 3, configure: AcidEngine.() -> Unit = {}): AcidEngine =
        AcidEngine(fs, wobble = true).apply {
            Presets.load(bank, 0, Presets.wobble[preset])
            set(Param.TEMPO, Param.bpmToNorm(bpm))
            set(Param.DRIVE, 0f); set(Param.DELAY, 0f); set(Param.REVERB, 0f); set(Param.SUB, 0f)
            configure()
        }

    private fun render(e: AcidEngine, seconds: Double): FloatArray {
        e.play()
        val frames = (seconds * fs).toInt()
        val out = FloatArray(frames * 2)
        val buf = FloatArray(256 * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(256, frames - done)
            e.render(buf, n)
            System.arraycopy(buf, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    /** RMS in windows of [ms] milliseconds, left channel. */
    private fun envelope(x: FloatArray, ms: Int): DoubleArray {
        val w = fs * ms / 1000
        return DoubleArray(x.size / 2 / w) { k ->
            var s = 0.0
            for (i in 0 until w) { val v = x[(k * w + i) * 2].toDouble(); s += v * v }
            sqrt(s / w)
        }
    }


    /** The shortest repeat period (seconds) of an envelope sampled every 5 ms: the first strong autocorrelation peak. */
    private fun period(env: DoubleArray, minLag: Int = 20, maxLag: Int = 200): Double {
        val mean = env.average()
        val d = DoubleArray(env.size) { env[it] - mean }
        val ac = DoubleArray(maxLag + 2) { lag -> var s = 0.0; for (i in 0 until d.size - lag) s += d[i] * d[i + lag]; s }
        val best = (minLag..maxLag).maxOf { ac[it] }
        val lag = (minLag..maxLag).first { ac[it] >= 0.8 * best && ac[it] >= ac[it - 1] && ac[it] >= ac[it + 1] }
        return lag * 0.005
    }

    @Test
    fun theWobbleLocksToTheTempo() {
        // One held note, 1/8 rate (2 cycles per beat) at 150 BPM: the filter opens every 0.2 s.
        val e = engine(150f) { set(Param.WOB_RATE, 4f / 8f); set(Param.WOB_DEPTH, 1f); set(Param.RESONANCE, 0.3f) }
        val x = render(e, 6.0)
        val env = envelope(x, 5).drop(200).toDoubleArray() // skip the first second
        assertEquals(0.2, period(env, 20, 80), 0.011)
    }

    @Test
    fun depthSetsHowFarTheFilterCloses() {
        fun swing(depth: Float): Double {
            val x = render(engine(150f) { set(Param.WOB_RATE, 4f / 8f); set(Param.WOB_DEPTH, depth); set(Param.CUTOFF, 0.5f) }, 4.0)
            // The middle of the first bar (0.3 s to 1.4 s), after the attack and before the gap between loops.
            // Brightness (the second difference of the signal: a gentle high-pass) over windows longer than the note's period.
            val bright = FloatArray(x.size) { if (it < 4) 0f else x[it] - 2 * x[it - 2] + x[it - 4] }
            val env = envelope(bright, 25).slice(12 until 56)
            return env.max() / env.min().coerceAtLeast(1e-6)
        }
        assertTrue("no depth should not wobble", swing(0f) < 1.6) // only the slow drift of the detune
        assertTrue("full depth should wobble", swing(1f) > 3.0)
    }

    @Test
    fun theRateKnobPicksTheDivision() {
        fun period(rate: Float): Double {
            val e = engine(120f) { set(Param.WOB_RATE, rate); set(Param.WOB_DEPTH, 1f); set(Param.RESONANCE, 0.2f) }
            return period(envelope(render(e, 8.0), 5).drop(200).toDoubleArray())
        }
        // At 120 BPM a beat is 0.5 s: 1/4 = 0.5 s, 1/8 = 0.25 s.
        assertEquals(0.5, period(2f / 8f), 0.04)
        assertEquals(0.25, period(4f / 8f), 0.012)
    }

    @Test
    fun everyPresetStaysWellUnderFullScale() {
        for (i in Presets.wobble.indices) {
            val e = engine(Presets.wobble[i].bpm.toFloat(), i) { set(Param.SUB, 0.5f); set(Param.DRIVE, 0.35f) }
            val x = render(e, 6.0)
            val peak = x.maxOf { abs(it) }
            assertTrue("${Presets.wobble[i].name} peaks at $peak", peak < 0.8f)
            assertTrue("${Presets.wobble[i].name} is nearly silent", peak > 0.05f)
        }
    }

    @Test
    fun theOscillatorShapesDiffer() {
        fun brightness(shape: Float): Double {
            val e = engine(150f) { set(Param.SHAPE, shape); set(Param.WOB_DEPTH, 0f); set(Param.CUTOFF, 1f); set(Param.RESONANCE, 0f) }
            val x = render(e, 2.0)
            var diff = 0.0
            var total = 0.0
            for (i in 2 * fs until x.size - 2 step 2) {
                val a = x[i].toDouble(); val b = x[i + 2].toDouble()
                diff += (b - a) * (b - a); total += a * a
            }
            return diff / total
        }
        val sine = brightness(0f)
        val saw = brightness(1f)
        assertTrue("a rounded saw ($saw) should be brighter than a sine ($sine)", saw > sine * 2)
    }

    @Test
    fun aSlidedNoteGlidesAndAnOrdinaryOneJumps() {
        // Two engines, same pitches; only the slide flag differs.
        fun lowEnergyAfterJump(slide: Boolean): Double {
            val e = AcidEngine(fs, wobble = true)
            e.bank.setStep(0, 0, PatternBank.pack(29, true, false, slide))
            e.bank.setStep(0, 1, PatternBank.pack(41, true, false, false))
            e.bank.setLength(0, 2)
            e.set(Param.TEMPO, Param.bpmToNorm(60f)); e.set(Param.GLIDE, 1f); e.set(Param.WOB_DEPTH, 0f)
            e.set(Param.CUTOFF, 1f); e.set(Param.DRIVE, 0f); e.set(Param.DELAY, 0f); e.set(Param.REVERB, 0f); e.set(Param.SUB, 0f)
            val x = render(e, 0.45)
            // Count zero crossings in the 80 ms after step 1 starts (0.25 s): a glide is still low in pitch.
            var z = 0
            for (i in (0.26 * fs).toInt() until (0.34 * fs).toInt()) if (x[i * 2] * x[(i + 1) * 2] < 0) z++
            return z.toDouble()
        }
        assertTrue(lowEnergyAfterJump(true) < lowEnergyAfterJump(false))
    }
}
