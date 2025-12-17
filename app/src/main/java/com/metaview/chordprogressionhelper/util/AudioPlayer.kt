@file:OptIn(InternalSerializationApi::class)

package com.metaview.chordprogressionhelper.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
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
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred

@OptIn(InternalSerializationApi::class)
class AudioPlayer {
    // helper for lowpass update to avoid numeric overload ambiguity in nested lambdas
    private fun lpFilter(prev: Double, alpha: Double, value: Double): Double = prev + alpha * (value - prev)

    // Dedicated audio thread and handler
    @Volatile private var audioHandlerThread: HandlerThread? = null
    @Volatile private var audioHandler: Handler? = null

    // Notes:
    // - sampleRate: Standard-CD-quality sampling rate. Higher rates increase CPU but give more bandwidth.
    // - Keeping 44100 is a good trade-off on mobile devices for CPU vs quality.
    private val sampleRate = 44100
    // Reusable buffers to avoid allocations in hot path
    // previewBuffer: 1s buffer for previews (max 44100 samples)
    private val previewBuffer = DoubleArray(sampleRate)
    // reuse for per-eighth buffers; resized when needed
    private var reusableEighthBuffer = DoubleArray(0)

    private fun ensureAudioThreadStarted() {
        synchronized(this) {
            if (audioHandlerThread?.isAlive != true) {
                audioHandlerThread = HandlerThread("AudioThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
                audioHandler = Handler(audioHandlerThread!!.looper)
                // Create a small cached preview AudioTrack on the audio thread to warm-up resources.
                audioHandler!!.post {
                    try {
                        if (previewAudioTrack == null) {
                            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                            previewAudioTrack = AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .setBufferSizeInBytes(maxOf(minBuf, sampleRate / 4)) // small buffer (250ms) for quick previews
                                .build()
                            try { previewAudioTrack?.play() } catch (_: Exception) {}
                        }
                    } catch (_: Throwable) { previewAudioTrack = null }
                }
            }
        }
    }

    private fun shutdownAudioThread() {
        synchronized(this) {
            try {
                audioHandlerThread?.quitSafely()
                audioHandlerThread = null
                audioHandler = null
            } catch (_: Throwable) {}
        }
    }

    private var audioTrack: AudioTrack? = null
    // Reusable small AudioTrack used for quick previews (chords/drums) to avoid allocating
    // a fresh AudioTrack on every preview call which is expensive and causes audible delay.
    @Volatile private var previewAudioTrack: AudioTrack? = null
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
    // Persisted low-pass filter state across buffers (single value because only one preset plays at a time)
    private var prevLP = 0.0
    /**
     * strokeOffsetMs: delay applied to UP strokes in milliseconds to audibly distinguish them from DOWN strokes.
     * - 0 (default) = no offset
     * - positive value delays the pluck of UP strokes by that many milliseconds within the eighth-note buffer
     */
    @Volatile var upStrokeOffsetMs: Int = 30
    /**
     * stringStaggerMs: additional delay applied between successive strings (in milliseconds)
     * when staggering plucks inside a single strum. Default 8 ms.
     */
    @Volatile var upStringStaggerMs: Int = 8
    /**
     * downStrokeOffsetMs / downStringStaggerMs: equivalent settings for Down strokes.
     * Defaults are 0 (no offset/stagger) to preserve previous behavior unless enabled.
     */
    @Volatile var downStrokeOffsetMs: Int = 0
    @Volatile var downStringStaggerMs: Int = 0

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
    ) {
        if (isPlaying) return
        ensureAudioThreadStarted()

        val deferred = CompletableDeferred<Unit>()
        // Capture params for posted runnable
        audioHandler!!.post {
            try {
                // run original audio loop inside audio thread
                // Create AudioTrack once; we'll write buffers of varying lengths when tempo changes
                val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .build()
                audioTrack?.play()

                // Ensure any residual audio data from previous runs is cleared before starting playback.
                try { if (reusableEighthBuffer.isNotEmpty()) java.util.Arrays.fill(reusableEighthBuffer, 0.0) } catch (_: Exception) {}
                try { java.util.Arrays.fill(previewBuffer, 0.0) } catch (_: Exception) {}

                isPlaying = true
                var activeStrings: List<KarplusStrongString> = emptyList()

                if (countInBeats > 0 && !isResuming) {
                    repeat(countInBeats) {
                        if (!isPlaying) return@repeat
                        val beatDuration = 60.0 / progression.tempo
                        val eighthNoteDuration = beatDuration / 2.0
                        val eighthNoteSamples = (sampleRate * eighthNoteDuration).toInt().coerceAtLeast(1)
                        val b = if (reusableEighthBuffer.size >= eighthNoteSamples*2) reusableEighthBuffer else DoubleArray(eighthNoteSamples*2).also { reusableEighthBuffer = it }
                        addHiHat(b, eighthNoteSamples * 2, drumLevel)
                        val samples = b.toPcmShortArray()
                        audioTrack?.write(samples, 0, samples.size)
                    }
                }

                var isFirstLoopLocal = true
                // Only reuse persisted prevLP if we are resuming playback; otherwise start filter state at 0
                var prevLPLocal = if (isResuming) this@AudioPlayer.prevLP else 0.0
                do {
                    val loopStartMeasure = if(isFirstLoopLocal) startMeasureIndex else 0
                    for (measureIndex in loopStartMeasure until progression.measures.size) {
                        val measure = progression.measures[measureIndex]
                        if (!isPlaying) break
                        val loopStartStrum = if (isFirstLoopLocal && measureIndex == startMeasureIndex) startStrumIndex else 0
                        if (isFirstLoopLocal && measureIndex == startMeasureIndex && loopStartStrum == 0) activeStrings = emptyList()

                        for (strumIndex in loopStartStrum until measure.strummingPattern.strums.size) {
                            if (!isPlaying) break
                            val beatDuration = 60.0 / progression.tempo
                            val eighthNoteDuration = beatDuration / 2.0
                            val eighthNoteSamples = (sampleRate * eighthNoteDuration).toInt().coerceAtLeast(1)

                            onPositionChanged(measureIndex, strumIndex)

                            val chord: Chord? = measure.getChordAt(strumIndex)
                            val currentStrum = measure.strummingPattern.strums[strumIndex]

                            val (baseMs, staggerMs) = when (currentStrum) {
                                Strum.UP -> Pair(upStrokeOffsetMs, upStringStaggerMs)
                                Strum.DOWN, Strum.MUTE -> Pair(downStrokeOffsetMs, downStringStaggerMs)
                                else -> Pair(0,0)
                            }
                            val offsetSamplesForPluck = if (baseMs > 0) (baseMs * sampleRate / 1000) else 0
                            val stringStaggerSamplesForPluck = if (staggerMs > 0) (staggerMs * sampleRate / 1000) else 0
                            val effOffsetSamplesForPluck = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 0 else offsetSamplesForPluck
                            val effStringStaggerSamplesForPluck = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 0 else stringStaggerSamplesForPluck

                            if (currentStrum != Strum.LETRING) prevLPLocal = 0.0

                            // build activeStrings similar to original
                            when (currentStrum) {
                                Strum.DOWN, Strum.UP -> {
                                    val frequencies = chord?.getMidiNotes()?.map { midiNoteToFrequency(it) }
                                    if (frequencies == null) activeStrings = emptyList() else {
                                        val sorted = frequencies.sorted().let { if (currentStrum == Strum.UP) it.asReversed() else it }
                                        val shouldPluckImmediately = (effOffsetSamplesForPluck == 0 && effStringStaggerSamplesForPluck == 0)
                                        activeStrings = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) {
                                            sorted.flatMap { freq ->
                                                listOf(
                                                    KarplusStrongString(freq, sampleRate, pluckStrength, 0.9992).apply { if (shouldPluckImmediately) pluck() },
                                                    KarplusStrongString(freq * 2.002, sampleRate, pluckStrength, 0.999).apply { if (shouldPluckImmediately) pluck() },
                                                    KarplusStrongString(freq / 2.0, sampleRate, pluckStrength, 0.997).apply { if (shouldPluckImmediately) pluck() }
                                                )
                                            }
                                        } else {
                                            sorted.map { freq -> KarplusStrongString(freq, sampleRate, pluckStrength, 0.998).apply { if (shouldPluckImmediately) pluck() } }
                                        }
                                    }
                                }
                                Strum.MUTE -> {
                                    val frequencies = chord?.getMidiNotes()?.map { midiNoteToFrequency(it) }
                                    if (frequencies == null) activeStrings = emptyList() else {
                                        val sorted = frequencies.sorted()
                                        val shouldPluckImmediately = (effOffsetSamplesForPluck == 0 && effStringStaggerSamplesForPluck == 0)
                                        activeStrings = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) {
                                            sorted.flatMap { freq ->
                                                listOf(
                                                    KarplusStrongString(freq, sampleRate, pluckStrength, 0.995).apply { if (shouldPluckImmediately) pluck() },
                                                    KarplusStrongString(freq * 2.002, sampleRate, pluckStrength, 0.993).apply { if (shouldPluckImmediately) pluck() }
                                                )
                                            }
                                        } else {
                                            sorted.map { freq -> KarplusStrongString(freq, sampleRate, pluckStrength, 0.985).apply { if (shouldPluckImmediately) pluck() } }
                                        }
                                    }
                                }
                                Strum.REST -> activeStrings = emptyList()
                                Strum.LETRING -> { /* do nothing */ }
                            }

                            // Reuse an allocated buffer to avoid allocations, but always respect the *current* required length.
                            // Do NOT iterate over the full backing array size because it may be larger than the
                            // requested eighthNoteSamples (causing longer audio chunks and thus ignoring tempo changes).
                            val b = if (reusableEighthBuffer.size >= eighthNoteSamples) reusableEighthBuffer else DoubleArray(eighthNoteSamples).also { reusableEighthBuffer = it }
                            val bufferLen = eighthNoteSamples

                            val presetNormalizationMultiplier = when (voicePreset) {
                                com.metaview.chordprogressionhelper.data.SoundPreset.PIANO -> 0.30
                                com.metaview.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.5
                                else -> 0.8
                            }

                            // scheduling arrays
                            var scheduledPlucks = IntArray(0)
                            var pluckedFlags = BooleanArray(0)
                            fun initPluckSchedules() {
                                val n = activeStrings.size
                                scheduledPlucks = IntArray(n)
                                pluckedFlags = BooleanArray(n)
                                val base = effOffsetSamplesForPluck.coerceAtLeast(0)
                                val stagger = effStringStaggerSamplesForPluck.coerceAtLeast(0)
                                val groupSize = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 3 else 1
                                for (j in 0 until n) {
                                    if (base == 0 && stagger == 0) {
                                        scheduledPlucks[j] = Int.MAX_VALUE
                                    } else {
                                        if (groupSize == 1) {
                                            scheduledPlucks[j] = (base + j * stagger).coerceAtMost(bufferLen - 1)
                                        } else {
                                            val noteIndex = j / groupSize; val intraIndex = j % groupSize
                                            val primary = base + noteIndex * stagger
                                            val intra = (intraIndex - 1) * stagger / 3
                                            scheduledPlucks[j] = (primary + intra).coerceAtMost(bufferLen - 1)
                                        }
                                    }
                                    pluckedFlags[j] = false
                                }
                            }
                            fun performScheduledPlucksAt(i: Int) {
                                if (scheduledPlucks.isEmpty()) return
                                for (j in scheduledPlucks.indices) if (!pluckedFlags[j] && i >= scheduledPlucks[j]) { try{ activeStrings[j].pluck() } catch (_: Exception){}; pluckedFlags[j]=true }
                            }

                            when (voicePreset) {
                                com.metaview.chordprogressionhelper.data.SoundPreset.CLEAN -> {
                                    val lpAlpha = if (currentStrum == Strum.MUTE) 0.03 else 0.12
                                    initPluckSchedules()
                                    for (i in 0 until bufferLen) {
                                        performScheduledPlucksAt(i)
                                        var sample = 0.0
                                        for (s in activeStrings) sample += s.tick()
                                        val filtered = prevLPLocal + lpAlpha * (sample.toDouble() - prevLPLocal.toDouble())
                                        prevLPLocal = filtered
                                        b[i] = filtered * 1.1
                                    }
                                }
                                com.metaview.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> {
                                    val gain = 3.0; val mix = 0.8; val cubicCoeff = 0.01
                                    val lpAlpha = if (currentStrum == Strum.MUTE) 0.05 else 0.2
                                    initPluckSchedules()
                                    for (i in 0 until bufferLen) {
                                        performScheduledPlucksAt(i)
                                        var sample = 0.0
                                        for (s in activeStrings) sample += s.tick()
                                        val driven = tanh(sample * gain)
                                        val cubic = cubicCoeff * (sample * gain * sample * gain * sample * gain)
                                        val out = mix * driven + (1.0 - mix) * sample + cubic
                                        val filtered = prevLPLocal + lpAlpha * (out.toDouble() - prevLPLocal.toDouble())
                                        prevLPLocal = filtered
                                        b[i] = filtered * 0.8
                                    }
                                }
                                com.metaview.chordprogressionhelper.data.SoundPreset.PIANO -> {
                                    val lpAlpha = if (currentStrum == Strum.MUTE) 0.03 else 0.1
                                    initPluckSchedules()
                                    for (i in 0 until bufferLen) {
                                        performScheduledPlucksAt(i)
                                        var sample = 0.0
                                        for (s in activeStrings) sample += s.tick()
                                        var raw = sample
                                        if (currentStrum != Strum.LETRING) {
                                            // neuen Anschlag erzeugen, wenn wir nicht ausklingen lassen
                                            val env = ((1.0 - i.toDouble() / bufferLen).coerceIn(
                                                0.0,
                                                1.0
                                            )).pow(0.9) * (0.95 + 0.15 * envelopeScale)
                                            val attackBoost = if (i < (bufferLen * 0.03).toInt()) 1.35 else 1.0
                                            raw = sample * env * attackBoost
                                        }
                                        val filtered = prevLPLocal + lpAlpha * (raw.toDouble() - prevLPLocal.toDouble())
                                        prevLPLocal = filtered
                                        b[i] = filtered
                                    }
                                }
                            }

                            if (currentStrum == Strum.MUTE) addMutePercussive(b)

                            try {
                                val updatedDrumPattern = progression.measures[measureIndex].drumPattern
                                val stepIndex = if (updatedDrumPattern.steps.isNotEmpty()) (strumIndex % updatedDrumPattern.steps.size) else strumIndex % 8
                                val drumStep = updatedDrumPattern.steps.getOrNull(stepIndex) ?: com.metaview.chordprogressionhelper.model.DrumStep()
                                val restPercussionScale = 0.25
                                val baseDrumScale = if (currentStrum == Strum.REST || currentStrum == Strum.LETRING) restPercussionScale else 1.0
                                val presetPercussionMultiplier = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 0.45 else 1.0
                                val drumScale = baseDrumScale * presetPercussionMultiplier
                                if (drumStep.hiHat) addHiHat(b, eighthNoteSamples, drumScale * drumLevel)
                                if (drumStep.kick) addKick(b, eighthNoteSamples * 2, drumScale * drumLevel)
                                if (drumStep.snare) addSnare(b, eighthNoteSamples * 2, drumScale * drumLevel)
                            } catch (_: Exception) {
                                val restPercussionScale = 0.25
                                val baseDrumScale = if (currentStrum == Strum.REST || currentStrum == Strum.LETRING) restPercussionScale else 1.0
                                val presetPercussionMultiplier = if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 0.45 else 1.0
                                val drumScale = baseDrumScale * presetPercussionMultiplier
                                addHiHat(b, eighthNoteSamples, drumScale)
                                if (strumIndex % 2 == 0) {
                                    val quarterNoteIndex = strumIndex / 2
                                    if (quarterNoteIndex % 2 == 0) addKick(b, eighthNoteSamples * 2, drumScale * if (voicePreset == com.metaview.chordprogressionhelper.data.SoundPreset.PIANO) 0.5 else 1.0)
                                    if (quarterNoteIndex % 2 == 1) addSnare(b, eighthNoteSamples * 2, drumScale)
                                }
                            }

                            val normalizationFactor = (activeStrings.size) * presetNormalizationMultiplier + 1.0
                            if (voiceGain != 1.0) for (i in 0 until bufferLen) b[i] = b[i] * voiceGain
                            var maxAbs = 0.0
                            for (i in 0 until bufferLen) { val a = kotlin.math.abs(b[i]); if (a > maxAbs) maxAbs = a }
                            val postNormPeak = if (normalizationFactor > 0.0) maxAbs / normalizationFactor else maxAbs
                            val headroom = if (postNormPeak > 0.99) 0.99 / postNormPeak else 1.0
                            val trimmed = b.copyOfRange(0, bufferLen)
                            val samples = trimmed.map { v -> v / normalizationFactor * headroom }.toDoubleArray().toPcmShortArray()
                            audioTrack?.write(samples, 0, samples.size)
                        }
                    }
                    isFirstLoopLocal = false
                    if (!isPlaying) break
                } while (shouldLoop() && isPlaying)

                // persist last prevLP into class field
                this@AudioPlayer.prevLP = prevLPLocal

                deferred.complete(Unit)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }

