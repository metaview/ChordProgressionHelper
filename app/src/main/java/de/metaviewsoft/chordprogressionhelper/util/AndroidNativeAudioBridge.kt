package de.metaviewsoft.chordprogressionhelper.util

/**
 * Android [NativeAudioBridge], delegating to the JNI [NativeAudio] object. NativeAudio itself is
 * left untouched so its `external` (JNI) bindings stay intact; this adapter just exposes it through
 * the shared interface that portable DSP code depends on.
 */
object AndroidNativeAudioBridge : NativeAudioBridge {
    override fun isAvailable(): Boolean = NativeAudio.isAvailable()

    override fun addKick(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, drumLevel: Double) =
        NativeAudio.addKick(buffer, duration, levelScale, envelopeScale, drumLevel)

    override fun addSnare(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, drumLevel: Double) =
        NativeAudio.addSnare(buffer, duration, levelScale, envelopeScale, drumLevel)

    override fun addHiHat(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, hiHatHighpass: Double) =
        NativeAudio.addHiHat(buffer, duration, levelScale, envelopeScale, hiHatHighpass)

    override fun generatePianoSample(buffer: DoubleArray, frequency: Double) =
        NativeAudio.generatePianoSample(buffer, frequency)

    override fun createKarplusString(frequency: Double, sampleRate: Int, pluckStrength: Int, decay: Double): Long =
        NativeAudio.createKarplusString(frequency, sampleRate, pluckStrength, decay)

    override fun destroyKarplusString(handle: Long) = NativeAudio.destroyKarplusString(handle)
    override fun pluckString(handle: Long) = NativeAudio.pluckString(handle)
    override fun tickString(handle: Long): Double = NativeAudio.tickString(handle)
    override fun tickStringBuffer(handle: Long, buffer: DoubleArray, length: Int) =
        NativeAudio.tickStringBuffer(handle, buffer, length)

    override fun midiNoteToFrequency(midiNote: Int): Double = NativeAudio.midiNoteToFrequency(midiNote)
    override fun doubleToPcmShort(input: DoubleArray, output: ShortArray) = NativeAudio.doubleToPcmShort(input, output)
    override fun applyOverdrive(buffer: DoubleArray, gain: Double) = NativeAudio.applyOverdrive(buffer, gain)
}
