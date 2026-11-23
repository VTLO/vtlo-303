package com.vtlo.acid303

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.vtlo.core.ui.DeckBadgeView
import com.vtlo.core.ui.DeckColors
import com.vtlo.core.ui.FlowGrid
import com.vtlo.core.ui.LoopBarView
import com.vtlo.core.ui.PadButton
import com.vtlo.core.ui.PadView
import com.vtlo.core.ui.ScreenHost
import com.vtlo.core.ui.dpi
import com.vtlo.core.ui.noteName
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.pads.PadLoop
import com.vtlo.sampler.dsp.SamplerEngine
import kotlin.math.max

/**
 * The optional 16-pad deck under an instrument page: a 4 x 4 grid of rubber pads (pad 1 bottom left,
 * as on a drum machine) to play the instrument by hand, and a loop recorder that writes the taps into
 * a loop of 8 or 16 bars locked to the transport. A slim handle shows or hides it per page.
 *
 *  - drum machines and the sampler: pads 1-8 play tracks / pads 1-8, pads 9-16 the same with accent;
 *  - bass lines: chromatic notes from a base note (OCT - / OCT + move it).
 */
class PadDeck(
    private val host: ScreenHost,
    private val app: StudioApp,
    private val partIndex: Int,
    private val landscape: Boolean,
    expanded: Boolean,
    private val onToggle: (Boolean) -> Unit,
) {
    private val context = host.activity
    private val engine get() = app.engine
    private val part get() = engine.parts[partIndex]
    private val loop get() = part.loop

    private val pads = arrayOfNulls<PadView>(PadLoop.PADS)
    private val seenHits = IntArray(PadLoop.PADS)
    private val flashUntil = LongArray(PadLoop.PADS)
    private var frame = 0

    private lateinit var rec: PadButton
    private lateinit var eight: PadButton
    private lateinit var sixteen: PadButton
    private lateinit var quant: PadButton
    private lateinit var play: PadButton
    private lateinit var click: PadButton
    private lateinit var loopBar: LoopBarView
    private val handle = TextView(context)
    private val body: LinearLayout
    var expanded: Boolean = expanded
        private set

    init {
        body = buildBody()
        for (i in 0 until PadLoop.PADS) seenHits[i] = loop.hits.get(i)
        applyExpanded()
    }

    /** [page] with the handle and the deck below it. */
    fun wrap(page: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(page, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(handle, LinearLayout.LayoutParams(MATCH_PARENT, context.dpi(24)).apply {
            marginStart = context.dpi(8); marginEnd = context.dpi(8); topMargin = context.dpi(2)
        })
        addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            marginStart = context.dpi(6); marginEnd = context.dpi(6); bottomMargin = context.dpi(4)
        })
    }

    // ─── Construction ────────────────────────────────────────

    private fun buildBody(): LinearLayout {
        styleHandle()
        val content = if (landscape) landscapeContent() else portraitContent()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(DeckColors.body, DeckColors.bodyShade)).apply {
                cornerRadius = context.dpi(10).toFloat()
                setStroke(context.dpi(1), DeckColors.bodyEdge)
            }
            setPadding(context.dpi(8), context.dpi(6), context.dpi(8), 0)
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // The navy edge along the bottom of the controller.
            addView(View(context).apply { setBackgroundColor(DeckColors.navy) },
                LinearLayout.LayoutParams(MATCH_PARENT, context.dpi(4)).apply { topMargin = context.dpi(6); marginStart = -context.dpi(8); marginEnd = -context.dpi(8) })
        }
        return card
    }

    private fun styleHandle() {
        handle.apply {
            gravity = Gravity.CENTER
            textSize = 10f
            letterSpacing = 0.25f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#C9C4BA"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2826"))
                cornerRadius = context.dpi(6).toFloat()
                setStroke(context.dpi(1), Color.parseColor("#44413D"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                expanded = !expanded
                applyExpanded()
                onToggle(expanded)
            }
        }
    }

    private fun applyExpanded() {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        handle.text = context.getString(if (expanded) R.string.deck_close else R.string.deck_open)
        handle.contentDescription = context.getString(if (expanded) R.string.deck_close_description else R.string.deck_open_description)
        if (expanded) refresh()
    }

    private fun button(label: String, led: Boolean = false, description: String? = null, action: () -> Unit) =
        PadButton(context, label, hasLed = led, dark = true).apply {
            if (description != null) contentDescription = description
            setOnClickListener {
                action()
                refresh()
            }
        }

    private fun buttonParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(0, context.dpi(if (landscape) 36 else 44), weight).apply { marginEnd = context.dpi(4); topMargin = context.dpi(3) }

    private fun controlsRows(): List<View> {
        rec = button("REC", led = true, description = "Record pad taps into the loop") { loop.recording = !loop.recording }
        eight = button("8 BARS", description = "Loop of 8 bars") { loop.bars = 8 }
        sixteen = button("16 BARS", description = "Loop of 16 bars") { loop.bars = 16 }
        play = button("LOOP", led = true, description = "Play the recorded loop") { loop.enabled = !loop.enabled }
        quant = button("QUANT", led = true, description = "Snap taps to 16th notes") { loop.quantize = !loop.quantize }
        click = button("CLICK", led = true, description = "Metronome click") { engine.click = !engine.click }
        val undo = button("UNDO", description = "Undo the last recorded pass") { loop.undo() }
        val clear = button("CLEAR", description = "Clear the recorded loop") { loop.clear() }

        fun row(vararg views: View) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, buttonParams()) }
        }
        val rows = ArrayList<View>()
        rows.add(row(rec, eight, sixteen, play))
        if (part.kind == StudioEngine.Kind.BASS) {
            val down = button("OCT −", description = "Pads one octave lower") { shiftOctave(-12) }
            val up = button("OCT +", description = "Pads one octave higher") { shiftOctave(12) }
            rows.add(row(quant, click, undo, clear))
            rows.add(row(down, up))
        } else {
            rows.add(row(quant, click, undo, clear))
        }
        return rows
    }

    private fun shiftOctave(delta: Int) {
        loop.baseNote = (loop.baseNote + delta).coerceIn(24, 60)
        refreshLabels()
    }

    private fun padGrid(): View {
        val grid = FlowGrid(context, context.dpi(40), cellHeight = context.dpi(if (landscape) 38 else 56), allowedColumns = intArrayOf(4))
        // Top row first: pad 13..16, down to 1..4 at the bottom, like a drum machine.
        for (row in 3 downTo 0) {
            for (col in 0 until 4) {
                val i = row * 4 + col
                val pad = PadView(context, i).apply {
                    onDown = { p, t -> padDown(p, t) }
                    onUp = { p, t -> padUp(p, t) }
                }
                pads[i] = pad
                grid.addView(pad)
            }
        }
        return grid
    }

    private fun landscapeContent(): View {
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(DeckBadgeView(context), LinearLayout.LayoutParams(MATCH_PARENT, context.dpi(58)))
        controlsRows().forEach { left.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        loopBar = LoopBarView(context)
        left.addView(loopBar, LinearLayout.LayoutParams(MATCH_PARENT, context.dpi(18)).apply { topMargin = context.dpi(4) })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 5f).apply { marginEnd = context.dpi(8) })
        row.addView(padGrid(), LinearLayout.LayoutParams(0, WRAP_CONTENT, 5f))
        return row
    }

    private fun portraitContent(): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(DeckBadgeView(context), LinearLayout.LayoutParams(MATCH_PARENT, context.dpi(58)))
        loopBar = LoopBarView(context)
        col.addView(loopBar, LinearLayout.LayoutParams(MATCH_PARENT, context.dpi(18)).apply { topMargin = context.dpi(2); bottomMargin = context.dpi(4) })
        col.addView(padGrid(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        controlsRows().forEach { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        return col
    }

    // ─── Playing and recording ───────────────────────────────

    private fun ageSeconds(eventTime: Long) = max(0.0, (SystemClock.uptimeMillis() - eventTime) / 1000.0)

    private fun padDown(pad: Int, eventTime: Long) {
        if (loop.recording && !host.transport.playing) {
            // Tapping a rhythm in starts the transport, and the first tap lands on beat one.
            host.toggleTransport()
            engine.padDown(part, pad, beat = 0.0)
        } else {
            engine.padDown(part, pad, ageSeconds(eventTime))
        }
    }

    private fun padUp(pad: Int, eventTime: Long) = engine.padUp(part, pad, ageSeconds(eventTime))

    // ─── Display ─────────────────────────────────────────────

    /** Re-reads every control from the loop (the part's own page may have changed shared state). */
    fun refresh() {
        if (!expanded) return
        rec.active = loop.recording
        eight.active = loop.bars == 8
        sixteen.active = loop.bars == 16
        play.active = loop.enabled
        quant.active = loop.quantize
        click.active = engine.click
        loopBar.bars = loop.bars
        loopBar.armed = loop.recording
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
        val now = SystemClock.uptimeMillis()
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
        loopBar.position = if (playing) (((engine.beatNow() % len) + len) % len / len).toFloat().coerceIn(0f, 1f) else -1f
        loopBar.armed = armed
        loopBar.bars = loop.bars
        if (frame % 20 == 0) {
            refreshLabels()
            rec.active = armed
            click.active = engine.click
        }
    }

    private companion object {
        const val FLASH_MS = 110L
    }
}
