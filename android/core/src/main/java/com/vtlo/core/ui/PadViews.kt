package com.vtlo.core.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Colours of the pad deck: a cream controller body with dark rubber pads and APC-style LEDs. */
object DeckColors {
    val body = Color.parseColor("#E9E6DE")
    val bodyShade = Color.parseColor("#D6D2C8")
    val bodyEdge = Color.parseColor("#B9B4A8")
    val navy = Color.parseColor("#1E2B73")
    val logoRed = Color.parseColor("#D8202F")
    val copper = Color.parseColor("#C9A27C")
    val padTop = Color.parseColor("#3E3E45")
    val padBottom = Color.parseColor("#232328")
    val padEdge = Color.parseColor("#5C5C64")
    val stored = Color.parseColor("#FFB000")
    val playing = Color.parseColor("#35E26B")
    val recording = Color.parseColor("#FF3B30")
    val label = Color.parseColor("#E8E4DE")
    val labelDim = Color.parseColor("#8C8C94")
}

/**
 * One rubber pad. Reports the finger going down and up with the touch's time stamp, so taps can be
 * placed accurately in a recording. Each pad is its own view, so several fingers can play at once
 * and TalkBack can reach every pad.
 */
@SuppressLint("ViewConstructor")
class PadView(context: Context, val index: Int) : View(context) {
    var label = ""
        set(v) { if (field != v) { field = v; contentDescription = describe(); invalidate() } }
    var tag = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The loop holds taps on this pad (amber LED). */
    var stored = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The loop is playing this pad right now (green flash). */
    var flash = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Recording is armed: a pressed pad glows red instead of green. */
    var armed = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var onDown: ((pad: Int, eventTime: Long) -> Unit)? = null
    var onUp: ((pad: Int, eventTime: Long) -> Unit)? = null

    private var pressed = false
    private val rect = RectF()
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = DeckColors.padEdge }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = textPaint(DeckColors.label, sp(12f)).apply { textAlign = Paint.Align.CENTER }
    private val small = textPaint(DeckColors.labelDim, sp(8.5f)).apply { textAlign = Paint.Align.LEFT }
    private val tagPaint = textPaint(DeckColors.labelDim, sp(8.5f)).apply { textAlign = Paint.Align.RIGHT }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = describe()
        isSoundEffectsEnabled = false
    }

    private fun describe() = "Pad ${index + 1}" + if (label.isNotEmpty()) ", $label" else ""

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        body.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), DeckColors.padTop, DeckColors.padBottom, Shader.TileMode.CLAMP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true) // a tap is not a page swipe
                if (!pressed) {
                    pressed = true
                    invalidate()
                    onDown?.invoke(index, event.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressed) {
                    pressed = false
                    invalidate()
                    onUp?.invoke(index, event.eventTime)
                }
            }
        }
        return true
    }

    /** Accessibility activation (a double tap in TalkBack): a short hit. */
    override fun performClick(): Boolean {
        super.performClick()
        onDown?.invoke(index, SystemClock.uptimeMillis())
        postDelayed({ onUp?.invoke(index, SystemClock.uptimeMillis()) }, 90)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(2f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = dp(6f)
        canvas.drawRoundRect(rect, r, r, body)

        val lit = pressed || flash
        if (lit) {
            glow.color = if (pressed && armed) DeckColors.recording else DeckColors.playing
            glow.alpha = if (pressed) 235 else 190
            rect.inset(dp(2.5f), dp(2.5f))
            canvas.drawRoundRect(rect, r - dp(2f), r - dp(2f), glow)
            rect.inset(-dp(2.5f), -dp(2.5f))
        } else if (stored) {
            glow.color = DeckColors.stored
            glow.alpha = 38
            rect.inset(dp(2.5f), dp(2.5f))
            canvas.drawRoundRect(rect, r - dp(2f), r - dp(2f), glow)
            rect.inset(-dp(2.5f), -dp(2.5f))
        }
        canvas.drawRoundRect(rect, r, r, edge)

        // The LED bar along the top edge: amber when the pad holds taps.
        val barW = min(width * 0.4f, dp(40f))
        val barY = rect.top + dp(4f)
        led.color = when {
            lit -> Color.WHITE
            stored -> DeckColors.stored
            else -> Color.parseColor("#4A4A50")
        }
        canvas.drawRoundRect(width / 2f - barW / 2, barY, width / 2f + barW / 2, barY + dp(2.5f), dp(1.2f), dp(1.2f), led)

        text.color = if (lit) Color.parseColor("#101012") else DeckColors.label
        val baseline = height / 2f + dp(5f)
        canvas.drawText(label, width / 2f, baseline, text)
        canvas.drawText((index + 1).toString(), rect.left + dp(6f), rect.bottom - dp(5f), small)
        if (tag.isNotEmpty()) canvas.drawText(tag, rect.right - dp(6f), rect.bottom - dp(5f), tagPaint)
    }
}

