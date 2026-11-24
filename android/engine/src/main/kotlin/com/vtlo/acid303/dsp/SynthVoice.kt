package com.vtlo.acid303.dsp

/**
 * What the sequencer engine needs from a monophonic voice: the shared knobs (all 0..1, except the tuning in
 * semitones) and the note handling. [Voice303] is the TB-303; [WobbleVoice] is the round-wave wobble synth.
 */
interface SynthVoice {
    var cutoff: Double
    var resonance: Double
    var envMod: Double
    var decay: Double
    var accent: Double
    var waveform: Double
    var tuneSemitones: Double

    /** Starts a note; with [legato] and an open gate the pitch glides instead of retriggering. */
    fun noteOn(note: Int, accented: Boolean, legato: Boolean)
    fun noteOff()
    fun reset()

    /** One sample at the engine's sample rate. */
    fun render(): Double
}
