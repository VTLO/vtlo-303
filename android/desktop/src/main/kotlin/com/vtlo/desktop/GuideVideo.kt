package com.vtlo.desktop

import com.vtlo.desktop.ui.Coin
import com.vtlo.desktop.ui.GridStep
import com.vtlo.desktop.ui.Knob
import com.vtlo.desktop.ui.MpcPad
import com.vtlo.desktop.ui.Pad
import com.vtlo.desktop.ui.StudioWindow
import com.vtlo.desktop.ui.Widget
import com.vtlo.desktop.ui.font
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * Records the French video guide from the real app: a scripted session of clicks, drags and pad taps
 * goes through the same pointer path as a mouse, the audio is rendered offline in step with every
 * frame, and each frame is a snapshot of the window with a caption bar. The output is a PNG
 * sequence and a WAV that ffmpeg turns into the lightweight clips (see tools/make-guide-video.sh).
 *
 *     vtlo-3o3 --video <dir>
 */
class GuideVideo(private val window: StudioWindow, private val studio: Studio) {
    private val fps = 15
    private val width = 1280
    private val height = 720
    private val uiHeight = 656
    private val fs = studio.sampleRate
    private val chunk = fs / fps

    private class Ev(val t: Double, val run: () -> Unit)
    private class Caption(val t: Double, val text: String)

    private val events = ArrayList<Ev>()
    private val captions = ArrayList<Caption>()
    private var next = 0

    // The pointer, in design units, tweened between targets.
    private var px = 640f
    private var py = 380f
    private var fromX = px
    private var fromY = py
    private var toX = px
    private var toY = py
    private var tweenStart = 0.0
    private var tweenLen = 0.0001
    private var rippleAt = -10.0
    private var pressed = false

    // A drag in progress: the pointer moves by (dx, dy) over [start, start + len].
    private var dragStart = -1.0
    private var dragLen = 0.0
    private var dragX = 0f
    private var dragY = 0f
    private var dragDx = 0f
    private var dragDy = 0f

    private var now = 0.0

    // ─── The script ──────────────────────────────────────────

    private fun at(t: Double, run: () -> Unit) {
        events.add(Ev(t, run))
    }

    private fun caption(t: Double, text: String) {
        captions.add(Caption(t, text))
    }

    private fun moveTo(x: Float, y: Float, seconds: Double) {
        fromX = px; fromY = py; toX = x; toY = y
        tweenStart = now; tweenLen = seconds.coerceAtLeast(0.0001)
    }

    private fun center(w: Widget) = (w.x + w.w / 2) to (w.y + w.h / 2)

    private fun pad(text: String, nth: Int = 0): Widget? = window.scriptWidgets().filterIsInstance<Pad>().filter { it.text == text }.getOrNull(nth)

    private fun knob(index: Int): Widget? = window.scriptWidgets().filterIsInstance<Knob>().getOrNull(index)

    private fun knobLabelled(label: String): Widget? = window.scriptWidgets().filterIsInstance<Knob>().firstOrNull { it.label == label }

    private fun click(t: Double, lead: Double = 0.75, find: () -> Widget?) {
        at(t - lead) { find()?.let { moveTo(center(it).first, center(it).second, lead - 0.12) } }
        at(t) {
            val w = find() ?: return@at
            px = center(w).first; py = center(w).second
            fromX = px; fromY = py; toX = px; toY = py
            rippleAt = now
            window.scriptPointer(0, px, py)
            pressed = true
        }
        at(t + 0.1) {
            window.scriptPointer(2, px, py)
            pressed = false
        }
    }

    /** Presses on a widget and drags the pointer by (dx, dy) design units over [seconds]. */
    private fun drag(t: Double, seconds: Double, dx: Float, dy: Float, lead: Double = 0.8, find: () -> Widget?) {
        at(t - lead) { find()?.let { moveTo(center(it).first, center(it).second, lead - 0.12) } }
        at(t) {
            val w = find() ?: return@at
            px = center(w).first; py = center(w).second
            fromX = px; fromY = py; toX = px; toY = py
            rippleAt = now
            window.scriptPointer(0, px, py)
            pressed = true
            dragStart = now; dragLen = seconds; dragX = px; dragY = py; dragDx = dx; dragDy = dy
        }
        at(t + seconds) {
            window.scriptPointer(2, px, py)
            pressed = false
            dragStart = -1.0
        }
    }

    private fun tab(name: String) = pad(name)

