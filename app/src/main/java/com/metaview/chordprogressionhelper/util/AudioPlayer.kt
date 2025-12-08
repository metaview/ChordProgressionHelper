@file:OptIn(InternalSerializationApi::class)

package com.metaview.chordprogressionhelper.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.metaview.chordprogressionhelper.model.Chord
import com.metaview.chordprogressionhelper.model.ChordProgression
import com.metaview.chordprogressionhelper.model.Strum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

@OptIn(InternalSerializationApi::class)
class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 44100
    @Volatile private var isPlaying = false

    // Live sound parameters (can be changed at runtime)
    @Volatile var drumLevel: Double = 1.0
    @Volatile var envelopeScale: Double = 1.0
    @Volatile var hiHatHighpass: Double = 1.0
    // Voice preset (CLEAN, OVERDRIVE, PIANO) - default CLEAN
    @Volatile var voicePreset: com.metaview.chordprogressionhelper.data.SoundPreset = com.metaview.chordprogressionhelper.data.SoundPreset.CLEAN

    @OptIn(InternalSerializationApi::class)
    suspend fun playProgression(
        progression: ChordProgression,
        shouldLoop: () -> Boolean,
        pluckStrength: Int,
        countInBeats: Int,
        onPositionChanged: (measureIndex: Int, strumIndex: Int) -> Unit,
        startMeasureIndex: Int = 0,
        startStrumIndex: Int = 0,
        isResuming: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (isPlaying) return@withContext

        isPlaying = true

        // Create AudioTrack once; we'll write buffers of varying lengths when tempo changes
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufferSize * 2)
            .build()
        audioTrack?.play()

        var activeStrings: List<KarplusStrongString> = emptyList()

        // Count-in: generate per-beat content using current tempo at each iteration
        if (countInBeats > 0 && !isResuming) {
            repeat(countInBeats) {
                if (!isPlaying) return@repeat
                val beatDuration = 60.0 / progression.tempo
                val eighthNoteDuration = beatDuration / 2.0
                val eighthNoteSamples = (sampleRate * eighthNoteDuration).toInt().coerceAtLeast(1)
                val buffer = DoubleArray(eighthNoteSamples * 2)
                addHiHat(buffer, eighthNoteSamples * 2)
                val samples = normalizeAndConvertToShort(buffer, 1.0)
                audioTrack?.write(samples, 0, samples.size)
            }
        }

        var isFirstLoop = true
        do {
            val loopStartMeasure = if(isFirstLoop) startMeasureIndex else 0

            for (measureIndex in loopStartMeasure until progression.measures.size) {
                val measure = progression.measures[measureIndex]
                if (!isPlaying) break

                val loopStartStrum = if (isFirstLoop && measureIndex == startMeasureIndex) startStrumIndex else 0
                // If this is the very first measure/strum we start playing (not resuming mid-measure), ensure no previous strings are ringing
                if (isFirstLoop && measureIndex == startMeasureIndex && loopStartStrum == 0) {
                    activeStrings = emptyList()
                }

                for (strumIndex in loopStartStrum until measure.strummingPattern.strums.size) {
                    if (!isPlaying) break

                    // Read tempo live from progression so changes take effect immediately
                    val beatDuration = 60.0 / progression.tempo
                    val eighthNoteDuration = beatDuration / 2.0
                    val eighthNoteSamples = (sampleRate * eighthNoteDuration).toInt().coerceAtLeast(1)

                    onPositionChanged(measureIndex, strumIndex)

                    val chord: Chord? = measure.getChordAt(strumIndex)
                    var currentStrum = measure.strummingPattern.strums[strumIndex]

                    // Note: keep REST silent even if a new chord event starts on that strum.
                    // Previously we forced REST->DOWN when a chord event started at this strum, but
                    // users expect a REST to produce silence. Do not override REST here.

                    when (currentStrum) {
                        Strum.DOWN, Strum.UP -> {
                            val frequencies = chord?.getMidiNotes()?.map { midiNoteToFrequency(it) }
                            activeStrings = if (frequencies != null) {
                                frequencies.sorted().let { if (currentStrum == Strum.UP) it.asReversed() else it }.map { freq ->
                                    KarplusStrongString(freq, sampleRate, pluckStrength).apply { pluck() }
                                }
                            } else {
                                emptyList()
                            }
                        }
                        Strum.REST, Strum.MUTE -> activeStrings = emptyList()
                        Strum.LETRING -> { /* Do nothing, let strings ring */ }
                    }

                    val buffer = DoubleArray(eighthNoteSamples)

                    if (currentStrum == Strum.MUTE) addMute(buffer)
                    else {
                        when (voicePreset) {
                            com.metaview.chordprogressionhelper.data.SoundPreset.CLEAN -> {
                                for (i in buffer.indices) {
                                    var sample = 0.0
                                    for (string in activeStrings) sample += string.tick()
                                    buffer[i] += sample
                                }
                            }
                            com.metaview.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> {
                                // Simple overdrive: sum strings, apply a gain and soft clipping via tanh
                                val gain = 2.5
                                for (i in buffer.indices) {
                                    var sample = 0.0
                                    for (string in activeStrings) sample += string.tick()
                                    // apply gain
                                    val driven = tanh(sample * gain)
                                    buffer[i] += driven
                                }
                            }
                            com.metaview.chordprogressionhelper.data.SoundPreset.PIANO -> {
                                // Simple piano-ish tone: slightly brighter (filter) and faster decay per string
                                for (i in buffer.indices) {
                                    var sample = 0.0
                                    for (string in activeStrings) {
                                        // emulate faster decay by calling tick twice for brighter attack
                                        sample += string.tick() * 1.0
                                    }
                                    // apply a mild high-frequency emphasis
                                    val hf = sample * 1.6
                                    buffer[i] += hf
                                }
                            }
                        }
                    }

                    addHiHat(buffer, eighthNoteSamples)
                    if (strumIndex % 2 == 0) {
                        val quarterNoteIndex = strumIndex / 2
                        if (quarterNoteIndex % 2 == 0) addKick(buffer, eighthNoteSamples * 2)
                        if (quarterNoteIndex % 2 == 1) addSnare(buffer, eighthNoteSamples * 2)
                    }

                    val normalizationFactor = (activeStrings.size) * 0.7 + 1.0
                    val samples = normalizeAndConvertToShort(buffer, normalizationFactor)
                    audioTrack?.write(samples, 0, samples.size)
                }
            }
            isFirstLoop = false
            if (!isPlaying) break
        } while (shouldLoop() && isPlaying)

    }

    private fun normalizeAndConvertToShort(buffer: DoubleArray, normalizationFactor: Double) : ShortArray {
        val shortArray = ShortArray(buffer.size)
        for(i in buffer.indices) {
            val sample = buffer[i] / normalizationFactor
            shortArray[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return shortArray
    }

    suspend fun previewChord(chord: Chord, pluckStrength: Int) = withContext(Dispatchers.IO) {
        val previewDuration = 1.0
        val numSamples = (sampleRate * previewDuration).toInt()
        val frequencies = chord.getMidiNotes().map { midiNoteToFrequency(it) }
        val strings = frequencies.map { KarplusStrongString(it, sampleRate, pluckStrength).apply { pluck() } }
        val buffer = DoubleArray(numSamples)
        for(i in buffer.indices) {
            var sample = 0.0
            for(string in strings) sample += string.tick()
            buffer[i] = sample
        }

        val samples = normalizeAndConvertToShort(buffer, (frequencies.size * 0.7) + 1.0)

        var previewAudioTrack: AudioTrack? = null
        try {
             val previewMinBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
             previewAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                // samples.size is number of PCM samples (shorts) - convert to bytes; ensure at least min buffer size
                .setBufferSizeInBytes(maxOf(previewMinBuffer, samples.size * 2))
                .build()

            previewAudioTrack.play()
            previewAudioTrack.write(samples, 0, samples.size)
            kotlinx.coroutines.delay((previewDuration * 1000).toLong())
        } finally {
            previewAudioTrack?.stop()
            previewAudioTrack?.release()
        }
    }

    private fun addMute(buffer: DoubleArray) {
        val muteDuration = (buffer.size * 0.25).toInt()
        var lastNoise = 0.0
        for (i in 0 until muteDuration) {
            val white = Random.nextDouble() * 2 - 1
            val bandPass = (white + lastNoise) / 2.0
            lastNoise = white
            val envelope = (1.0 - i.toDouble() / muteDuration).pow(2)
            buffer[i] += bandPass * envelope * 0.6
        }
    }

    private fun addKick(buffer: DoubleArray, duration: Int) {
        val freq = 60.0
        val kickDuration = (duration * 0.5).toInt().coerceAtMost(buffer.size)
        for (i in 0 until kickDuration) {
            val progress = i.toDouble() / kickDuration
            val envelope = (1.0 - progress).pow(4) * envelopeScale
            val angle = 2.0 * PI * i * (freq * (1.0 - progress * 0.5)) / sampleRate
            // use live drumLevel
            buffer[i] += sin(angle) * envelope * 3.6 * drumLevel
        }
    }

    private fun addSnare(buffer: DoubleArray, duration: Int) {
        val snareDuration = (duration * 0.2).toInt().coerceAtMost(buffer.size)
        for (i in 0 until snareDuration) {
            val noise = (Random.nextDouble() * 2 - 1)
            val envelope = (1.0 - i.toDouble() / snareDuration).pow(2) * envelopeScale
            buffer[i] += noise * envelope * 1.4 * drumLevel
        }
    }

    private fun addHiHat(buffer: DoubleArray, duration: Int) {
        val hiHatDuration = (duration * 0.25).toInt().coerceAtMost(buffer.size)
        var lastNoise = 0.0
        for (i in 0 until hiHatDuration) {
            val white = Random.nextDouble() * 2 - 1
            val highPass = (white - lastNoise) * hiHatHighpass
            lastNoise = white
            val envelope = (1.0 - i.toDouble() / hiHatDuration).pow(2) * envelopeScale
            buffer[i] += highPass * envelope * 1.2
        }
    }

    @Suppress("unused")
    private class KarplusStrongString(frequency: Double, sampleRate: Int, pluckStrength: Int) {
        private val frequencyHz = frequency
        private val sampleRateHz = sampleRate
        private val pluckStrengthLevel = pluckStrength
        private val bufferSize = (sampleRateHz / frequencyHz).toInt().coerceAtLeast(1)
        private val ringBuffer = DoubleArray(bufferSize)
        private var currentIndex = 0
        private val decayFactor = 0.998

        fun pluck() {
            val whiteNoise = DoubleArray(bufferSize) { Random.nextDouble() * 2 - 1 }
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
            val nextSample = (currentSample + ringBuffer[(currentIndex + 1) % bufferSize]) * 0.5 * decayFactor
            ringBuffer[currentIndex] = nextSample
            currentIndex = (currentIndex + 1) % bufferSize
            return currentSample
        }
    }

    private fun midiNoteToFrequency(midiNote: Int): Double {
        // Some parts of the app provide only a pitch class (0..11) as midiOffset.
        // If the midi value looks like an offset (very low), shift it into a usable octave
        // so we generate audible, correctly pitched tones instead of sub-audio rumble/noise.
        var midi = midiNote
        if (midi < 36) {
            midi += 60 // move into mid register (e.g. C4 = 60)
        }
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    it.flush()
                    it.stop()
                    it.release()
                } catch (_: IllegalStateException) { /* Can happen if track is already released */ }
            }
        }
        audioTrack = null
    }
}
