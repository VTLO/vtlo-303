package com.vtlo.live

import com.vtlo.acid303.StudioEngine
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.SampleBank
import com.vtlo.sampler.dsp.SamplerEngine
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class LiveSetsTest {
    companion object {
        private const val FS = 48000
        private lateinit var samples: SampleBank

        @BeforeClass
        @JvmStatic
        fun load() {
            samples = SampleBank.load(FS) { File("../drums/src/main/assets", it).inputStream() }
        }
    }

    private fun studio() = StudioEngine(
        AcidEngine(FS), DrumEngine(FS, samples), SamplerEngine(FS), AcidEngine(FS), DrumEngine(FS, samples), AcidEngine(FS, wobble = true),
    )

    private fun db(x: Double) = 20 * log10(maxOf(x, 1e-9))

    /** Plays [bars] bars of scene [scene] from the top and returns (peak, rms) in dBFS. */
    private fun level(e: StudioEngine, scene: Int, bars: Int): Pair<Double, Double> {
        e.stop(); e.render(FloatArray(512), 256)
        e.selectScene(scene)
        e.play()
        val buf = FloatArray(512 * 2)
        val seconds = bars * 4 * 60.0 / e.bpmSetting()
        var peak = 0.0
        var sum = 0.0
        var n = 0
        for (b in 0 until (seconds * FS / 512).toInt()) {
            e.render(buf, 512)
            if (b * 512 < FS * 0.2) continue
            for (v in buf) { peak = maxOf(peak, abs(v).toDouble()); sum += v * v.toDouble(); n++ }
        }
        return db(peak) to db(sqrt(sum / n))
    }

    private fun StudioEngine.bpmSetting() = Param.normToBpm(bass.get(Param.TEMPO)).toDouble()

    @Test
    fun everySceneOfEverySetIsAudibleAndLeavesHeadroom() {
        for (set in LiveSets.all) {
            val e = studio()
            set.load(e)
            assertEquals(set.bpm.toDouble(), e.bpmSetting(), 0.6)
            assertEquals(0, e.scene())
            for (scene in 0..5) {
                val (peak, rms) = level(e, scene, 2)
                println("SCENE %-16s %d %-7s peak %6.1f dBFS  rms %6.1f dBFS".format(set.name, scene + 1, set.scenes[scene], peak, rms))
                assertTrue("${set.name} ${set.scenes[scene]} is silent", rms > (if (scene == 0) -50 else -40))
                assertTrue("${set.name} ${set.scenes[scene]} peaks at $peak dBFS", peak < -0.5)
            }
            // The drop must not be quieter than the intro.
            assertTrue(level(e, 2, 2).second >= level(e, 0, 2).second - 0.5)
        }
    }

    @Test
    fun selectingASceneMovesEveryInstrumentTogether() {
        val e = studio()
        LiveSets.hardtekTribe.load(e)
        e.selectScene(3)
        assertEquals(3, e.scene())
        assertEquals(3, e.drums2.selectedPattern)
        assertEquals(3, e.wobble.selectedPattern)
        e.wobble.selectedPattern = 1
        assertEquals(-1, e.scene())
    }

    @Test
    fun theSetsUseTheirKitsAndTempo() {
        val e = studio()
        LiveSets.ukDnb.load(e)
        assertTrue((0 until 8).all { e.drums2.kit(it) == Kit.DNB })
        assertEquals(174.0, e.bpmSetting(), 0.6)
        LiveSets.hardtekTribe.load(e)
        assertTrue((0 until 8).all { e.drums2.kit(it) == Kit.HTK })
        assertTrue((0 until 8).all { e.drums.kit(it) == Kit.TR909 })
        assertEquals(150.0, e.bpmSetting(), 0.6)
        // Loading replaces the old patterns: the DnB wobble drop is gone.
        assertEquals(0, e.wobble.bank.step(0, 0) and (1 shl 8))
    }
}
