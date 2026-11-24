package com.vtlo.desktop

import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.TrackParam
import com.vtlo.fx.FxId
import com.vtlo.sampler.dsp.PadParam

/**
 * The soundtrack of the video guide, programmed in the app itself: a French tekno / mental
 * tribe hardtekno track in A minor at 150 BPM (slow enough for the on-screen actions to read clearly):
 * a distorted pitch-enveloped kick, a hypnotic acid line, crisp offbeat hats and rolling tribal percussion,
 * arranged as intro, tension build-up (sub-bass cut, accelerating snare roll, resonant filter sweep) and drop.
 *
 *  - DRUMS 1 (909): a heavily driven, tuned-down kick on every beat, claps on 2 and 4, offbeat open hats
 *    over rolling 16th hats. The tom track is left empty on purpose: the video programs it live;
 *  - DRUMS 2 (808): tribal percussion, toms in the 3-3-3-3-4 polyrhythm and syncopated rim shots;
 *  - BASS 1: a short rolling offbeat sub bass on a square wave, with a drive;
 *  - BASS 2: a hypnotic resonant acid line on the minor pentatonic, with the built-in echo;
 *  - SAMPLER: shimmer and ghost hits around the beat.
 */
object GuideMusic {
    const val BPM = 150f

    fun setUp(s: Studio) {
        // Clear the factory patterns first.
        s.drums.bank.clear(0); s.drums2.bank.clear(0); s.sampler.bank.clear(0)
        s.bass.bank.clear(0); s.bass2.bank.clear(0)

        // ─── DRUMS 1: 909 kit, the kick-driven backbone ───
        val d1 = s.drums
        for (t in 0 until Kit.TRACKS) d1.setKit(t, Kit.TR909)
        for (step in intArrayOf(0, 4, 8, 12)) d1.bank.toggle(0, 0, step)
        d1.bank.toggleAccent(0, 0, 0); d1.bank.toggleAccent(0, 0, 8)
        for (step in intArrayOf(4, 12)) { d1.bank.toggle(0, 3, step); d1.bank.toggleAccent(0, 3, step) } // claps on 2 and 4
        d1.bank.toggle(0, 1, 15) // a ghost snare before the bar line
        for (step in intArrayOf(1, 3, 5, 7, 9, 11, 13, 15)) d1.bank.toggle(0, 5, step) // rolling closed hats
        for (step in intArrayOf(2, 6, 10, 14)) d1.bank.toggle(0, 6, step) // offbeat open hats
        d1.set(0, TrackParam.LEVEL, 1f); d1.set(0, TrackParam.TUNE, 0.34f) // a deep kick
        d1.set(3, TrackParam.LEVEL, 0.75f)
        d1.set(5, TrackParam.LEVEL, 0.5f); d1.set(6, TrackParam.LEVEL, 0.62f)
        d1.set(DrumParam.DRIVE, 0.8f); d1.set(DrumParam.ACCENT, 0.8f)
        d1.set(DrumParam.REVERB, 0.07f); d1.set(DrumParam.DELAY, 0f)
        d1.set(DrumParam.VOLUME, 0.5f)

        // ─── DRUMS 2: 808 kit, tribal percussion ───
        val d2 = s.drums2
        for (t in 0 until Kit.TRACKS) d2.setKit(t, Kit.TR808)
        for (step in intArrayOf(0, 3, 6, 9, 12)) d2.bank.toggle(0, 2, step) // toms: 3 + 3 + 3 + 3 + 4
        for (step in intArrayOf(2, 7, 10, 15)) d2.bank.toggle(0, 4, step) // rim shots, syncopated
        d2.bank.toggleAccent(0, 2, 0); d2.bank.toggleAccent(0, 2, 9)
        d2.set(2, TrackParam.TUNE, 0.62f); d2.set(2, TrackParam.LEVEL, 0.85f)
        d2.set(4, TrackParam.LEVEL, 0.6f); d2.set(4, TrackParam.PAN, 0.65f)
        d2.set(DrumParam.DRIVE, 0.3f); d2.set(DrumParam.REVERB, 0.28f)
        d2.set(DrumParam.DELAY, 0.12f); d2.set(DrumParam.VOLUME, 0.42f)

        // ─── BASS 1: a rolling offbeat sub ───
        fun note(bank: PatternBank, i: Int, n: Int, accent: Boolean = false, slide: Boolean = false) =
            bank.setStep(0, i, PatternBank.pack(n, true, accent, slide))
        val b1 = s.bass
        note(b1.bank, 2, 33); note(b1.bank, 3, 57, accent = true); note(b1.bank, 6, 33, accent = true); note(b1.bank, 7, 33)
        note(b1.bank, 10, 33); note(b1.bank, 11, 60, accent = true); note(b1.bank, 14, 33, accent = true); note(b1.bank, 15, 31)
        b1.set(Param.WAVEFORM, 1f)
        b1.set(Param.CUTOFF, 0.28f); b1.set(Param.RESONANCE, 0.4f); b1.set(Param.ENV_MOD, 0.45f)
        b1.set(Param.DECAY, 0.22f); b1.set(Param.ACCENT, 0.7f); b1.set(Param.DRIVE, 0.7f)
        b1.set(Param.DELAY, 0f); b1.set(Param.REVERB, 0f); b1.set(Param.VOLUME, 0.5f)

        // ─── BASS 2: the hypnotic acid line (A minor pentatonic) ───
        val b2 = s.bass2
        val line = listOf(
            0 to 45, 2 to 45, 3 to 48, 5 to 52, 7 to 45, 8 to 43, 10 to 45, 12 to 48, 13 to 50, 15 to 52,
        )
        for ((i, n) in line) note(b2.bank, i, n, accent = i in intArrayOf(0, 5, 10, 15), slide = i in intArrayOf(3, 13))
        b2.set(Param.WAVEFORM, 0f)
        b2.set(Param.CUTOFF, 0.22f); b2.set(Param.RESONANCE, 0.85f); b2.set(Param.ENV_MOD, 0.8f)
        b2.set(Param.DECAY, 0.3f); b2.set(Param.ACCENT, 0.85f); b2.set(Param.DRIVE, 0.55f)
        b2.set(Param.DELAY, 0.3f); b2.set(Param.DELAY_FEEDBACK, 0.5f); b2.set(Param.REVERB, 0.15f)
        b2.set(Param.VOLUME, 0.38f)

        // ─── SAMPLER: shimmer and ghost hits (the pads are seeded with 808/909/606 sounds) ───
        val smp = s.sampler
        for (step in intArrayOf(2, 6, 10, 14)) smp.bank.toggle(0, 7, step) // pad 8: offbeat shimmer
        for (step in intArrayOf(7, 15)) smp.bank.toggle(0, 3, step) // pad 4: clap ghosts
        smp.bank.toggle(0, 1, 11) // pad 2: a snare ghost
        smp.set(com.vtlo.sampler.dsp.SamplerParam.VOLUME, 0.36f)

        // Tempo: the first bass line's value leads at start-up and the others follow.
        s.bass.set(Param.TEMPO, Param.bpmToNorm(BPM))

        // Effects: reverse echoes as a riser, a stutter roll for the last bars (the video switches them on).
        s.engine.fx.effect(FxId.BEAT_REPEAT).apply { set(0, 0.75f); set(2, 0f) } // 1/16, no decay
        s.engine.fx.effect(FxId.GHOST).apply { set(1, 0.55f); set(3, 0.45f) }

        // The arrangement starts with the drums alone; the video brings the other parts in.
        s.engine.parts.forEach { it.on = false }
        s.engine.partOf(s.drums).on = true
    }

