package com.vtlo.desktop.ui

import com.vtlo.acid303.StudioEngine
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.pads.PadLoop
import com.vtlo.sampler.dsp.SamplerEngine
import java.awt.event.KeyEvent

/**
 * The optional 16-pad deck under an instrument page: a 4 x 4 grid of rubber pads (pad 1 bottom left)
 * to play the instrument by hand, and a loop recorder that writes the taps into a loop of 8 or 16
 * bars locked to the transport. A slim handle shows or hides it per page.
 *
 * Besides the mouse, the pads answer to the computer keyboard laid out like the grid:
 * `1 2 3 4` (top row), `Q W E R`, `A S D F`, `Z X C V` (bottom row), with several keys at once.
 */
class PadDeck(private val host: Host, private val partIndex: Int, expanded: Boolean, private val onToggle: (Boolean) -> Unit) {
    private val engine get() = host.studio.engine
    private val part get() = engine.parts[partIndex]
    private val loop get() = part.loop

    var expanded = expanded
        private set

    private val handle = Pad("", dark = true, hint = "Show or hide the 16 pads and loop recorder") { setExpanded(!this.expanded, notify = true) }
    private val body = ArrayList<Widget>()
    private val pads = arrayOfNulls<MpcPad>(PadLoop.PADS)
    private val seenHits = IntArray(PadLoop.PADS)
    private val flashUntil = LongArray(PadLoop.PADS)
    private val heldKeys = HashSet<Int>()
    private var frame = 0

    private lateinit var rec: Pad
    private lateinit var eight: Pad
    private lateinit var sixteen: Pad
    private lateinit var quant: Pad
    private lateinit var play: Pad
    private lateinit var click: Pad
    private val bar = LoopBar()

    /** Design units this deck adds below the page. */
    val height get() = if (expanded) HEIGHT else HANDLE_HEIGHT

    init {
        handle.place(8f, StudioWindow.DH + 2f, 984f, 18f)
        buildBody()
        for (i in 0 until PadLoop.PADS) seenHits[i] = loop.hits.get(i)
        handle.text = handleText()
    }

    fun widgets(): List<Widget> = if (expanded) listOf<Widget>(handle) + body else listOf(handle)

    private fun handleText() = if (expanded) "▼  PADS" else "▲  PADS"

    fun setExpanded(v: Boolean, notify: Boolean = false) {
        expanded = v
        handle.text = handleText()
        if (v) refresh()
        if (notify) onToggle(v)
    }

    // ─── Construction ────────────────────────────────────────

    private fun <T : Widget> put(w: T, x: Float, y: Float, width: Float, height: Float): T {
        w.place(x, y, width, height)
        body.add(w)
        return w
    }

    private fun button(label: String, x: Float, y: Float, w: Float, hint: String, led: Boolean = false, action: () -> Unit): Pad =
        put(Pad(label, led = led, dark = true, hint = hint) { action(); refresh() }, x, y, w, 30f)

