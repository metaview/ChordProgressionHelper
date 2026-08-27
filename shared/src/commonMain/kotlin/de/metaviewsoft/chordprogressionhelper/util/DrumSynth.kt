package de.metaviewsoft.chordprogressionhelper.util

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shared (KMP) drum synthesis. Each generator uses the native C++ engine via [NativeAudioBridge]
 * when available and otherwise falls back to the identical pure-Kotlin synthesis previously in
 * AudioPlayer. Global mix parameters (envelopeScale, drumLevel, hiHatHighpass) are passed per call
 * so this stays stateless.
 */
object DrumSynth {

    /** Low kick: sine whose frequency drops over a fast pow(4) envelope. */
    fun addKick(
        bridge: NativeAudioBridge,
        buffer: DoubleArray,
        duration: Int,
        levelScale: Double,
        envelopeScale: Double,
        drumLevel: Double,
        sampleRate: Int,
        logWarn: (String) -> Unit = {},
    ) {
        if (bridge.isAvailable()) {
            try {
                bridge.addKick(buffer, duration, levelScale, envelopeScale, drumLevel)
                return
            } catch (e: Exception) {
                logWarn("Native addKick failed, using fallback: ${e.message}")
            }
        }
        val freq = 60.0
        val kickDuration = (duration * 0.5).toInt().coerceAtMost(buffer.size)
        for (i in 0 until kickDuration) {
            val progress = i.toDouble() / kickDuration
            val envelope = (1.0 - progress).pow(4) * envelopeScale
            val angle = 2.0 * PI * i * (freq * (1.0 - progress * 0.5)) / sampleRate
            // 3.6: empirical multiplier to make the synthesized kick audible in the mix.
            buffer[i] += sin(angle) * envelope * 3.6 * drumLevel * levelScale
        }
    }

    /** Snare: short noise burst shaped by a pow(2) envelope. */
    fun addSnare(
        bridge: NativeAudioBridge,
        buffer: DoubleArray,
        duration: Int,
        levelScale: Double,
        envelopeScale: Double,
        drumLevel: Double,
        logWarn: (String) -> Unit = {},
    ) {
        if (bridge.isAvailable()) {
            try {
                bridge.addSnare(buffer, duration, levelScale, envelopeScale, drumLevel)
                return
            } catch (e: Exception) {
                logWarn("Native addSnare failed, using fallback: ${e.message}")
            }
        }
        val snareDuration = (duration * 0.2).toInt().coerceAtMost(buffer.size)
        for (i in 0 until snareDuration) {
            val noise = (Random.nextDouble() * 2 - 1)
            val envelope = (1.0 - i.toDouble() / snareDuration).pow(2) * envelopeScale
            // 1.4: snare noise gain — empirical value to sit snare in the mix.
            buffer[i] += noise * envelope * 1.4 * drumLevel * levelScale
        }
    }

    /** Hi-hat: high-passed noise burst; [hiHatHighpass] shapes the HF content. */
    fun addHiHat(
        bridge: NativeAudioBridge,
        buffer: DoubleArray,
        duration: Int,
        levelScale: Double,
        envelopeScale: Double,
        hiHatHighpass: Double,
        logWarn: (String) -> Unit = {},
    ) {
        if (bridge.isAvailable()) {
            try {
                bridge.addHiHat(buffer, duration, levelScale, envelopeScale, hiHatHighpass)
                return
            } catch (e: Exception) {
                logWarn("Native addHiHat failed, using fallback: ${e.message}")
            }
        }
        val hiHatDuration = (duration * 0.25).toInt().coerceAtMost(buffer.size)
        var lastNoise = 0.0
        for (i in 0 until hiHatDuration) {
            val white = Random.nextDouble() * 2 - 1
            val highPass = (white - lastNoise) * hiHatHighpass
            lastNoise = white
            val envelope = (1.0 - i.toDouble() / hiHatDuration).pow(2) * envelopeScale
            // 1.2: hi-hat gain factor (empirical).
            buffer[i] += highPass * envelope * 1.2 * levelScale
        }
    }

    /** Subtle percussive transient (the palm-mute 'thud'); pure Kotlin, no native path. */
    fun addMutePercussive(buffer: DoubleArray, drumLevel: Double) {
        val n = buffer.size
        if (n <= 0) return
        val attackSamples = (n * 0.1).toInt().coerceAtLeast(6)
        var last = 0.0
        for (i in 0 until attackSamples) {
            val white = Random.nextDouble() * 2 - 1
            // low-pass-ish smoothing to make it less clicky
            val band = (white + last) * 0.5
            last = white
            val env = (1.0 - i.toDouble() / attackSamples).pow(1.8) * 0.9
            buffer[i] += band * env * 0.2 * drumLevel
        }
    }
}
