package com.metaview.chordprogressionhelper.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.metaview.chordprogressionhelper.model.Chord
import com.metaview.chordprogressionhelper.model.ChordProgression
import com.metaview.chordprogressionhelper.model.StrummingPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 44100
    private var isPlaying = false

    suspend fun playProgression(progression: ChordProgression) = withContext(Dispatchers.IO) {
        isPlaying = true
        val beatDuration = 60.0 / progression.tempo // Duration of one beat in seconds
        
        try {
            for (measure in progression.measures) {
                if (!isPlaying) break
                
                // Play each quarter note in the measure
                for (quarterNote in 0 until 4) {
                    if (!isPlaying) break
                    
                    val chord = measure.getChordAt(quarterNote)
                    if (chord != null) {
                        playChord(chord, beatDuration, measure.strummingPattern)
                    } else {
                        // Rest
                        Thread.sleep((beatDuration * 1000).toLong())
                    }
                }
            }
        } finally {
            isPlaying = false
            audioTrack?.release()
            audioTrack = null
        }
    }

    private fun playChord(chord: Chord, duration: Double, pattern: StrummingPattern) {
        val midiNotes = chord.getMidiNotes()
        val frequencies = midiNotes.map { midiNoteToFrequency(it) }
        
        // Generate audio samples for the chord
        val samples = generateChordSamples(frequencies, duration)
        
        // Create and configure AudioTrack
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        audioTrack?.play()
        audioTrack?.write(samples, 0, samples.size)
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun generateChordSamples(frequencies: List<Double>, duration: Double): ShortArray {
        val numSamples = (sampleRate * duration).toInt()
        val samples = ShortArray(numSamples)
        
        for (i in 0 until numSamples) {
            var sample = 0.0
            
            // Mix all frequencies
            for (freq in frequencies) {
                val angle = 2.0 * Math.PI * i * freq / sampleRate
                sample += sin(angle)
            }
            
            // Apply envelope (ADSR)
            val envelope = getEnvelope(i, numSamples)
            sample *= envelope
            
            // Normalize and convert to short
            sample = sample / frequencies.size * 0.3 // Reduce amplitude to avoid clipping
            samples[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        
        return samples
    }

    private fun getEnvelope(sample: Int, totalSamples: Int): Double {
        val attackSamples = (totalSamples * 0.05).toInt()
        val releaseSamples = (totalSamples * 0.2).toInt()
        
        return when {
            sample < attackSamples -> sample.toDouble() / attackSamples
            sample > totalSamples - releaseSamples -> 
                (totalSamples - sample).toDouble() / releaseSamples
            else -> 1.0
        }
    }

    private fun midiNoteToFrequency(midiNote: Int): Double {
        // A4 (MIDI note 69) = 440 Hz
        return 440.0 * Math.pow(2.0, (midiNote - 69) / 12.0)
    }

    fun stop() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