    private fun buildBody() {
        val top = StudioWindow.DH + 22f
        put(DeckBody(), 8f, top, 984f, 176f)
        put(DeckBadge(), 22f, top + 6f, 320f, 62f)

        val x = 22f
        rec = button("REC", x, top + 72f, 72f, "Record pad taps into the loop (the first tap starts the transport)", led = true) { loop.recording = !loop.recording }
        eight = button("8 BARS", x + 78f, top + 72f, 76f, "Loop of 8 bars") { loop.bars = 8 }
        sixteen = button("16 BARS", x + 160f, top + 72f, 80f, "Loop of 16 bars") { loop.bars = 16 }
        play = button("LOOP", x + 246f, top + 72f, 72f, "Play the recorded loop", led = true) { loop.enabled = !loop.enabled }
        quant = button("QUANT", x, top + 106f, 72f, "Snap taps to 16th notes", led = true) { loop.quantize = !loop.quantize }
        click = button("CLICK", x + 78f, top + 106f, 76f, "Metronome click on every beat", led = true) { engine.click = !engine.click }
        button("UNDO", x + 160f, top + 106f, 80f, "Undo the last recorded pass") { loop.undo() }
        button("CLEAR", x + 246f, top + 106f, 72f, "Clear the recorded loop") { loop.clear() }
        if (part.kind == StudioEngine.Kind.BASS) {
            button("OCT −", x, top + 140f, 154f, "Pads one octave lower") { shiftOctave(-12) }
            button("OCT +", x + 164f, top + 140f, 154f, "Pads one octave higher") { shiftOctave(12) }
        }

        put(bar, 352f, top + 6f, 626f, 16f)
        val padW = 152f
        val padH = 32f
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val i = row * 4 + col
                val py = top + 26f + (3 - row) * (padH + 4f)
                pads[i] = put(MpcPad(i, KEY_LABELS[i], ::padDown, ::padUp), 352f + col * (padW + 6f), py, padW, padH)
            }
        }
    }

    private fun shiftOctave(delta: Int) {
        loop.baseNote = (loop.baseNote + delta).coerceIn(24, 60)
        refreshLabels()
    }

    // ─── Playing and recording ───────────────────────────────

    private fun padDown(pad: Int) {
        if (loop.recording && !host.playing) {
            // Tapping a rhythm in starts the transport, and the first tap lands on beat one.
            host.toggleTransport()
            engine.padDown(part, pad, beat = 0.0)
        } else {
            engine.padDown(part, pad)
        }
    }

    private fun padUp(pad: Int) = engine.padUp(part, pad)

    /** A computer key went down or up. @return true if it was one of the pad keys. */
    fun key(code: Int, pressed: Boolean): Boolean {
        val i = KEY_PADS[code] ?: return false
        val pad = pads[i] ?: return false
        if (pressed) {
            if (!heldKeys.add(code)) return true // auto-repeat
            if (!pad.held) {
                pad.held = true
                padDown(i)
            }
        } else if (heldKeys.remove(code)) {
            if (pad.held) {
                pad.held = false
                padUp(i)
            }
        }
        return true
    }

    // ─── Display ─────────────────────────────────────────────

    fun refresh() {
        rec.active = loop.recording
        eight.active = loop.bars == 8
        sixteen.active = loop.bars == 16
        play.active = loop.enabled
        quant.active = loop.quantize
        click.active = engine.click
        bar.bars = loop.bars
        bar.armed = loop.recording
        refreshLabels()
    }

    private fun refreshLabels() {
        for (i in 0 until PadLoop.PADS) {
            val pad = pads[i] ?: continue
            when (val e = part.engine) {
                is DrumEngine -> {
                    val t = i % 8
                    pad.label = e.kit(t).trackNames[t]
                    pad.tag = if (i >= 8) "ACC" else ""
                }
                is SamplerEngine -> {
                    pad.label = e.padNames[i % 8].ifBlank { "PAD ${i % 8 + 1}" }.take(9)
                    pad.tag = if (i >= 8) "ACC" else ""
                }
                else -> {
                    pad.label = noteName(loop.baseNote + i)
                    pad.tag = ""
                }
            }
        }
    }

    /** Once per display frame while this page is showing. */
    fun onFrame(playing: Boolean) {
        if (!expanded) return
        frame++
        val now = System.currentTimeMillis()
        val armed = loop.recording
        for (i in 0 until PadLoop.PADS) {
            val h = loop.hits.get(i)
            if (h != seenHits[i]) {
                seenHits[i] = h
                flashUntil[i] = now + FLASH_MS
            }
            pads[i]?.apply {
                flash = now < flashUntil[i]
                stored = loop.padCounts.get(i) > 0
                this.armed = armed
            }
        }
        val len = loop.loopBeats
        bar.position = if (playing) (((engine.beatNow() % len) + len) % len / len).toFloat().coerceIn(0f, 1f) else -1f
        bar.armed = armed
        bar.bars = loop.bars
        if (frame % 20 == 0) {
            refreshLabels()
            rec.active = armed
            click.active = engine.click
        }
    }

    companion object {
        const val HANDLE_HEIGHT = 22f
        const val HEIGHT = 202f
        private const val FLASH_MS = 110L

        private val KEY_LABELS = arrayOf(
            "Z", "X", "C", "V", "A", "S", "D", "F", "Q", "W", "E", "R", "1", "2", "3", "4",
        )

        /** Computer keys by pad, laid out like the grid (bottom row Z X C V). */
        private val KEY_PADS: Map<Int, Int> = mapOf(
            KeyEvent.VK_Z to 0, KeyEvent.VK_X to 1, KeyEvent.VK_C to 2, KeyEvent.VK_V to 3,
            KeyEvent.VK_A to 4, KeyEvent.VK_S to 5, KeyEvent.VK_D to 6, KeyEvent.VK_F to 7,
            KeyEvent.VK_Q to 8, KeyEvent.VK_W to 9, KeyEvent.VK_E to 10, KeyEvent.VK_R to 11,
            KeyEvent.VK_1 to 12, KeyEvent.VK_2 to 13, KeyEvent.VK_3 to 14, KeyEvent.VK_4 to 15,
        )
    }
}