/**
 * The deck's badge: a red outline wordmark in the style of a hardware controller, over a
 * "VTLO Mod. V2.0" nameplate with corner brackets.
 */
class DeckBadgeView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DeckColors.logoRed; style = Paint.Style.FILL_AND_STROKE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC); textSkewX = -0.2f
    }
    private val inline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DeckColors.body; style = Paint.Style.STROKE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC); textSkewX = -0.2f
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DeckColors.copper; strokeWidth = dp(1f); style = Paint.Style.STROKE }
    private val vtlo = textPaint(Color.parseColor("#141414"), sp(11f)).apply { textAlign = Paint.Align.LEFT }
    private val mod = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B5750"); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC); textSize = sp(11f)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dpi(190), widthMeasureSpec), resolveSize(dpi(62), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val logoSize = min(height * 0.5f, width / 5.2f)
        fill.textSize = logoSize
        inline.textSize = logoSize
        fill.strokeWidth = logoSize * 0.11f
        inline.strokeWidth = logoSize * 0.035f
        val x = dp(4f)
        val y = logoSize * 0.92f
        canvas.drawText("PAD16", x, y, fill)
        canvas.drawText("PAD16", x, y, inline) // a hairline through the strokes, like an inline typeface

        // The nameplate: a thin copper frame, open at the bottom corners.
        val top = y + dp(7f)
        val bottom = height - dp(3f)
        val left = x + dp(2f)
        val right = min(width - dp(4f), left + dp(150f))
        val c = dp(8f)
        canvas.drawLine(left, top, right, top, line)
        canvas.drawLine(left, top, left, bottom, line)
        canvas.drawLine(right, top, right, bottom, line)
        canvas.drawLine(left, bottom, left + c, bottom, line)
        canvas.drawLine(right - c, bottom, right, bottom, line)
        val base = (top + bottom) / 2f + dp(4f)
        canvas.drawText("VTLO", left + dp(10f), base, vtlo)
        canvas.drawText("Mod. V2.0", left + dp(10f) + vtlo.measureText("VTLO ") , base, mod)
    }
}

/** Shows where the loop is: 8 or 16 bar segments with a moving playhead. */
class LoopBarView(context: Context) : View(context) {
    var bars = 8
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Position in the loop, 0..1, or -1 while stopped. */
    var position = -1f
        set(v) { if (field != v) { field = v; invalidate() } }

    var armed = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B2B31") }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6A6A72"); strokeWidth = dp(1f) }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = textPaint(Color.parseColor("#5B5750"), sp(9f)).apply { textAlign = Paint.Align.RIGHT }
    private val rect = RectF()
    private val done = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dpi(120), widthMeasureSpec), resolveSize(dpi(16), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val labelW = label.measureText("BAR 16/16") + dp(6f)
        val w = width - labelW
        rect.set(0f, dp(3f), w, height - dp(3f))
        canvas.drawRoundRect(rect, dp(3f), dp(3f), track)
        if (position >= 0f) {
            head.color = if (armed) DeckColors.recording else DeckColors.playing
            done.set(0f, rect.top, w * position, rect.bottom)
            head.alpha = 90
            canvas.drawRoundRect(done, dp(3f), dp(3f), head)
            head.alpha = 255
            canvas.drawRect(w * position - dp(1.5f), rect.top - dp(1f), w * position + dp(1.5f), rect.bottom + dp(1f), head)
        }
        for (i in 1 until bars) {
            val x = w * i / bars
            canvas.drawLine(x, rect.top, x, rect.bottom, tick)
        }
        val bar = if (position >= 0f) 1 + (position * bars).toInt().coerceIn(0, bars - 1) else 0
        canvas.drawText(if (bar > 0) "BAR $bar/$bars" else "$bars BARS", width.toFloat(), height / 2f + dp(3.5f), label)
    }
}
