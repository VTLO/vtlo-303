package com.vtlo.desktop.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D

/** Colours of the pad deck: a cream controller body with dark rubber pads and APC-style LEDs. */
object DeckColors {
    val body = Color(0xE9E6DE)
    val bodyShade = Color(0xD6D2C8)
    val bodyEdge = Color(0xB9B4A8)
    val navy = Color(0x1E2B73)
    val logoRed = Color(0xD8202F)
    val copper = Color(0xC9A27C)
    val padTop = Color(0x3E3E45)
    val padBottom = Color(0x232328)
    val padEdge = Color(0x5C5C64)
    val stored = Color(0xFFB000)
    val playing = Color(0x35E26B)
    val recording = Color(0xFF3B30)
    val label = Color(0xE8E4DE)
    val labelDim = Color(0x8C8C94)
}

/** The controller body: a cream panel with a navy edge along the bottom. */
class DeckBody : Widget() {
    override val interactive = false

    override fun paint(g: Graphics2D) {
        val shape = RoundRectangle2D.Float(x, y, w, h, 14f, 14f)
        g.paint = GradientPaint(x, y, DeckColors.body, x, y + h, DeckColors.bodyShade)
        g.fill(shape)
        val clip = g.clip
        g.clip(shape)
        g.color = DeckColors.navy
        g.fillRect(x.toInt(), (y + h - 5f).toInt(), w.toInt() + 1, 6)
        g.clip = clip
        g.stroke = BasicStroke(1.2f)
        g.color = DeckColors.bodyEdge
        g.draw(shape)
    }
}

/**
 * The deck's badge: a red outline wordmark in the style of a hardware controller, over a
 * "VTLO Mod. V2.0" nameplate with corner brackets.
 */
class DeckBadge : Widget() {
    override val interactive = false

    override fun paint(g: Graphics2D) {
        val size = min(h * 0.62f, 38f)
        val font = Font(Font.SANS_SERIF, Font.BOLD or Font.ITALIC, 1).deriveFont(size)
        val shape = TextLayout("PAD16", font, g.fontRenderContext)
            .getOutline(AffineTransform().apply { translate((x + 2f).toDouble(), (y + size * 0.86f).toDouble()); shear(-0.2, 0.0) })
        g.color = DeckColors.logoRed
        g.stroke = BasicStroke(size * 0.11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        g.fill(shape)
        // A hairline through the strokes, like an inline typeface.
        g.color = DeckColors.body
        g.stroke = BasicStroke(size * 0.035f)
        g.draw(shape)

        // The nameplate: a thin copper frame, open at the bottom corners.
        val top = y + size + 8f
        val bottom = y + h - 2f
        val left = x + 4f
        val right = left + 168f
        g.color = DeckColors.copper
        g.stroke = BasicStroke(1.1f)
        g.draw(Line2D.Float(left, top, right, top))
        g.draw(Line2D.Float(left, top, left, bottom))
        g.draw(Line2D.Float(right, top, right, bottom))
        g.draw(Line2D.Float(left, bottom, left + 9f, bottom))
        g.draw(Line2D.Float(right - 9f, bottom, right, bottom))
        val base = (top + bottom) / 2f + 4.5f
        g.font = font(11f)
        g.color = Color(0x141414)
        g.drawString("VTLO", left + 12f, base)
        val vtloW = g.fontMetrics.stringWidth("VTLO ")
        g.font = Font(Font.SANS_SERIF, Font.ITALIC, 1).deriveFont(11f)
        g.color = Color(0x5B5750)
        g.drawString("Mod. V2.0", left + 12f + vtloW, base)
    }

    private fun min(a: Float, b: Float) = if (a < b) a else b
}

/** Where the loop is: 8 or 16 bar segments with a moving playhead. */
class LoopBar : Widget() {
    override val interactive = false
    var bars = 8
    /** Position in the loop, 0..1, or -1 while stopped. */
    var position = -1f
    var armed = false

