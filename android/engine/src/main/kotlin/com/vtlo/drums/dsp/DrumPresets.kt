package com.vtlo.drums.dsp

import com.vtlo.core.seq.StepPatternBank
import kotlin.random.Random

class DrumPreset(val name: String, val bpm: Int, val kit: Kit, val rows: IntArray)

/**
 * Built-in grooves. Each row is written as 16 characters per track in track
 * order (kick, snare, tom, clap/high tom, rim, closed hat, open hat, cymbal):
 * `.` rest, `x` hit, `X` accented hit.
 */
object DrumPresets {
    private fun rows(vararg tracks: Pair<Int, String>): IntArray {
        val out = IntArray(Kit.TRACKS)
        for ((t, s) in tracks) {
            require(s.length == StepPatternBank.STEPS)
            var r = 0
            s.forEachIndexed { i, c ->
                if (c == 'x' || c == 'X') r = r or (1 shl i)
                if (c == 'X') r = r or (1 shl (i + 16))
            }
            out[t] = r
        }
        return out
    }

    private const val BD = 0
    private const val SD = 1
    private const val TOM = 2
    private const val CP = 3
    private const val RS = 4
    private const val CH = 5
    private const val OH = 6
    private const val CY = 7

    val all = listOf(
        DrumPreset("House", 124, Kit.TR909, rows(
            BD to "X...X...X...X...",
            CP to "....X.......X...",
            CH to "x..xx..xx..xx..x",
            OH to "..x...x...x...x.",
        )),
        DrumPreset("Techno", 132, Kit.TR909, rows(
            BD to "X...X...X...X...",
            CP to "....x.......x...",
            RS to "..x..x....x..x..",
            CH to "xxXxxxXxxxXxxxXx",
            CY to "..x...x...x...x.",
        )),
        DrumPreset("Electro", 118, Kit.TR808, rows(
            BD to "X.....x...X..x..",
            SD to "....X.......X...",
            CP to "....x.......x..x",
            TOM to "..........x.x...",
            CH to "x.x.x.x.x.x.x.xX",
            CY to "X...............",
        )),
        DrumPreset("Hip Hop", 90, Kit.TR808, rows(
            BD to "X......x..X.....",
            SD to "....X.......X...",
            RS to "..........x.....",
            CH to "x.x.x.x.x.x.x...",
            OH to ".............x..",
        )),
        DrumPreset("Acid", 130, Kit.TR606, rows(
            BD to "X...X...X...X...",
            SD to "....X.......X..x",
            TOM to "..............x.",
            CP to ".............x..",
            CH to "xxXxxxXxxxXxxxXx",
            OH to "..x...x...x...x.",
        )),
        DrumPreset("Breakbeat", 140, Kit.TR909, rows(
            BD to "X.x.......X.....",
            SD to "....X..x.x..X...",
            CH to "x.x.x.x.x.x.x.x.",
            OH to "..............x.",
            CY to "X...............",
        )),
        DrumPreset("Miami Bass", 128, Kit.TR808, rows(
            BD to "X..x..X...x..x..",
            CP to "....X.......X...",
            TOM to "...........x..x.",
            CH to "x.xxx.xxx.xxx.xx",
        )),
        DrumPreset("Minimal", 122, Kit.TR606, rows(
            BD to "X...X...X...X...",
            RS to "...x..x....x..x.",
            CP to ".......x........",
            CH to "..x...x...x...x.",
        )),
    )

    /** Grooves for the sample-pack kits of DRUMS 2. */
    val hard = listOf(
        DrumPreset("Frenchcore", 180, Kit.FRC, rows(
            BD to "X..xX..xX..xX.x.",
            SD to "....X.......X...",
            TOM to "...x......x.....",
            CP to "..x...x...x...x.",
            CH to "x.x.x.x.x.x.x.x.",
            OH to "..x...x...x...x.",
        )),
        DrumPreset("Hardtek", 150, Kit.HTK, rows(
            BD to "X...X...X...X...",
            SD to "........X.......",
            CP to "..x..x..x..x..x.",
            RS to ".x..x..x..x..x..",
            CH to "x.x.x.x.x.x.x.x.",
            OH to "..x...x...x...x.",
            CY to "X.......X.......",
        )),
        DrumPreset("Tribe", 160, Kit.HTK, rows(
            BD to "X..x..x.X..x..x.",
            TOM to "..x..x..x..x..x.",
            CP to ".x..x..x..x..x..",
            RS to "x..x..x..x..x..x",
            CH to "xxxxxxxxxxxxxxxx",
            OH to "..x.......x.....",
        )),
        DrumPreset("Drum & Bass", 174, Kit.DNB, rows(
            BD to "X.........X.....",
            SD to "....X.......X...",
            CH to "x.x.x.x.x.x.x.x.",
            OH to "..............x.",
            CY to ".......x........",
        )),
        DrumPreset("Dubstep", 140, Kit.DNB, rows(
            BD to "X.......x.......",
            SD to "........X.......",
            TOM to "..........x.....",
            RS to "............x...",
            CH to "x.x.x.x.x.x.x.x.",
            OH to "......x.......x.",
        )),
        DrumPreset("Kick bank", 150, Kit.FCK, rows(
            0 to "X...X...X...X...",
            1 to "..x...x...x...x.",
            2 to "....x.......x...",
            4 to "x.......X.......",
            5 to "..x.......x.....",
            7 to ".......x.......x",
        )),
        DrumPreset("Bass hits", 140, Kit.BAS, rows(
            0 to "X.......x.......",
            2 to "..x.......x.....",
            4 to "....x.......x...",
            6 to "........x...x...",
        )),
        DrumPreset("Kicks and bass", 150, Kit.MIX, rows(
            3 to "X...X...X...X...",
            5 to "..x...x...x...x.",
            0 to "x.......x.......",
            6 to "......x.......x.",
        )),
    )

    /** The presets drum machine [number] offers. */
    fun forMachine(number: Int): List<DrumPreset> = if (number == 1) all else hard

    fun load(bank: StepPatternBank, pattern: Int, preset: DrumPreset) {
        for (t in 0 until Kit.TRACKS) bank.setRow(pattern, t, preset.rows[t])
        bank.setLength(pattern, StepPatternBank.STEPS)
    }

    /** A random but danceable groove: kick on the beat, backbeat, busy hats. */
    fun randomize(bank: StepPatternBank, pattern: Int, random: Random = Random.Default) {
        fun row(prob: (Int) -> Float, accentProb: Float): Int {
            var r = 0
            for (i in 0 until StepPatternBank.STEPS) {
                if (random.nextFloat() < prob(i)) {
                    r = r or (1 shl i)
                    if (random.nextFloat() < accentProb) r = r or (1 shl (i + 16))
                }
            }
            return r
        }
        bank.setRow(pattern, BD, row({ if (it % 4 == 0) 0.9f else 0.12f }, 0.4f))
        bank.setRow(pattern, SD, row({ if (it % 8 == 4) 0.85f else 0.07f }, 0.3f))
        bank.setRow(pattern, TOM, row({ 0.06f }, 0.2f))
        bank.setRow(pattern, CP, row({ if (it % 8 == 4) 0.4f else 0.04f }, 0.2f))
        bank.setRow(pattern, RS, row({ 0.12f }, 0.2f))
        bank.setRow(pattern, CH, row({ if (it % 2 == 0) 0.85f else 0.45f }, 0.25f))
        bank.setRow(pattern, OH, row({ if (it % 4 == 2) 0.45f else 0f }, 0.2f))
        bank.setRow(pattern, CY, row({ if (it == 0) 0.3f else 0f }, 0f))
    }
}