        // wait for audio thread to finish scheduling the run
        deferred.await()
    }

    suspend fun previewChord(chord: Chord, pluckStrength: Int) = withContext(Dispatchers.IO) {
        // Generate and play preview on the audio thread using the cached previewAudioTrack to minimize latency.
        ensureAudioThreadStarted()
        val deferred = CompletableDeferred<Unit>()
        audioHandler!!.post {
            try {
                val previewDuration = 1.0
                val numSamples = (sampleRate * previewDuration).toInt()
                val frequencies = chord.getMidiNotes().map { midiNoteToFrequency(it) }
                val strings = frequencies.map { KarplusStrongString(it, sampleRate, pluckStrength).apply { pluck() } }
                val buf = previewBuffer
                for (i in 0 until numSamples) {
                    var sample = 0.0
                    for (s in strings) sample += s.tick()
                    buf[i] = sample
                }
                val presetNormalizationMultiplier = when (voicePreset) {
                    com.metaview.chordprogressionhelper.data.SoundPreset.PIANO -> 0.30
                    com.metaview.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.7
                    else -> 0.7
                }
                val normalizationFactor = (frequencies.size) * presetNormalizationMultiplier + 1.0
                if (voiceGain != 1.0) for (i in 0 until numSamples) buf[i] = buf[i] * voiceGain
                var maxAbs = 0.0
                for (i in 0 until numSamples) { val a = kotlin.math.abs(buf[i]); if (a > maxAbs) maxAbs = a }
                val postNormPeak = if (normalizationFactor > 0.0) maxAbs / normalizationFactor else maxAbs
                val headroom = if (postNormPeak > 0.99) 0.99 / postNormPeak else 1.0
                val samples = buf.copyOfRange(0, numSamples).map { it / normalizationFactor * headroom }.toDoubleArray().toPcmShortArray()

                // Use cached previewAudioTrack if available
                val at = previewAudioTrack
                if (at != null) {
                    try {
                        at.flush()
                    } catch (_: Exception) {}
                    at.write(samples, 0, samples.size)
                    // Keep the thread sleeping for the playback duration so callers that await this suspend function
                    // get the previous blocking behavior. The sleep runs on the audio thread so it doesn't block UI.
                    try { Thread.sleep((previewDuration * 1000).toLong()) } catch (_: Exception) {}
                } else {
                    // Fallback: create a temporary track if cached one is not available
                    var temp: AudioTrack? = null
                    try {
                        val previewMinBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        temp = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            // samples.size is number of PCM samples (shorts) - convert to bytes; ensure at least min buffer size
                            .setBufferSizeInBytes(maxOf(previewMinBuffer, samples.size * 2))
                            .build()

                        temp.play()
                        temp.write(samples, 0, samples.size)
                        Thread.sleep((previewDuration * 1000).toLong())
                    } finally { try { temp?.stop() } catch (_: Exception) {}; try { temp?.release() } catch (_: Exception) {} }
                }

                deferred.complete(Unit)
            } catch (t: Throwable) { deferred.completeExceptionally(t) }
        }
        deferred.await()
    }

    suspend fun previewKick(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        ensureAudioThreadStarted()
        val deferred = CompletableDeferred<Unit>()
        audioHandler!!.post {
            try {
                val previewDuration = 0.25
                val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(64)
                previewBuffer.fill(0.0)
                val buf = previewBuffer
                addKick(buf, numSamples, levelScale)
                val samples = buf.copyOfRange(0, numSamples).toPcmShortArray()
                val at = previewAudioTrack
                if (at != null) {
                    try { at.flush() } catch (_: Exception) {}
                    at.write(samples, 0, samples.size)
                    try { Thread.sleep((previewDuration*1000).toLong()) } catch (_: Exception) {}
                } else {
                    // fallback to temporary track
                    var tmp: AudioTrack? = null
                    try {
                        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        tmp = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                            .build()
                        tmp.play(); tmp.write(samples, 0, samples.size); try { Thread.sleep((previewDuration*1000).toLong()) } catch (_: Exception) {}
                    } finally { try { tmp?.stop() } catch (_: Exception) {} ; try { tmp?.release() } catch (_: Exception) {} }
                }
                deferred.complete(Unit)
            } catch (t: Throwable) { deferred.completeExceptionally(t) }
        }
        deferred.await()
    }

    suspend fun previewSnare(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        ensureAudioThreadStarted()
        val deferred = CompletableDeferred<Unit>()
        audioHandler!!.post {
            try {
                val previewDuration = 0.22
                val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(64)
                previewBuffer.fill(0.0)
                val buf = previewBuffer
                addSnare(buf, numSamples, levelScale)
                val samples = buf.copyOfRange(0, numSamples).toPcmShortArray()
                val at = previewAudioTrack
                if (at != null) {
                    try { at.flush() } catch (_: Exception) {}
                    at.write(samples, 0, samples.size)
                    try { Thread.sleep((previewDuration*1000).toLong()) } catch (_: Exception) {}
                } else {
                    var tmp: AudioTrack? = null
                    try {
                        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        tmp = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                            .build()
                        tmp.play(); tmp.write(samples, 0, samples.size); try { Thread.sleep((previewDuration*1000).toLong()) } catch (_: Exception) {}
                    } finally { try { tmp?.stop() } catch (_: Exception) {}; try { tmp?.release() } catch (_: Exception) {} }
                }
                deferred.complete(Unit)
            } catch (t: Throwable) { deferred.completeExceptionally(t) }
        }
        deferred.await()
    }

    suspend fun previewHiHat(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        ensureAudioThreadStarted()
        val deferred = CompletableDeferred<Unit>()
        audioHandler!!.post {
            try {
                val previewDuration = 0.12
                val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(32)
                previewBuffer.fill(0.0)
                val buf = previewBuffer
                addHiHat(buf, numSamples, levelScale)
                val samples = buf.copyOfRange(0, numSamples).toPcmShortArray()
                val at = previewAudioTrack
                if (at != null) {
                    try { at.flush() } catch (_: Exception) {}
                    at.write(samples, 0, samples.size)
                    try { Thread.sleep((previewDuration*1000).toLong()) } catch (_: Exception) {}
                } else {
                    var tmp: AudioTrack? = null
                    try {
                        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        tmp = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                            .build()
                        tmp.play(); tmp.write(samples, 0, samples.size); try { Thread.sleep((previewDuration*1000).toLong()) } catch (_: Exception) {}
                    } finally { try { tmp?.stop() } catch (_: Exception) {}; try { tmp?.release() } catch (_: Exception) {} }
                }
                deferred.complete(Unit)
            } catch (t: Throwable) { deferred.completeExceptionally(t) }
        }
        deferred.await()
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

    // Small percussive transient used specifically to add a short 'thud' to palm-muted strums
    private fun addMutePercussive(buffer: DoubleArray) {
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
            // smaller magnitude than full drum to keep it subtle
            buffer[i] += band * env * 0.2 * drumLevel
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
        // Also stop and release previewAudioTrack if present
        try {
            previewAudioTrack?.let {
                try { it.flush() } catch (_: Exception) {}
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        previewAudioTrack = null
         // Reset persisted lowpass filter state so subsequent starts/count-ins don't inherit DC/offset
         try { this.prevLP = 0.0 } catch (_: Exception) {}
        // Clear reusable buffers so a subsequent start cannot reuse stale sample data
        try { if (reusableEighthBuffer.isNotEmpty()) java.util.Arrays.fill(reusableEighthBuffer, 0.0) } catch (_: Exception) {}
        try { java.util.Arrays.fill(previewBuffer, 0.0) } catch (_: Exception) {}
    }
}