    override fun paint(g: Graphics2D) {
        val labelW = 70f
        val tw = w - labelW
        g.color = Color(0x2B2B31)
        g.fill(RoundRectangle2D.Float(x, y + 2f, tw, h - 4f, 5f, 5f))
        if (position >= 0f) {
            val c = if (armed) DeckColors.recording else DeckColors.playing
            g.color = Color(c.red, c.green, c.blue, 90)
            g.fill(RoundRectangle2D.Float(x, y + 2f, tw * position, h - 4f, 5f, 5f))
            g.color = c
            g.fillRect((x + tw * position - 1.5f).toInt(), (y + 1f).toInt(), 3, (h - 2f).toInt())
        }
        g.color = Color(0x6A6A72)
        g.stroke = BasicStroke(1f)
        for (i in 1 until bars) g.draw(Line2D.Float(x + tw * i / bars, y + 2f, x + tw * i / bars, y + h - 2f))
        val bar = if (position >= 0f) 1 + (position * bars).toInt().coerceIn(0, bars - 1) else 0
        g.textRight(if (bar > 0) "BAR $bar/$bars" else "$bars BARS", x + w, y + h - 3f, font(9.5f), Color(0x5B5750))
    }

    private fun Graphics2D.textRight(text: String, right: Float, baseline: Float, f: Font, c: Color) {
        font = f
        color = c
        drawString(text, right - fontMetrics.stringWidth(text), baseline)
    }
}

/** One rubber pad of the deck. Mouse press and release, or a computer key, play it. */
class MpcPad(val index: Int, private val key: String, private val onDown: (Int) -> Unit, private val onUp: (Int) -> Unit) : Widget() {
    var label = ""
    var tag = ""
    /** The loop holds taps on this pad (amber LED). */
    var stored = false
    /** The loop is playing this pad right now (green flash). */
    var flash = false
    /** Recording is armed: a pressed pad glows red instead of green. */
    var armed = false
    /** Held down by the mouse or a key. */
    var held = false

    override val tooltip get() = "Pad ${index + 1}" + (if (label.isNotEmpty()) " ($label)" else "") + " — key $key"

    override fun press(p: Press): Boolean {
        if (!held) {
            held = true
            onDown(index)
        }
        return true
    }

    override fun release(px: Float, py: Float) {
        if (held) {
            held = false
            onUp(index)
        }
    }

    override fun paint(g: Graphics2D) {
        val r = 8f
        val shape = RoundRectangle2D.Float(x + 1f, y + 1f, w - 2f, h - 2f, r, r)
        g.paint = GradientPaint(x, y, DeckColors.padTop, x, y + h, DeckColors.padBottom)
        g.fill(shape)
        val lit = held || flash
        val inner = RoundRectangle2D.Float(x + 3.5f, y + 3.5f, w - 7f, h - 7f, r - 2f, r - 2f)
        if (lit) {
            val c = if (held && armed) DeckColors.recording else DeckColors.playing
            g.color = Color(c.red, c.green, c.blue, if (held) 235 else 190)
            g.fill(inner)
        } else if (stored) {
            g.color = Color(DeckColors.stored.red, DeckColors.stored.green, DeckColors.stored.blue, 38)
            g.fill(inner)
        }
        g.stroke = BasicStroke(1f)
        g.color = DeckColors.padEdge
        g.draw(shape)

        val ledW = minOf(w * 0.4f, 44f)
        g.color = when {
            lit -> Color.WHITE
            stored -> DeckColors.stored
            else -> Color(0x4A4A50)
        }
        g.fill(RoundRectangle2D.Float(x + w / 2 - ledW / 2, y + 5f, ledW, 2.6f, 2f, 2f))

        g.textCentered(label, x + w / 2, y + h / 2 + 5f, font(12f), if (lit) Color(0x101012) else DeckColors.label)
        g.textLeft((index + 1).toString(), x + 7f, y + h - 5f, font(8.5f), DeckColors.labelDim)
        if (tag.isNotEmpty()) {
            g.font = font(8.5f)
            g.color = DeckColors.labelDim
            g.drawString(tag, x + w - 7f - g.fontMetrics.stringWidth(tag), y + h - 5f)
        }
    }
}
