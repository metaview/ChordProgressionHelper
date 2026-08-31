package de.metaviewsoft.chordprogressionhelper.util

/**
 * [NativeAudioBridge] that reports the native C++ engine as unavailable. All portable DSP code
 * (DrumSynth/PianoSynth/DspSupport/KarplusStrongString) checks [isAvailable] and falls back to
 * its pure-Kotlin implementation, so audio works end-to-end without cinterop. Wiring the real
 * C++ engine via cinterop is a later optimization; the interface stays the same.
 */
object UnavailableNativeAudioBridge : NativeAudioBridge {
    override fun isAvailable(): Boolean = false

    private fun unavailable(): Nothing =
        throw IllegalStateException("native audio engine not available on iOS (Kotlin fallback expected)")

    override fun addKick(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, drumLevel: Double) = unavailable()
    override fun addSnare(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, drumLevel: Double) = unavailable()
    override fun addHiHat(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, hiHatHighpass: Double) = unavailable()
    override fun generatePianoSample(buffer: DoubleArray, frequency: Double) = unavailable()
    override fun createKarplusString(frequency: Double, sampleRate: Int, pluckStrength: Int, decay: Double): Long = unavailable()
    override fun destroyKarplusString(handle: Long) = unavailable()
    override fun pluckString(handle: Long) = unavailable()
    override fun tickString(handle: Long): Double = unavailable()
    override fun tickStringBuffer(handle: Long, buffer: DoubleArray, length: Int) = unavailable()
    override fun midiNoteToFrequency(midiNote: Int): Double = unavailable()
    override fun doubleToPcmShort(input: DoubleArray, output: ShortArray) = unavailable()
    override fun applyOverdrive(buffer: DoubleArray, gain: Double) = unavailable()
}
