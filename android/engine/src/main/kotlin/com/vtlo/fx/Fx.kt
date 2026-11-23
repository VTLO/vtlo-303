package com.vtlo.fx

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

/**
 * A stereo insert effect working in place on interleaved frames. Parameters are written
 * from the UI thread and read on the audio thread (lock-free).
 */
abstract class Effect(val id: FxId, val params: List<FxParam>, protected val fs: Int) {
    @Volatile var on = false

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

    /** Processes [frames] interleaved stereo frames in place. [bpm] is the studio tempo. */
    abstract fun process(buf: FloatArray, frames: Int, bpm: Float)

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

    fun process(buf: FloatArray, frames: Int, bpm: Float) {
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
                fx.process(buf, frames, bpm)
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
                fx.process(buf, frames, bpm)
                continue
            }
            if (scratch.size < n) scratch = FloatArray(n)
            System.arraycopy(buf, 0, scratch, 0, n)
            fx.process(scratch, frames, bpm)
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
