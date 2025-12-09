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
    // Notes:
    // - sampleRate: Standard-CD-quality sampling rate. Higher rates increase CPU but give more bandwidth.
    // - Keeping 44100 is a good trade-off on mobile devices for CPU vs quality.
    @Volatile private var isPlaying = false

    // Live sound parameters (can be changed at runtime)
    /**
     * drumLevel: globale Multiplikator für alle Drum-Elemente (Kick/Snare/HiHat).
     * - 1.0 = neutral (Original-Lautstärke), <1.0 = leiser, >1.0 = lauter
     * - Wird zur Laufzeit angepasst (Settings -> Drum Level) und direkt im Audio-Thread verwendet.
     */
    @Volatile var drumLevel: Double = 1.0
    /**
     * envelopeScale: skaliert Hüllkurven/Amplituden von Percussion und einigen Effekten.
     * - Werte um 1.0 sind normal; größere Werte verlängern teilweise Sustain/Release in den verwendeten Envelopes
     * - Wird z.B. beim Kick/Snare/HiHat und bei der Piano-Envelope eingesetzt
     */
    @Volatile var envelopeScale: Double = 1.0
    /**
     * hiHatHighpass: Einflussfaktor im HiHat-Generator
     * - wirkt wie ein einfacher Hochpass/Detail-Filter auf das generierte Rauschen der HiHat
     * - Werte >1 betonen die hohen Frequenzen; Werte <1 dämpfen die Höhen
     */
    @Volatile var hiHatHighpass: Double = 1.0
    // Multiplier applied to final buffer for the current preset (1.0 default)
    /**
     * voiceGain: globaler Make-up-Gain für das aktuell gewählte Instrument-Preset
     * - nützlich um z.B. Piano lauter zu machen oder Overdrive abzusenken
     */
    @Volatile private var voiceGain: Double = 1.0

    // Voice preset (CLEAN, OVERDRIVE, PIANO) - default CLEAN
    // Set a fixed per-preset makeup gain (no user override): Clean=1.0, Overdrive=0.9, Piano=1.5
    @Volatile var voicePreset: com.metaview.chordprogressionhelper.data.SoundPreset = com.metaview.chordprogressionhelper.data.SoundPreset.CLEAN
        set(value) {
            field = value
            try {
                voiceGain = when (value) {
                    com.metaview.chordprogressionhelper.data.SoundPreset.CLEAN -> 1.0
                    com.metaview.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.9
                    com.metaview.chordprogressionhelper.data.SoundPreset.PIANO -> 1.5
                }
            } catch (_: Exception) {
                // ignore
            }
        }

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
        // - minBufferSize: the platform's recommended minimum audio buffer (in bytes). We allocate a multiple
        //   to prevent underruns if we produce chunks smaller than the hardware prefers.
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
                val samples = buffer.toPcmShortArray()
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
                    val currentStrum = measure.strummingPattern.strums[strumIndex]

                    // Debug logging to trace issues where preview doesn't sound the first chord
                    try {
                        android.util.Log.d("AudioPlayer", "playProgression: measure=$measureIndex strum=$strumIndex currentStrum=$currentStrum chord=${chord?.getDisplayName()}")
                    } catch (_: Exception) {}

                    // Note: keep REST silent even if a new chord event starts on that strum.
                    // Previously we forced REST->DOWN when a chord event started at this strum, but
                    // users expect a REST to produce silence. Do not override REST here.

                    when (currentStrum) {
                        Strum.DOWN, Strum.UP -> {
                            val frequencies = chord?.getMidiNotes()?.map { midiNoteToFrequency(it) }
                            activeStrings = if (frequencies != null) {
                                val sorted = frequencies.sorted().let { if (currentStrum == Strum.UP) it.asReversed() else it }
                                if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) {
                                    // For piano, add extra octave and sub-octave strings for fullness and longer sustain
                                    sorted.flatMap { freq ->
                                        listOf(
                                            KarplusStrongString(freq, sampleRate, pluckStrength, 0.9992).apply { pluck() },
                                            KarplusStrongString(freq * 2.002, sampleRate, pluckStrength, 0.999).apply { pluck() },
                                            KarplusStrongString(freq / 2.0, sampleRate, pluckStrength, 0.997).apply { pluck() }
                                        )
                                    }
                                } else {
                                    // Default single string per note
                                    sorted.map { freq -> KarplusStrongString(freq, sampleRate, pluckStrength, 0.998).apply { pluck() } }
                                }
                            } else {
                                emptyList()
                            }
                            try { android.util.Log.d("AudioPlayer", "activeStrings after pluck: ${activeStrings.size}") } catch (_: Exception) {}
                        }
                        Strum.REST, Strum.MUTE -> activeStrings = emptyList()
                        Strum.LETRING -> { /* Do nothing, let strings ring */ }
                    }

                    val buffer = DoubleArray(eighthNoteSamples)

                    if (currentStrum == Strum.MUTE) addMute(buffer)
                    else {
                        // We'll apply a small final filter and preset-specific shaping
                        when (voicePreset) {
                            com.metaview.chordprogressionhelper.data.SoundPreset.CLEAN -> {
                                var prevLP = 0.0
                                val lpAlpha = 0.12 // gentle lowpass to remove harsh HF
                                for (i in buffer.indices) {
                                    var sample = 0.0
                                    for (string in activeStrings) sample += string.tick()
                                    val filtered = prevLP + lpAlpha * (sample - prevLP)
                                    prevLP = filtered
                                    buffer[i] += filtered
                                }
                            }
                            com.metaview.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> {
                                // Overdrive / Distortion parameters
                                // - gain: Eingangsverstärkung vor Nonlinearität (tanh) -> bestimmt, wie stark die Signalform gekrümmt wird
                                // - mix: Verhältnis zwischen 'driven' (verzerrt) und 'clean' Signalanteil
                                // - cubic: kleine zusätzliche nicht-lineare Harmonische, um den Klang 'körperreicher' zu machen
                                // Tipps:
                                //  - gain erhöhen -> mehr Obertöne / härtere Verzerrung
                                //  - mix erhöhen -> mehr verzerrtes Signal im Ausgang
                                val gain = 3.5
                                val mix = 0.8
                                var prevLP = 0.0
                                val lpAlpha = 0.10
                                for (i in buffer.indices) {
                                    var sample = 0.0
                                    for (string in activeStrings) sample += string.tick()
                                    // soft clip
                                    val driven = tanh(sample * gain)
                                    // reduced cubic harmonic contribution
                                    // cubic term: sehr kleiner Anteil zur Erzeugung zusätzlicher Obertöne
                                    val cubic = 0.02 * (sample * gain * sample * gain * sample * gain)
                                    val out = mix * driven + (1.0 - mix) * sample + cubic
                                    val filtered = prevLP + lpAlpha * (out - prevLP)
                                    prevLP = filtered
                                    // slightly reduce make-up for overdrive so it sits better in the mix
                                    buffer[i] += filtered * 0.9
                                }
                            }
                            com.metaview.chordprogressionhelper.data.SoundPreset.PIANO -> {
                                // Piano synthesis parameters and rationale:
                                // - For a simple synthetic 'piano' we use multiple Karplus-Strong strings:
                                //   base frequency, octave (x2.002) and sub-octave (/2). This adds harmonic richness.
                                // - lpAlpha: per-sample lowpass smoothing to emulate the body/warmth of a piano tone
                                // - env: envelope controlling release/sustain. pow(0.85) produces a slower decay than a linear curve.
                                // - attackBoost: short initial boost to simulate hammer attack on the strings
                                // - final make-up (1.35): raises overall piano level so it is audible without strong drum backing
                                // Notes for tweaking:
                                //  - Increase envelope exponent (<1) to lengthen sustain further
                                //  - Increase lpAlpha to reduce high frequency brilliance
                                var prevLP = 0.0
                                val lpAlpha = 0.05 // smoother lowpass for piano
                                for (i in buffer.indices) {
                                    var sample = 0.0
                                    for (string in activeStrings) sample += string.tick()
                                    // longer release envelope for piano-like sustain (slower decay)
                                    val env = ((1.0 - i.toDouble() / buffer.size).coerceIn(0.0, 1.0)).pow(0.9) * (0.95 + 0.15 * envelopeScale)
                                    val attackBoost = if (i < (buffer.size * 0.03).toInt()) 1.35 else 1.0
                                    val raw = sample * env * attackBoost
                                    val filtered = prevLP + lpAlpha * (raw - prevLP)
                                    prevLP = filtered
                                    // apply a mild make-up so piano cuts through without needing extra drums
                                    buffer[i] += filtered * 1.35
                                }
                            }
                        }
                    }

                    // Percussion: use the drum pattern attached to the current measure.
                    // A DrumPattern contains a list of DrumStep for eighth-note positions. Multiple instruments
                    // (kick/snare/hiHat) may be set on the same eighth; play them together when present.
                    try {
                        // Update the drum pattern dynamically during playback
                        val updatedDrumPattern = progression.measures[measureIndex].drumPattern
                        // Ensure the updated pattern is used immediately
                        val stepIndex = if (updatedDrumPattern.steps.isNotEmpty()) (strumIndex % updatedDrumPattern.steps.size) else strumIndex % 8
                        val drumStep = updatedDrumPattern.steps.getOrNull(stepIndex) ?: com.metaview.chordprogressionhelper.model.DrumStep()

                        // Apply the drum pattern changes dynamically
                        val restPercussionScale = 0.25
                        val baseDrumScale = if (currentStrum == Strum.REST || currentStrum == Strum.LETRING) restPercussionScale else 1.0
                        val presetPercussionMultiplier = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 0.45 else 1.0
                        val drumScale = baseDrumScale * presetPercussionMultiplier

                        if (drumStep.hiHat) addHiHat(buffer, eighthNoteSamples, drumScale * drumLevel)
                        if (drumStep.kick) addKick(buffer, eighthNoteSamples * 2, drumScale * drumLevel)
                        if (drumStep.snare) addSnare(buffer, eighthNoteSamples * 2, drumScale * drumLevel)
                    } catch (_: Exception) {
                        // Fallback to previous simple behaviour if anything goes wrong
                        val restPercussionScale = 0.25
                        val baseDrumScale = if (currentStrum == Strum.REST || currentStrum == Strum.LETRING) restPercussionScale else 1.0
                        val presetPercussionMultiplier = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 0.45 else 1.0
                        val drumScale = baseDrumScale * presetPercussionMultiplier
                        addHiHat(buffer, eighthNoteSamples, drumScale)
                        if (strumIndex % 2 == 0) {
                            val quarterNoteIndex = strumIndex / 2
                            if (quarterNoteIndex % 2 == 0) addKick(buffer, eighthNoteSamples * 2, drumScale * if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 0.5 else 1.0)
                            if (quarterNoteIndex % 2 == 1) addSnare(buffer, eighthNoteSamples * 2, drumScale)
                        }
                    }

                    // apply preset gain before normalization
                    // normalization: compute a preset-aware normalization factor and apply headroom scaling
                    // Rationale:
                    // - Previously a fixed multiplier (0.7) scaled down signals proportionally to the number of active strings.
                    //   This caused presets that create multiple strings (e.g. PIANO) to be strongly attenuated, cancelling voiceGain.
                    // - To let presets like PIANO be louder, use a smaller per-string attenuation for PIANO.
                    val presetNormalizationMultiplier = when (voicePreset) {
                        com.metaview.chordprogressionhelper.data.SoundPreset.PIANO -> 0.30 // less attenuation per string for piano (was 0.45)
                        com.metaview.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.7
                        else -> 0.7
                    }
                    val normalizationFactor = (activeStrings.size) * presetNormalizationMultiplier + 1.0

                    // Apply voiceGain before final normalization so intended make-up gain affects peak
                    if (voiceGain != 1.0) { for (i in buffer.indices) buffer[i] = buffer[i] * voiceGain }

                    // Compute post-normalization peak and add headroom scaling to avoid clipping while respecting voiceGain
                    var maxAbs = 0.0
                    for (v in buffer) { val a = kotlin.math.abs(v); if (a > maxAbs) maxAbs = a }
                    val postNormPeak = if (normalizationFactor > 0.0) maxAbs / normalizationFactor else maxAbs
                    val headroom = if (postNormPeak > 0.99) 0.99 / postNormPeak else 1.0

                    // Final samples: divide by normalizationFactor and apply additional headroom scale
                    val samples = buffer.map { v -> v / normalizationFactor * headroom }.toDoubleArray().toPcmShortArray()
                    try {
                        val peakInfo = "measure=$measureIndex strum=$strumIndex activeStrings=${activeStrings.size} normalizationFactor=$normalizationFactor postNormPeak=$postNormPeak headroom=$headroom"
                        android.util.Log.d("AudioPlayer", "bufferDebug: $peakInfo")
                    } catch (_: Exception) {}
                     audioTrack?.write(samples, 0, samples.size)
                 }
             }
             isFirstLoop = false
             if (!isPlaying) break
         } while (shouldLoop() && isPlaying)

    }

    suspend fun previewChord(chord: Chord, pluckStrength: Int) = withContext(Dispatchers.IO) {
        // previewDuration (seconds): how long a single chord preview should sound.
        // - 1.0s is a short but audible preview. Increasing gives longer sustain for testing/enjoyment.
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

        // Apply preset-aware normalization similar to real playback so preview loudness matches
        val presetNormalizationMultiplier = when (voicePreset) {
            com.metaview.chordprogressionhelper.data.SoundPreset.PIANO -> 0.30
            com.metaview.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.7
            else -> 0.7
        }
        val normalizationFactor = (frequencies.size) * presetNormalizationMultiplier + 1.0

        // Apply voiceGain before normalization so make-up gain is meaningful
        if (voiceGain != 1.0) { for (i in buffer.indices) buffer[i] = buffer[i] * voiceGain }

        // Compute peak and headroom
        var maxAbs = 0.0
        for (v in buffer) { val a = kotlin.math.abs(v); if (a > maxAbs) maxAbs = a }
        val postNormPeak = if (normalizationFactor > 0.0) maxAbs / normalizationFactor else maxAbs
        val headroom = if (postNormPeak > 0.99) 0.99 / postNormPeak else 1.0

        val samples = buffer.map { it / normalizationFactor * headroom }.toDoubleArray().toPcmShortArray()

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

    // Short percussion preview helpers: generate a short buffer containing the requested percussion
    suspend fun previewKick(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        val previewDuration = 0.25 // 250ms should be enough to hear the transient
        val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(64)
        val buffer = DoubleArray(numSamples)
        try {
            addKick(buffer, numSamples, levelScale)
        } catch (_: Exception) {}
        val samples = buffer.toPcmShortArray()
        var at: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                .build()
            at.play()
            at.write(samples, 0, samples.size)
            kotlinx.coroutines.delay((previewDuration * 1000).toLong())
        } finally {
            try { at?.stop() } catch (_: Exception) {}
            try { at?.release() } catch (_: Exception) {}
        }
    }

    suspend fun previewSnare(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        val previewDuration = 0.22
        val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(64)
        val buffer = DoubleArray(numSamples)
        try { addSnare(buffer, numSamples, levelScale) } catch (_: Exception) {}
        val samples = buffer.toPcmShortArray()
        var at: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                .build()
            at.play()
            at.write(samples, 0, samples.size)
            kotlinx.coroutines.delay((previewDuration * 1000).toLong())
        } finally {
            try { at?.stop() } catch (_: Exception) {}
            try { at?.release() } catch (_: Exception) {}
        }
    }

    suspend fun previewHiHat(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        val previewDuration = 0.12
        val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(32)
        val buffer = DoubleArray(numSamples)
        try { addHiHat(buffer, numSamples, levelScale) } catch (_: Exception) {}
        val samples = buffer.toPcmShortArray()
        var at: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                .build()
            at.play()
            at.write(samples, 0, samples.size)
            kotlinx.coroutines.delay((previewDuration * 1000).toLong())
        } finally {
            try { at?.stop() } catch (_: Exception) {}
            try { at?.release() } catch (_: Exception) {}
        }
    }

    // Convert a DoubleArray (values roughly in -1..1) to 16-bit PCM ShortArray.
    private fun DoubleArray.toPcmShortArray(): ShortArray {
        val shortArray = ShortArray(this.size)
        for (i in this.indices) {
            val sample = this[i].coerceIn(-1.0, 1.0)
            shortArray[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return shortArray
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

    /**
     * addKick
     * - duration: number of samples to generate the kick for
     * - levelScale: additional multiplicative scale specifically for this kick call (0..1 typical)
     * Implementation details:
     * - Uses a low sine-like tone whose frequency drops slightly over the envelope to mimic an acoustic kick
     * - envelope uses pow(4) to get a fast initial transient and then a quick decay
     * - multiplied by envelopeScale (global) and drumLevel (global)
     */
    private fun addKick(buffer: DoubleArray, duration: Int, levelScale: Double = 1.0) {
        val freq = 60.0
        val kickDuration = (duration * 0.5).toInt().coerceAtMost(buffer.size)
        for (i in 0 until kickDuration) {
            val progress = i.toDouble() / kickDuration
            val envelope = (1.0 - progress).pow(4) * envelopeScale
            val angle = 2.0 * PI * i * (freq * (1.0 - progress * 0.5)) / sampleRate
            // use live drumLevel
            // 3.6: empirical multiplier to make the synthesized kick audible in the mix
            // - If the kick is too loud, reduce this (or reduce drumLevel). If too weak, increase.
            buffer[i] += sin(angle) * envelope * 3.6 * drumLevel * levelScale
        }
    }

    /**
     * addSnare
     * - creates short noise burst shaped by an envelope
     * - levelScale: multiplicative scale for loudness
     */
    private fun addSnare(buffer: DoubleArray, duration: Int, levelScale: Double = 1.0) {
        val snareDuration = (duration * 0.2).toInt().coerceAtMost(buffer.size)
        for (i in 0 until snareDuration) {
            val noise = (Random.nextDouble() * 2 - 1)
            val envelope = (1.0 - i.toDouble() / snareDuration).pow(2) * envelopeScale
            // 1.4: snare noise gain — empirical value to sit snare in the mix
            buffer[i] += noise * envelope * 1.4 * drumLevel * levelScale
        }
    }

    /**
     * addHiHat
     * - generates short high-frequency noise bursts, with a simple high-pass effect via `hiHatHighpass`
     * - envelopeScale and levelScale control the perceived length and loudness
     */
    private fun addHiHat(buffer: DoubleArray, duration: Int, levelScale: Double = 1.0) {
        val hiHatDuration = (duration * 0.25).toInt().coerceAtMost(buffer.size)
        var lastNoise = 0.0
        for (i in 0 until hiHatDuration) {
            val white = Random.nextDouble() * 2 - 1
            val highPass = (white - lastNoise) * hiHatHighpass
            lastNoise = white
            val envelope = (1.0 - i.toDouble() / hiHatDuration).pow(2) * envelopeScale
            // 1.2: hi-hat gain factor (empirical). hiHatHighpass shapes HF content; envelopeScale controls length
            buffer[i] += highPass * envelope * 1.2 * levelScale
        }
    }

    @Suppress("unused")
    // Karplus-Strong string implementation parameters:
    // - bufferSize = sampleRate / frequency -> controls the fundamental pitch (integer delay line length)
    // - decay: feedback multiplier (near 1.0 -> longer sustain)
    // - pluckStrength: controls initial noise smoothing; higher values produce softer attacks
    private class KarplusStrongString(frequency: Double, sampleRate: Int, pluckStrength: Int, decay: Double = 0.998) {
        private val frequencyHz = frequency
        private val sampleRateHz = sampleRate
        private val pluckStrengthLevel = pluckStrength
        private val bufferSize = (sampleRateHz / frequencyHz).toInt().coerceAtLeast(1)
        // bufferSize notes:
        // - Because bufferSize must be integer, the frequency resolution is limited; this is typical for KS synthesis.
        // - Very low frequencies result in large buffers; we coerce to at least 1 to avoid division by zero.
        private val ringBuffer = DoubleArray(bufferSize)
        private var currentIndex = 0
        private val decayFactor = decay

        fun pluck() {
            val whiteNoise = DoubleArray(bufferSize) { Random.nextDouble() * 2 - 1 }
            // pluckStrengthLevel meanings:
            //  - 1: hard pluck (full white noise)
            //  - 3: soft pluck (averaged noise -> rounder attack)
            //  - other: intermediate smoothing
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
