package com.vtlo.acid303

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vtlo.core.ui.FitScaleLayout
import com.vtlo.core.ui.FlowGrid
import com.vtlo.core.ui.InstrumentScreen
import com.vtlo.core.ui.KnobView
import com.vtlo.core.ui.PadButton
import com.vtlo.core.ui.Palette
import com.vtlo.core.ui.ScreenHost
import com.vtlo.core.ui.dpi
import com.vtlo.fx.Effect
import com.vtlo.fx.FxRack
import kotlin.math.roundToInt

/**
 * The master effects page: six effects in signal-chain order (beat repeat, warble, vowel,
 * flanger, chorus, ghost), each with an ON switch and four knobs. They process the whole mix.
 * Landscape shows all six at once (3 x 2); portrait stacks them and scrolls.
 */
class FxScreen(host: ScreenHost, private val rack: FxRack, private val bpm: () -> Float) : InstrumentScreen(host) {

    private class Bound(val fx: Effect, val index: Int, val knob: KnobView)

    private val bound = ArrayList<Bound>()
    private val toggles = HashMap<Effect, PadButton>()
    private val syncs = HashMap<Effect, PadButton>()
    private lateinit var playButton: PadButton
    private lateinit var bpmText: TextView
    private var frameCount = 0

    private fun dpi(v: Int) = context.dpi(v)

    override fun build(landscape: Boolean): View {
        bound.clear()
        toggles.clear()
        syncs.clear()
        val view = if (landscape) landscapeUi() else portraitUi()
        refreshAll()
        return view
    }

    private fun landscapeUi(): View {
        val fit = FitScaleLayout(context, dpi(DESIGN_WIDTH_DP))
        val content = column().apply {
            val pad = dpi(8)
            setPadding(pad, pad / 2, pad, pad)
        }
        fit.addView(content)
        content.addView(header(landscape = true))
        for (r in 0 until 2) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            for (c in 0 until 3) {
                val fx = rack.effects[r * 3 + c]
                row.addView(effectPanel(fx, landscape = true), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    marginEnd = dpi(if (c < 2) 8 else 0)
                })
            }
            content.addView(row)
        }
        return fit
    }

    private fun portraitUi(): View {
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val content = column().apply {
            val pad = dpi(10)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(header(landscape = false))
        rack.effects.forEach { content.addView(effectPanel(it, landscape = false)) }
        return scroll
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun header(landscape: Boolean): View {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(6), dpi(4), dpi(6), dpi(8))
        }
        val titles = column()
        titles.addView(TextView(context).apply {
            text = context.getString(R.string.fx_title)
            setTextColor(Palette.panelText)
            textSize = if (landscape) 20f else 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.12f
        })
        titles.addView(TextView(context).apply {
            text = context.getString(R.string.fx_subtitle)
            setTextColor(Palette.red)
            textSize = 10f
            letterSpacing = 0.15f
        })
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val allOff = PadButton(context, context.getString(R.string.fx_all_off), dark = false).apply {
            contentDescription = context.getString(R.string.fx_all_off_description)
            setOnClickListener {
                rack.effects.forEach { it.on = false }
                refreshAll()
            }
        }
        top.addView(allOff, LinearLayout.LayoutParams(dpi(if (landscape) 96 else 76), dpi(48)))

        bpmText = TextView(context).apply {
            setTextColor(Palette.ledOn)
            textSize = 22f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dpi(12), dpi(2), dpi(12), dpi(2))
            background = GradientDrawable().apply {
                setColor(0xFF0E0C0B.toInt()); cornerRadius = dpi(6).toFloat()
                setStroke(dpi(1), Palette.knobRim)
            }
            contentDescription = "Tempo"
        }
        top.addView(bpmText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(8) })

        playButton = PadButton(context, "RUN", hasLed = true).apply {
            setOnClickListener { host.toggleTransport() }
        }
        top.addView(playButton, LinearLayout.LayoutParams(dpi(if (landscape) 140 else 96), dpi(48)).apply { marginStart = dpi(10) })
        return top
    }

    private fun effectPanel(fx: Effect, landscape: Boolean): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(8), dpi(6), dpi(8), dpi(8))
            background = GradientDrawable().apply {
                setColor(Palette.panel)
                cornerRadius = dpi(10).toFloat()
                setStroke(dpi(1), Palette.panelEdge)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dpi(8) }
        }
        val title = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = column()
        names.addView(TextView(context).apply {
            text = fx.id.label
            setTextColor(Palette.red)
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.2f
            isFocusable = false
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        names.addView(TextView(context).apply {
            text = fx.id.blurb
            setTextColor(Palette.inkMuted)
            textSize = 9.5f
            maxLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        title.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // SYNC locks the effect's speed or time to the studio tempo; beat repeat is always locked.
        val sync = PadButton(context, "SYNC", hasLed = true, dark = false).apply {
            if (fx.alwaysSynced) {
                isClickable = false
                isFocusable = false
                active = true
                contentDescription = "${fx.id.label} is always tempo-synced"
            } else {
                setOnClickListener {
                    fx.sync = !fx.sync
                    refreshSync(fx)
                }
            }
        }
        syncs[fx] = sync
        title.addView(sync, LinearLayout.LayoutParams(dpi(58), dpi(48)).apply { marginStart = dpi(6) })
        val toggle = PadButton(context, "OFF", hasLed = true, dark = true).apply {
            setOnClickListener {
                fx.on = !fx.on
                refreshToggle(fx)
            }
        }
        toggles[fx] = toggle
        title.addView(toggle, LinearLayout.LayoutParams(dpi(64), dpi(48)).apply { marginStart = dpi(6) })
        panel.addView(title)

        val grid = FlowGrid(context, dpi(if (landscape) 40 else 66), allowedColumns = intArrayOf(4))
        fx.params.forEachIndexed { i, p ->
            val knob = KnobView(context, p.label, p.default, { v -> fx.knobText(i, v) }, { v -> fx.setFromKnob(i, v) }, p.detents)
            bound.add(Bound(fx, i, knob))
            grid.addView(knob)
        }
        panel.addView(grid)
        return panel
    }

    /** Updates the SYNC switch and turns the rate knob into a division selector (or back). */
    private fun refreshSync(fx: Effect) {
        syncs[fx]?.takeIf { !fx.alwaysSynced }?.apply {
            active = fx.sync
            contentDescription = "${fx.id.label} tempo sync " + if (fx.sync) "on" else "off"
        }
        bound.filter { it.fx === fx }.forEach {
            it.knob.detents = fx.knobDetents(it.index)
            it.knob.default = fx.knobDefault(it.index)
            it.knob.setValue(fx.knobValue(it.index))
            it.knob.invalidate()
        }
    }

    private fun refreshToggle(fx: Effect) {
        toggles[fx]?.apply {
            active = fx.on
            setLabel(if (fx.on) "ON" else "OFF")
            contentDescription = "${fx.id.label} " + if (fx.on) "on" else "off"
        }
    }

    override fun refreshAll() {
        rack.effects.forEach(::refreshSync)
        rack.effects.forEach(::refreshToggle)
        refreshBpm()
        onTransportChanged(host.transport.playing)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshBpm() {
        bpmText.text = "%3d".format(bpm().roundToInt())
    }

    override fun onTransportChanged(playing: Boolean) {
        playButton.active = playing
        playButton.setLabel(if (playing) "STOP" else "RUN")
    }

    override fun onFrame(playing: Boolean) {
        // The tempo can be changed on any page; the knobs themselves only change here.
        if (++frameCount % 15 == 0) refreshBpm()
    }

    private companion object {
        const val DESIGN_WIDTH_DP = 900
    }
}
