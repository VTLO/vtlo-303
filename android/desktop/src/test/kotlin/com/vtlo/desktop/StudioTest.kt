package com.vtlo.desktop

import com.vtlo.acid303.dsp.Param
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.Kit
import com.vtlo.sampler.dsp.PadParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The desktop studio without a window or sound card: start-up, kits, persistence. */
class StudioTest {
    private fun tempDir(): File = Files.createTempDirectory("vtlo-studio").toFile().apply { deleteOnExit() }

    @Test
    fun firstRunLoadsPresetsKitsAndSeedsPads() {
        val s = Studio(tempDir(), sampleRate = 48000)
        assertTrue(s.bass.bank.step(0, 0) != 0)
        assertTrue(s.drums.bank.row(0, 0) != 0)
        for (p in 0 until 8) assertNotNull(s.sampler.sample(p))
        assertEquals("808 BD", s.sampler.padNames[0])
        // Rendering the whole studio works without an audio device.
        s.engine.play()
        val buf = FloatArray(512)
        repeat(200) { s.engine.render(buf, 256) }
        assertTrue(buf.all { it.isFinite() && kotlin.math.abs(it) <= 1f })
    }

    @Test
    fun stateSurvivesRestart() {
        val dir = tempDir()
        val a = Studio(dir, sampleRate = 48000)
        a.bass.set(Param.CUTOFF, 0.77f)
        a.bass.selectedPattern = 5
        a.drums.setKit(3, Kit.TR606)
        a.drums.setMuted(2, true)
        a.drums.set(DrumParam.REVERB, 0.42f)
        a.sampler.set(4, PadParam.CRUSH, 0.6f)
        a.sampler.padNames[4] = "MIC 1"
        a.sampler.bank.toggle(0, 4, 7)
        a.engine.partOf(a.drums).on = false
        a.save()
        Thread.sleep(500) // pad files are written in the background

        val b = Studio(dir, sampleRate = 48000)
        assertEquals(0.77f, b.bass.get(Param.CUTOFF), 0f)
        assertEquals(5, b.bass.selectedPattern)
        assertEquals(Kit.TR606, b.drums.kit(3))
        assertTrue(b.drums.isMuted(2))
        assertEquals(0.42f, b.drums.get(DrumParam.REVERB), 0f)
        assertEquals(0.6f, b.sampler.get(4, PadParam.CRUSH), 0f)
        assertEquals("MIC 1", b.sampler.padNames[4])
        assertTrue(b.sampler.bank.isOn(0, 4, 7))
        assertTrue(!b.engine.partOf(b.drums).on)
        assertNotNull(b.sampler.sample(0)) // seeded pad audio was stored and reloaded
    }

    @Test
    fun coinPaintsBothFacesWithoutTakingClicks() {
        val coin = com.vtlo.desktop.ui.Coin().place(0f, 0f, 64f, 64f)
        assertTrue(!coin.interactive)
        val img = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        coin.paint(g)
        g.dispose()
        // The face images are bundled, so the centre of the coin is painted.
        assertTrue((img.getRGB(32, 32) ushr 24) != 0)
    }

    @Test
    fun secondBassAndDrumsStartEmptyAndSurviveRestart() {
        val dir = tempDir()
        val a = Studio(dir, sampleRate = 48000)
        assertEquals(6, a.engine.parts.size)
        // Fresh: nothing programmed on the new pages, the original mix is unchanged.
        assertTrue((0 until 16).all { a.bass2.bank.step(0, it) and (1 shl 8) == 0 })
        assertTrue(a.drums2.bank.row(0, 0) == 0)
        assertTrue(a.engine.parts.all { it.on })

        // The wobble synth starts from its own settings, with an empty pattern.
        assertEquals(0.55f, a.wobble.get(Param.CUTOFF), 0f)
        assertTrue((0 until 16).all { a.wobble.bank.step(0, it) and (1 shl 8) == 0 })
        // DRUMS 2 runs the sample-pack kits.
        assertTrue((0 until 8).all { a.drums2.kit(it) in Kit.EXTRA })

        a.bass2.set(Param.CUTOFF, 0.31f)
        a.wobble.set(Param.WOB_RATE, 0.75f)
        a.wobble.set(Param.CUTOFF, 0.8f)
        a.wobble.bank.setStep(0, 5, com.vtlo.acid303.dsp.PatternBank.pack(29, true, false, true))
        a.bass2.bank.setStep(0, 3, com.vtlo.acid303.dsp.PatternBank.pack(40, true, true, false))
        a.drums2.setKit(1, Kit.HTK)
        a.drums2.bank.toggle(0, 2, 4)
        a.engine.partOf(a.drums2).on = false
        a.save()

        val b = Studio(dir, sampleRate = 48000)
        assertEquals(0.31f, b.bass2.get(Param.CUTOFF), 0f)
        assertEquals(40, b.bass2.bank.step(0, 3) and 0x7f)
        assertEquals(Kit.HTK, b.drums2.kit(1))
        assertEquals(0.75f, b.wobble.get(Param.WOB_RATE), 0f)
        assertEquals(0.8f, b.wobble.get(Param.CUTOFF), 0f)
        assertEquals(29, b.wobble.bank.step(0, 5) and 0x7f)
        assertTrue(b.drums2.bank.row(0, 2) != 0)
        assertTrue(!b.engine.partOf(b.drums2).on)
        // The first instruments are separate: untouched by the second ones' edits.
        assertTrue(b.bass.get(Param.CUTOFF) != 0.31f)
        assertTrue(b.drums.bank.row(0, 2) != b.drums2.bank.row(0, 2))
    }

