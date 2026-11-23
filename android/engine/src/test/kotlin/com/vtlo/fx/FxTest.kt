package com.vtlo.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FxTest {
    private val fs = 48000

    private fun run(rack: FxRack, input: FloatArray, bpm: Float = 120f, block: Int = 480): FloatArray {
        val out = input.copyOf()
        var f = 0
        val frames = input.size / 2
        while (f < frames) {
            val n = minOf(block, frames - f)
            val chunk = out.copyOfRange(f * 2, (f + n) * 2)
            rack.process(chunk, n, bpm)
            System.arraycopy(chunk, 0, out, f * 2, n * 2)
            f += n
        }
        return out
    }

    private fun sine(freq: Double, seconds: Double, amp: Float = 0.5f): FloatArray {
        val n = (seconds * fs).toInt()
        return FloatArray(n * 2) { i -> (amp * sin(2 * PI * freq * (i / 2) / fs)).toFloat() }
    }

    private fun noise(seconds: Double, amp: Float = 0.3f, seed: Long = 1): FloatArray {
        val r = Random(seed)
        return FloatArray((seconds * fs).toInt() * 2) { (r.nextFloat() * 2f - 1f) * amp }
    }

    /** Goertzel power of the left channel at [freq]. */
    private fun power(x: FloatArray, freq: Double): Double {
        val n = x.size / 2
        val w = 2 * cos(2 * PI * freq / fs)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s = x[i * 2] + w * s1 - s2
            s2 = s1
            s1 = s
        }
        return (s1 * s1 + s2 * s2 - w * s1 * s2) / n
    }

    private fun on(rack: FxRack, id: FxId, vararg values: Pair<Int, Float>): Effect {
        val fx = rack.effect(id)
        values.forEach { (i, v) -> fx.set(i, v) }
        fx.on = true
        return fx
    }

    @Test
    fun allOffIsBitExactBypass() {
        val rack = FxRack(fs)
        val input = noise(0.5)
        val out = run(rack, input)
        assertTrue(input.indices.all { input[it] == out[it] })
    }

    @Test
    fun everyEffectStaysFiniteAndBoundedAtExtremeSettings() {
        for (id in FxId.values()) {
            for (extreme in listOf(0f, 1f)) {
                val rack = FxRack(fs)
                val fx = on(rack, id)
                fx.params.indices.forEach { fx.set(it, extreme) }
                val out = run(rack, noise(1.5, amp = 0.9f), bpm = 200f, block = 333)
                assertTrue("$id at $extreme", out.all { it.isFinite() && abs(it) < 6f })
            }
        }
    }

    @Test
    fun defaultsAndDetentsAreApplied() {
        val rack = FxRack(fs)
        val br = rack.effect(FxId.BEAT_REPEAT)
        assertEquals("1/8", br.params[0].format(br.get(0)))
        br.set(0, 0.3f)
        assertEquals(0.25f, br.get(0), 0f) // snapped to 1/4
        assertEquals("1/4", br.params[0].format(br.get(0)))
        br.set(3, 7f)
        assertEquals(1f, br.get(3), 0f) // clamped
        br.resetParams()
        assertEquals(0.5f, br.get(0), 0f)
        assertEquals(6, rack.effects.size)
        assertTrue(rack.effects.all { it.params.size == 4 })
    }

    @Test
    fun switchingOffReturnsToExactBypass() {
        val rack = FxRack(fs)
        val fx = on(rack, FxId.FLANGER)
        run(rack, sine(220.0, 0.5))
        fx.on = false
        run(rack, sine(220.0, 0.2)) // crossfade out
        val tail = sine(330.0, 0.3)
        val out = run(rack, tail)
        assertTrue(tail.indices.all { tail[it] == out[it] })
    }

    @Test
    fun flangerCombsTheSignal() {
        val rack = FxRack(fs)
        on(rack, FxId.FLANGER, 3 to 1f, 2 to 0.7f)
        val input = sine(1000.0, 1.0)
        val out = run(rack, input)
        val diff = input.indices.maxOf { abs(out[it] - input[it]) }
        assertTrue("flanger changes the signal ($diff)", diff > 0.1f)
    }

    @Test
    fun chorusDecorrelatesLeftAndRight() {
        val rack = FxRack(fs)
        on(rack, FxId.CHORUS, 2 to 1f, 3 to 1f)
        val mono = FloatArray(fs * 2) { i -> (0.4 * sin(2 * PI * 440.0 * (i / 2) / fs)).toFloat() }
        val out = run(rack, mono)
        var d = 0.0
        for (f in fs / 2 until fs) d += abs(out[f * 2] - out[f * 2 + 1])
        assertTrue("left and right differ (${d / (fs / 2)})", d / (fs / 2) > 0.01)
    }

    @Test
    fun vowelPlacesEnergyOnTheFormants() {
        val rack = FxRack(fs)
        on(rack, FxId.VOWEL, 0 to 0f, 1 to 0f, 3 to 1f) // vowel A, no sweep, fully wet
        val out = run(rack, noise(1.5, seed = 3))
        val tail = out.copyOfRange(fs, out.size)
        val atFormant = power(tail, 800.0)
        val away = power(tail, 5500.0)
        assertTrue("A formant stands out: ${atFormant / away}", atFormant / away > 8.0)

        // Moving to U shifts the first formant from 800 Hz down to 325 Hz.
        val rack2 = FxRack(fs)
        on(rack2, FxId.VOWEL, 0 to 1f, 1 to 0f, 3 to 1f)
        val tail2 = run(rack2, noise(1.5, seed = 3)).let { it.copyOfRange(fs, it.size) }
        assertTrue(power(tail2, 325.0) > power(tail2, 800.0) * 2)
    }

    private fun periodSpread(x: FloatArray): Double {
        val crossings = ArrayList<Double>()
        for (f in 1 until x.size / 2) {
            val a = x[(f - 1) * 2]
            val b = x[f * 2]
            if (a < 0f && b >= 0f) crossings.add(f - 1 + (-a / (b - a)).toDouble())
        }
        val periods = crossings.zipWithNext { p, q -> q - p }
        val mean = periods.average()
        return sqrt(periods.sumOf { (it - mean) * (it - mean) } / periods.size)
    }

    @Test
    fun warbleBendsThePitch() {
        val steady = FxRack(fs).also { on(it, FxId.WARBLE, 0 to 0f, 1 to 0f, 2 to 0f) }
        val bent = FxRack(fs).also { on(it, FxId.WARBLE, 0 to 1f, 1 to 1f, 2 to 0.2f) }
        val input = sine(1000.0, 3.0)
        val a = periodSpread(run(steady, input).let { it.copyOfRange(fs, it.size) })
        val b = periodSpread(run(bent, input).let { it.copyOfRange(fs, it.size) })
        assertTrue("wow and flutter vary the period ($a vs $b)", b > a * 10 && b > 0.1)
    }

    private fun clicks(x: FloatArray, from: Int, threshold: Float = 0.3f): List<Int> {
        val out = ArrayList<Int>()
        var f = from
        while (f < x.size / 2) {
            if (abs(x[f * 2]) > threshold) {
                // Take the local peak, then skip ahead so one click counts once.
                var best = f
                for (k in f until minOf(f + 200, x.size / 2)) if (abs(x[k * 2]) > abs(x[best * 2])) best = k
                out.add(best)
                f += 2000
            } else f++
        }
        return out
    }

    private fun beatRepeatSpacing(bpm: Float, grid: Float): List<Int> {
        val rack = FxRack(fs)
        val fx = rack.effect(FxId.BEAT_REPEAT)
        fx.set(0, grid)
        fx.set(2, 0f) // no decay
        val input = FloatArray(160000 * 2)
        input[20000 * 2] = 1f
        input[20000 * 2 + 1] = 1f
        val head = input.copyOfRange(0, 26000 * 2)
        run(rack, head, bpm)
        fx.on = true
        val out = run(rack, FloatArray(100000 * 2), bpm) // silence in; the loop plays on
        return clicks(out, 0)
    }

    @Test
    fun beatRepeatLoopsTheLastSliceInTimeWithTheTempo() {
        // 120 BPM, 1/8 note = half a beat = 0.25 s = 12000 frames.
        val c = beatRepeatSpacing(120f, 0.5f)
        assertTrue("several repeats: $c", c.size >= 5)
        c.zipWithNext { a, b -> assertEquals(12000.0, (b - a).toDouble(), 2.0) }
        // 60 BPM doubles the slice.
        val slow = beatRepeatSpacing(60f, 0.5f)
        slow.zipWithNext { a, b -> assertEquals(24000.0, (b - a).toDouble(), 2.0) }
        // 1/16 at 120 BPM: 6000 frames.
        val fast = beatRepeatSpacing(120f, 0.75f)
        fast.zipWithNext { a, b -> assertEquals(6000.0, (b - a).toDouble(), 2.0) }
    }

    @Test
    fun beatRepeatReleasesBackToTheLiveSignal() {
        val rack = FxRack(fs)
        val fx = rack.effect(FxId.BEAT_REPEAT)
        run(rack, noise(0.6))
        fx.on = true
        run(rack, FloatArray(fs * 2))
        fx.on = false
        run(rack, FloatArray(fs / 5 * 2)) // release
        val live = noise(0.3, seed = 9)
        val out = run(rack, live)
        assertTrue(live.indices.all { live[it] == out[it] })
    }

    @Test
    fun beatRepeatDecayFadesTheRepeats() {
        val rack = FxRack(fs)
        val fx = rack.effect(FxId.BEAT_REPEAT)
        fx.set(2, 1f) // fast decay
        run(rack, noise(0.5))
        fx.on = true
        val out = run(rack, FloatArray(fs * 3 * 2))
        fun rms(from: Int, to: Int): Double = sqrt((from until to).sumOf { (out[it * 2] * out[it * 2]).toDouble() } / (to - from))
        assertTrue(rms(0, 24000) > rms(fs * 2, fs * 3 - 1) * 4)
    }

    @Test
    fun ghostEchoesAfterTheInputStops() {
        val rack = FxRack(fs)
        on(rack, FxId.GHOST, 0 to 0.3f, 1 to 0.5f, 3 to 1f)
        val burst = noise(0.4)
        val input = burst + FloatArray(fs * 2)
        val out = run(rack, input)
        val after = (fs * 0.45).toInt() until fs
        val energy = after.sumOf { (out[it * 2] * out[it * 2]).toDouble() }
        assertTrue("ghost echoes keep sounding: $energy", energy > 0.5)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun resetClearsTails() {
        val rack = FxRack(fs)
        on(rack, FxId.GHOST, 1 to 0.85f, 3 to 1f)
        run(rack, noise(1.0))
        rack.reset()
        val out = run(rack, FloatArray(fs * 2))
        assertEquals(0f, out.maxOf { abs(it) }, 1e-6f)
    }

    // ─── Tempo sync ──────────────────────────────────────────

    @Test
    fun lfoClockFreeRunsOrLocksToTheBeat() {
        val c = LfoClock()
        // Free: advances hz / fs per sample.
        assertEquals(0.5, c.next(false, 0.0, 0, 0.0, 1.0, 2.0, fs, steps = fs / 4), 1e-9) // 2 Hz for a quarter second
        assertEquals(0.0, c.next(false, 0.0, 0, 0.0, 1.0, 2.0, fs, steps = fs / 4) % 1.0, 1e-9) // wraps back to the start
        val d = LfoClock()
        // Synced to 1 bar (4 beats): the phase is the position within the bar, whatever came before.
        assertEquals(0.25, d.next(true, 1.0, 0, 0.001, 4.0, 99.0, fs), 1e-9)
        assertEquals(0.5, d.next(true, 2.0, 0, 0.001, 4.0, 99.0, fs), 1e-9)
        assertEquals(0.0, d.next(true, 8.0, 0, 0.001, 4.0, 99.0, fs), 1e-9) // two bars later: same place
        assertEquals(0.5, d.next(true, 0.0, 2000, 0.001, 4.0, 99.0, fs), 1e-9) // sample offset inside a block
    }

    @Test
    fun syncedSweepsFollowTheTransportPositionButFreeOnesDoNot() {
        fun block(id: FxId, sync: Boolean, beat: Double): FloatArray {
            val rack = FxRack(fs)
            val fx = on(rack, id, 3 to 1f)
            fx.set(0, 0.3f)
            if (sync) { fx.sync = true; fx.setDivision(3f / 7f) } // 1 bar
            val input = sine(1000.0, 0.2)
            val out = input.copyOf()
            rack.process(out, out.size / 2, 120f, beat)
            return out
        }
        for (id in listOf(FxId.FLANGER, FxId.CHORUS, FxId.WARBLE)) {
            assertTrue("$id synced at beat 0 and 1 differ", !block(id, true, 0.0).contentEquals(block(id, true, 1.0)))
            // One bar (4 beats) later the sweep is back in the same place.
            assertTrue("$id synced repeats every bar", block(id, true, 0.0).contentEquals(block(id, true, 4.0)))
            assertTrue("$id free ignores the beat", block(id, false, 0.0).contentEquals(block(id, false, 7.3)))
        }
    }

    @Test
    fun syncTurnsTheRateKnobIntoADivisionSelectorAndRemembersBoth() {
        val fx = FxRack(fs).effect(FxId.FLANGER)
        fx.set(0, 0.8f)
        assertEquals(0.8f, fx.knobValue(0), 0f)
        assertEquals(0, fx.knobDetents(0))
        assertTrue(fx.knobText(0, 0.8f).endsWith("Hz"))
        fx.sync = true
        assertEquals(8, fx.knobDetents(0))
        assertEquals("1 bar", fx.knobText(0, fx.knobValue(0)))
        fx.setFromKnob(0, 1f)
        assertEquals("1/16", fx.division().label)
        assertEquals(0.25f, fx.division().beats, 0f)
        assertEquals(0.8f, fx.get(0), 0f) // the free value is untouched
        fx.sync = false
        assertEquals(0.8f, fx.knobValue(0), 0f)
        // Only the knob that carries the rate changes; the others keep their meaning.
        fx.sync = true
        assertEquals(fx.params[1].detents, fx.knobDetents(1))
        assertTrue(fx.syncable && !fx.alwaysSynced)
        assertTrue(FxRack(fs).effect(FxId.BEAT_REPEAT).alwaysSynced)
    }

    @Test
    fun ghostTimeFollowsTheTempoAndFitsItsBuffer() {
        val g = Ghost(fs)
        g.sync = true
        g.setDivision(1f / 5f) // 1/8: half a beat
        assertEquals(12000, g.grainFrames(120f))
        assertEquals(24000, g.grainFrames(60f))
        g.setDivision(1f) // 1/2 note = 2 beats: 96000 frames at 60 BPM is too long, so it halves
        assertEquals(48000, g.grainFrames(60f))
        assertEquals(48000, g.grainFrames(120f)) // 2 beats at 120 BPM is exactly one second
    }

    @Test
    fun everySyncedEffectStaysFiniteAtExtremes() {
        for (id in FxId.values()) {
            val rack = FxRack(fs)
            val fx = on(rack, id)
            if (fx.syncable) { fx.sync = true; fx.setDivision(1f) }
            fx.params.indices.forEach { fx.set(it, 1f) }
            var beat = 0.0
            val out = noise(1.0, amp = 0.9f).copyOf()
            var f = 0
            while (f < out.size / 2) {
                val n = minOf(257, out.size / 2 - f)
                val chunk = out.copyOfRange(f * 2, (f + n) * 2)
                rack.process(chunk, n, 190f, beat)
                System.arraycopy(chunk, 0, out, f * 2, n * 2)
                beat += n * 190.0 / 60.0 / fs
                f += n
            }
            assertTrue("$id", out.all { it.isFinite() && abs(it) < 6f })
        }
    }
}
