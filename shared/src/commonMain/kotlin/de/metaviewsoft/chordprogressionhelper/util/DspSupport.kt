package de.metaviewsoft.chordprogressionhelper.util

import kotlin.math.pow
import kotlin.math.tanh

/**
 * Stateless DSP helpers, shared (KMP). Each native-accelerated helper falls back to a pure-Kotlin
 * implementation when the [NativeAudioBridge] reports the native library is unavailable — behaviour
 * identical to the previous Android-only AudioPlayer code.
 */
object DspSupport {

    /** One-pole low-pass step: prev + alpha * (value - prev). */
    fun lowpass(prev: Double, alpha: Double, value: Double): Double = prev + alpha * (value - prev)

    /**
     * tanh soft-clip overdrive, blended with the dry signal. [crunchLevel] ~0 = subtle warmth,
     * 2.0 = heavy. Matches the saturation used in full playback so live previews sound the same.
     */
    fun overdrive(sample: Double, crunchLevel: Double, driveBoost: Double = 1.0): Double {
        val gain = (1.2 + (crunchLevel * 2.5)) * driveBoost
        val mix = 0.5 + (crunchLevel * 0.35)
        val driven = tanh(sample * gain)
        return mix * driven + (1.0 - mix) * sample
    }

    /** Convert doubles in [-1.0, 1.0] to 16-bit PCM, natively if possible. */
    fun pcmFromDoubles(
        bridge: NativeAudioBridge,
        input: DoubleArray,
        logWarn: (String) -> Unit = {},
    ): ShortArray {
        val out = ShortArray(input.size)
        if (bridge.isAvailable()) {
            try {
                bridge.doubleToPcmShort(input, out)
                return out
            } catch (e: Exception) {
                logWarn("Native doubleToPcmShort failed, using fallback: ${e.message}")
            }
        }
        for (i in input.indices) {
            val sample = input[i].coerceIn(-1.0, 1.0)
            out[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /**
     * MIDI note number to frequency (Hz), natively if possible. Offset-encoded chord notes
     * (roughly -20..+13, stored around C=0) below 18 are lifted into the C4 register; real solo
     * keyboard notes (B0 = 23 and up) are left as-is.
     */
    fun midiNoteToFrequency(
        bridge: NativeAudioBridge,
        midiNote: Int,
        logWarn: (String) -> Unit = {},
    ): Double {
        if (bridge.isAvailable()) {
            try {
                return bridge.midiNoteToFrequency(midiNote)
            } catch (e: Exception) {
                logWarn("Native midiNoteToFrequency failed, using fallback: ${e.message}")
            }
        }
        var midi = midiNote
        if (midi < 18) midi += 60
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }
}