    @Test
    fun effectsSurviveRestartAndOldPartSwitchesStillApply() {
        val dir = tempDir()
        val a = Studio(dir, sampleRate = 48000)
        a.engine.fx.effect(com.vtlo.fx.FxId.VOWEL).apply { on = true; set(0, 0.5f) }
        a.engine.fx.effect(com.vtlo.fx.FxId.GHOST).set(1, 0.6f)
        a.engine.fx.effect(com.vtlo.fx.FxId.CHORUS).apply { sync = true; setDivision(0f) }
        a.save()
        val b = Studio(dir, sampleRate = 48000)
        assertTrue(b.engine.fx.effect(com.vtlo.fx.FxId.VOWEL).on)
        assertEquals(0.5f, b.engine.fx.effect(com.vtlo.fx.FxId.VOWEL).get(0), 0f)
        assertEquals(0.6f, b.engine.fx.effect(com.vtlo.fx.FxId.GHOST).get(1), 0f)
        assertTrue(!b.engine.fx.effect(com.vtlo.fx.FxId.CHORUS).on)
        assertTrue(b.engine.fx.effect(com.vtlo.fx.FxId.CHORUS).sync)
        assertEquals("8 bars", b.engine.fx.effect(com.vtlo.fx.FxId.CHORUS).division().label)
        assertTrue(!b.engine.fx.effect(com.vtlo.fx.FxId.FLANGER).sync)

        // A save from before the pages were regrouped: part1 was the drums, part2 the sampler.
        val old = tempDir()
        File(old, "studio.properties").writeText("part1=false\npart2=false\n")
        val c = Studio(old, sampleRate = 48000)
        assertTrue(!c.engine.partOf(c.drums).on)
        assertTrue(!c.engine.partOf(c.sampler).on)
        assertTrue(c.engine.partOf(c.bass).on && c.engine.partOf(c.drums2).on && c.engine.partOf(c.bass2).on)
    }

    @Test
    fun fxPageHasSixEffectsAndTheirSwitchesWork() {
        val studio = Studio(tempDir(), sampleRate = 48000)
        val host = object : com.vtlo.desktop.ui.Host {
            override val studio = studio
            override val playing = false
            override fun toggleTransport() {}
            override fun toast(message: String) {}
            override fun saveWav(name: String, render: (java.io.OutputStream) -> Unit) {}
            override fun openAudio(onFile: (File) -> Unit) {}
            override fun popup(items: List<String>, x: Float, y: Float, onPick: (Int) -> Unit) {}
        }
        val page = com.vtlo.desktop.ui.FxScreen(host)
        assertEquals(24, page.widgets.count { it is com.vtlo.desktop.ui.Knob })
        val switches = page.widgets.filterIsInstance<com.vtlo.desktop.ui.Pad>().filter { it.text == "OFF" }
        assertEquals(6, switches.size)
        val flanger = studio.engine.fx.effect(com.vtlo.fx.FxId.FLANGER)
        val pad = switches[3] // chain order: repeat, warble, vowel, flanger, chorus, ghost
        pad.press(com.vtlo.desktop.ui.Press(pad.x + 2, pad.y + 2, false, 1, false))
        pad.release(pad.x + 2, pad.y + 2)
        assertTrue(flanger.on)
        assertEquals("ON", pad.text)
    }

