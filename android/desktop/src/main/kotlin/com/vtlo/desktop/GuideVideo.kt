package com.vtlo.desktop

import com.vtlo.desktop.ui.Coin
import com.vtlo.desktop.ui.GridStep
import com.vtlo.desktop.ui.Keys
import com.vtlo.desktop.ui.Knob
import com.vtlo.desktop.ui.NoteStep
import com.vtlo.desktop.ui.TrackPad
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
 * Records the English video guide from the real app: a scripted session of clicks, drags and pad taps
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

    // The transport: when it last started (beat 0), and the beat a scripted pad tap is meant to land on.
    private var playStart = 0.0
    private var wasPlaying = false
    @Volatile private var plannedBeat = 0.0

    private fun beatAt(t: Double) = (t - playStart) * GuideMusic.BPM / 60.0
    private fun timeOf(beat: Double) = playStart + beat * 60.0 / GuideMusic.BPM

    // ─── The script ──────────────────────────────────────────

    /** Schedules [run] at video time [t]; also fine while the session is running (the list stays ordered). */
    private fun at(t: Double, run: () -> Unit) {
        var i = events.size
        while (i > next && events[i - 1].t > t) i--
        events.add(i, Ev(t, run))
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

    private fun runPad(): Widget? = pad("RUN") ?: pad("STOP")

    private fun setPart(engine: com.vtlo.core.audio.SequencerEngine, on: Boolean) {
        studio.engine.partOf(engine).on = on
    }

    /** One tap on a deck pad, planned for transport beat [beat]: the pointer clicks it and the loop records it exactly there. */
    private fun tapPad(beat: Double, index: Int) {
        val t = timeOf(beat)
        at(t - 0.2) {
            window.scriptWidgets().filterIsInstance<MpcPad>().firstOrNull { it.index == index }?.let { moveTo(center(it).first, center(it).second, 0.12) }
        }
        at(t) {
            val w = window.scriptWidgets().filterIsInstance<MpcPad>().firstOrNull { it.index == index } ?: return@at
            px = center(w).first; py = center(w).second
            fromX = px; fromY = py; toX = px; toY = py
            plannedBeat = beat
            rippleAt = now
            window.scriptPointer(0, px, py)
            pressed = true
        }
        at(t + 0.08) {
            window.scriptPointer(2, px, py)
            pressed = false
        }
    }

    /** The Nth widget of a kind on the current page. */
    private inline fun <reified T : Widget> nth(i: Int): Widget? = window.scriptWidgets().filterIsInstance<T>().getOrNull(i)

    private fun trackName(tr: Int) = nth<TrackPad>(tr)

    private fun gridStep(tr: Int, step: Int): Widget? =
        window.scriptWidgets().filterIsInstance<GridStep>().firstOrNull { abs(it.y - (288f + tr * 29.5f)) < 1f && abs(it.x - (152f + step * 52f)) < 1f }

    /** A pad that looks the same in the page's two states (OFF / ON, RUN / STOP). */
    private fun fxToggle(i: Int): Widget? = window.scriptWidgets().filterIsInstance<Pad>().filter { it.text == "OFF" || it.text == "ON" }.getOrNull(i)

    /** A key of the on-screen piano, by semitone above its lowest C. */
    private fun pianoKey(semitone: Int): Widget? {
        val keys = window.scriptWidgets().filterIsInstance<Keys>().firstOrNull() ?: return null
        val white = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
        val black = intArrayOf(1, 3, 6, 8, 10)
        val blackPos = floatArrayOf(1f, 2f, 4f, 5f, 6f)
        val ww = keys.w / 8
        val (cx, cy) = if (semitone in white) {
            (keys.x + (white.indexOf(semitone) + 0.5f) * ww) to (keys.y + keys.h * 0.8f)
        } else {
            (keys.x + blackPos[black.indexOf(semitone)] * ww) to (keys.y + keys.h * 0.3f)
        }
        return object : Widget() {
            init { x = cx - 4f; y = cy - 4f; w = 8f; h = 8f }
            override fun paint(g: Graphics2D) {}
        }
    }

    /** Shows page [name] again so the screen picks up patterns written straight into the engine. */
    private fun showPage(name: String) = window.scriptSetPage(com.vtlo.desktop.ui.StudioWindow.PAGE_NAMES.indexOf(name))

    /** A knob turn: the pointer grabs it and moves by [dy] design units (up is negative); 200 units is the whole range. */
    private fun turn(t: Double, seconds: Double, dy: Float, label: String) = drag(t, seconds, 0f, dy) { knobLabelled(label) }

    /**
     * The video is one live take: a track built from nothing through the real interface at 150 BPM.
     * Every caption says what the pointer is about to do. Timings are in video seconds (one bar is 1.6 s).
     */
    private fun script() {
        // ── 1. DRUMS 1: tap the beat, kick and snare only ──
        at(0.2) { window.scriptSetPage(com.vtlo.desktop.ui.StudioWindow.PAGE_NAMES.indexOf("DRUMS 1")) } // the app opens on BASS 1; the take starts on DRUMS 1 under the title card
        caption(3.6, "DRUMS 1: a 909 kit. Start the transport, then tap the beat: a kick on every quarter note")
        click(4.4) { runPad() }
        for ((k, step) in intArrayOf(0, 4, 8, 12).withIndex()) click(6.0 + k * 0.8, lead = 0.5) { gridStep(0, step) }
        caption(9.6, "Snare on beats 2 and 4")
        for ((k, step) in intArrayOf(4, 12).withIndex()) click(10.2 + k * 0.8, lead = 0.5) { gridStep(1, step) }

        // ── 2. Tone and drive, slowly ──
        caption(12.4, "Select the kick, then lower TUNE: a deeper, heavier thump")
        click(12.8) { trackName(0) }
        turn(13.8, 2.6, 46f, "TUNE")
        caption(16.8, "The same for the snare: tune it up for a sharper crack")
        click(17.2) { trackName(1) }
        turn(18.2, 2.0, -34f, "TUNE")
        caption(20.6, "DRIVE saturates the whole kit: push it slowly")
        turn(21.4, 4.6, -120f, "DRIVE")
        caption(26.6, "Off-beat open hats complete the groove")
        click(27.0) { trackName(6) }
        for ((k, step) in intArrayOf(2, 6, 10, 14).withIndex()) click(27.8 + k * 0.7, lead = 0.4) { gridStep(6, step) }

        // ── 3. BASS 2: an acid loop with only five notes ──
        caption(31.2, "BASS 2: a TB-303 line. Five notes are enough: select a step, play a key")
        click(31.6) { tab("BASS 2") }
        // A minor: the keyboard starts on C, so A is 9 semitones up, C 12, G 7 and E 4.
        click(32.8, lead = 0.6) { nth<NoteStep>(0) }; click(33.5, lead = 0.4) { pianoKey(9) }; click(34.2, lead = 0.4) { pad("ACCENT") }
        click(35.1, lead = 0.5) { nth<NoteStep>(3) }; click(35.8, lead = 0.4) { pianoKey(9) }
        caption(36.4, "ACCENT hits harder; SLIDE glides into the next note")
        click(36.7, lead = 0.5) { nth<NoteStep>(6) }; click(37.4, lead = 0.4) { pianoKey(12) }; click(38.1, lead = 0.4) { pad("SLIDE") }
        click(39.0, lead = 0.5) { nth<NoteStep>(7) }; click(39.7, lead = 0.4) { pianoKey(7) }
        click(40.6, lead = 0.5) { nth<NoteStep>(10) }; click(41.3, lead = 0.4) { pianoKey(4) }; click(42.0, lead = 0.4) { pad("ACCENT") }

        // ── 4. Knobs, one at a time ──
        caption(43.2, "CUT OFF opens and closes the low-pass filter")
        turn(44.0, 3.0, -110f, "CUT OFF")
        turn(47.4, 1.6, 40f, "CUT OFF")
        caption(49.4, "RESO: resonance, the acid squelch")
        turn(50.2, 2.6, -70f, "RESO")
        caption(53.4, "ENV MOD: how far each note opens the filter")
        turn(54.2, 2.4, -60f, "ENV MOD")
        caption(57.2, "DECAY: the length of each note")
        turn(58.0, 2.2, 50f, "DECAY")
        caption(61.0, "DRIVE adds grit, DELAY adds space")
        turn(61.8, 1.8, -60f, "DRIVE")
        turn(64.0, 1.8, -50f, "DELAY")
        caption(66.4, "SAW or SQUARE: two oscillators, two characters")
        click(67.0) { pad("SAW") }
        click(69.0) { pad("SQUARE") }

        // ── 5. The 16 pads and the loop recorder ──
        caption(70.4, "Back on DRUMS 1: the 16 pads play the kit by hand")
        click(70.8) { tab("DRUMS 1") }
        click(72.2) { pad("▲  PADS") }
        caption(73.4, "REC captures your taps as a loop of 8 or 16 bars, locked to the tempo")
        click(74.0) { pad("REC") }
        at(74.6) {
            val base = Math.ceil(beatAt(76.4) / 4.0) * 4.0
            val phrase = listOf(0.75 to 4, 1.5 to 4, 2.0 to 11, 2.75 to 4, 3.5 to 4, 4.75 to 4, 5.5 to 4, 6.0 to 11)
            for ((offset, pad) in phrase) tapPad(base + offset, pad)
            click(timeOf(base + 8.0) + 0.6) { pad("REC") }
        }

        // ── 6. Effects on the master ──
        caption(84.0, "FX: six effects on the whole mix; SYNC locks them to the tempo")
        click(84.4) { tab("FX") }
        click(86.0) { fxToggle(3) } // flanger on
        click(87.1) { pad("SYNC", 3) }
        drag(88.2, 1.0, 0f, -34f) { nth<Knob>(3 * 4) } // flanger rate becomes a note length
        click(89.8) { fxToggle(4) } // chorus on
        click(91.0) { fxToggle(0) } // beat repeat on: a stutter
        click(92.6) { fxToggle(0) } // and off

        // ── 7. WOBBLE: round waves and a filter LFO locked to the beat ──
        caption(93.4, "WOBBLE: a bass synth for dubstep and drum and bass. Round waves, a filter LFO locked to the tempo")
        click(94.0) { tab("WOBBLE") }
        at(94.4) { GuideMusic.wobble(studio); showPage("WOBBLE") }
        caption(97.4, "RATE picks the note division: the wobble speeds up")
        turn(98.2, 1.4, -45f, "RATE")
        caption(100.8, "DEPTH: how far the filter closes")
        turn(101.6, 1.4, 55f, "DEPTH")
        turn(103.4, 1.2, -55f, "DEPTH")
        caption(105.0, "Four oscillator shapes and five LFO shapes")
        click(105.6) { pad("ROUND SQ") }
        click(107.0) { pad("LFO SINE") }

        // ── 8. DRUMS 2 and the rest ──
        caption(108.6, "DRUMS 2 runs sample-pack kits: frenchcore, hardtek, drum and bass, bass hits")
        click(109.0) { tab("DRUMS 2") }
        at(109.4) { GuideMusic.tribal(studio); showPage("DRUMS 2") }
        click(111.0) { pad("HTK") }
        click(112.4) { pad("DNB") }
        click(113.8) { pad("FRC") }
        caption(115.0, "Six instruments, one tempo: BASS 1 and the SAMPLER join in")
        click(115.4) { tab("BASS 1") }
        at(115.8) { GuideMusic.sub(studio); showPage("BASS 1") }
        click(117.0) { tab("SAMPLER") }
        at(117.4) { GuideMusic.shimmer(studio); showPage("SAMPLER") }
        caption(118.8, "EXPORT renders any loop to WAV. Windows, Linux, Android and web")
    }

    private val endAt = 122.6
    private val totalSeconds = 125.1

    // ─── Running ─────────────────────────────────────────────

    /** Renders every frame to [dir] (frame00000.png ...) and the soundtrack to audio.wav. */
    fun record(dir: File) {
        dir.mkdirs()
        script()
        studio.engine.tapBeatSource = { plannedBeat }
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
        // The transport starts inside an event of this frame, so this frame's audio chunk begins beat 0.
        if (window.playing && !wasPlaying) playStart = now
        wasPlaying = window.playing
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
        if (now < 3.4) card(g, "VTLO 3O3", "Video guide", "build a track live, from the first kick", alpha = ((3.4 - now) / 0.6).coerceIn(0.0, 1.0))
        if (now > endAt - 0.6) card(g, "Have fun!", "VTLO 3O3", "github.com/VTLO/vtlo-303", alpha = ((now - (endAt - 0.6)) / 0.6).coerceIn(0.0, 1.0), coinBack = true)
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
