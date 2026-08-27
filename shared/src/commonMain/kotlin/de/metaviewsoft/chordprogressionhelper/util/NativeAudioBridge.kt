package de.metaviewsoft.chordprogressionhelper.util

/**
 * Platform-independent access to the native C++ audio engine.
 *
 * On Android this is implemented by delegating to the JNI `NativeAudio` object (whose `external`
 * functions are bound by name to `de.metaviewsoft.chordprogressionhelper.util.NativeAudio`, so it
 * must stay in the app at that exact package). iOS will implement this later via cinterop against
 * the same C++ sources. Portable DSP code in :shared depends only on this interface.
 */
interface NativeAudioBridge {
    fun isAvailable(): Boolean

    fun addKick(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, drumLevel: Double)
    fun addSnare(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, drumLevel: Double)
    fun addHiHat(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, hiHatHighpass: Double)

    fun generatePianoSample(buffer: DoubleArray, frequency: Double)

    fun createKarplusString(frequency: Double, sampleRate: Int, pluckStrength: Int, decay: Double): Long
    fun destroyKarplusString(handle: Long)
    fun pluckString(handle: Long)
    fun tickString(handle: Long): Double
    fun tickStringBuffer(handle: Long, buffer: DoubleArray, length: Int)

    fun midiNoteToFrequency(midiNote: Int): Double
    fun doubleToPcmShort(input: DoubleArray, output: ShortArray)
    fun applyOverdrive(buffer: DoubleArray, gain: Double)
}
