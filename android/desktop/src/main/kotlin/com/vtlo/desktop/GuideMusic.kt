package com.vtlo.desktop

import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.TrackParam
import com.vtlo.fx.FxId
import com.vtlo.sampler.dsp.PadParam

/**
 * The studio of the video guide at 150 BPM: every pattern empty, every sound already shaped as a neutral
 * starting point, so the video builds the track live, step by step, through the real interface:
 * kick and snare, their tone and drive, then an acid line and its filter, pads and effects.
 *
 * The three parts that only join for the overview at the end (BASS 1, DRUMS 2 and the SAMPLER) are
 * written by [sub], [tribal] and [shimmer] at the moment their screen is shown. The key is A minor.
 */
object GuideMusic {
    const val BPM = 150f

    fun setUp(s: Studio) {
        s.drums.bank.clear(0); s.drums2.bank.clear(0); s.sampler.bank.clear(0)
        s.bass.bank.clear(0); s.bass2.bank.clear(0)

        // DRUMS 1: a plain 909 kit; the video tunes the kick and the snare and pushes the drive.
        val d1 = s.drums
        for (t in 0 until Kit.TRACKS) d1.setKit(t, Kit.TR909)
        d1.set(0, TrackParam.LEVEL, 1f); d1.set(0, TrackParam.TUNE, 0.5f)
        d1.set(1, TrackParam.LEVEL, 0.8f); d1.set(1, TrackParam.TUNE, 0.5f)
        d1.set(3, TrackParam.LEVEL, 0.75f)
        d1.set(5, TrackParam.LEVEL, 0.5f); d1.set(6, TrackParam.LEVEL, 0.62f)
        d1.set(DrumParam.DRIVE, 0.1f); d1.set(DrumParam.ACCENT, 0.7f)
        d1.set(DrumParam.REVERB, 0.07f); d1.set(DrumParam.DELAY, 0f)
        d1.set(DrumParam.VOLUME, 0.4f)

        // DRUMS 2: 808 kit for the overview.
        val d2 = s.drums2
        for (t in 0 until Kit.TRACKS) d2.setKit(t, Kit.TR808)
        d2.set(2, TrackParam.TUNE, 0.62f); d2.set(2, TrackParam.LEVEL, 0.85f)
        d2.set(4, TrackParam.LEVEL, 0.6f); d2.set(4, TrackParam.PAN, 0.65f)
        d2.set(DrumParam.DRIVE, 0.3f); d2.set(DrumParam.REVERB, 0.28f)
        d2.set(DrumParam.DELAY, 0.12f); d2.set(DrumParam.VOLUME, 0.34f)

        // BASS 1: a short square sub for the overview.
        val b1 = s.bass
        b1.set(Param.WAVEFORM, 1f)
        b1.set(Param.CUTOFF, 0.28f); b1.set(Param.RESONANCE, 0.4f); b1.set(Param.ENV_MOD, 0.45f)
        b1.set(Param.DECAY, 0.22f); b1.set(Param.ACCENT, 0.7f); b1.set(Param.DRIVE, 0.7f)
        b1.set(Param.DELAY, 0f); b1.set(Param.REVERB, 0f); b1.set(Param.VOLUME, 0.4f)

        // BASS 2: the acid voice, set dull on purpose (closed filter, little resonance) so every knob turn is heard.
        val b2 = s.bass2
        b2.set(Param.WAVEFORM, 0f)
        b2.set(Param.CUTOFF, 0.2f); b2.set(Param.RESONANCE, 0.5f); b2.set(Param.ENV_MOD, 0.45f)
        b2.set(Param.DECAY, 0.45f); b2.set(Param.ACCENT, 0.7f); b2.set(Param.DRIVE, 0.3f)
        b2.set(Param.DELAY, 0f); b2.set(Param.DELAY_FEEDBACK, 0.5f); b2.set(Param.REVERB, 0.1f)
        b2.set(Param.VOLUME, 0.6f)

        s.sampler.set(com.vtlo.sampler.dsp.SamplerParam.VOLUME, 0.3f)

        // The first bass line's tempo leads at start-up and the others follow.
        s.bass.set(Param.TEMPO, Param.bpmToNorm(BPM))

        // Effects wait for their turn.
        s.engine.fx.effect(FxId.BEAT_REPEAT).apply { set(0, 0.75f); set(2, 0f) } // 1/16, no decay
        s.engine.fx.effect(FxId.GHOST).apply { set(1, 0.55f); set(3, 0.45f) }

        s.engine.parts.forEach { it.on = false }
        s.engine.partOf(s.drums).on = true
        s.engine.partOf(s.bass2).on = true
    }

    private fun note(bank: PatternBank, i: Int, n: Int, accent: Boolean = false, slide: Boolean = false) =
        bank.setStep(0, i, PatternBank.pack(n, true, accent, slide))

    /** BASS 1: a rolling offbeat sub on A1 with two dark stabs. */
    fun sub(s: Studio) {
        val b = s.bass.bank
        note(b, 2, 33); note(b, 3, 57, accent = true); note(b, 6, 33, accent = true); note(b, 7, 33)
        note(b, 10, 33); note(b, 11, 60, accent = true); note(b, 14, 33, accent = true); note(b, 15, 31)
        s.engine.partOf(s.bass).on = true
    }

    /** DRUMS 2: tribal toms in 3 + 3 + 3 + 3 + 4 and syncopated rim shots. */
    fun tribal(s: Studio) {
        val d = s.drums2
        for (step in intArrayOf(0, 3, 6, 9, 12)) d.bank.toggle(0, 2, step)
        for (step in intArrayOf(2, 7, 10, 15)) d.bank.toggle(0, 4, step)
        d.bank.toggleAccent(0, 2, 0); d.bank.toggleAccent(0, 2, 9)
        s.engine.partOf(d).on = true
    }

    /** SAMPLER: off-beat shimmer and ghost hits. */
    fun shimmer(s: Studio) {
        val m = s.sampler
        for (step in intArrayOf(2, 6, 10, 14)) m.bank.toggle(0, 7, step)
        for (step in intArrayOf(7, 15)) m.bank.toggle(0, 3, step)
        m.bank.toggle(0, 1, 11)
        s.engine.partOf(m).on = true
    }
}