    private class FakeHost(override val studio: Studio) : com.vtlo.desktop.ui.Host {
        var toggles = 0
        override var playing = false
        override fun toggleTransport() { toggles++; playing = !playing }
        override fun toast(message: String) {}
        override fun saveWav(name: String, render: (java.io.OutputStream) -> Unit) {}
        override fun openAudio(onFile: (File) -> Unit) {}
        override fun popup(items: List<String>, x: Float, y: Float, onPick: (Int) -> Unit) {}
    }

    @Test
    fun padDeckKeysPlayPadsAndTappingARhythmInStartsTheTransport() {
        val studio = Studio(tempDir(), sampleRate = 48000)
        val host = FakeHost(studio)
        var opened = false
        val deck = com.vtlo.desktop.ui.PadDeck(host, 3, expanded = false) { opened = it } // page 4 = DRUMS 1 (after the wobble synth)
        val part = studio.engine.parts[3]
        assertEquals(1, deck.widgets().size) // only the handle while hidden
        assertEquals(com.vtlo.desktop.ui.PadDeck.HANDLE_HEIGHT, deck.height, 0f)
        deck.setExpanded(true, notify = true)
        assertTrue(opened)
        assertEquals(com.vtlo.desktop.ui.PadDeck.HEIGHT, deck.height, 0f)
        assertEquals(16, deck.widgets().count { it is com.vtlo.desktop.ui.MpcPad })
        val pads = deck.widgets().filterIsInstance<com.vtlo.desktop.ui.MpcPad>().associateBy { it.index }
        assertEquals("BD", pads[0]!!.label)
        assertEquals("ACC", pads[8]!!.tag)
        // Pad 1 is at the bottom left of the grid.
        assertTrue(pads[0]!!.y > pads[12]!!.y && pads[0]!!.x == pads[12]!!.x)

        // Not recording: keys sound the pad but record nothing and start nothing.
        assertTrue(deck.key(java.awt.event.KeyEvent.VK_Z, true))
        assertTrue(deck.key(java.awt.event.KeyEvent.VK_Z, false))
        assertEquals(0, part.loop.eventCount)
        assertEquals(0, host.toggles)
        assertTrue(!deck.key(java.awt.event.KeyEvent.VK_SPACE, true)) // not a pad key

        // Armed and stopped: the first tap starts the transport; a held key does not repeat.
        part.loop.recording = true
        assertTrue(deck.key(java.awt.event.KeyEvent.VK_S, true)) // pad 6
        assertTrue(deck.key(java.awt.event.KeyEvent.VK_S, true)) // auto-repeat
        assertTrue(deck.key(java.awt.event.KeyEvent.VK_S, false))
        assertEquals(1, host.toggles)
        assertEquals(1, part.loop.eventCount)
        assertEquals(1, part.loop.padCounts.get(5))
        // Mouse works as well.
        val pad = pads[9]!!
        pad.press(com.vtlo.desktop.ui.Press(pad.x + 1, pad.y + 1, false, 1, false))
        pad.release(pad.x + 1, pad.y + 1)
        assertEquals(2, part.loop.eventCount)
        assertEquals(1, host.toggles) // already running
    }

    @Test
    fun loopsClickAndDeckVisibilitySurviveARestart() {
        val dir = tempDir()
        val a = Studio(dir, sampleRate = 48000)
        val loop = a.engine.parts[1].loop // BASS 2
        loop.recording = true; loop.press(3, 4.0); loop.press(7, 9.0); loop.recording = false
        loop.bars = 16
        loop.baseNote = 48
        a.engine.click = true
        a.props["deck.1"] = "true"
        a.save()
        val b = Studio(dir, sampleRate = 48000)
        val restored = b.engine.parts[1].loop
        assertEquals(2, restored.eventCount)
        assertEquals(16, restored.bars)
        assertEquals(48, restored.baseNote)
        assertTrue(b.engine.click)
        assertEquals("true", b.props.getProperty("deck.1"))
        assertEquals(0, b.engine.parts[0].loop.eventCount)
    }

    @Test
    fun anOldDrums2SaveWithAClassicKitMovesToTheSamplePackKits() {
        val dir = tempDir()
        File(dir, "studio.properties").writeText("drums2.patterns=" + com.vtlo.core.seq.StepPatternBank().serialize() + "\ndrums2.t0.kit=TR808\ndrums2.t1.kit=TR909\n")
        val s = Studio(dir, sampleRate = 48000)
        assertTrue((0 until 8).all { s.drums2.kit(it) in Kit.EXTRA })
        assertEquals(Kit.FRC, s.drums2.kit(0))
    }
}
