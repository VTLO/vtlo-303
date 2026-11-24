package com.vtlo.live

import com.vtlo.acid303.StudioEngine
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets
import com.vtlo.acid303.dsp.Presets.REST
import com.vtlo.acid303.dsp.Presets.pat
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.DrumPresets
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.TrackParam

/**
 * A complete live set: the sounds, the tempo and eight scenes (patterns A1 to A8 of every instrument) written
 * into the whole studio. Scenes are meant to be played, not just heard: [StudioEngine.selectScene] moves every
 * instrument to the same pattern slot, each one switching at the end of its own bar, so a drop, a break or a
 * build-up lands together. Loading a set replaces patterns A1 to A8 of every instrument.
 */
class LiveSet(
    val name: String,
    val bpm: Int,
    val description: String,
    /** The names of the eight scenes, in slot order. */
    val scenes: List<String>,
    private val build: (StudioEngine) -> Unit,
) {
    fun load(e: StudioEngine) {
        for (p in 0 until SCENES) {
            e.bass.bank.clear(p); e.bass2.bank.clear(p); e.wobble.bank.clear(p)
            e.drums.bank.clear(p); e.drums2.bank.clear(p); e.sampler.bank.clear(p)
        }
        e.fx.effects.forEach { it.on = false }
        e.fx.effect(com.vtlo.fx.FxId.BEAT_REPEAT).apply { set(0, 0.75f); set(2, 0f) } // a 1/16 stutter that holds until released
        e.parts.forEach { it.on = true }
        build(e)
        e.bass.set(Param.TEMPO, Param.bpmToNorm(bpm.toFloat()))
        e.selectScene(0)
    }

    companion object {
        const val SCENES = 8
    }
}

object LiveSets {
    private fun drums(e: DrumEngine, slot: Int, vararg tracks: Pair<Int, String>) {
        val rows = DrumPresets.rows(*tracks)
        for (t in 0 until Kit.TRACKS) e.bank.setRow(slot, t, rows[t])
        e.bank.setLength(slot, 16)
    }

    private fun line(e: AcidEngine, slot: Int, steps: IntArray) {
        steps.forEachIndexed { i, v -> e.bank.setStep(slot, i, v) }
        e.bank.setLength(slot, PatternBank.STEPS)
    }

    private fun setup(e: DrumEngine, kit: Kit, vararg levels: Pair<Int, Float>) {
        for (t in 0 until Kit.TRACKS) { e.setKit(t, kit); e.set(t, TrackParam.LEVEL, 0.8f); e.set(t, TrackParam.TUNE, 0.5f) }
        for ((t, l) in levels) e.set(t, TrackParam.LEVEL, l)
    }

    private const val BD = 0
    private const val SD = 1
    private const val TM = 2
    private const val CP = 3
    private const val RS = 4
    private const val CH = 5
    private const val OH = 6
    private const val CY = 7

    private fun wobbleRate(division: Int) = division / 8f // see WobbleVoice.RATE_LABELS: 0 = 1 bar ... 8 = 1/32

    // ═══ UK drum and bass · 170 BPM · F minor ═══

