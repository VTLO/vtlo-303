package com.vtlo.desktop.ui

import com.vtlo.acid303.dsp.Param
import com.vtlo.fx.Effect
import kotlin.math.roundToInt

/**
 * The master effects page: six effects in signal-chain order (beat repeat, warble, vowel,
 * flanger, chorus, ghost), each with an ON switch and four knobs, acting on the whole mix.
 */
class FxScreen(host: Host) : Screen(host) {
    private val rack = host.studio.engine.fx
    private class Bound(val fx: Effect, val index: Int, val knob: Knob)

    private val bound = ArrayList<Bound>()
    private val toggles = HashMap<Effect, Pad>()
    private val run: Pad

    init {
        val t = TOP
        add(TextBox({ "VTLO FX" }, 22f, Colors.panelText), 16f, t + 4, 300f, 28f)
        add(TextBox({ "COMPUTER CONTROLLED  ·  MASTER EFFECTS, IN CHAIN ORDER" }, 10f, Colors.red), 16f, t + 32, 420f, 14f)
        add(Pad("ALL OFF", dark = false, hint = "Switch every effect off") {
            rack.effects.forEach { it.on = false }
            refresh()
        }, 600f, t + 6, 120f, 46f)
        add(BpmDisplay { bpm() }, 740f, t + 8, 90f, 40f)
        run = add(Pad("RUN", led = true, hint = "Start/stop all instruments (Space)") { host.toggleTransport() }, 842f, t + 6, 150f, 46f)

        val top = t + 62
        val pw = 322f
        val ph = 222f
        add(ChainStrip(rack.effects), 8f, top + 2 * ph + 16f + 8f, 984f, 36f)
        rack.effects.forEachIndexed { n, fx ->
            val x = 8f + (n % 3) * (pw + 9f)
            val y = top + (n / 3) * (ph + 8f)
            add(PanelBox { fx.id.label }, x, y, pw, ph)
            add(TextBox({ fx.id.blurb }, 9.5f, Colors.panelEdge, bold = false), x + 12f, y + 24f, pw - 100f, 16f)
            toggles[fx] = add(Pad("OFF", led = true, hint = "Switch ${fx.id.label} on or off") {
                fx.on = !fx.on
                refreshToggle(fx)
            }, x + pw - 84f, y + 6f, 74f, 32f)
            fx.params.forEachIndexed { i, p ->
                val knob = Knob(p.label, p.default, p.format, { v -> fx.set(i, v) }, p.detents)
                bound.add(Bound(fx, i, add(knob, x + 9f + i * 76f, y + 46f, 76f, 150f)))
            }
        }
    }

    /** The signal path along the bottom: the mix, through each effect in order, to the output. */
    private class ChainStrip(private val effects: List<Effect>) : Widget() {
        override val interactive = false

        override fun paint(g: java.awt.Graphics2D) {
            val names = listOf("MIX") + effects.map { it.id.label } + listOf("OUT")
            val gap = 18f
            val boxW = (w - gap * (names.size - 1)) / names.size
            names.forEachIndexed { i, name ->
                val lit = i in 1..effects.size && effects[i - 1].on
                val bx = x + i * (boxW + gap)
                g.roundRect(bx, y, boxW, h, 8f, if (lit) Colors.button else Colors.panelShade, if (lit) Colors.ledOn else Colors.panelEdge, if (lit) 2f else 1f)
                g.dot(bx + 10f, y + h / 2, 3.2f, if (lit) Colors.ledOn else Colors.ledOff)
                g.textCentered(name, bx + boxW / 2 + 5f, y + h / 2 + 4f, font(9.5f), if (lit) Colors.buttonText else Colors.inkMuted)
                if (i < names.size - 1) g.textCentered("▸", bx + boxW + gap / 2, y + h / 2 + 4f, font(11f), Colors.panelEdge)
            }
        }
    }

    private fun bpm() = Param.normToBpm(host.studio.bass.get(Param.TEMPO)).roundToInt()

    private fun refreshToggle(fx: Effect) {
        toggles[fx]?.apply {
            active = fx.on
            text = if (fx.on) "ON" else "OFF"
        }
    }

    override fun refresh() {
        bound.forEach { it.knob.set(it.fx.get(it.index)) }
        rack.effects.forEach(::refreshToggle)
        run.active = host.playing
        run.text = if (host.playing) "STOP" else "RUN"
    }

    override fun onFrame(playing: Boolean) {
        run.active = playing
        run.text = if (playing) "STOP" else "RUN"
    }
}
