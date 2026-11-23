package com.vtlo.pads

import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.floor
import kotlin.math.max

/**
 * Records taps on a 4 x 4 pad grid and plays them back in a loop of 8 or 16 bars, locked to
 * the studio transport (the position is counted in quarter notes from the last play).
 *
 * Threading: the UI thread does everything that changes the recording ([press], [release],
 * [undo], [clear], [load]) and publishes an immutable snapshot; the audio thread only reads that
 * snapshot in [collect], so no locks and no allocation happen while rendering.
 *
 * What a pad means is up to the instrument: drum and sampler pads trigger a track or pad
 * (pads 9 to 16 are the accented versions), bass pads play chromatic notes from [baseNote].
 */
class PadLoop {
    /** Loop length in bars: 8 or 16. Recorded taps beyond a shorter loop are kept, just not played. */
    @Volatile var bars = 8
        set(v) { field = if (v >= 12) 16 else 8 }

    /** Snap recorded taps to the nearest 16th note. */
    @Volatile var quantize = true

    /** While armed, taps are written into the loop (overdub). */
    @Volatile var recording = false
        set(v) {
            if (v && !field) newPass = true
            field = v
        }

    /** Plays the recorded loop back. */
    @Volatile var enabled = true

    /** The bass pads play chromatic notes from here (MIDI note number). */
    @Volatile var baseNote = 36

    val loopBeats: Double get() = bars * 4.0

    /** Events per pad, for the pads' "has content" lights. */
    val padCounts = AtomicIntegerArray(PADS)

    /** Counts playback triggers per pad, so the UI can flash a pad when the loop plays it. */
    val hits = AtomicIntegerArray(PADS)

    val eventCount: Int get() = events.size

    // ─── UI thread: recording ────────────────────────────────

    private class Ev(val tick: Int, val pad: Int, var dur: Int, val pass: Int, val pressBeat: Double)

    private val events = ArrayList<Ev>()
    private val lastPress = arrayOfNulls<Ev>(PADS)
    private var pass = 0
    private var lastLoopIndex = -1
    private var newPass = true

    class Snapshot(val ticks: IntArray, val pads: IntArray, val durs: IntArray)

    @Volatile private var snap = Snapshot(IntArray(0), IntArray(0), IntArray(0))

    /**
     * A pad went down at transport position [beat] (quarter notes). Recorded if armed.
     * @return true if the tap was written.
     */
    fun press(pad: Int, beat: Double): Boolean {
        if (!recording || pad !in 0 until PADS || events.size >= MAX_EVENTS) return false
        val len = loopBeats
        val loopIndex = floor(beat / len).toInt()
        var pos = beat - loopIndex * len
        if (quantize) pos = Math.round(pos * 4.0) / 4.0
        if (pos >= len) pos -= len
        // Each lap of the loop (and each time recording is armed) is one pass, for UNDO.
        if (newPass || loopIndex != lastLoopIndex) {
            pass++
            newPass = false
            lastLoopIndex = loopIndex
        }
        val ev = Ev(Math.round(pos * PPQ).toInt() % (bars * 4 * PPQ), pad, DEFAULT_DUR, pass, beat)
        events.add(ev)
        lastPress[pad] = ev
        publish()
        return true
    }

    /** The pad came up at [beat]: sets how long the note lasts (only the bass pads use it). */
    fun release(pad: Int, beat: Double) {
        val ev = lastPress.getOrNull(pad) ?: return
        lastPress[pad] = null
        if (!events.contains(ev)) return
        ev.dur = Math.round((beat - ev.pressBeat) * PPQ).toInt().coerceIn(MIN_DUR, 4 * PPQ)
        publish()
    }

    /** Removes everything recorded in the most recent pass. */
    fun undo() {
        if (events.isEmpty()) return
        val last = events.maxOf { it.pass }
        events.removeAll { it.pass == last }
        lastPress.fill(null)
        publish()
    }

    fun clear() {
        events.clear()
        lastPress.fill(null)
        pass = 0
        publish()
    }

    private fun publish() {
        val n = events.size
        val ticks = IntArray(n)
        val pads = IntArray(n)
        val durs = IntArray(n)
        val counts = IntArray(PADS)
        events.forEachIndexed { i, e ->
            ticks[i] = e.tick
            pads[i] = e.pad
            durs[i] = e.dur
            counts[e.pad]++
        }
        snap = Snapshot(ticks, pads, durs)
        for (p in 0 until PADS) padCounts.set(p, counts[p])
    }

    // ─── Saving ──────────────────────────────────────────────