    /**
     * Two-step drums on the DNB kit, an 808 layer for weight and fills, a rolling wobble sub and sparse acid stabs.
     * Scenes: 1 intro (hats and shaker), 2 groove (two-step + sub roller), 3 drop (wobble), 4 build (snare roll),
     * 5 fill, 6 half-time break, 7 outro.
     */
    val ukDnb = LiveSet(
        "UK Drum & Bass", 170, "Two-step, rolling sub and wobble, F minor, 170 BPM",
        listOf("INTRO", "GROOVE", "DROP", "BUILD", "FILL", "BREAK", "OUTRO", "SPARE"),
    ) { e ->
        // DRUMS 2: the main kit.
        val d = e.drums2
        setup(d, Kit.DNB, BD to 0.95f, SD to 0.85f, TM to 0.7f, CP to 0.6f, RS to 0.6f, CH to 0.75f, OH to 0.8f, CY to 0.8f)
        d.set(DrumParam.DRIVE, 0.15f); d.set(DrumParam.ACCENT, 0.7f); d.set(DrumParam.REVERB, 0.1f)
        d.set(DrumParam.DELAY, 0f); d.set(DrumParam.VOLUME, 0.72f); d.set(DrumParam.SWING, 0f)
        drums(d, 0, CH to "x.x.x.x.x.x.x.x.", OH to "......x.......x.", CY to ".x...x...x...x..", RS to "..x.....x.....x.")
        drums(d, 1, BD to "X.........x.....", SD to "....X..x....X..x", CH to "x.x.x.x.x.x.x.x.", OH to "......x.......x.", CY to ".x...x...x...x..")
        drums(d, 2, BD to "X.........X.....", TM to "..........x.....", SD to "....X..x....X..x", CH to "x.x.xxx.x.x.xxx.",
            OH to "......x.......x.", RS to "..x.....x.....x.", CY to "X.......x.......")
        drums(d, 3, BD to "X...............", SD to "x...x...x.x.xxxX", CH to "xxxxxxxxxxxxxxxx")
        drums(d, 4, BD to "X.........X.....", SD to "....X.......xxxX", CH to "x.x.x.x.x.x.x.x.", OH to "......x.........", CY to "X...............")
        drums(d, 5, BD to "X...............", SD to "........X.......", CH to "x...x...x...x...", OH to "..............x.", CY to "X...............")
        drums(d, 6, BD to "X.........x.....", CH to "x.x.x.x.x.x.x.x.", CY to ".x...x...x...x..")

        // DRUMS 1: the 808 kit, a layer under the drop and the tom roll of the build.
        val a = e.drums
        setup(a, Kit.TR808, BD to 0.75f, CP to 0.55f, TM to 0.6f, RS to 0.5f)
        a.set(DrumParam.DRIVE, 0.1f); a.set(DrumParam.REVERB, 0.15f); a.set(DrumParam.DELAY, 0f); a.set(DrumParam.VOLUME, 0.5f)
        drums(a, 1, RS to "..x.....x....x..")
        drums(a, 2, BD to "X.........x.....", CP to "....x.......x...", TM to "..............x.")
        drums(a, 3, TM to "........xxx.xxxx", CP to "............x.x.")
        drums(a, 4, TM to "............xxxx", BD to "X.........x.....")
        drums(a, 5, RS to "x.......x.......")
        drums(a, 6, RS to "..x.....x....x..")

        // WOBBLE: the bass. A rolling sub in the groove, the wobble in the drop, a rising line in the build.
        val w = e.wobble
        w.set(Param.SHAPE, 1f); w.set(Param.CUTOFF, 0.55f); w.set(Param.RESONANCE, 0.5f)
        w.set(Param.WOB_RATE, wobbleRate(2)); w.set(Param.WOB_DEPTH, 0.8f); w.set(Param.SUB, 0.6f); w.set(Param.GLIDE, 0.15f)
        w.set(Param.DECAY, 0.3f); w.set(Param.DRIVE, 0.4f); w.set(Param.DELAY, 0f); w.set(Param.REVERB, 0.05f); w.set(Param.VOLUME, 0.62f)
        line(w, 1, pat("29a", "29s", "29s", "29s", REST, REST, 29, REST, "32a", "32s", "32s", REST, "29a", REST, REST, 34))
        line(w, 2, pat("29a", "29s", "29s", "29s", "29s", "29s", "32a", "32s", "32s", "32s", "29a", "29s", "29s", "27a", "27s", REST))
        line(w, 3, pat("29a", "29s", "29s", "29s", "29s", "29s", "29s", "29s", "36a", "36s", "36s", "36s", "41a", "41s", "41s", "41s"))
        line(w, 4, pat("29a", "29s", "29s", "29s", "29s", "29s", "32a", "32s", "32s", "32s", "29a", "29s", REST, REST, REST, REST))
        line(w, 5, pat("41a", "41s", "41s", "41s", "41s", "41s", "41s", "41s", "44s", "44s", "44s", "44s", "41a", "41s", "41s", "41s"))

        // BASS 1: a 303 for stabs in F minor, drenched in delay.
        val b = e.bass
        b.set(Param.WAVEFORM, 0f); b.set(Param.CUTOFF, 0.35f); b.set(Param.RESONANCE, 0.6f); b.set(Param.ENV_MOD, 0.6f)
        b.set(Param.DECAY, 0.3f); b.set(Param.ACCENT, 0.7f); b.set(Param.DRIVE, 0.4f); b.set(Param.DELAY, 0.3f)
        b.set(Param.DELAY_FEEDBACK, 0.5f); b.set(Param.REVERB, 0.2f); b.set(Param.VOLUME, 0.4f)
        line(b, 1, pat(REST, REST, REST, "48a", REST, REST, 44, REST, REST, REST, REST, "48a", REST, REST, 41, REST))
        line(b, 2, pat("41a", REST, 48, REST, "44a", "44s", REST, 48, REST, "41a", REST, 49, "48as", 44, REST, 41))
        line(b, 4, pat("41a", REST, 48, REST, "44a", "44s", REST, 48, REST, "41a", REST, REST, REST, REST, REST, REST))
        line(b, 5, pat("48a", REST, REST, REST, REST, REST, REST, REST, "44a", REST, REST, REST, REST, REST, REST, REST))

        // BASS 2 and the sampler rest in this set.
        e.bass2.set(Param.VOLUME, 0.4f)
    }

