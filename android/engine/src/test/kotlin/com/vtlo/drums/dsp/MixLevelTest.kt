package com.vtlo.drums.dsp

import com.vtlo.acid303.StudioEngine
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets
import com.vtlo.sampler.dsp.SamplerEngine
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.BeforeClass
import org.junit.Test

/** Prints how loud each built-in groove is at the default settings (a gain-staging aid, not an assertion). */
class MixLevelTest {
    companion object {
        private const val FS = 48000
        private lateinit var samples: SampleBank

        @BeforeClass
        @JvmStatic
        fun load() {
            samples = SampleBank.load(FS) { File(System.getenv("KITS_DIR") ?: "../drums/src/main/assets", it).inputStream() }
        }
    }

    private fun db(x: Double) = 20 * log10(maxOf(x, 1e-9))

    @Test
    fun printLevels() {
        for (preset in DrumPresets.all + DrumPresets.hard) {
            val e = DrumEngine(FS, samples)
            DrumPresets.load(e.bank, 0, preset)
            for (t in 0 until Kit.TRACKS) e.setKit(t, preset.kit)
            e.set(DrumParam.TEMPO, DrumParam.bpmToNorm(preset.bpm.toFloat()))
            e.play()
            val buf = FloatArray(512 * 2)
            var peak = 0.0
            var sum = 0.0
            var n = 0
            for (b in 0 until (6.0 * FS / 512).toInt()) {
                e.render(buf, 512)
                if (b * 512 < FS) continue
                for (v in buf) { peak = maxOf(peak, abs(v).toDouble()); sum += v * v.toDouble(); n++ }
            }
            println("LEVEL %-16s %-4s peak %6.1f dBFS  rms %6.1f dBFS".format(preset.name, preset.kit.label, db(peak), db(sqrt(sum / n))))
        }
    }

    private fun measure(label: String, e: com.vtlo.core.audio.SequencerEngine) {
        e.play()
        val buf = FloatArray(512 * 2)
        var peak = 0.0
        var sum = 0.0
        var n = 0
        for (b in 0 until (8.0 * FS / 512).toInt()) {
            e.render(buf, 512)
            if (b * 512 < FS) continue
            for (v in buf) { peak = maxOf(peak, abs(v).toDouble()); sum += v * v.toDouble(); n++ }
        }
        println("LEVEL %-26s peak %6.1f dBFS  rms %6.1f dBFS".format(label, db(peak), db(sqrt(sum / n))))
    }

    @Test
    fun printStudioLevels() {
        val bass = AcidEngine(FS).also { Presets.load(it.bank, 0, Presets.all[0]); it.set(Param.TEMPO, Param.bpmToNorm(130f)) }
        measure("303 alone (Acid Classic)", bass)
        val wob = AcidEngine(FS, wobble = true).also { Presets.load(it.bank, 0, Presets.wobble[0]); it.set(Param.TEMPO, Param.bpmToNorm(140f)) }
        measure("wobble alone (Half-Time)", wob)
        val drums = DrumEngine(FS, samples).also { DrumPresets.load(it.bank, 0, DrumPresets.all[0]); for (t in 0 until Kit.TRACKS) it.setKit(t, Kit.TR909) }
        val studio = StudioEngine(
            AcidEngine(FS).also { Presets.load(it.bank, 0, Presets.all[0]); it.set(Param.TEMPO, Param.bpmToNorm(130f)) },
            drums, SamplerEngine(FS), AcidEngine(FS), DrumEngine(FS, samples),
            AcidEngine(FS, wobble = true).also { Presets.load(it.bank, 0, Presets.wobble[0]) },
        )
        measure("studio: 303 + 909 House", studio)
    }
}