    fun serialize(): String = buildString {
        append("v1|").append(bars).append('|').append(if (quantize) 1 else 0).append('|').append(baseNote).append('|')
        events.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append(e.tick).append('.').append(e.pad).append('.').append(e.dur).append('.').append(e.pass)
        }
    }

    fun load(data: String): Boolean {
        val parts = data.split('|')
        if (parts.size < 5 || parts[0] != "v1") return false
        val b = parts[1].toIntOrNull() ?: return false
        val q = parts[2].toIntOrNull() ?: return false
        val base = parts[3].toIntOrNull() ?: return false
        val loaded = ArrayList<Ev>()
        if (parts[4].isNotEmpty()) {
            for (token in parts[4].split(',')) {
                val f = token.split('.')
                if (f.size != 4) return false
                val tick = f[0].toIntOrNull() ?: return false
                val pad = f[1].toIntOrNull() ?: return false
                val dur = f[2].toIntOrNull() ?: return false
                val ps = f[3].toIntOrNull() ?: return false
                if (pad !in 0 until PADS || tick < 0 || tick >= 16 * 4 * PPQ || loaded.size >= MAX_EVENTS) return false
                loaded.add(Ev(tick, pad, dur.coerceIn(MIN_DUR, 4 * PPQ), ps, 0.0))
            }
        }
        events.clear()
        events.addAll(loaded)
        lastPress.fill(null)
        pass = loaded.maxOfOrNull { it.pass } ?: 0
        bars = b
        quantize = q != 0
        baseNote = base.coerceIn(12, 84)
        newPass = true
        publish()
        return true
    }

    // ─── Audio thread: playback ──────────────────────────────

    /** The events that fall inside one audio block, sorted by frame (note-offs before note-ons). */
    class Sink(capacity: Int = 512) {
        val offset = IntArray(capacity)
        val pad = IntArray(capacity)
        val on = BooleanArray(capacity)
        var n = 0
        private val key = IntArray(capacity)

        internal fun add(frame: Int, pad: Int, on: Boolean) {
            if (n == offset.size) return
            this.offset[n] = frame
            this.pad[n] = pad
            this.on[n] = on
            key[n] = frame * 2 + if (on) 1 else 0
            n++
        }

        internal fun sort() {
            for (i in 1 until n) {
                val k = key[i]
                val o = offset[i]
                val p = pad[i]
                val on = this.on[i]
                var j = i - 1
                while (j >= 0 && key[j] > k) {
                    key[j + 1] = key[j]; offset[j + 1] = offset[j]; pad[j + 1] = pad[j]; this.on[j + 1] = this.on[j]
                    j--
                }
                key[j + 1] = k; offset[j + 1] = o; pad[j + 1] = p; this.on[j + 1] = on
            }
        }
    }

    /**
     * Fills [sink] with the playback events for a block that starts at transport position [beat0]
     * and lasts [blockBeats] quarter notes ([frames] audio frames).
     */
    fun collect(beat0: Double, blockBeats: Double, frames: Int, sink: Sink) {
        sink.n = 0
        if (!enabled || frames <= 0) return
        val s = snap
        if (s.ticks.isEmpty()) return
        val len = loopBeats
        val limit = (bars * 4 * PPQ)
        val p0 = beat0 - floor(beat0 / len) * len
        val beatsPerFrame = blockBeats / frames
        for (i in s.ticks.indices) {
            val tick = s.ticks[i]
            if (tick >= limit) continue
            var d = tick.toDouble() / PPQ - p0
            if (d < 0) d += len
            if (d < blockBeats) sink.add(frameOf(d, beatsPerFrame, frames), s.pads[i], true)
            val end = (tick + s.durs[i]).toDouble() / PPQ
            var e = (end - floor(end / len) * len) - p0
            if (e < 0) e += len
            if (e < blockBeats) sink.add(frameOf(e, beatsPerFrame, frames), s.pads[i], false)
        }
        sink.sort()
    }

    /** The frame inside the block for a position [beats] after its start (a hair of tolerance for rounding). */
    private fun frameOf(beats: Double, beatsPerFrame: Double, frames: Int): Int =
        max(0, (beats / beatsPerFrame + 1e-6).toInt()).coerceAtMost(frames - 1)

    companion object {
        const val PADS = 16

        /** Resolution of recorded positions: ticks per quarter note. */
        const val PPQ = 96
        const val MAX_EVENTS = 4096
        const val DEFAULT_DUR = PPQ / 4 // a 16th note
        const val MIN_DUR = PPQ / 8
    }
}
