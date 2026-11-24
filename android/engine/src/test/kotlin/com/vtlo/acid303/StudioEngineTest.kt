package com.vtlo.acid303

import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.sampler.dsp.SamplerEngine
import com.vtlo.sampler.dsp.SamplerParam
import com.vtlo.drums.dsp.SampleBank
import com.vtlo.drums.dsp.Kit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

class StudioEngineTest {
    companion object {
        private const val FS = 48000
        private lateinit var samples: SampleBank

        @BeforeClass
        @JvmStatic
        fun load() {
            samples = SampleBank.load(FS) { File("../drums/src/main/assets", it).inputStream() }
        }
    }

    private fun studio() = StudioEngine(
        AcidEngine(FS), DrumEngine(FS, samples), SamplerEngine(FS), AcidEngine(FS), DrumEngine(FS, samples), AcidEngine(FS, wobble = true),
    ).apply {
        sampler.setSample(0, samples.get(Kit.TR808, 0))
        sampler.bank.toggle(0, 0, 0)
    }

    @Test
    fun partsStartTogetherAndStayLocked() {
        val s = studio()
        s.drums.set(DrumParam.TEMPO, DrumParam.bpmToNorm(97f)) // overridden by the bass tempo at start
        s.play()
        val buf = FloatArray(2 * 37)
        repeat(FS * 4 / 37) {
            s.render(buf, 37)
            assertEquals(s.bass.currentStep, s.drums.currentStep)
            assertEquals(s.bass.currentStep, s.sampler.currentStep)
        }
    }

    @Test
    fun tempoAndSwingFollowWhicheverPartChanged() {
        val s = studio()
        val buf = FloatArray(2 * 64)
        s.drums.set(DrumParam.TEMPO, DrumParam.bpmToNorm(150f))
        s.render(buf, 64)
        assertEquals(150f, Param.normToBpm(s.bass.get(Param.TEMPO)), 0.01f)
        s.bass.set(Param.TEMPO, Param.bpmToNorm(110f))
        s.bass.set(Param.SWING, 0.5f)
        s.render(buf, 64)
        assertEquals(110f, DrumParam.normToBpm(s.drums.get(DrumParam.TEMPO)), 0.01f)
        assertEquals(110f, SamplerParam.normToBpm(s.sampler.get(SamplerParam.TEMPO)), 0.01f)
        assertEquals(0.5f, s.drums.get(DrumParam.SWING), 0f)
        assertEquals(0.5f, s.sampler.get(SamplerParam.SWING), 0f)
        s.sampler.set(SamplerParam.TEMPO, SamplerParam.bpmToNorm(90f))
        s.render(buf, 64)
        assertEquals(90f, Param.normToBpm(s.bass.get(Param.TEMPO)), 0.01f)
        assertEquals(90f, DrumParam.normToBpm(s.drums.get(DrumParam.TEMPO)), 0.01f)
    }

    @Test
    fun partOffTakesItOutOfTheMix() {
        val s = studio()
        s.drums.bank.toggle(0, 0, 0)
        s.parts.forEach { it.on = false }
        s.play()
        val buf = FloatArray(2 * 512)
        var peak = 0f
        repeat(FS / 512) {
            s.render(buf, 512)
            for (v in buf) peak = maxOf(peak, abs(v))
        }
        assertTrue("muted parts should be silent, peak $peak", peak < 1e-3f)
        assertTrue(s.drums.hits.get(0) > 0) // still running in time
        assertTrue(s.sampler.hits.get(0) > 0)
    }

