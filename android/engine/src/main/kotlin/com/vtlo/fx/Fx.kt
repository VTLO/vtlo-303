package com.vtlo.fx

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.exp

/** The master effects, in signal-chain order. */
enum class FxId(val key: String, val label: String, val blurb: String) {
    BEAT_REPEAT("repeat", "BEAT REPEAT", "Loops the last slice, locked to tempo"),
    WARBLE("warble", "WARBLE", "Tape wow and flutter, warm saturation"),
    VOWEL("vowel", "VOWEL", "A talking filter gliding A E I O U"),
    FLANGER("flanger", "FLANGER", "Swept comb filter with feedback"),
    CHORUS("chorus", "CHORUS", "Three detuned voices, spread wide"),
    GHOST("ghost", "GHOST", "Reversed echoes that haunt the mix"),
}

/** One knob of an effect: normalized 0..1, with a display formatter. */
class FxParam(
    val key: String,
    val label: String,
    val default: Float,
    /** Detented knobs snap to this many positions (0 = continuous). */
    val detents: Int = 0,
    val format: (Float) -> String,
)

/** A musical length for tempo sync: [beats] quarter notes long. */
class Division(val label: String, val beats: Float)

/** Division lists shared by the effects. */
object Divisions {
    /** LFO cycle lengths, from slow sweeps to fast wobbles. */
    val CYCLES = listOf(
        Division("8 bars", 32f), Division("4 bars", 16f), Division("2 bars", 8f), Division("1 bar", 4f),
        Division("1/2", 2f), Division("1/4", 1f), Division("1/8", 0.5f), Division("1/16", 0.25f),
    )

    /** Echo / grain lengths. */
    val ECHO = listOf(
        Division("1/16", 0.25f), Division("1/8", 0.5f), Division("1/8.", 0.75f),
        Division("1/4", 1f), Division("1/4.", 1.5f), Division("1/2", 2f),
    )
}

/**
 * A stereo insert effect working in place on interleaved frames. Parameters are written
 * from the UI thread and read on the audio thread (lock-free).
 *
 * Tempo sync: an effect with [divisions] can lock one knob ([syncParam]) to the studio tempo.
 * With [sync] on, that knob picks a note division instead of a free value, and each setting
 * is remembered separately, so switching back and forth loses nothing.
 */
abstract class Effect(
    val id: FxId,
    val params: List<FxParam>,
    protected val fs: Int,
    val divisions: List<Division>? = null,
    val syncParam: Int = -1,
    divisionDefault: Float = 0.5f,
) {
    @Volatile var on = false

    /** True for effects that are always locked to the tempo (beat repeat). */
    open val alwaysSynced: Boolean get() = false

    val syncable: Boolean get() = divisions != null

    @Volatile var sync = false

    private val divBits = AtomicInteger(divisionDefault.toRawBits())

    fun getDivision(): Float = Float.fromBits(divBits.get())

    fun setDivision(v: Float) {
        val n = divisions?.size ?: return
        var x = v.coerceIn(0f, 1f)
        if (n > 1) x = Math.round(x * (n - 1)).toFloat() / (n - 1)
        divBits.set(x.toRawBits())
    }

    /** The division currently selected (meaningful when [syncable]). */
    fun division(): Division {
        val d = divisions!!
        return d[Math.round(getDivision() * (d.size - 1))]
    }

    private fun syncActive(i: Int) = sync && divisions != null && i == syncParam

    /** What the knob for parameter [i] shows and edits right now. */
    fun knobValue(i: Int): Float = if (syncActive(i)) getDivision() else get(i)

    fun setFromKnob(i: Int, v: Float) {
        if (syncActive(i)) setDivision(v) else set(i, v)
    }

    fun knobDetents(i: Int): Int = if (syncActive(i)) divisions!!.size else params[i].detents

    fun knobDefault(i: Int): Float = if (syncActive(i)) 0.5f else params[i].default

    fun knobText(i: Int, v: Float): String =
        if (syncActive(i)) divisions!![Math.round(v.coerceIn(0f, 1f) * (divisions.size - 1))].label else params[i].format(v)

    private val values = AtomicIntegerArray(params.size).also { a ->
        params.forEachIndexed { i, p -> a.set(i, p.default.toRawBits()) }
    }

    fun get(i: Int): Float = Float.fromBits(values.get(i))

    fun set(i: Int, v: Float) {
        val p = params[i]
        var x = v.coerceIn(0f, 1f)
        if (p.detents > 1) x = Math.round(x * (p.detents - 1)).toFloat() / (p.detents - 1)
        values.set(i, x.toRawBits())
    }

    fun resetParams() = params.indices.forEach { set(it, params[it].default) }

    /** Effects that manage their own on/off (e.g. they must keep recording while bypassed). */
    open val selfBypass: Boolean get() = false

    /** Clears delay lines and tails. Audio thread only. */
    abstract fun reset()

    /**
     * Processes [frames] interleaved stereo frames in place. [bpm] is the studio tempo and
     * [beat] the transport position, in quarter notes, at the first frame (synced LFOs lock to it).
     */
    abstract fun process(buf: FloatArray, frames: Int, bpm: Float, beat: Double)

    protected fun p(i: Int) = get(i)
}

/**
 * The master effects rack: six effects in series, applied to the whole mix before the
 * limiter. An effect that is switched off is bypassed (with a short crossfade).
 */
class FxRack(val sampleRate: Int) {
    val effects: List<Effect> = listOf(
        BeatRepeat(sampleRate),
        Warble(sampleRate),
        Vowel(sampleRate),
        Flanger(sampleRate),
        Chorus(sampleRate),
        Ghost(sampleRate),
    )

    fun effect(id: FxId): Effect = effects.first { it.id == id }

    @Volatile private var resetRequested = false
    private var scratch = FloatArray(0)
    private val enable = FloatArray(effects.size)
    private val wasRunning = BooleanArray(effects.size)
    private val enableStep = 1f - exp(-1f / (0.008f * sampleRate)) // ~8 ms crossfade

    val anyOn: Boolean get() = effects.any { it.on }

    /** Requests a reset (stop / panic) from any thread; it happens on the next audio block. */
    fun reset() {
        resetRequested = true
    }

    fun process(buf: FloatArray, frames: Int, bpm: Float, beat: Double = 0.0) {
        if (resetRequested) {
            resetRequested = false
            effects.forEach { it.reset() }
            enable.fill(0f)
            wasRunning.fill(false)
        }
        val n = frames * 2
        for (k in effects.indices) {
            val fx = effects[k]
            if (fx.selfBypass) {
                fx.process(buf, frames, bpm, beat)
                continue
            }
            val target = if (fx.on) 1f else 0f
            var e = enable[k]
            if (e == 0f && target == 0f) {
                wasRunning[k] = false
                continue
            }
            if (!wasRunning[k]) fx.reset() // starting from silence: no stale tail
            wasRunning[k] = true
            if (e == 1f && target == 1f) {
                fx.process(buf, frames, bpm, beat)
                continue
            }
            if (scratch.size < n) scratch = FloatArray(n)
            System.arraycopy(buf, 0, scratch, 0, n)
            fx.process(scratch, frames, bpm, beat)
            var i = 0
            for (f in 0 until frames) {
                e += (target - e) * enableStep
                if (kotlin.math.abs(target - e) < 1e-4f) e = target
                buf[i] += (scratch[i] - buf[i]) * e
                buf[i + 1] += (scratch[i + 1] - buf[i + 1]) * e
                i += 2
            }
            enable[k] = e
        }
    }
}
