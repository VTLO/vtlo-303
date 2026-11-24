package com.vtlo.acid303

import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.core.audio.SequencerEngine
import com.vtlo.core.dsp.SoftLimiter
import com.vtlo.fx.FxRack
import com.vtlo.pads.PadLoop
import kotlin.math.max
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.sampler.dsp.SamplerEngine
import com.vtlo.sampler.dsp.SamplerParam
import kotlin.math.exp

/**
 * Two bass lines, a wobble synth, two drum machines and a sampler as one instrument: all render in the same
 * audio callback, start on the same sample and share tempo and swing, so they
 * stay locked. Each part can be switched out of the mix (it keeps running in time).
 *
 * [parts] are in page order: bass line 1 and 2, the wobble synth, drums 1 and 2, then the sampler. The master
 * [fx] rack (flanger, chorus, beat repeat and more) processes the summed mix before the limiter.
 */
class StudioEngine(
    val bass: AcidEngine,
    val drums: DrumEngine,
    val sampler: SamplerEngine,
    val bass2: AcidEngine,
    val drums2: DrumEngine,
    /** The wobble synth (an [AcidEngine] built with `wobble = true`). */
    val wobble: AcidEngine,
) : SequencerEngine {

    /** Master effects, applied to the whole mix. */
    val fx = FxRack(bass.sampleRate)

    // The transport position in quarter notes, advanced by the audio thread and restarted at 0 on
    // every play(). Synced effects and the pad loops read it; the UI interpolates it between blocks.
    @Volatile private var clockReset = false
    private var beatClock = 0.0
    @Volatile private var blockBeat = 0.0
    @Volatile private var blockNanos = 0L
    @Volatile private var blockBpm = 120f

    /** The transport position in quarter notes right now (an estimate between audio blocks). */
    fun beatNow(): Double =
        if (blockNanos == 0L) 0.0 else blockBeat + (System.nanoTime() - blockNanos) / 1e9 * blockBpm / 60.0

    /** The studio tempo, as last rendered. */
    val bpm: Float get() = blockBpm

    enum class Kind { BASS, DRUMS, SAMPLER }

    /**
     * One instrument in the mix, with access to its (normalized) tempo and swing, and the pad
     * [loop] that records taps for it.
     */
    class Part(
        val engine: SequencerEngine,
        val kind: Kind,
        val getTempo: () -> Float, val setTempo: (Float) -> Unit,
        val getSwing: () -> Float, val setSwing: (Float) -> Unit,
    ) {
        @Volatile var on = true
        val loop = PadLoop()
        internal var buf = FloatArray(0)
        internal var lastBassPad = -1
        internal var gain = Double.NaN // set from [on] on the first render
    }

    val parts = listOf(
        Part(bass, Kind.BASS, { bass.get(Param.TEMPO) }, { bass.set(Param.TEMPO, it) },
            { bass.get(Param.SWING) }, { bass.set(Param.SWING, it) }),
        Part(bass2, Kind.BASS, { bass2.get(Param.TEMPO) }, { bass2.set(Param.TEMPO, it) },
            { bass2.get(Param.SWING) }, { bass2.set(Param.SWING, it) }),
        Part(wobble, Kind.BASS, { wobble.get(Param.TEMPO) }, { wobble.set(Param.TEMPO, it) },
            { wobble.get(Param.SWING) }, { wobble.set(Param.SWING, it) }),
        Part(drums, Kind.DRUMS, { drums.get(DrumParam.TEMPO) }, { drums.set(DrumParam.TEMPO, it) },
            { drums.get(DrumParam.SWING) }, { drums.set(DrumParam.SWING, it) }),
        Part(drums2, Kind.DRUMS, { drums2.get(DrumParam.TEMPO) }, { drums2.set(DrumParam.TEMPO, it) },
            { drums2.get(DrumParam.SWING) }, { drums2.set(DrumParam.SWING, it) }),
        Part(sampler, Kind.SAMPLER, { sampler.get(SamplerParam.TEMPO) }, { sampler.set(SamplerParam.TEMPO, it) },
            { sampler.get(SamplerParam.SWING) }, { sampler.set(SamplerParam.SWING, it) }),
    )

    // ─── Pads ────────────────────────────────────────────────

    /**
     * How late a tap reaches the loop, in seconds: touch handling plus the audio output buffer.
     * Recorded taps are moved earlier by this much so they land where the player meant them.
     */
    @Volatile var tapLatencySeconds = 0.045

    /** Replaces the clock for taps (used by the scripted video recording to land taps exactly on the grid). */
    @Volatile var tapBeatSource: (() -> Double)? = null

    /** The transport position of a tap that happened [ageSeconds] ago, latency compensated. */
    fun tapBeat(ageSeconds: Double = 0.0): Double =
        tapBeatSource?.invoke() ?:
        if (clockReset) 0.0 // the transport has just been started and the clock restarts on the next block
        else max(0.0, beatNow() - (ageSeconds + tapLatencySeconds) * blockBpm / 60.0)

    /**
     * Plays pad [pad] (0..15) of [part] now, from any thread, and records the tap if that part's loop
     * is armed. [beat] overrides the transport position (use 0 when the tap itself starts the transport).
     */
    fun padDown(part: Part, pad: Int, ageSeconds: Double = 0.0, beat: Double = tapBeat(ageSeconds)) {
        firePad(part, pad, true)
        part.loop.press(pad, beat)
    }

    /** The pad came up (ends a bass note and sets its recorded length). */
    fun padUp(part: Part, pad: Int, ageSeconds: Double = 0.0) {
        firePad(part, pad, false)
        part.loop.release(pad, tapBeat(ageSeconds))
    }

    /** Starts or stops what a pad plays. Safe from the UI and the audio thread. */
    private fun firePad(part: Part, pad: Int, on: Boolean) {
        when (part.kind) {
            Kind.DRUMS -> if (on) (part.engine as DrumEngine).trigger(pad % 8, pad >= 8)
            Kind.SAMPLER -> if (on) (part.engine as SamplerEngine).trigger(pad % 8, pad >= 8)
            Kind.BASS -> {
                val e = part.engine as AcidEngine
                if (on) {
                    part.lastBassPad = pad
                    e.padNoteOn(part.loop.baseNote + pad, false)
                } else if (part.lastBassPad == pad) {
                    e.padNoteOff() // a newer note keeps its own length
                }
            }
        }
    }

    /**
     * Moves every instrument to pattern slot [scene] (0..7, A1 to A8). Each one switches at the end of its own
     * loop, so the whole studio changes together on the next bar line.
     */
    fun selectScene(scene: Int) {
        val s = scene.coerceIn(0, 7)
        bass.selectedPattern = s; bass2.selectedPattern = s; wobble.selectedPattern = s
        drums.selectedPattern = s; drums2.selectedPattern = s; sampler.selectedPattern = s
    }

    /** The scene (0..7) every instrument is set to, or -1 if they differ. */
    fun scene(): Int {
        val s = bass.selectedPattern
        return if (listOf(bass2.selectedPattern, wobble.selectedPattern, drums.selectedPattern, drums2.selectedPattern, sampler.selectedPattern).all { it == s }) s else -1
    }

    /** The part that plays [engine], for callers that think in instruments rather than page order. */
    fun partOf(engine: SequencerEngine): Part = parts.first { it.engine === engine }

    override val isPlaying: Boolean get() = parts.any { it.engine.isPlaying }

    private val partFade = exp(-1.0 / (0.01 * bass.sampleRate))
    private var tempo = Float.NaN
    private var swing = Float.NaN

    init {
        parts[2].loop.baseNote = 29 // the wobble pads start on a low F, where a wobble lives
        val rate = bass.sampleRate
        require(listOf(drums.sampleRate, sampler.sampleRate, bass2.sampleRate, drums2.sampleRate, wobble.sampleRate).all { it == rate })
        syncTiming()
    }

    @Volatile private var running = false

    /** A metronome click on every beat (louder on the bar), to tap along to. */
    @Volatile var click = false
    private var clickLeft = 0
    private var clickTotal = 1
    private var clickPhase = 0.0
    private var clickInc = 0.0
    private var clickGain = 0f

    override fun play() {
        parts.forEach { it.engine.play() }
        clockReset = true
        running = true
    }

    override fun stop() {
        parts.forEach { it.engine.stop() }
        running = false
    }

    override fun panic() {
        running = false
        parts.forEach { it.engine.panic() }
        fx.reset()
    }

    /**
     * Whichever part's tempo or swing knob moved last sets it for all. All parts
     * use the same normalized ranges (60–200 BPM, swing 0–1).
     */
    private fun syncTiming() {
        tempo = sync(tempo, Part::getTempo, Part::setTempo)
        swing = sync(swing, Part::getSwing, Part::setSwing)
    }

    private inline fun sync(last: Float, get: (Part) -> () -> Float, set: (Part) -> (Float) -> Unit): Float {
        var v = last
        for (i in parts.indices) {
            val x = get(parts[i])()
            if (x != last) {
                v = x
                break
            }
        }
        if (v == last || v.isNaN()) return last
        for (i in parts.indices) if (get(parts[i])() != v) set(parts[i])(v)
        return v
    }

    override fun render(out: FloatArray, frames: Int) {
        syncTiming()
        if (clockReset) {
            clockReset = false
            beatClock = 0.0
        }
        val tempo = Param.normToBpm(bass.get(Param.TEMPO))
        blockBeat = beatClock
        blockNanos = System.nanoTime()
        blockBpm = tempo
        val n = frames * 2
        for (pi in parts.indices) {
            val p = parts[pi]
            if (p.buf.size < n) p.buf = FloatArray(n)
            renderPart(p, frames, tempo)
            if (p.gain.isNaN()) p.gain = if (p.on) 1.0 else 0.0
        }
        java.util.Arrays.fill(out, 0, n, 0f)
        for (pi in parts.indices) {
            val p = parts[pi]
            val target = if (p.on) 1.0 else 0.0
            var g = p.gain
            val b = p.buf
            var i = 0
            for (f in 0 until frames) {
                g = target + partFade * (g - target)
                out[i] += (b[i] * g * MIX_TRIM).toFloat()
                out[i + 1] += (b[i + 1] * g * MIX_TRIM).toFloat()
                i += 2
            }
            p.gain = g
        }
        fx.process(out, frames, tempo, blockBeat)
        if (click && running) addClick(out, frames, tempo)
        beatClock += frames * tempo / 60.0 / bass.sampleRate
        for (i in 0 until n) out[i] = SoftLimiter.process(out[i].toDouble()).toFloat()
    }

    private companion object {
        /** Headroom for the sum: a kick and a bass note on the same beat no longer reach the limiter (-2.5 dB). */
        const val MIX_TRIM = 0.75
    }

    // ─── Pad loop playback ───────────────────────────────────

    private val sink = PadLoop.Sink()
    private var seg = FloatArray(0)

    /**
     * Renders one part. When its pad loop has events in this block the block is split at each
     * event, so every pad hit starts on exactly the right sample.
     */
    private fun renderPart(p: Part, frames: Int, tempo: Float) {
        sink.n = 0
        if (running) p.loop.collect(blockBeat, frames * tempo / 60.0 / bass.sampleRate, frames, sink)
        if (sink.n == 0) {
            p.engine.render(p.buf, frames)
            return
        }
        var done = 0
        for (k in 0 until sink.n) {
            val at = sink.offset[k]
            if (at > done) {
                renderSegment(p, done, at - done)
                done = at
            }
            firePad(p, sink.pad[k], sink.on[k])
            if (sink.on[k]) p.loop.hits.incrementAndGet(sink.pad[k])
        }
        if (done < frames) renderSegment(p, done, frames - done)
    }

    private fun renderSegment(p: Part, start: Int, len: Int) {
        if (seg.size < len * 2) seg = FloatArray(len * 2)
        p.engine.render(seg, len)
        System.arraycopy(seg, 0, p.buf, start * 2, len * 2)
    }

    private fun addClick(out: FloatArray, frames: Int, tempo: Float) {
        val beatsPerFrame = tempo / 60.0 / bass.sampleRate
        val end = blockBeat + frames * beatsPerFrame
        var nextBeat = Math.ceil(blockBeat - 1e-9)
        var nextAt = if (nextBeat < end) ((nextBeat - blockBeat) / beatsPerFrame).toInt() else -1
        var i = 0
        for (f in 0 until frames) {
            if (f == nextAt) {
                val bar = nextBeat.toLong() % 4L == 0L
                clickTotal = (0.03 * bass.sampleRate).toInt()
                clickLeft = clickTotal
                clickInc = 2.0 * Math.PI * (if (bar) 1800.0 else 1200.0) / bass.sampleRate
                clickGain = if (bar) 0.32f else 0.2f
                clickPhase = 0.0
                nextBeat += 1.0
                nextAt = ((nextBeat - blockBeat) / beatsPerFrame).toInt()
                if (nextAt >= frames) nextAt = -1
            }
            if (clickLeft > 0) {
                val env = clickLeft.toFloat() / clickTotal
                val v = (Math.sin(clickPhase) * env * env * clickGain).toFloat()
                out[i] += v
                out[i + 1] += v
                clickPhase += clickInc
                clickLeft--
            }
            i += 2
        }
    }
}