    // ═══ Hardtekno tribe · 155 BPM · A minor ═══

    /**
     * A distorted four-on-the-floor on the HTK kit, a 909 for tribal toms, snare rolls and hats, a square sub, a
     * hypnotic acid line and dark stabs on the wobble synth. Scenes: 1 intro (kick and offbeat hats), 2 tribe
     * (percussion and a short acid line), 3 drop, 4 build (sub cut, snare roll, kick only), 5 fill, 6 break
     * (no kick: toms, perc and acid), 7 outro.
     */
    val hardtekTribe = LiveSet(
        "Hardtekno Tribe", 155, "Distorted kick, tribal toms, acid and stabs, A minor, 155 BPM",
        listOf("INTRO", "TRIBE", "DROP", "BUILD", "FILL", "BREAK", "OUTRO", "SPARE"),
    ) { e ->
        // DRUMS 2: HTK kit, a heavily driven kick, off-beat open hats, a long sub boom on the drop.
        val d = e.drums2
        setup(d, Kit.HTK, BD to 1f, SD to 0.7f, TM to 0.6f, CP to 0.65f, RS to 0.55f, CH to 0.5f, OH to 0.6f, CY to 0.7f)
        d.set(BD, TrackParam.TUNE, 0.45f)
        d.set(DrumParam.DRIVE, 0.6f); d.set(DrumParam.ACCENT, 0.8f); d.set(DrumParam.REVERB, 0.08f)
        d.set(DrumParam.DELAY, 0f); d.set(DrumParam.VOLUME, 0.55f); d.set(DrumParam.SWING, 0f)
        drums(d, 0, BD to "X...X...X...X...", OH to "..x...x...x...x.", CH to "x.x.x.x.x.x.x.x.")
        drums(d, 1, BD to "X...X...X...X...", OH to "..x...x...x...x.", CH to "x.x.x.x.x.x.x.x.", CP to "....x.......x...", RS to ".x..x..x..x..x..")
        drums(d, 2, BD to "X...X...X...X...", CY to "X.......x.......", TM to ".x.......x......", CP to "....x.......x...",
            RS to ".x..x..x..x..x..", CH to "xxxxxxxxxxxxxxxx", OH to "..x...x...x...x.")
        drums(d, 3, BD to "X...X...X...X...", CH to "x.x.x.x.x.x.x.x.")
        drums(d, 4, BD to "X...X...X...X..x", CY to "X...............", CP to "....x.......x...", RS to ".x..x..x..x..x..",
            CH to "xxxxxxxxxxxxxxxx", OH to "..x...x...x...x.")
        drums(d, 5, CP to "....x.......x...", RS to "x..x..x.x..x..x.", OH to "..x...x...x...x.", CH to "x.x.x.x.x.x.x.x.")
        drums(d, 6, BD to "X...X...X...X...", OH to "..x...x...x...x.")

        // DRUMS 1: 909 for tribal toms, the snare roll and a crisp hat layer.
        val a = e.drums
        setup(a, Kit.TR909, SD to 0.7f, TM to 0.7f, CP to 0.6f, RS to 0.55f, CH to 0.4f, OH to 0.5f)
        a.set(DrumParam.DRIVE, 0.25f); a.set(DrumParam.ACCENT, 0.75f); a.set(DrumParam.REVERB, 0.1f)
        a.set(DrumParam.DELAY, 0.08f); a.set(DrumParam.VOLUME, 0.5f)
        drums(a, 1, TM to "x..x..x.x..x..x.")
        drums(a, 2, TM to "x..x..x.x..x..x.", RS to "..x...x...x...x.", CH to "x.x.x.x.x.x.x.x.")
        drums(a, 3, SD to "x...x...x.x.xxXx", TM to "x...x...x...x...")
        drums(a, 4, TM to "x..x..x.x..x....", SD to ".............xxX")
        drums(a, 5, TM to "x..x..x.x..x..x.", RS to "..x...x...x...x.")
        drums(a, 6, CH to "x.x.x.x.x.x.x.x.")

        // BASS 1: the square sub kick-bass (cut in the build and the break).
        val b = e.bass
        b.set(Param.WAVEFORM, 1f); b.set(Param.CUTOFF, 0.28f); b.set(Param.RESONANCE, 0.4f); b.set(Param.ENV_MOD, 0.45f)
        b.set(Param.DECAY, 0.22f); b.set(Param.ACCENT, 0.7f); b.set(Param.DRIVE, 0.7f); b.set(Param.DELAY, 0f)
        b.set(Param.REVERB, 0f); b.set(Param.VOLUME, 0.4f)
        val sub = pat(REST, REST, 33, "57a", REST, REST, "33a", 33, REST, REST, 33, "60a", REST, REST, "33a", 31)
        line(b, 2, sub); line(b, 4, sub); line(b, 6, sub)

        // BASS 2: the hypnotic acid line, A minor.
        val c = e.bass2
        c.set(Param.WAVEFORM, 0f); c.set(Param.CUTOFF, 0.3f); c.set(Param.RESONANCE, 0.82f); c.set(Param.ENV_MOD, 0.75f)
        c.set(Param.DECAY, 0.35f); c.set(Param.ACCENT, 0.85f); c.set(Param.DRIVE, 0.55f); c.set(Param.DELAY, 0.3f)
        c.set(Param.DELAY_FEEDBACK, 0.5f); c.set(Param.REVERB, 0.12f); c.set(Param.VOLUME, 0.55f)
        val short = pat("45a", REST, 45, REST, REST, 52, REST, 45, "43a", REST, 45, REST, 48, REST, REST, REST)
        val full = pat("45a", REST, 45, "48s", REST, "52a", REST, 45, 43, REST, "45a", REST, 48, "50s", REST, "52a")
        line(c, 1, short); line(c, 2, full); line(c, 3, full); line(c, 4, full); line(c, 5, short)

        // WOBBLE: dark stabs, a fast wobble on the cutoff.
        val w = e.wobble
        w.set(Param.SHAPE, 1f); w.set(Param.CUTOFF, 0.6f); w.set(Param.RESONANCE, 0.6f)
        w.set(Param.WOB_RATE, wobbleRate(6)); w.set(Param.WOB_DEPTH, 0.85f); w.set(Param.SUB, 0.2f); w.set(Param.GLIDE, 0f)
        w.set(Param.DECAY, 0.15f); w.set(Param.DRIVE, 0.5f); w.set(Param.DELAY, 0.3f); w.set(Param.DELAY_FEEDBACK, 0.5f)
        w.set(Param.REVERB, 0.2f); w.set(Param.VOLUME, 0.4f)
        val stabs = pat(REST, REST, REST, "57a", REST, REST, 57, REST, REST, REST, REST, "55a", REST, REST, 52, REST)
        line(w, 2, stabs); line(w, 4, stabs)
        line(w, 5, pat("57a", REST, REST, REST, REST, REST, REST, REST, "55a", REST, REST, REST, REST, REST, REST, REST))
    }

    val all = listOf(ukDnb, hardtekTribe)
}