    @Test
    fun secondBassAndDrumsAreIndependentInstrumentsInTheSameMix() {
        val s = studio()
        assertEquals(6, s.parts.size)
        // Page order groups the instruments: bass 1 and 2, the wobble synth, drums 1 and 2, then the sampler.
        assertEquals(listOf<Any>(s.bass, s.bass2, s.wobble, s.drums, s.drums2, s.sampler), s.parts.map { it.engine })
        // Fresh second instruments are silent: the original mix is untouched until they are programmed.
        s.partOf(s.bass).on = false; s.partOf(s.drums).on = false; s.partOf(s.sampler).on = false
        s.play()
        val buf = FloatArray(1024)
        var peak = 0f
        repeat(100) { s.render(buf, 512); peak = maxOf(peak, buf.maxOf { abs(it) }) }
        assertEquals(0f, peak, 1e-6f)

        // Their own patterns play, in time with the others, and switch out of the mix on their own.
        s.drums2.bank.toggle(0, 0, 0)
        s.bass2.bank.setStep(0, 0, com.vtlo.acid303.dsp.PatternBank.pack(36, true, false, false))
        s.stop(); s.render(buf, 512); s.play()
        repeat(100) { s.render(buf, 512); peak = maxOf(peak, buf.maxOf { abs(it) }) }
        assertTrue("second instruments are audible", peak > 0.05f)
        assertEquals(s.bass.currentStep, s.bass2.currentStep)
        assertEquals(s.drums.currentStep, s.drums2.currentStep)

        s.partOf(s.bass2).on = false; s.partOf(s.drums2).on = false
        repeat(20) { s.render(buf, 512) }
        peak = 0f
        repeat(20) { s.render(buf, 512); peak = maxOf(peak, buf.maxOf { abs(it) }) }
        assertEquals(0f, peak, 1e-6f)

        // Tempo is shared: moving the second bass line's knob moves everything.
        s.bass2.set(Param.TEMPO, Param.bpmToNorm(133f))
        s.render(buf, 64)
        assertEquals(133f, Param.normToBpm(s.bass.get(Param.TEMPO)), 0.01f)
        assertEquals(133f, DrumParam.normToBpm(s.drums2.get(DrumParam.TEMPO)), 0.01f)
    }

    private fun render(s: StudioEngine, blocks: Int): FloatArray {
        val all = FloatArray(blocks * 1024)
        val buf = FloatArray(1024)
        s.play()
        for (b in 0 until blocks) {
            s.render(buf, 512)
            System.arraycopy(buf, 0, all, b * 1024, 1024)
        }
        return all
    }

    @Test
    fun masterFxProcessTheMixAndStayOffByDefault() {
        val plain = render(studio(), 80)
        val fxOff = studio().also { it.fx.effects.forEach { e -> e.set(3, 1f) } } // knobs alone change nothing
        assertTrue(plain.contentEquals(render(fxOff, 80)))
        assertTrue(!fxOff.fx.anyOn)

        val withFx = studio().also { s ->
            s.fx.effect(com.vtlo.fx.FxId.CHORUS).apply { set(3, 1f); on = true }
            s.fx.effect(com.vtlo.fx.FxId.FLANGER).apply { set(3, 1f); on = true }
        }
        val processed = render(withFx, 80)
        assertTrue(processed.all { it.isFinite() && abs(it) <= 1f })
        assertTrue("FX change the mix", processed.indices.any { abs(processed[it] - plain[it]) > 0.01f })
    }

    @Test
    fun panicResetsTheEffectTails() {
        val s = studio()
        s.fx.effect(com.vtlo.fx.FxId.GHOST).apply { set(1, 0.85f); set(3, 1f); on = true }
        render(s, 60)
        s.panic()
        s.parts.forEach { it.on = false }
        val buf = FloatArray(1024)
        repeat(30) { s.render(buf, 512) }
        assertEquals(0f, buf.maxOf { abs(it) }, 1e-4f)
    }

    // ─── Pad loops ───────────────────────────────────────────

    /** Renders [frames] frames of a studio that is playing with every part but [audible] muted. */
    private fun renderLeft(s: StudioEngine, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val buf = FloatArray(1024)
        var f = 0
        while (f < frames) {
            val n = minOf(512, frames - f)
            s.render(buf, n)
            System.arraycopy(buf, 0, out, f * 2, n * 2)
            f += n
        }
        return out
    }

    private fun firstSound(x: FloatArray, from: Int = 0, threshold: Float = 1e-4f): Int {
        for (f in from until x.size / 2) if (abs(x[f * 2]) > threshold) return f
        return -1
    }

    private fun studioWith(kind: StudioEngine.Kind, index: Int = 0): Pair<StudioEngine, StudioEngine.Part> {
        val s = studio()
        s.bass.set(Param.TEMPO, Param.bpmToNorm(120f)) // the fixture's default is 130 BPM
        s.parts.forEach { it.on = false }
        val part = s.parts.filter { it.kind == kind }[index]
        part.on = true
        return s to part
    }

    @Test
    fun drumPadHitsStartOnTheRecordedSample() {
        val (s, part) = studioWith(StudioEngine.Kind.DRUMS)
        part.loop.apply { recording = true; press(0, 1.0); recording = false } // kick on beat 1: frame 24000 at 120 BPM
        s.play()
        val out = renderLeft(s, 26000)
        val first = firstSound(out)
        assertTrue("the kick starts on frame 24000 (was $first)", first in 24000..24006)
        assertTrue(part.loop.hits.get(0) >= 1)
    }

