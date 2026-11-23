package com.vtlo.pads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadLoopTest {
    private val fs = 48000
    private val frames = 2400 // 0.1 beat at 120 BPM
    private val blockBeats = 0.1

    private fun armed(quantize: Boolean = true) = PadLoop().apply { recording = true; this.quantize = quantize }

    private fun events(loop: PadLoop, beat0: Double): List<Triple<Int, Int, Boolean>> {
        val sink = PadLoop.Sink()
        loop.collect(beat0, blockBeats, frames, sink)
        return (0 until sink.n).map { Triple(sink.offset[it], sink.pad[it], sink.on[it]) }
    }

    @Test
    fun tapsAreQuantizedToTheNearestSixteenth() {
        val loop = armed()
        assertTrue(loop.press(3, 2.13)) // nearest 16th is 2.25
        assertEquals(listOf(Triple(1200, 3, true)), events(loop, 2.2).filter { it.third })
        assertEquals(1, loop.padCounts.get(3))
    }

    @Test
    fun withQuantizeOffTapsKeepTheirTiming() {
        val loop = armed(quantize = false)
        loop.press(0, 2.13) // tick 204 = 2.125 beats
        assertEquals(600, events(loop, 2.1).first { it.third }.first)
    }

    @Test
    fun tapsOnlyRecordWhileArmed() {
        val loop = PadLoop()
        assertFalse(loop.press(0, 1.0))
        assertEquals(0, loop.eventCount)
        loop.recording = true
        assertTrue(loop.press(0, 1.0))
        loop.recording = false
        assertFalse(loop.press(1, 2.0))
        assertEquals(1, loop.eventCount)
    }

    @Test
    fun theLoopWrapsAtTheEndOfTheBars() {
        val loop = armed()
        loop.press(1, 31.9) // rounds up to the very end of an 8-bar loop = the start
        // A block that straddles the loop point still plays it, on the right frame.
        assertEquals(listOf(Triple(1200, 1, true)), events(loop, 31.95).filter { it.third })
        // The same tap one lap later lands on the same place.
        val later = armed().apply { press(1, 31.9 + 32.0) }
        assertEquals(1200, events(later, 31.95).first { it.third }.first)
    }

    @Test
    fun eachLapAndEachArmingIsOnePassForUndo() {
        val loop = armed()
        loop.press(0, 1.0); loop.press(1, 2.0) // lap 1
        loop.press(2, 33.0); loop.press(3, 34.0) // lap 2
        assertEquals(4, loop.eventCount)
        loop.undo()
        assertEquals(2, loop.eventCount)
        assertEquals(0, loop.padCounts.get(2))
        loop.undo()
        assertEquals(0, loop.eventCount)
        loop.undo() // nothing left: harmless

        // Arming again starts a new pass even within the same lap.
        loop.press(0, 1.0)
        loop.recording = false
        loop.recording = true
        loop.press(1, 1.5)
        loop.undo()
        assertEquals(1, loop.eventCount)
        assertEquals(1, loop.padCounts.get(0))
    }

    @Test
    fun clearEmptiesTheLoop() {
        val loop = armed()
        loop.press(5, 1.0)
        loop.clear()
        assertEquals(0, loop.eventCount)
        assertEquals(0, loop.padCounts.get(5))
        assertTrue(events(loop, 0.95).isEmpty())
    }

    @Test
    fun aShorterLoopKeepsButSilencesTheTapsBeyondIt() {
        val loop = armed().apply { bars = 16 }
        loop.press(0, 40.0) // bar 11 of 16
        assertTrue(events(loop, 39.95).any { it.third })
        loop.bars = 8
        assertEquals(1, loop.eventCount)
        assertTrue(events(loop, 7.95).isEmpty()) // would be 40 mod 32 = 8, but the tap is outside 8 bars
        loop.bars = 16
        assertTrue(events(loop, 39.95).any { it.third })
        loop.bars = 12 // only 8 or 16 exist
        assertEquals(16, loop.bars)
    }

    @Test
    fun disabledLoopsPlayNothing() {
        val loop = armed()
        loop.press(0, 1.0)
        loop.enabled = false
        assertTrue(events(loop, 0.95).isEmpty())
    }

    @Test
    fun releaseSetsNoteLengthAndTheNoteOffIsScheduled() {
        val loop = armed()
        loop.press(4, 1.0) // quantized to beat 1.0
        loop.release(4, 1.5) // held half a beat
        val on = events(loop, 0.95).first { it.third }
        assertEquals(1200, on.first)
        val off = events(loop, 1.45).first { !it.third }
        assertEquals(1200, off.first)
        assertEquals(4, off.second)
        // A quick tap still gets a minimum length.
        val quick = armed().apply { press(0, 1.0); release(0, 1.01) }
        assertTrue(events(quick, 1.1).any { !it.third }) // 12 ticks = 0.125 beat later
    }

    @Test
    fun offsBeforeOnsAtTheSameFrame() {
        val loop = armed()
        loop.press(0, 1.0); loop.release(0, 1.25) // 0 ends at 1.25
        loop.press(1, 1.25) // 1 starts at 1.25
        val atSameFrame = events(loop, 1.2).filter { it.first == 1200 }
        assertEquals(listOf(false, true), atSameFrame.map { it.third })
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val loop = armed(quantize = false).apply { bars = 16; baseNote = 48 }
        loop.press(0, 1.0); loop.press(9, 3.3); loop.release(9, 3.9)
        val data = loop.serialize()
        val copy = PadLoop()
        assertTrue(copy.load(data))
        assertEquals(16, copy.bars)
        assertFalse(copy.quantize)
        assertEquals(48, copy.baseNote)
        assertEquals(data, copy.serialize())
        assertEquals(1, copy.padCounts.get(9))
        assertEquals(events(loop, 3.2), events(copy, 3.2))
    }

    @Test
    fun badDataIsRejectedAndLeavesTheLoopAlone() {
        val loop = armed().apply { press(0, 1.0) }
        for (bad in listOf("", "nonsense", "v2|8|1|36|", "v1|8|1|36|1.2.3", "v1|8|1|36|1.99.24.1", "v1|8|1|36|99999.0.24.1", "v1|x|1|36|")) {
            assertFalse(bad, loop.load(bad))
        }
        assertEquals(1, loop.eventCount)
        assertTrue(loop.load("v1|8|1|36|")) // an empty loop is valid
        assertEquals(0, loop.eventCount)
    }

    @Test
    fun theEventCountIsCapped() {
        val loop = armed()
        repeat(PadLoop.MAX_EVENTS + 50) { loop.press(it % 16, (it % 100) * 0.25) }
        assertEquals(PadLoop.MAX_EVENTS, loop.eventCount)
    }
}
