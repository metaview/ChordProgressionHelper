package de.metaviewsoft.chordprogressionhelper.util

import kotlin.random.Random

/**
 * Pure-Kotlin Karplus-Strong plucked-string synthesizer (shared, KMP).
 *
 * Parameters:
 * - bufferSize = sampleRate / frequency controls the fundamental pitch (integer delay-line length).
 *   Integer sizing limits frequency resolution (typical for KS synthesis); coerced to at least 1 to
 *   avoid division by zero at very low frequencies.
 * - decay: feedback multiplier (near 1.0 → longer sustain).
 * - pluckStrength: initial noise smoothing; higher values give softer attacks.
 */
class KarplusStrongString(
    frequency: Double,
    sampleRate: Int,
    pluckStrength: Int,
    decay: Double = 0.998
) {
    private val frequencyHz = frequency
    private val sampleRateHz = sampleRate
    private val pluckStrengthLevel = pluckStrength
    private val bufferSize = (sampleRateHz / frequencyHz).toInt().coerceAtLeast(1)

    private val ringBuffer = DoubleArray(bufferSize)
    private var currentIndex = 0
    private val decayFactor = decay

    fun pluck() {
        val whiteNoise = DoubleArray(bufferSize) { Random.nextDouble() * 2 - 1 }
        // pluckStrengthLevel: 1 = hard (full white noise), 3 = soft (4-tap averaged, rounder attack),
        // other = intermediate 2-tap smoothing.
        when (pluckStrengthLevel) {
            1 -> whiteNoise.copyInto(ringBuffer)
            3 -> {
                for (i in 0 until bufferSize) {
                    val n1 = whiteNoise.getOrElse(i) { 0.0 }
                    val n2 = whiteNoise.getOrElse(i - 1) { 0.0 }
                    val n3 = whiteNoise.getOrElse(i - 2) { 0.0 }
                    val n4 = whiteNoise.getOrElse(i - 3) { 0.0 }
                    ringBuffer[i] = (n1 + n2 + n3 + n4) / 4.0
                }
            }

            else -> {
                ringBuffer[0] = whiteNoise[0]
                for (i in 1 until bufferSize) {
                    ringBuffer[i] = (whiteNoise[i] + whiteNoise[i - 1]) / 2.0
                }
            }
        }
    }

    fun tick(): Double {
        val currentSample = ringBuffer[currentIndex]
        val nextSample =
            (currentSample + ringBuffer[(currentIndex + 1) % bufferSize]) * 0.5 * decayFactor
        ringBuffer[currentIndex] = nextSample
        currentIndex = (currentIndex + 1) % bufferSize
        return currentSample
    }
}
