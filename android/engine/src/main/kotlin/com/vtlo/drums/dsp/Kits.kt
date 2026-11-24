package com.vtlo.drums.dsp

import com.vtlo.core.dsp.Resampler
import com.vtlo.core.dsp.WavReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The three drum machines. Every kit maps its sounds onto the same eight track
 * roles so patterns keep their meaning when the kit changes:
 * kick, snare, tom, clap (606: high tom), rim, closed hat, open hat, cymbal (909: ride).
 */
enum class Kit(val label: String, val files: List<String>, val trackNames: List<String>) {
    TR606(
        "606",
        listOf("Bass", "Snare", "Tom_Lo", "Tom_Hi", "Rim", "HH_Closed", "HH_Open", "Cymbal"),
        listOf("BD", "SD", "LT", "HT", "RS", "CH", "OH", "CY"),
    ),
    TR808(
        "808",
        listOf("Bass", "Snare", "Tom", "Claps", "Rim", "HH_Closed", "HH_Open", "Cymbal"),
        listOf("BD", "SD", "TOM", "CP", "RS", "CH", "OH", "CY"),
    ),
    TR909(
        "909",
        listOf("Bass", "Snare", "Tom", "Claps", "Rim", "HH_Closed", "HH_Open", "Ride"),
        listOf("BD", "SD", "TOM", "CP", "RS", "CH", "OH", "RD"),
    ),

    // The extra kits of DRUMS 2, built from sample packs (see tools/build-kits.py). Same track roles as the
    // factory kits (kick, snare, tom, clap, rim, closed hat, open hat, cymbal) where a kit has them, so a pattern
    // keeps its meaning when the kit changes; the kick banks and bass kits use each track for another hit.
    /** Frenchcore: a distorted long kick, a snare, percussion hits and hats sliced from loops. */
    FRC(
        "FRC",
        listOf("F1", "SD", "E1", "PC1", "PC2", "CH", "OH", "SHK"),
        listOf("F1", "SD", "E1", "PC1", "PC2", "CH", "OH", "SHK"),
    ),
    /** Hardtek: short hard kicks and a tuned snare. */
    HTK(
        "HTK",
        listOf("K36", "SD", "K48", "PC1", "PC2", "CH", "OH", "SUB"),
        listOf("K36", "SD", "K48", "PC1", "PC2", "CH", "OH", "SUB"),
    ),
    /** Drum and bass, dubstep: long sub kicks, a breakbeat kick, short punches. */
    DNB(
        "DNB",
        listOf("DMN", "SD", "BRK", "CRM", "RCK", "CH", "OH", "SHK"),
        listOf("DMN", "SD", "BRK", "CRM", "RCK", "CH", "OH", "SHK"),
    ),
    /** Frenchcore kick bank: seven tuned kicks and one hardtek kick. */
    FCK(
        "FCK",
        listOf("F1A", "F1B", "G1A", "G1B", "E1", "E2", "D2", "L10"),
        listOf("F1A", "F1B", "G1A", "G1B", "E1", "E2", "D2", "L10"),
    ),
    /** Bass hits: eight sustained bass sounds, played pitched from the TUNE knob or the pads. */
    BAS(
        "BAS",
        listOf("REES", "DARK", "CERB", "CRYS", "DRO", "VANI", "SAW", "SPNZ"),
        listOf("REES", "DARK", "CERB", "CRYS", "DRO", "VANI", "SAW", "SPNZ"),
    ),
    /** Three more bass hits and five kicks. */
    MIX(
        "MIX",
        listOf("BUBL", "LIVE", "ACOU", "L11", "K49", "K50", "KAC", "RACK"),
        listOf("BUBL", "LIVE", "ACOU", "L11", "K49", "K50", "KAC", "RACK"),
    );

    fun assetPath(track: Int) = "kits/$label/${files[track]}.wav"

    companion object {
        const val TRACKS = 8
        const val CLOSED_HAT = 5
        const val OPEN_HAT = 6

        /** The kits of DRUMS 1: the classic machines. */
        val FACTORY = listOf(TR606, TR808, TR909)

        /** The kits of DRUMS 2: the sample-pack kits. */
        val EXTRA = listOf(FRC, HTK, DNB, FCK, BAS, MIX)

        /** The kits that drum machine [number] (1 or 2) offers. */
        fun forMachine(number: Int): List<Kit> = if (number == 1) FACTORY else EXTRA

        /** [kit] if machine [number] offers it, else that machine's first kit (a save from before DRUMS 2 changed). */
        fun fit(number: Int, kit: Kit): Kit = if (kit in forMachine(number)) kit else forMachine(number)[0]
    }
}

/** Every kit's samples, converted to one output rate. Immutable once built. */
class SampleBank private constructor(val sampleRate: Int, private val samples: Array<Array<FloatArray>>) {
    fun get(kit: Kit, track: Int): FloatArray = samples[kit.ordinal][track]

    /** Writes the converted samples so later launches can skip resampling. */
    fun writeTo(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(CACHE_VERSION)
        d.writeInt(sampleRate)
        for (kit in samples) for (data in kit) {
            d.writeInt(data.size)
            for (v in data) d.writeFloat(v)
        }
        d.flush()
    }

    companion object {
        private const val CACHE_VERSION = 2

        /** Reads a bank written by [writeTo]; null if it is for another rate or version. */
        fun readFrom(input: InputStream, sampleRate: Int): SampleBank? {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != CACHE_VERSION || d.readInt() != sampleRate) return null
            return SampleBank(sampleRate, Array(Kit.entries.size) {
                Array(Kit.TRACKS) { FloatArray(d.readInt()).also { a -> for (i in a.indices) a[i] = d.readFloat() } }
            })
        }

        /** @param open opens an asset path such as `kits/909/Bass.wav`. */
        fun load(sampleRate: Int, open: (String) -> InputStream): SampleBank = SampleBank(
            sampleRate,
            Array(Kit.entries.size) { k ->
                val kit = Kit.entries[k]
                Array(Kit.TRACKS) { t ->
                    val s = open(kit.assetPath(t)).use(WavReader::read)
                    Resampler.resample(s.data, s.sampleRate, sampleRate)
                }
            }
        )
    }
}