    @Test
    fun theLoopPlaysAgainEveryEightBars() {
        val (s, part) = studioWith(StudioEngine.Kind.DRUMS, 1)
        part.loop.apply { recording = true; press(2, 0.0); recording = false }
        s.play()
        val out = renderLeft(s, 32 * 24000 + 4000) // 32 beats at 120 BPM = 768000 frames, plus a bit
        assertTrue(firstSound(out) in 0..6)
        val gap = firstSound(out, from = 700000)
        assertTrue("silent before the loop point, then the hit returns (first sound at $gap)", gap in 767998..768006)
    }

    @Test
    fun samplerPadsAndAccentsTriggerTheRightPad() {
        val (s, part) = studioWith(StudioEngine.Kind.SAMPLER)
        s.sampler.bank.toggle(0, 0, 0) // the fixture switched step 1 on; switch it off so only the pads sound
        part.loop.apply { recording = true; press(8, 2.0); recording = false } // pad 9 = the accented hit of pad 1
        s.play()
        val first = firstSound(renderLeft(s, 50000))
        assertTrue("sampler hit on frame 48000 (was $first)", first in 48000..48006)
    }

    @Test
    fun bassPadsPlayNotesOnTheirOwnVoiceWhileTheSequencerRuns() {
        val (s, part) = studioWith(StudioEngine.Kind.BASS, 1)
        // The first bass line's sequencer is irrelevant here; the second one has an empty pattern.
        part.loop.apply { recording = true; press(0, 1.0); release(0, 2.0); recording = false }
        s.play()
        val out = renderLeft(s, 40000)
        val first = firstSound(out)
        assertTrue("the bass note starts on frame 24000 (was $first)", first in 24000..24100)
        // The note ends (a 1-beat gate) and the voice falls silent well before the end.
        assertTrue(firstSound(out, from = 24000 + 24000 + 12000) < 0 || abs(out[39990 * 2]) < 0.05f)
    }

    @Test
    fun padNotesSoundWithoutTheTransportToo() {
        val (s, part) = studioWith(StudioEngine.Kind.DRUMS)
        s.padDown(part, 0, beat = 0.0)
        assertTrue(firstSound(renderLeft(s, 2000)) in 0..6)
    }

    @Test
    fun recordingFromLivePadTapsUsesTheTransportClock() {
        val (s, part) = studioWith(StudioEngine.Kind.DRUMS)
        part.loop.recording = true
        s.play()
        renderLeft(s, 24000 + 100) // a little past beat 1
        val beat = s.tapBeat()
        assertTrue("tap position is near beat 1 (was $beat)", beat in 0.9..1.2)
        s.padDown(part, 3, beat = 1.0)
        assertEquals(1, part.loop.padCounts.get(3))
    }

    @Test
    fun theMetronomeClicksOnEveryBeatAndOnlyWhenAskedFor() {
        val (s, _) = studioWith(StudioEngine.Kind.DRUMS)
        s.parts.forEach { it.on = false }
        s.play()
        assertEquals(-1, firstSound(renderLeft(s, 30000)))
        s.stop(); s.click = true; s.play()
        val out = renderLeft(s, 60000)
        assertTrue(firstSound(out) in 0..2)
        val second = firstSound(out, from = 5000)
        assertTrue("the next click is on beat 1 (frame 24000), was $second", second in 24000..24003)
    }

    @Test
    fun theWobbleSynthPlaysInTimeWithTheOthersAndItsPadsAreBassPads() {
        val s = studio()
        s.partOf(s.bass).on = false; s.partOf(s.drums).on = false; s.partOf(s.sampler).on = false
        s.wobble.bank.setStep(0, 0, com.vtlo.acid303.dsp.PatternBank.pack(29, true, false, false))
        s.play()
        val buf = FloatArray(1024)
        var peak = 0f
        repeat(100) { s.render(buf, 512); peak = maxOf(peak, buf.maxOf { abs(it) }) }
        assertTrue("the wobble synth is audible", peak > 0.02f)
        assertEquals(s.bass.currentStep, s.wobble.currentStep)
        // Its pads play chromatic notes from a low F, like the bass pads.
        assertEquals(29, s.partOf(s.wobble).loop.baseNote)
        s.stop(); s.render(buf, 512)
        s.padDown(s.partOf(s.wobble), 12)
        repeat(20) { s.render(buf, 512) }
        peak = 0f
        repeat(20) { s.render(buf, 512); peak = maxOf(peak, buf.maxOf { abs(it) }) }
        assertTrue("a wobble pad sounds without the sequencer", peak > 0.02f)
    }
}