    private fun script() {
        // The title card runs until 3.0 s.
        caption(3.2, "Six écrans : basses, batteries, sampler et effets")
        click(4.0) { tab("BASS 2") }
        click(5.0) { tab("DRUMS 1") }
        click(6.0) { tab("DRUMS 2") }
        click(7.0) { tab("SAMPLER") }
        click(8.0) { tab("FX") }
        click(9.0) { tab("DRUMS 1") }

        caption(9.8, "RUN lance tous les instruments, calés sur le même tempo")
        click(10.6) { pad("RUN") }

        // Program a few rim-shot steps on the drum grid.
        caption(13.0, "Cliquez sur un pas pour le programmer")
        for ((k, step) in intArrayOf(2, 6, 10, 14).withIndex()) {
            click(13.8 + k * 0.9, lead = 0.6) {
                // Track 5 (RS): rows are 29.5 high from y = 288; steps are 52 wide from x = 152.
                window.scriptWidgets().filterIsInstance<GridStep>().firstOrNull { abs(it.y - (288f + 4 * 29.5f)) < 1f && abs(it.x - (152f + step * 52f)) < 1f }
            }
        }

        caption(18.0, "Glissez un bouton pour façonner le son")
        click(18.2) { tab("BASS 1") }
        drag(19.8, 1.5, 0f, -85f) { knobLabelled("CUT OFF") }
        drag(21.8, 1.1, 0f, 60f) { knobLabelled("CUT OFF") }
        drag(23.0, 1.2, 0f, -70f) { knobLabelled("RESO") }

        caption(24.6, "Sous chaque instrument, ▲ PADS ouvre 16 pads")
        click(25.0) { tab("DRUMS 1") }
        click(26.4) { pad("▲  PADS") }

        caption(27.6, "Armez REC puis tapez un rythme : il se grave en boucle (8 ou 16 mesures)")
        click(28.4) { pad("REC") }
        // Tapped rhythm: kick, snare, hats (pads 1, 2, 6) and a closed-hat flourish.
        val taps = listOf(
            29.3 to 0, 29.55 to 5, 30.1 to 1, 30.35 to 5, 30.9 to 0, 31.15 to 5, 31.7 to 1, 31.95 to 6,
            32.5 to 0, 32.75 to 5, 33.3 to 1, 33.55 to 3, 34.1 to 0,
        )
        for ((t, index) in taps) click(t, lead = 0.2) { window.scriptWidgets().filterIsInstance<MpcPad>().firstOrNull { it.index == index } }
        click(35.2) { pad("REC") }

        caption(36.0, "Le rythme boucle, couche après couche ; UNDO annule, CLEAR efface")
        click(37.0) { pad("16 BARS") }
        click(38.0) { pad("8 BARS") }

        caption(39.4, "L'onglet FX : six effets sur tout le mix, SYNC les cale sur le tempo")
        click(39.6) { tab("FX") }
        click(41.2) { fxToggle(3) } // flanger on
        click(42.3) { pad("SYNC", 3) }
        drag(43.4, 1.0, 0f, -34f) { knob(3 * 4) } // flanger rate: now a note length
        click(45.0) { fxToggle(4) } // chorus on
        click(46.1) { fxToggle(5) } // ghost on

        events.sortBy { it.t }
    }

    private fun fxToggle(i: Int): Widget? = window.scriptWidgets().filterIsInstance<Pad>().filter { it.text == "OFF" || it.text == "ON" }.getOrNull(i)

    private val endAt = 48.0
    private val totalSeconds = 50.5

    // ─── Running ─────────────────────────────────────────────

