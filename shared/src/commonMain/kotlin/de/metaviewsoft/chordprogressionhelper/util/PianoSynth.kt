package de.metaviewsoft.chordprogressionhelper.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Shared (KMP) piano synthesis. Uses the native C++ engine via [NativeAudioBridge] when available,
 * otherwise the identical pure-Kotlin additive-synthesis fallback previously in AudioPlayer.
 */
object PianoSynth {

    /** Single piano note: native, or 3-harmonic additive synthesis with an ADSR envelope. */
    fun generatePianoSample(
        bridge: NativeAudioBridge,
        frequency: Double,
        durationSec: Double,
        sampleRate: Int,
        logWarn: (String) -> Unit = {},
    ): DoubleArray {
        val numSamples = (sampleRate * durationSec).toInt()
        val samples = DoubleArray(numSamples)

        if (bridge.isAvailable()) {
            try {
                bridge.generatePianoSample(samples, frequency)
                return samples
            } catch (e: Exception) {
                logWarn("Native generatePianoSample failed, using fallback: ${e.message}")
            }
        }

        // Piano has harmonics with specific amplitude ratios (3 harmonics for performance).
        val harmonics = listOf(
            1.0 to 1.0,      // Fundamental
            2.0 to 0.6,      // 2nd harmonic (octave)
            3.0 to 0.3       // 3rd harmonic
        )

        val attackTime = 0.002  // 2ms attack (very fast for immediate response)
        val decayTime = 0.15    // 150ms decay
        val sustainLevel = 0.3  // 30% sustain level

        val attackSamples = (attackTime * sampleRate).toInt()
        val decaySamples = (decayTime * sampleRate).toInt()
        val attackDecaySamples = attackSamples + decaySamples

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var sample = 0.0

            for ((harmonic, amplitude) in harmonics) {
                val freq = frequency * harmonic
                sample += amplitude * sin(2.0 * PI * freq * t)
            }

            val envelope = when {
                i < attackSamples -> i.toDouble() / attackSamples
                i < attackDecaySamples -> {
                    val decayProgress = (i - attackSamples).toDouble() / decaySamples
                    1.0 - (1.0 - sustainLevel) * decayProgress
                }

                else -> {
                    val releaseProgress =
                        (i - attackDecaySamples).toDouble() / (numSamples - attackDecaySamples)
                    sustainLevel * (1.0 - releaseProgress)
                }
            }.coerceIn(0.0, 1.0)

            samples[i] = sample * envelope
        }

        val maxVal = samples.maxOfOrNull { abs(it) } ?: 1.0
        if (maxVal > 0.0) {
            for (i in samples.indices) {
                samples[i] /= maxVal
            }
        }

        return samples
    }

    /** Mix several piano notes (given as MIDI numbers) into one normalized buffer. */
    fun mixPianoNotes(
        bridge: NativeAudioBridge,
        midiNotes: List<Int>,
        durationSec: Double,
        sampleRate: Int,
        logWarn: (String) -> Unit = {},
    ): DoubleArray {
        val numSamples = (sampleRate * durationSec).toInt()
        val mixed = DoubleArray(numSamples)

        for (midiNote in midiNotes) {
            val freq = DspSupport.midiNoteToFrequency(bridge, midiNote, logWarn)
            val noteSamples = generatePianoSample(bridge, freq, durationSec, sampleRate, logWarn)
            for (i in 0 until minOf(numSamples, noteSamples.size)) {
                mixed[i] += noteSamples[i]
            }
        }

        val maxVal = mixed.maxOfOrNull { abs(it) } ?: 1.0
        if (maxVal > 0.0) {
            val normFactor = 0.8 / maxVal // Leave some headroom
            for (i in mixed.indices) {
                mixed[i] *= normFactor
            }
        }

        return mixed
    }
}