    // ─── The arrangement ──────────────────────────────────────

    private const val BASE_B1 = 0.5f
    private const val BASE_B2 = 0.38f
    private const val BASE_SMP = 0.36f

    /**
     * Schedules the arrangement through [at] (video seconds), the transport starting at [t0]; one bar is 1.6 s.
     * Intro: kick, hats, then the acid line and shimmer. Build-up (bars 5-7): the sub-bass is cut, a snare roll
     * speeds up from quarter notes to 16ths to a 1/32 stutter, the acid filter sweeps open, and the kick drops
     * out for the last beat. Drop (bar 8): everything returns at once, ducked on every kick (a sidechain pump).
     */
    fun arrange(s: Studio, at: (Double, () -> Unit) -> Unit, t0: Double, end: Double, fps: Int) {
        val bar = 4 * 60.0 / BPM
        val beat = 60.0 / BPM
        fun on(e: com.vtlo.core.audio.SequencerEngine, v: Boolean) { s.engine.partOf(e).on = v }
        val snare = 1
        fun snareRow(steps: IntArray) = s.drums.bank.setRow(0, snare, steps.fold(0) { m, i -> m or (1 shl i) } or (1 shl 16))

        // Intro.
        at(t0 + 1 * bar) { on(s.bass2, true) }
        at(t0 + 3 * bar) { on(s.sampler, true) }
        at(t0 + 5 * bar) { /* build-up starts */ }
        // The acid filter opens slowly through the intro, then sweeps wide open into the drop.
        sweep(s, at, t0 + 1 * bar, t0 + 5 * bar, 0.22f, 0.4f)
        sweep(s, at, t0 + 5 * bar, t0 + 8 * bar, 0.4f, 0.95f)
        at(t0 + 5 * bar) { s.bass2.set(Param.RESONANCE, 0.9f) }

        // Build-up: sub-bass cut and the accelerating roll.
        at(t0 + 5 * bar) { on(s.bass, false); snareRow(intArrayOf(0, 4, 8, 12)); s.drums.set(1, TrackParam.LEVEL, 0.7f) }
        at(t0 + 6 * bar) { snareRow(IntArray(8) { it * 2 }) }
        at(t0 + 7 * bar) { snareRow(IntArray(16) { it }) }
        at(t0 + 7.5 * bar) { s.engine.fx.effect(FxId.BEAT_REPEAT).apply { set(0, 1f); on = true } } // 1/32 stutter
        at(t0 + 7.75 * bar) { s.drums.set(0, TrackParam.LEVEL, 0f) } // the kick drops out for the last beat

        // Drop.
        at(t0 + 8 * bar) {
            s.engine.fx.effect(FxId.BEAT_REPEAT).apply { on = false; set(0, 0.75f) }
            s.drums.bank.setRow(0, snare, 1 shl 15 or (1 shl 31)) // back to the single ghost snare
            s.drums.set(0, TrackParam.LEVEL, 1f); s.drums.set(1, TrackParam.LEVEL, 0.5f)
            s.bass2.set(Param.RESONANCE, 0.85f)
            on(s.bass, true); on(s.drums2, true)
        }
        sweep(s, at, t0 + 8 * bar, t0 + 10 * bar, 0.95f, 0.5f)

        // The sidechain pump: bass, acid and shimmer duck on every kick and swell back within the beat.
        val duck = floatArrayOf(0.2f, 0.55f, 0.85f)
        var b = t0 + 1 * bar
        while (b < end) {
            val depth = if (b >= t0 + 8 * bar) 1f else 0.6f
            for ((j, f) in duck.withIndex()) {
                val k = 1f - depth * (1f - f)
                at(b + j.toDouble() / fps) { pump(s, k) }
            }
            at(b + duck.size.toDouble() / fps) { pump(s, 1f) }
            b += beat
        }
    }

    private fun pump(s: Studio, k: Float) {
        s.bass.set(Param.VOLUME, BASE_B1 * k)
        s.bass2.set(Param.VOLUME, BASE_B2 * k)
        s.sampler.set(com.vtlo.sampler.dsp.SamplerParam.VOLUME, BASE_SMP * k)
    }

    /** Moves the acid filter's cutoff from [from] to [to] between [t1] and [t2], in steps of a tenth of a second. */
    private fun sweep(s: Studio, at: (Double, () -> Unit) -> Unit, t1: Double, t2: Double, from: Float, to: Float) {
        var t = t1
        while (t < t2) {
            val v = from + (to - from) * ((t - t1) / (t2 - t1)).toFloat()
            at(t) { s.bass2.set(Param.CUTOFF, v) }
            t += 0.1
        }
    }
}