    /** Renders every frame to [dir] (frame00000.png ...) and the soundtrack to audio.wav. */
    fun record(dir: File) {
        dir.mkdirs()
        script()
        val frames = (totalSeconds * fps).toInt()
        val audio = ByteArray(frames * chunk * 4)
        val start = System.nanoTime()
        val block = FloatArray(400 * 2)
        for (k in 0 until frames) {
            // Pace the session in real time: pad flashes and the beat clock follow the wall clock.
            val due = start + (k * 1_000_000_000L / fps)
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
            var image: BufferedImage? = null
            SwingUtilities.invokeAndWait { image = step(k) }
            renderAudio(audio, k * chunk, block)
            ImageIO.write(image!!, "png", File(dir, "frame%05d.png".format(k)))
        }
        val stream = AudioInputStream(ByteArrayInputStream(audio), AudioFormat(fs.toFloat(), 16, 2, true, false), (frames * chunk).toLong())
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, File(dir, "audio.wav"))
        println("recorded $frames frames at $fps fps and ${frames * chunk / fs} s of audio into $dir")
    }

    private fun renderAudio(target: ByteArray, firstFrame: Int, block: FloatArray) {
        var f = 0
        while (f < chunk) {
            val n = min(400, chunk - f)
            studio.engine.render(block, n)
            for (i in 0 until n * 2) {
                val v = (block[i].coerceIn(-1f, 1f) * 32767f).toInt()
                val o = (firstFrame + f) * 4 + i * 2
                target[o] = (v and 0xff).toByte()
                target[o + 1] = (v shr 8).toByte()
            }
            f += n
        }
    }

    private fun step(k: Int): BufferedImage {
        now = k.toDouble() / fps
        while (next < events.size && events[next].t <= now) events[next++].run()
        // Pointer: a drag overrides the tween while it lasts.
        if (dragStart >= 0) {
            val u = ((now - dragStart) / dragLen).coerceIn(0.0, 1.0)
            val e = (u * u * (3 - 2 * u)).toFloat()
            px = dragX + dragDx * e
            py = dragY + dragDy * e
            window.scriptPointer(1, px, py)
        } else {
            val u = ((now - tweenStart) / tweenLen).coerceIn(0.0, 1.0)
            val e = (u * u * (3 - 2 * u)).toFloat()
            px = fromX + (toX - fromX) * e
            py = fromY + (toY - fromY) * e
        }

        val ui = window.render(width, uiHeight) { g -> drawPointer(g) }
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x0E0D0C)
        g.fillRect(0, 0, width, height)
        g.drawImage(ui, 0, 0, null)
        drawCaption(g)
        // The title card fades out into the app; the end card fades in over it.
        if (now < 3.4) card(g, "VTLO 3O3", "Guide vidéo", "le studio de poche en 50 secondes", alpha = ((3.4 - now) / 0.6).coerceIn(0.0, 1.0))
        if (now > endAt - 0.6) card(g, "Bonne session !", "VTLO 3O3", "github.com/VTLO/vtlo-303", alpha = ((now - (endAt - 0.6)) / 0.6).coerceIn(0.0, 1.0), coinBack = true)
        g.dispose()
        return out
    }

    private fun drawPointer(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val u = ((now - rippleAt) / 0.45)
        if (u in 0.0..1.0) {
            val r = 6.0 + 26.0 * u
            g.color = Color(255, 255, 255, ((1.0 - u) * 200).toInt())
            g.stroke = java.awt.BasicStroke(2.5f)
            g.draw(Ellipse2D.Double(px - r, py - r, r * 2, r * 2))
        }
        val arrow = Path2D.Float().apply {
            moveTo(px, py); lineTo(px, py + 19f); lineTo(px + 4.6f, py + 14.6f); lineTo(px + 8.2f, py + 22f)
            lineTo(px + 11.6f, py + 20.4f); lineTo(px + 8f, py + 13.2f); lineTo(px + 14f, py + 13.2f); closePath()
        }
        g.color = Color(0, 0, 0, 200)
        g.stroke = java.awt.BasicStroke(3.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        g.draw(arrow)
        g.color = if (pressed) Color(0xFFD23A) else Color.WHITE
        g.fill(arrow)
    }

    private fun drawCaption(g: Graphics2D) {
        g.color = Color(0x0E0D0C)
        g.fillRect(0, uiHeight, width, height - uiHeight)
        g.color = Color(0x3A3633)
        g.fillRect(0, uiHeight, width, 2)
        val c = captions.lastOrNull { it.t <= now } ?: return
        val fade = ((now - c.t) / 0.35).coerceIn(0.0, 1.0)
        g.font = font(27f).deriveFont(java.awt.Font.BOLD)
        g.color = Color(0xF0ECE6.toInt() and 0xFFFFFF or ((fade * 255).toInt() shl 24), true)
        val w = g.fontMetrics.stringWidth(c.text)
        g.drawString(c.text, (width - w) / 2, uiHeight + 42)
    }

    private fun card(g: Graphics2D, title: String, sub: String, line: String, alpha: Double, coinBack: Boolean = false) {
        if (alpha <= 0.0) return
        val saved = g.composite
        g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha.toFloat())
        g.paint = GradientPaint(0f, 0f, Color(0x0E0D0C), 0f, height.toFloat(), Color(0x23201D))
        g.fillRect(0, 0, width, height)
        // The coin sways on one face (robot on the title card, smiley on the end card) instead of spinning edge-on.
        Coin().draw(g, width / 2f, 230f, 92f, ((if (coinBack) PI else 0.0) + 0.7 * kotlin.math.sin(now * 2.2)).toFloat())
        g.font = font(84f)
        g.color = Color(0xF4F1EA)
        g.drawString(title, (width - g.fontMetrics.stringWidth(title)) / 2, 440)
        g.font = font(34f)
        g.color = Color(0xFF3B2A)
        g.drawString(sub, (width - g.fontMetrics.stringWidth(sub)) / 2, 500)
        g.font = font(24f).deriveFont(java.awt.Font.PLAIN)
        g.color = Color(0xA39B91)
        g.drawString(line, (width - g.fontMetrics.stringWidth(line)) / 2, 556)
        g.composite = saved
    }
}
