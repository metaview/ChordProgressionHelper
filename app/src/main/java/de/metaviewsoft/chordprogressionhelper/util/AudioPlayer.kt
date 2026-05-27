@file:OptIn(InternalSerializationApi::class)

package de.metaviewsoft.chordprogressionhelper.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import de.metaviewsoft.chordprogressionhelper.model.Chord
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Strum
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

// Callback Interface für AudioThread-Bereitschaft
interface AudioThreadReadyCallback {
    fun onAudioThreadReady()
}

@OptIn(InternalSerializationApi::class)
class AudioPlayer {
    // Callback für AudioThread-Bereitschaft
    var audioThreadReadyCallback: AudioThreadReadyCallback? = null

    // helper for lowpass update to avoid numeric overload ambiguity in nested lambdas
    private fun lpFilter(prev: Double, alpha: Double, value: Double): Double =
        prev + alpha * (value - prev)

    // Dedicated audio thread and handler
    @Volatile
    private var audioHandlerThread: HandlerThread? = null
    @Volatile
    private var audioHandler: Handler? = null

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
                audioHandlerThread = HandlerThread(
                    "AudioThread",
                    Process.THREAD_PRIORITY_URGENT_AUDIO
                ).apply { start() }
                audioHandler = Handler(audioHandlerThread!!.looper)
                // Create a small cached preview AudioTrack on the audio thread to warm-up resources.
                audioHandler!!.post {
                    try {
                        if (previewAudioTrack == null) {
                            val minBuf = AudioTrack.getMinBufferSize(
                                sampleRate,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            // Use minBuf directly (not *2) to minimise buffering latency
                            val bufferSize = if (minBuf > 0) minBuf else sampleRate / 10
                            val builder = AudioTrack.Builder()
                                .setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_GAME)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build()
                                )
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setSampleRate(sampleRate)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                                )
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .setBufferSizeInBytes(bufferSize)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            }
                            previewAudioTrack = builder.build()
                            try {
                                previewAudioTrack?.play()
                                previewAudioTrack?.setVolume(masterVolume.toFloat())
                            } catch (e: Exception) {
                                android.util.Log.w(
                                    "AudioPlayer",
                                    "previewAudioTrack.play() failed: ${e.message}",
                                    e
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "ensureAudioThreadStarted: failed to create previewAudioTrack: ${t.message}",
                            t
                        )
                        previewAudioTrack = null
                    }
                    // Pre-generate drum samples for instant drum previews
                    // Run synchronously (blocking) on the audio thread to ensure they're ready before playback
                    val drumGenStartTime = System.currentTimeMillis()
                    try {
                        preGenerateDrumSamples()
                        val drumGenEndTime = System.currentTimeMillis()
                        android.util.Log.d(
                            "AudioPlayer",
                            "preGenerateDrumSamples took ${drumGenEndTime - drumGenStartTime}ms (synchronous)"
                        )
                    } catch (t: Throwable) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "preGenerateDrumSamples failed: ${t.message}",
                            t
                        )
                    }

                    // JIT warm-up: Pre-compile the audio playback code path by running a tiny dummy playback
                    // This ensures the first real playback won't stall due to JIT compilation
                    val warmupStartTime = System.currentTimeMillis()
                    try {
                        val warmupBuf = DoubleArray(100)  // Just 100 samples
                        addHiHat(warmupBuf, 100, drumLevel)
                        // Don't actually write to AudioTrack, just trigger code paths
                        val warmupEndTime = System.currentTimeMillis()
                        android.util.Log.d(
                            "AudioPlayer",
                            "JIT warm-up took ${warmupEndTime - warmupStartTime}ms"
                        )
                    } catch (t: Throwable) {
                        android.util.Log.w("AudioPlayer", "JIT warm-up failed: ${t.message}", t)
                    }

                    // Warm up the Handler's playback lambda to prevent JIT compilation delays on first play
                    val handlerWarmupStart = System.currentTimeMillis()
                    try {
                        audioHandler!!.post {
                            // Actually trigger the playback code paths with real audio operations
                            try {
                                if (audioTrack != null) {
                                    // Generate tiny amount of actual audio (triggers all synthesis code)
                                    val warmupBuf = DoubleArray(100)
                                    addHiHat(warmupBuf, 100, 0.1)
                                    val warmupSamples = warmupBuf.toPcmShortArray()
                                    audioTrack?.write(warmupSamples, 0, warmupSamples.size)
                                    audioTrack?.flush()
                                    // Trigger position check
                                    audioTrack?.playbackHeadPosition
                                }
                            } catch (e: Exception) {
                                android.util.Log.d("AudioPlayer", "AudioTrack warmup: ${e.message}")
                            }
                        }
                        val handlerWarmupEnd = System.currentTimeMillis()
                        android.util.Log.d(
                            "AudioPlayer",
                            "Handler lambda warmup posted (${handlerWarmupEnd - handlerWarmupStart}ms)"
                        )
                    } catch (t: Throwable) {
                        android.util.Log.w("AudioPlayer", "Handler warmup failed: ${t.message}", t)
                    }

                    // Callback aufrufen: AudioThread ist bereit
                    try {
                        audioThreadReadyCallback?.onAudioThreadReady()
                    } catch (t: Throwable) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "audioThreadReadyCallback failed: ${t.message}",
                            t
                        )
                    }
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
            } catch (_: Throwable) {
            }
        }
    }

    private var audioTrack: AudioTrack? = null

    // Reusable small AudioTrack used for quick previews (chords/drums) to avoid allocating
    // a fresh AudioTrack on every preview call which is expensive and causes audible delay.
    @Volatile
    private var previewAudioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    @Volatile
    private var shouldStopPreview = false
    @Volatile
    private var currentPreviewId = 0L


    // Pre-generated drum sound samples
    private var cachedKickSamples: ShortArray? = null
    private var cachedSnareSamples: ShortArray? = null
    private var cachedHiHatSamples: ShortArray? = null
    private var drumSamplesCachedForSampleRate = -1

    // Live sound parameters (can be changed at runtime)
    /**
     * drumLevel: globale Multiplikator für alle Drum-Elemente (Kick/Snare/HiHat).
     * - 1.0 = neutral (Original-Lautstärke), <1.0 = leiser, >1.0 = lauter
     * - Wird zur Laufzeit angepasst (Settings -> Drum Level) und direkt im Audio-Thread verwendet.
     */
    @Volatile
    var drumLevel: Double = 1.0

    /**
     * soloLevel: Multiplikator für die Lautstärke aller Solo-Pattern-Sounds.
     * - 1.0 = neutral, <1.0 = leiser, >1.0 = lauter
     * - Wird zur Laufzeit angepasst (Settings -> Solo Level)
     */
    @Volatile
    var soloLevel: Double = 1.5

    /**
     * strumLevel: Multiplikator für die Lautstärke der Strumming/Chord-Sounds.
     * - 1.0 = neutral, <1.0 = leiser, >1.0 = lauter
     * - Wird zur Laufzeit angepasst (Settings -> Strum Level)
     */
    @Volatile
    var strumLevel: Double = 1.0

    /**
     * masterVolume: In-App Master-Lautstärkeregler (0.0 = stumm, 1.0 = volle Lautstärke).
     * Wirkt als Gain-Multiplikator direkt auf den AudioTrack und beeinflusst nur diese App.
     */
    @Volatile
    var masterVolume: Double = 1.0
        set(value) {
            field = value
            audioTrack?.setVolume(value.toFloat())
            previewAudioTrack?.setVolume(value.toFloat())
        }

    /**
     * envelopeScale: skaliert Hüllkurven/Amplituden von Percussion und einigen Effekten.
     * - Werte um 1.0 sind normal; größere Werte verlängern teilweise Sustain/Release in den verwendeten Envelopes
     * - Wird z.B. beim Kick/Snare/HiHat und bei der Piano-Envelope eingesetzt
     */
    @Volatile
    var envelopeScale: Double = 1.0

    /**
     * hiHatHighpass: Einflussfaktor im HiHat-Generator
     * - wirkt wie ein einfacher Hochpass/Detail-Filter auf das generierte Rauschen der HiHat
     * - Werte >1 betonen die hohen Frequenzen; Werte <1 daempfen die Hoehen
     */
    @Volatile
    var hiHatHighpass: Double = 1.0
    // Multiplier applied to final buffer for the current preset (1.0 default)
    /**
     * voiceGain: globaler Make-up-Gain für das aktuell gewählte Instrument-Preset
     * - nützlich um z.B. Piano lauter zu machen oder Overdrive abzusenken
     */
    @Volatile
    private var voiceGain: Double = 1.0

    // Persisted low-pass filter state across buffers (single value because only one preset plays at a time)
    private var prevLP = 0.0

    /**
     * strokeOffsetMs: delay applied to UP strokes in milliseconds to audibly distinguish them from DOWN strokes.
     * - 0 (default) = no offset
     * - positive value delays the pluck of UP strokes by that many milliseconds within the eighth-note buffer
     */
    @Volatile
    var upStrokeOffsetMs: Int = 30

    /**
     * stringStaggerMs: additional delay applied between successive strings (in milliseconds)
     * when staggering plucks inside a single strum. Default 8 ms.
     */
    @Volatile
    var upStringStaggerMs: Int = 8

    /**
     * downStrokeOffsetMs / downStringStaggerMs: equivalent settings for Down strokes.
     * Defaults are 0 (no offset/stagger) to preserve previous behavior unless enabled.
     */
    @Volatile
    var downStrokeOffsetMs: Int = 0
    @Volatile
    var downStringStaggerMs: Int = 0

    // Voice preset (CLEAN, OVERDRIVE, PIANO) - default CLEAN
    // Set a fixed per-preset makeup gain (no user override): Clean=1.0, Overdrive=0.9, Piano=1.5
    @Volatile
    var voicePreset: de.metaviewsoft.chordprogressionhelper.data.SoundPreset =
        de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN
        set(value) {
            field = value
            try {
                voiceGain = when (value) {
                    de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN -> 1.0
                    de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.9
                    de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO -> 1.5
                }
            } catch (_: Exception) {
                // ignore
            }
        }

    // Solo preset (separate from voice preset for strumming)
    @Volatile
    var soloPreset: de.metaviewsoft.chordprogressionhelper.data.SoundPreset =
        de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO
        set(value) {
            field = value
        }

    // Shuffle factor to control the intensity of the shuffle rhythm (0.0 to 2.0)
    // 0.0 = straight eighths (1:1 ratio)
    // 1.0 = standard swing/shuffle (2:1 ratio, like triplet feel)
    // 2.0 = extreme shuffle (3:1 ratio)
    @Volatile
    var shuffleFactor: Float = 0.0f
        set(value) {
            field = value.coerceIn(0.0f, 2.0f)
        }

    // Crunch/Overdrive gain levels (separate for strum and solo)
    // Range 0.0 (no crunch/clean) to 2.0 (heavy crunch), default 1.0 (medium)
    @Volatile
    var strumCrunchLevel: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 2.0f)
        }

    @Volatile
    var soloCrunchLevel: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 2.0f)
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
        val playStartTime = System.currentTimeMillis()
        android.util.Log.d(
            "AudioPlayer",
            "playProgression CALLED (isResuming=$isResuming, startMeasure=$startMeasureIndex)"
        )

        if (isPlaying) {
            android.util.Log.w("AudioPlayer", "playProgression REJECTED (already playing)")
            return
        }

        val ensureStartTime = System.currentTimeMillis()
        ensureAudioThreadStarted()
        val ensureEndTime = System.currentTimeMillis()
        android.util.Log.d(
            "AudioPlayer",
            "ensureAudioThreadStarted took ${ensureEndTime - ensureStartTime}ms"
        )

        val deferred = CompletableDeferred<Unit>()
        // Capture params for posted runnable
        val handlerPostTime = System.currentTimeMillis()
        android.util.Log.d("AudioPlayer", "CALLING audioHandler.post (time=$handlerPostTime)")

        audioHandler!!.post {
            val handlerStartTime = System.currentTimeMillis()
            android.util.Log.d(
                "AudioPlayer",
                "audioHandler POST EXECUTING (queueDelay=${handlerStartTime - handlerPostTime}ms, time=$handlerStartTime)"
            )

            try {
                val trackStartTime = System.currentTimeMillis()
                // Create fresh AudioTrack for each playback session
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .build()
                val trackPlayTime = System.currentTimeMillis()
                audioTrack?.play()
                audioTrack?.setVolume(masterVolume.toFloat())
                val trackPlayDoneTime = System.currentTimeMillis()
                android.util.Log.d(
                    "AudioPlayer",
                    "AudioTrack creation took ${trackPlayTime - trackStartTime}ms, play() took ${trackPlayDoneTime - trackPlayTime}ms"
                )

                // Warm up AudioTrack by writing silent samples to reduce latency on first real write
                // This prevents a long blocking wait on the first audioTrack.write() call
                try {
                    val warmupSilent = ShortArray(sampleRate / 100)  // 10ms of silence
                    audioTrack?.write(warmupSilent, 0, warmupSilent.size)
                    // Flush to clear warmup buffer so it doesn't play back
                    audioTrack?.flush()
                    android.util.Log.d("AudioPlayer", "AudioTrack warmup write + flush completed")
                } catch (e: Exception) {
                    android.util.Log.w(
                        "AudioPlayer",
                        "AudioTrack warmup write failed: ${e.message}"
                    )
                }

                // Ensure any residual audio data from previous runs is cleared before starting playback.
                val clearStartTime = System.currentTimeMillis()
                android.util.Log.d(
                    "AudioPlayer",
                    "BEFORE buffer clearing (timeSinceTrackPlay=${clearStartTime - trackPlayDoneTime}ms)"
                )

                try {
                    if (reusableEighthBuffer.isNotEmpty()) java.util.Arrays.fill(
                        reusableEighthBuffer,
                        0.0
                    )
                } catch (e: Exception) {
                    android.util.Log.w(
                        "AudioPlayer",
                        "failed to clear reusableEighthBuffer: ${e.message}",
                        e
                    )
                }
                try {
                    java.util.Arrays.fill(previewBuffer, 0.0)
                } catch (e: Exception) {
                    android.util.Log.w(
                        "AudioPlayer",
                        "failed to clear previewBuffer: ${e.message}",
                        e
                    )
                }
                val clearEndTime = System.currentTimeMillis()
                android.util.Log.d(
                    "AudioPlayer",
                    "Buffer clearing took ${clearEndTime - clearStartTime}ms"
                )

                val isPlayingSetTime = System.currentTimeMillis()
                isPlaying = true
                android.util.Log.d(
                    "AudioPlayer",
                    "isPlaying set (took ${isPlayingSetTime - clearEndTime}ms)"
                )

                var activeStrings: List<KarplusStrongString> = emptyList()
                val activeStringsInitTime = System.currentTimeMillis()
                android.util.Log.d(
                    "AudioPlayer",
                    "activeStrings initialized (took ${activeStringsInitTime - isPlayingSetTime}ms)"
                )

                if (countInBeats > 0 && !isResuming) {
                    val countInStartTime = System.currentTimeMillis()
                    android.util.Log.d(
                        "AudioPlayer",
                        "STARTING countInBeats (${countInBeats} beats)"
                    )

                    val beatDuration = 60.0 / progression.tempo
                    val eighthNoteDuration = beatDuration / 2.0
                    val eighthNoteSamples =
                        (sampleRate * eighthNoteDuration).toInt().coerceAtLeast(1)

                    // Wenn countInBeats > 2: die letzten 2 Viertel werden durch 4 Achtel ersetzt
                    val regularBeats = if (countInBeats > 2) countInBeats - 2 else countInBeats
                    val hasEighthNotes = countInBeats > 2

                    // Reguläre Viertel-Beats (HiHat auf ganzen Vierteln)
                    repeat(regularBeats) { beatIndex ->
                        if (!isPlaying) return@repeat
                        val beatStart = System.currentTimeMillis()
                        // Buffer für einen ganzen Viertelschlag (= 2 Achtel)
                        val quarterNoteSamples = eighthNoteSamples * 2
                        val b =
                            if (reusableEighthBuffer.size >= quarterNoteSamples) reusableEighthBuffer else DoubleArray(
                                quarterNoteSamples
                            ).also { reusableEighthBuffer = it }
                        val genStart = System.currentTimeMillis()
                        addHiHat(b, quarterNoteSamples, drumLevel)
                        val genEnd = System.currentTimeMillis()
                        // Nur die ersten quarterNoteSamples konvertieren und schreiben
                        val samples = ShortArray(quarterNoteSamples)
                        for (i in 0 until quarterNoteSamples) {
                            val pcmValue = (b[i] * 32767.0).toInt().coerceIn(-32768, 32767)
                            samples[i] = pcmValue.toShort()
                        }
                        val writeStart = System.currentTimeMillis()
                        audioTrack?.write(samples, 0, quarterNoteSamples)
                        val writeEnd = System.currentTimeMillis()
                        val beatEnd = System.currentTimeMillis()
                        android.util.Log.d(
                            "AudioPlayer",
                            "countInBeats[$beatIndex]: gen=${genEnd - genStart}ms, write=${writeEnd - writeStart}ms, total=${beatEnd - beatStart}ms"
                        )
                    }

                    // Letzte 2 Viertel als 4 Achtel (wenn countInBeats > 2)
                    if (hasEighthNotes) {
                        repeat(4) { eighthIndex ->
                            if (!isPlaying) return@repeat
                            val eighthStart = System.currentTimeMillis()
                            // Buffer für einen Achtelschlag
                            val b =
                                if (reusableEighthBuffer.size >= eighthNoteSamples) reusableEighthBuffer else DoubleArray(
                                    eighthNoteSamples
                                ).also { reusableEighthBuffer = it }
                            val genStart = System.currentTimeMillis()
                            addHiHat(b, eighthNoteSamples, drumLevel)
                            val genEnd = System.currentTimeMillis()
                            // Nur die ersten eighthNoteSamples konvertieren und schreiben
                            val samples = ShortArray(eighthNoteSamples)
                            for (i in 0 until eighthNoteSamples) {
                                val pcmValue = (b[i] * 32767.0).toInt().coerceIn(-32768, 32767)
                                samples[i] = pcmValue.toShort()
                            }
                            val writeStart = System.currentTimeMillis()
                            audioTrack?.write(samples, 0, eighthNoteSamples)
                            val writeEnd = System.currentTimeMillis()
                            val eighthEnd = System.currentTimeMillis()
                            android.util.Log.d(
                                "AudioPlayer",
                                "countInEighths[$eighthIndex]: gen=${genEnd - genStart}ms, write=${writeEnd - writeStart}ms, total=${eighthEnd - eighthStart}ms"
                            )
                        }
                    }

                    val countInEndTime = System.currentTimeMillis()
                    android.util.Log.d(
                        "AudioPlayer",
                        "countInBeats (${countInBeats} beats) took ${countInEndTime - countInStartTime}ms"
                    )
                } else {
                    android.util.Log.d(
                        "AudioPlayer",
                        "SKIPPING countInBeats (countInBeats=$countInBeats, isResuming=$isResuming)"
                    )
                }

                var isFirstLoopLocal = true
                // Only reuse persisted prevLP if we are resuming playback; otherwise start filter state at 0
                var prevLPLocal = if (isResuming) this@AudioPlayer.prevLP else 0.0
                val mainLoopStartTime = System.currentTimeMillis()
                android.util.Log.d(
                    "AudioPlayer",
                    "STARTING MAIN LOOP (loopStartMeasure=${if (isFirstLoopLocal) startMeasureIndex else 0}, timeSinceTrackPlay=${mainLoopStartTime - trackPlayDoneTime}ms"
                )

                do {
                    val loopStartMeasure = if (isFirstLoopLocal) startMeasureIndex else 0
                    for (measureIndex in loopStartMeasure until progression.measures.size) {
                        val measure = progression.measures[measureIndex]
                        if (!isPlaying) break
                        val loopStartStrum =
                            if (isFirstLoopLocal && measureIndex == startMeasureIndex) startStrumIndex else 0
                        if (isFirstLoopLocal && measureIndex == startMeasureIndex && loopStartStrum == 0) activeStrings =
                            emptyList()

                        if (isFirstLoopLocal && measureIndex == startMeasureIndex) {
                            android.util.Log.d(
                                "AudioPlayer",
                                "FIRST ITERATION START (measure=$measureIndex, strum=$loopStartStrum)"
                            )
                        }

                        // Check if this measure has a solo pattern
                        val soloPattern = try {
                            measure.soloPattern
                        } catch (_: Exception) {
                            null
                        }
                        val hasSoloPattern = soloPattern != null && !soloPattern.isEmpty()

                        // Build solo element timeline for this measure if present
                        // Timeline maps eighthIndex -> SoloElement
                        val soloElementTimeline = if (hasSoloPattern) {
                            val timeline =
                                mutableMapOf<Int, MutableList<de.metaviewsoft.chordprogressionhelper.model.SoloElement>>()
                            var pos = 0

                            // Use elements from soloPattern
                            val elementsList = soloPattern!!.elements

                            for (element in elementsList) {
                                if (pos >= 8) break
                                // Add this element at position 'pos'
                                timeline.getOrPut(pos) { mutableListOf() }.add(element)
                                pos += element.lengthEighths
                            }
                            timeline
                        } else null

                        // Active piano strings for let-ring functionality
                        // Map: midi -> KarplusStrongString for persistent resonance
                        val activePianoStrings = mutableMapOf<Int, KarplusStrongString>()

                        for (strumIndex in loopStartStrum until measure.strummingPattern.strums.size) {
                            if (!isPlaying) break
                            if (isFirstLoopLocal && measureIndex == startMeasureIndex && strumIndex == loopStartStrum) {
                                android.util.Log.d(
                                    "AudioPlayer",
                                    "FIRST STRUM ITERATION START (measure=$measureIndex, strum=$strumIndex, timeSinceFirstIterationStart=${System.currentTimeMillis() - mainLoopStartTime}ms)"
                                )
                            }
                            val beatDuration = 60.0 / progression.tempo
                            val eighthNoteDuration = beatDuration / 2.0

                            // Apply shuffle rhythm:
                            // - strumIndex % 2 == 0 (first of pair): longer note (on-beat)
                            // - strumIndex % 2 == 1 (second of pair): shorter note (off-beat)
                            // shuffleFactor 0.0 = straight (1:1 ratio)
                            // shuffleFactor 1.0 = swing (2:1 ratio, like triplet feel)
                            // shuffleFactor 2.0 = extreme (3:1 ratio)
                            // Use the live shuffle setting from AudioPlayer (updated via Settings)
                            val effectiveShuffleFactor = shuffleFactor.toDouble()
                            val shuffleRatio = 1.0 + effectiveShuffleFactor // 1.0 to 3.0
                            val adjustedEighthNoteDuration = if (strumIndex % 2 == 0) {
                                // First eighth (on-beat): longer
                                eighthNoteDuration * shuffleRatio / (1.0 + shuffleRatio / 2.0)
                            } else {
                                // Second eighth (off-beat): shorter
                                eighthNoteDuration * 1.0 / (1.0 + shuffleRatio / 2.0)
                            }

                            val eighthNoteSamples =
                                (sampleRate * adjustedEighthNoteDuration).toInt().coerceAtLeast(1)

                            onPositionChanged(measureIndex, strumIndex)

                            val chord: Chord? = measure.getChordAt(strumIndex)
                            val currentStrum = measure.strummingPattern.strums[strumIndex]

                            val (baseMs, staggerMs) = when (currentStrum) {
                                Strum.UP -> Pair(upStrokeOffsetMs, upStringStaggerMs)
                                Strum.DOWN, Strum.MUTE -> Pair(
                                    downStrokeOffsetMs,
                                    downStringStaggerMs
                                )

                                else -> Pair(0, 0)
                            }
                            val offsetSamplesForPluck =
                                if (baseMs > 0) (baseMs * sampleRate / 1000) else 0
                            val stringStaggerSamplesForPluck =
                                if (staggerMs > 0) (staggerMs * sampleRate / 1000) else 0
                            val effOffsetSamplesForPluck =
                                if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) 0 else offsetSamplesForPluck
                            val effStringStaggerSamplesForPluck =
                                if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) 0 else stringStaggerSamplesForPluck

                            if (currentStrum != Strum.LETRING) prevLPLocal = 0.0

                            // build activeStrings similar to original
                            when (currentStrum) {
                                Strum.DOWN, Strum.UP -> {
                                    val frequencies =
                                        chord?.getMidiNotes()?.map { midiNoteToFrequency(it) }
                                    if (frequencies == null) activeStrings = emptyList() else {
                                        val sorted = frequencies.sorted()
                                            .let { if (currentStrum == Strum.UP) it.asReversed() else it }
                                        val shouldPluckImmediately =
                                            (effOffsetSamplesForPluck == 0 && effStringStaggerSamplesForPluck == 0)
                                        activeStrings =
                                            if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                                                sorted.flatMap { freq ->
                                                    listOf(
                                                        KarplusStrongString(
                                                            freq,
                                                            sampleRate,
                                                            pluckStrength,
                                                            0.9992
                                                        ).apply { if (shouldPluckImmediately) pluck() },
                                                        KarplusStrongString(
                                                            freq * 2.002,
                                                            sampleRate,
                                                            pluckStrength,
                                                            0.999
                                                        ).apply { if (shouldPluckImmediately) pluck() },
                                                        KarplusStrongString(
                                                            freq / 2.0,
                                                            sampleRate,
                                                            pluckStrength,
                                                            0.997
                                                        ).apply { if (shouldPluckImmediately) pluck() }
                                                    )
                                                }
                                            } else {
                                                sorted.map { freq ->
                                                    KarplusStrongString(
                                                        freq,
                                                        sampleRate,
                                                        pluckStrength,
                                                        0.998
                                                    ).apply { if (shouldPluckImmediately) pluck() }
                                                }
                                            }
                                    }
                                }

                                Strum.MUTE -> {
                                    val frequencies =
                                        chord?.getMidiNotes()?.map { midiNoteToFrequency(it) }
                                    if (frequencies == null) activeStrings = emptyList() else {
                                        val sorted = frequencies.sorted()
                                        val shouldPluckImmediately =
                                            (effOffsetSamplesForPluck == 0 && effStringStaggerSamplesForPluck == 0)
                                        activeStrings =
                                            if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                                                sorted.flatMap { freq ->
                                                    listOf(
                                                        KarplusStrongString(
                                                            freq,
                                                            sampleRate,
                                                            pluckStrength,
                                                            0.995
                                                        ).apply { if (shouldPluckImmediately) pluck() },
                                                        KarplusStrongString(
                                                            freq * 2.002,
                                                            sampleRate,
                                                            pluckStrength,
                                                            0.993
                                                        ).apply { if (shouldPluckImmediately) pluck() }
                                                    )
                                                }
                                            } else {
                                                sorted.map { freq ->
                                                    KarplusStrongString(
                                                        freq,
                                                        sampleRate,
                                                        pluckStrength,
                                                        0.985
                                                    ).apply { if (shouldPluckImmediately) pluck() }
                                                }
                                            }
                                    }
                                }

                                Strum.REST -> activeStrings = emptyList()
                                Strum.LETRING -> { /* do nothing */
                                }
                            }

                            // Reuse an allocated buffer to avoid allocations, but always respect the *current* required length.
                            // Do NOT iterate over the full backing array size because it may be larger than the
                            // requested eighthNoteSamples (causing longer audio chunks and thus ignoring tempo changes).
                            val b =
                                if (reusableEighthBuffer.size >= eighthNoteSamples) reusableEighthBuffer else DoubleArray(
                                    eighthNoteSamples
                                ).also { reusableEighthBuffer = it }
                            val bufferLen = eighthNoteSamples

                            // scheduling arrays
                            var scheduledPlucks = IntArray(0)
                            var pluckedFlags = BooleanArray(0)
                            fun initPluckSchedules() {
                                val n = activeStrings.size
                                scheduledPlucks = IntArray(n)
                                pluckedFlags = BooleanArray(n)
                                val base = effOffsetSamplesForPluck.coerceAtLeast(0)
                                val stagger = effStringStaggerSamplesForPluck.coerceAtLeast(0)
                                val groupSize =
                                    if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) 3 else 1
                                for (j in 0 until n) {
                                    if (base == 0 && stagger == 0) {
                                        scheduledPlucks[j] = Int.MAX_VALUE
                                    } else {
                                        if (groupSize == 1) {
                                            scheduledPlucks[j] = (base + j * stagger).coerceAtMost(bufferLen - 1)
                                        } else {
                                            val noteIndex = j / groupSize;
                                            val intraIndex = j % groupSize
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
                                for (j in scheduledPlucks.indices) if (!pluckedFlags[j] && i >= scheduledPlucks[j]) {
                                    try {
                                        activeStrings[j].pluck()
                                    } catch (e: Exception) {
                                        android.util.Log.w(
                                            "AudioPlayer",
                                            "pluck() failed: ${e.message}",
                                            e
                                        )
                                    }
                                    pluckedFlags[j] = true
                                }
                            }

                            when (voicePreset) {
                                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN -> {
                                    // Cleaner sound: less low-pass filtering for more clarity and presence
                                    val lpAlpha = if (currentStrum == Strum.MUTE) 0.05 else 0.25
                                    initPluckSchedules()
                                    for (i in 0 until bufferLen) {
                                        performScheduledPlucksAt(i)
                                        var sample = 0.0
                                        for (s in activeStrings)
                                            sample += s.tick()
                                        // Light filtering for natural tone without muddiness
                                        val filtered = prevLPLocal + lpAlpha * (sample.toDouble() - prevLPLocal.toDouble())
                                        prevLPLocal = filtered
                                        b[i] = filtered * 1.0  // Reduced from 1.1 for cleaner dynamics
                                    }
                                }

                                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> {
                                    // Adjustable crunch level: strumCrunchLevel controls gain and saturation
                                    // At crunch=0: subtle amp warmth, at crunch=2.0: heavy saturation
                                    val basegain = 1.2 + (strumCrunchLevel * 2.5)  // Range: 1.2 (subtle) to 6.2 (heavy)
                                    val mix = 0.5 + (strumCrunchLevel * 0.35)  // Range: 0.5 (more dry) to 1.2 (fully saturated)
                                    val cubicCoeff = 0.001 + (strumCrunchLevel * 0.015)  // Range: 0.001 to 0.031
                                    val lpAlpha = if (currentStrum == Strum.MUTE) 0.05 else (0.18 + strumCrunchLevel * 0.04)
                                    initPluckSchedules()
                                    for (i in 0 until bufferLen) {
                                        performScheduledPlucksAt(i)
                                        var sample = 0.0
                                        for (s in activeStrings)
                                            sample += s.tick()
                                        // tanh saturation - subtle at low crunch, heavy at high crunch
                                        val driven = tanh(sample * basegain)
                                        // cubic harmonic distortion - adds warmth even at low levels
                                        val cubic = cubicCoeff * (sample * basegain * sample * basegain * sample * basegain)
                                        val out = mix * driven + (1.0 - mix) * sample + cubic
                                        val filtered = prevLPLocal + lpAlpha * (out.toDouble() - prevLPLocal.toDouble())
                                        prevLPLocal = filtered
                                        b[i] = filtered * 0.85  // Slightly higher output than before
                                    }
                                }

                                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO -> {
                                    val lpAlpha = if (currentStrum == Strum.MUTE) 0.03 else 0.1
                                    initPluckSchedules()
                                    for (i in 0 until bufferLen) {
                                        performScheduledPlucksAt(i)
                                        var sample = 0.0
                                        for (s in activeStrings)
                                            sample += s.tick()
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

                            // Apply strum level to the strumming sounds BEFORE adding solo/drums
                            // This ensures that strumLevel only affects chord sounds, not drums or solo
                            if (strumLevel != 1.0) {
                                for (i in 0 until bufferLen) {
                                    b[i] = b[i] * strumLevel
                                }
                            }

                            // Add piano pattern elements if present for this eighth note
                            if (soloElementTimeline != null) {
                                val soloElements = soloElementTimeline[strumIndex]

                                // Calculate gain based on soloPreset and multiply with soloLevel setting
                                val soloGainBase = when (soloPreset) {
                                    de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN -> 1.0
                                    de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.9
                                    de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO -> 1.5
                                }
                                val pianoGain = soloGainBase * soloLevel  // Apply soloLevel setting

                                if (soloElements != null && soloElements.isNotEmpty()) {
                                    // Process each piano element at this position
                                    for (element in soloElements) {
                                        when (element) {
                                            is de.metaviewsoft.chordprogressionhelper.model.SoloElement.Note -> {
                                                // New note: clear old strings and start new ones
                                                activePianoStrings.clear()

                                                val freq = midiNoteToFrequency(element.midi)

                                                if (soloPreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                                                    // Use additive synthesis for Piano preset
                                                    val pianoSamples = generatePianoSample(
                                                        freq,
                                                        eighthNoteDuration
                                                    )
                                                    // Ensure solo/piano loudness is constant regardless of whether a strum is present.
                                                    // Previously: val pianoBlend = if (currentStrum == Strum.REST) 1.2 else 0.7
                                                    val pianoBlend = 1.0
                                                    for (i in 0 until minOf(
                                                        bufferLen,
                                                        pianoSamples.size
                                                    )) {
                                                        b[i] += pianoSamples[i] * pianoGain * pianoBlend
                                                    }
                                                } else {
                                                    // Use Karplus-Strong for Clean/Overdrive - create persistent string
                                                    val string = KarplusStrongString(
                                                        freq,
                                                        sampleRate,
                                                        3,
                                                        0.998
                                                    ).apply { pluck() }
                                                    activePianoStrings[element.midi] = string

                                                    // Generate initial samples
                                                    for (i in 0 until bufferLen) {
                                                        val sample = string.tick()
                                                        val processed = when (soloPreset) {
                                                            de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN -> sample
                                                            de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> {
                                                                // Adjustable crunch for solo - subtle warmth at 0, heavy at 2.0
                                                                val gain = 1.2 + (soloCrunchLevel * 2.5)
                                                                val mix = 0.5 + (soloCrunchLevel * 0.35)
                                                                val driven = tanh(sample * gain)
                                                                mix * driven + (1.0 - mix) * sample
                                                            }

                                                            else -> sample
                                                        }
                                                        // Keep solo loudness constant regardless of current strum state
                                                        val pianoBlend = 1.0
                                                        b[i] += processed * pianoGain * pianoBlend
                                                    }
                                                }
                                            }

                                            is de.metaviewsoft.chordprogressionhelper.model.SoloElement.Rest -> {
                                                // Rest: stop all active piano strings (silence)
                                                activePianoStrings.clear()
                                            }

                                            is de.metaviewsoft.chordprogressionhelper.model.SoloElement.LetRing -> {
                                                // LetRing: continue active strings
                                                if (activePianoStrings.isNotEmpty() && soloPreset != de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                                                    for ((_, string) in activePianoStrings) {
                                                        for (i in 0 until bufferLen) {
                                                            val sample = string.tick()
                                                            val processed = when (soloPreset) {
                                                                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN -> sample
                                                                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> {
                                                                    // Adjustable crunch for solo - subtle warmth at 0, heavy at 2.0
                                                                    val gain = 1.2 + (soloCrunchLevel * 2.5)
                                                                    val mix = 0.5 + (soloCrunchLevel * 0.35)
                                                                    val driven = tanh(sample * gain)
                                                                    mix * driven + (1.0 - mix) * sample
                                                                }

                                                                else -> sample
                                                            }
                                                            // Keep solo loudness constant regardless of current strum state
                                                            val pianoBlend = 1.0
                                                            b[i] += processed * pianoGain * pianoBlend
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (activePianoStrings.isNotEmpty() && soloPreset != de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                                    // No element at this position - continue ringing active strings
                                    // This handles implicit continuation when no element is specified
                                    for ((_, string) in activePianoStrings) {
                                        for (i in 0 until bufferLen) {
                                            val sample = string.tick()
                                            val processed = when (soloPreset) {
                                                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN -> sample
                                                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> {
                                                    // Adjustable crunch for solo - subtle warmth at 0, heavy at 2.0
                                                    val gain = 1.2 + (soloCrunchLevel * 2.5)
                                                    val mix = 0.5 + (soloCrunchLevel * 0.35)
                                                    val driven = tanh(sample * gain)
                                                    mix * driven + (1.0 - mix) * sample
                                                }

                                                else -> sample
                                            }
                                            // Keep solo loudness constant regardless of current strum state
                                            val pianoBlend = 1.0
                                            b[i] += processed * pianoGain * pianoBlend
                                        }
                                    }
                                }
                            }

                            try {
                                val updatedDrumPattern =
                                    progression.measures[measureIndex].drumPattern
                                val stepIndex =
                                    if (updatedDrumPattern.steps.isNotEmpty()) (strumIndex % updatedDrumPattern.steps.size) else strumIndex % 8
                                val drumStep = updatedDrumPattern.steps.getOrNull(stepIndex)
                                    ?: de.metaviewsoft.chordprogressionhelper.model.DrumStep()
                                val restPercussionScale = 0.25
                                val baseDrumScale =
                                    if (currentStrum == Strum.REST || currentStrum == Strum.LETRING) restPercussionScale else 1.0
                                val presetPercussionMultiplier =
                                    if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) 0.45 else 1.0
                                val drumScale = baseDrumScale * presetPercussionMultiplier
                                if (drumStep.hiHat) addCachedDrumSample(
                                    b,
                                    cachedHiHatSamples,
                                    ::addHiHat,
                                    eighthNoteSamples,
                                    drumScale * drumLevel
                                )
                                if (drumStep.kick) addCachedDrumSample(
                                    b,
                                    cachedKickSamples,
                                    ::addKick,
                                    eighthNoteSamples * 2,
                                    drumScale * drumLevel
                                )
                                if (drumStep.snare) addCachedDrumSample(
                                    b,
                                    cachedSnareSamples,
                                    ::addSnare,
                                    eighthNoteSamples * 2,
                                    drumScale * drumLevel
                                )
                            } catch (e: Exception) {
                                val restPercussionScale = 0.25
                                val baseDrumScale =
                                    if (currentStrum == Strum.REST || currentStrum == Strum.LETRING) restPercussionScale else 1.0
                                val presetPercussionMultiplier =
                                    if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) 0.45 else 1.0
                                val drumScale = baseDrumScale * presetPercussionMultiplier
                                addCachedDrumSample(
                                    b,
                                    cachedHiHatSamples,
                                    ::addHiHat,
                                    eighthNoteSamples,
                                    drumScale
                                )
                                if (strumIndex % 2 == 0) {
                                    val quarterNoteIndex = strumIndex / 2
                                    if (quarterNoteIndex % 2 == 0) addCachedDrumSample(
                                        b,
                                        cachedKickSamples,
                                        ::addKick,
                                        eighthNoteSamples * 2,
                                        drumScale * if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) 0.5 else 1.0
                                    )
                                    if (quarterNoteIndex % 2 == 1) addCachedDrumSample(
                                        b,
                                        cachedSnareSamples,
                                        ::addSnare,
                                        eighthNoteSamples * 2,
                                        drumScale
                                    )
                                }
                                android.util.Log.w(
                                    "AudioPlayer",
                                    "Failed to read drum pattern for progression (using fallback): ${e.message}",
                                    e
                                )
                            }

                            // Apply voice gain only (strumLevel is applied earlier to avoid affecting drums/solo)
                            if (voiceGain != 1.0) for (i in 0 until bufferLen) b[i] =
                                b[i] * voiceGain

                            // Compute measured peak and scale empirically to targetPeak to avoid
                            // loudness jumps when parts are added/removed (solo vs chord)
                            var maxAbs = 0.0
                            for (i in 0 until bufferLen) {
                                val a = kotlin.math.abs(b[i]); if (a > maxAbs) maxAbs = a
                            }

                            val targetPeak = 0.85
                            val scale = if (maxAbs > 0.0) {
                                if (maxAbs > targetPeak) targetPeak / maxAbs else 1.0
                            } else 1.0

                            val trimmed = b.copyOfRange(0, bufferLen)
                            val samples =
                                trimmed.map { v -> (v * scale).coerceIn(-1.0, 1.0) }.toDoubleArray()
                                    .toPcmShortArray()
                            audioTrack?.write(samples, 0, samples.size)
                        }
                    }
                    isFirstLoopLocal = false
                    if (!isPlaying) break
                } while (shouldLoop() && isPlaying)

                val mainLoopEndTime = System.currentTimeMillis()
                android.util.Log.d(
                    "AudioPlayer",
                    "MAIN LOOP COMPLETED (totalTime=${mainLoopEndTime - mainLoopStartTime}ms)"
                )

                // persist last prevLP into class field
                this@AudioPlayer.prevLP = prevLPLocal

                // IMPORTANT: Reset isPlaying flag when playback completes normally
                isPlaying = false
                android.util.Log.d(
                    "AudioPlayer",
                    "playProgression: isPlaying set to false after normal completion"
                )

                deferred.complete(Unit)
            } catch (t: Throwable) {
                android.util.Log.e(
                    "AudioPlayer",
                    "playProgression handler EXCEPTION: ${t.message}",
                    t
                )
                // Reset isPlaying flag on exception too
                isPlaying = false
                deferred.completeExceptionally(t)
            }
        }

        // wait for audio thread to finish scheduling the run
        deferred.await()
        val playEndTime = System.currentTimeMillis()
        android.util.Log.d(
            "AudioPlayer",
            "playProgression COMPLETED (totalTime=${playEndTime - playStartTime}ms)"
        )
    }

    suspend fun previewChord(chord: Chord, pluckStrength: Int) = withContext(Dispatchers.IO) {
        // Generate and play preview on the audio thread using the cached previewAudioTrack to minimize latency.
        val startTime = System.currentTimeMillis()
        val myPreviewId = ++currentPreviewId
        android.util.Log.d(
            "AudioPlayer",
            "previewChord START: $chord (previewId=$myPreviewId, threadId=${Thread.currentThread().id})"
        )
        ensureAudioThreadStarted()
        val deferred = CompletableDeferred<Unit>()
        val postTime = System.currentTimeMillis()
        android.util.Log.d(
            "AudioPlayer",
            "previewChord: posting to audioHandler (elapsed=${postTime - startTime}ms, previewId=$myPreviewId)"
        )
        audioHandler!!.post {
            val handlerStartTime = System.currentTimeMillis()
            android.util.Log.d(
                "AudioPlayer",
                "previewChord: audioHandler.post EXECUTING (queueDelay=${handlerStartTime - postTime}ms, chord=$chord, previewId=$myPreviewId)"
            )

            // Check if this preview is still the current one (not superseded by a newer preview)
            if (myPreviewId != currentPreviewId) {
                android.util.Log.d(
                    "AudioPlayer",
                    "previewChord: ABORT - superseded by newer preview (myId=$myPreviewId, currentId=$currentPreviewId, chord=$chord)"
                )
                deferred.complete(Unit)
                return@post
            }

            try {
                // Reduced preview duration for lower latency
                val previewDuration = 0.4
                val numSamples = (sampleRate * previewDuration).toInt()
                val midiNotes = chord.getMidiNotes()

                val genStartTime = System.currentTimeMillis()

                // Generate chord using SAME ALGORITHM as playback for consistent sound
                val frequencies = midiNotes.map { midiNoteToFrequency(it) }
                val buf = previewBuffer

                // Create strings exactly like playback does
                val strings =
                    if (voicePreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                        // PIANO: two strings per frequency with higher decay
                        frequencies.flatMap { freq ->
                            listOf(
                                KarplusStrongString(
                                    freq,
                                    sampleRate,
                                    pluckStrength,
                                    0.9992
                                ).apply { pluck() },
                                KarplusStrongString(
                                    freq * 1.001,
                                    sampleRate,
                                    pluckStrength,
                                    0.9992
                                ).apply { pluck() }
                            )
                        }
                    } else {
                        // CLEAN/OVERDRIVE: single string per frequency with standard decay
                        frequencies.map { freq ->
                            KarplusStrongString(freq, sampleRate, pluckStrength).apply { pluck() }
                        }
                    }

                // Pre-calculate normalization parameters
                val presetNormalizationMultiplier = when (voicePreset) {
                    de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO -> 0.30
                    de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.7
                    else -> 0.7
                }
                val normalizationFactor = (frequencies.size) * presetNormalizationMultiplier + 1.0

                // OPTIMIZATION: Generate only minimal initial buffer (20ms) for instant playback
                // Rest will be generated and written progressively during playback
                val initialBufferMs = 20 // 20ms for instant start - minimal latency
                val initialSampleCount = minOf((sampleRate * initialBufferMs / 1000), numSamples)

                // Generate initial samples and estimate headroom
                var quickMax = 0.0
                for (i in 0 until initialSampleCount) {
                    var sample = 0.0
                    for (s in strings) sample += s.tick()
                    buf[i] = sample * voiceGain
                    val a = kotlin.math.abs(buf[i])
                    if (a > quickMax) quickMax = a
                }

                // Estimate headroom from initial samples
                val estimatedPostNormPeak =
                    if (normalizationFactor > 0.0) quickMax / normalizationFactor else quickMax
                val estimatedHeadroom =
                    if (estimatedPostNormPeak > 0.99) 0.99 / estimatedPostNormPeak else 1.0
                val finalGain = estimatedHeadroom / normalizationFactor

                // Convert initial buffer to PCM16
                val initialSamples = ShortArray(initialSampleCount)
                for (i in 0 until initialSampleCount) {
                    val pcmValue = (buf[i] * finalGain * 32767.0).toInt().coerceIn(-32768, 32767)
                    initialSamples[i] = pcmValue.toShort()
                }

                val initialGenTime = System.currentTimeMillis() - genStartTime
                android.util.Log.d(
                    "AudioPlayer",
                    "previewChord: generated initial ${initialSampleCount} samples (${initialBufferMs}ms) in ${initialGenTime}ms"
                )

                // Check if this preview was superseded
                if (myPreviewId != currentPreviewId) {
                    android.util.Log.d(
                        "AudioPlayer",
                        "previewChord: ABORT - superseded (myId=$myPreviewId, currentId=$currentPreviewId, chord=$chord)"
                    )
                    deferred.complete(Unit)
                    return@post
                }

                // Use cached previewAudioTrack if available
                val at = previewAudioTrack
                if (at != null) {
                    try {
                        at.flush()
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewChord: flush failed: ${e.message}",
                            e
                        )
                    }

                    val writeStartTime = System.currentTimeMillis()

                    // STEP 1: Write initial buffer immediately for instant sound
                    var totalWritten = at.write(initialSamples, 0, initialSamples.size)
                    val initialWriteTime = System.currentTimeMillis() - writeStartTime
                    android.util.Log.d(
                        "AudioPlayer",
                        "previewChord: wrote initial ${totalWritten} samples in ${initialWriteTime}ms - AUDIO STARTS NOW"
                    )

                    // STEP 2: Generate and write remaining samples progressively, but LIMIT to 100ms total
                    // This keeps queue delay low for rapid preview changes
                    var samplesGenerated = initialSampleCount
                    val maxProgressiveGenTime = 100 // Max 100ms for progressive generation
                    val progressiveStartTime = System.currentTimeMillis()
                    val chunkSize = sampleRate / 20 // 50ms chunks

                    while (samplesGenerated < numSamples && myPreviewId == currentPreviewId) {
                        // Check if we've exceeded time budget
                        if (System.currentTimeMillis() - progressiveStartTime > maxProgressiveGenTime) {
                            android.util.Log.d(
                                "AudioPlayer",
                                "previewChord: stopping progressive gen after ${System.currentTimeMillis() - progressiveStartTime}ms (time budget exceeded)"
                            )
                            break
                        }

                        val chunkEnd = minOf(samplesGenerated + chunkSize, numSamples)
                        val chunkSamples = chunkEnd - samplesGenerated

                        // Generate chunk
                        for (i in samplesGenerated until chunkEnd) {
                            var sample = 0.0
                            for (s in strings) sample += s.tick()
                            buf[i] = sample * voiceGain
                        }

                        // Convert to PCM16
                        val chunkShorts = ShortArray(chunkSamples)
                        for (i in 0 until chunkSamples) {
                            val pcmValue = (buf[samplesGenerated + i] * finalGain * 32767.0).toInt()
                                .coerceIn(-32768, 32767)
                            chunkShorts[i] = pcmValue.toShort()
                        }

                        // Write chunk
                        val written = at.write(chunkShorts, 0, chunkShorts.size)
                        if (written > 0) {
                            totalWritten += written
                            samplesGenerated = chunkEnd
                        } else if (written < 0) {
                            android.util.Log.w("AudioPlayer", "previewChord: write error $written")
                            break
                        }
                    }

                    val totalWriteTime = System.currentTimeMillis() - writeStartTime
                    android.util.Log.d(
                        "AudioPlayer",
                        "previewChord: wrote total ${totalWritten} samples in ${totalWriteTime}ms (initial+progressive, generated ${samplesGenerated}/${numSamples})"
                    )

                    // Calculate remaining sleep time
                    // Audio is already playing during progressive generation, so we might need little to no extra sleep
                    val audioPlaybackMs = (previewDuration * 1000).toLong()
                    val remainingSleepMs = maxOf(0, audioPlaybackMs - totalWriteTime)

                    android.util.Log.d(
                        "AudioPlayer",
                        "previewChord: entering sleep loop (remaining=${remainingSleepMs}ms after ${totalWriteTime}ms write)"
                    )
                    try {
                        val checkIntervalMs = 10L
                        var elapsed = 0L
                        val sleepStartTime = System.currentTimeMillis()
                        while (elapsed < remainingSleepMs && myPreviewId == currentPreviewId) {
                            Thread.sleep(minOf(checkIntervalMs, remainingSleepMs - elapsed))
                            elapsed += checkIntervalMs
                        }
                        val sleepEndTime = System.currentTimeMillis()
                        val actualSleepMs = sleepEndTime - sleepStartTime
                        val wasSuperseded = myPreviewId != currentPreviewId
                        android.util.Log.d(
                            "AudioPlayer",
                            "previewChord: sleep loop DONE (planned=${remainingSleepMs}ms, actual=${actualSleepMs}ms, superseded=$wasSuperseded, previewId=$myPreviewId, chord=$chord)"
                        )
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewChord: sleep interrupted: ${e.message}",
                            e
                        )
                    }
                } else {
                    // Fallback: create a temporary track if cached one is not available
                    // For fallback, just use initial samples (quick preview better than nothing)
                    android.util.Log.w(
                        "AudioPlayer",
                        "previewChord: using fallback temporary AudioTrack (initial buffer only)"
                    )
                    var temp: AudioTrack? = null
                    try {
                        val previewMinBuffer = AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        temp = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                            )
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(previewMinBuffer, initialSamples.size * 2))
                            .build()

                        temp.play()
                        temp.write(initialSamples, 0, initialSamples.size)
                        shouldStopPreview = false
                        try {
                            val sleepMs = (previewDuration * 1000).toLong()
                            val checkIntervalMs = 10L
                            var elapsed = 0L
                            while (elapsed < sleepMs && myPreviewId == currentPreviewId) {
                                Thread.sleep(minOf(checkIntervalMs, sleepMs - elapsed))
                                elapsed += checkIntervalMs
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewChord temp sleep interrupted: ${e.message}",
                                e
                            )
                        }
                    } finally {
                        try {
                            temp?.stop()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewChord temp.stop() failed: ${e.message}",
                                e
                            )
                        }
                        try {
                            temp?.release()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewChord temp.release() failed: ${e.message}",
                                e
                            )
                        }
                    }
                }

                val handlerEndTime = System.currentTimeMillis()
                android.util.Log.d(
                    "AudioPlayer",
                    "previewChord: audioHandler.post COMPLETED (totalHandlerTime=${handlerEndTime - handlerStartTime}ms, chord=$chord)"
                )
                deferred.complete(Unit)
            } catch (t: Throwable) {
                android.util.Log.e("AudioPlayer", "previewChord: EXCEPTION in audioHandler.post", t)
                deferred.completeExceptionally(t)
            }
        }
        android.util.Log.d(
            "AudioPlayer",
            "previewChord: waiting for deferred.await() (chord=$chord, previewId=$myPreviewId)"
        )
        deferred.await()
        val endTime = System.currentTimeMillis()
        android.util.Log.d(
            "AudioPlayer",
            "previewChord FINISHED: $chord (totalTime=${endTime - startTime}ms, previewId=$myPreviewId)"
        )
    }

    suspend fun previewKick(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        ensureAudioThreadStarted()
        val deferred = CompletableDeferred<Unit>()
        audioHandler!!.post {
            try {
                val previewDuration = 0.25

                // Use pre-generated kick samples if available and levelScale is 1.0
                val samples =
                    if (levelScale == 1.0 && cachedKickSamples != null && drumSamplesCachedForSampleRate == sampleRate) {
                        cachedKickSamples!!
                    } else {
                        // Generate on-demand for custom level scale
                        val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(64)
                        previewBuffer.fill(0.0)
                        val buf = previewBuffer
                        addKick(buf, numSamples, levelScale)
                        buf.copyOfRange(0, numSamples).toPcmShortArray()
                    }
                val at = previewAudioTrack
                if (at != null) {
                    try {
                        at.flush()
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewKick flush failed: ${e.message}",
                            e
                        )
                    }
                    at.write(samples, 0, samples.size)
                    shouldStopPreview = false
                    try {
                        val sleepMs = (previewDuration * 1000).toLong()
                        val checkIntervalMs = 10L
                        var elapsed = 0L
                        while (elapsed < sleepMs && !shouldStopPreview) {
                            Thread.sleep(minOf(checkIntervalMs, sleepMs - elapsed))
                            elapsed += checkIntervalMs
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewKick sleep interrupted: ${e.message}",
                            e
                        )
                    }
                } else {
                    // fallback to temporary track
                    var tmp: AudioTrack? = null
                    try {
                        val minBuf = AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        tmp = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                            )
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                            .build()
                        tmp.play()
                        tmp.write(samples, 0, samples.size)
                        shouldStopPreview = false
                        try {
                            val sleepMs = (previewDuration * 1000).toLong()
                            val checkIntervalMs = 10L
                            var elapsed = 0L
                            while (elapsed < sleepMs && !shouldStopPreview) {
                                Thread.sleep(minOf(checkIntervalMs, sleepMs - elapsed))
                                elapsed += checkIntervalMs
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewKick tmp sleep interrupted: ${e.message}",
                                e
                            )
                        }
                    } finally {
                        try {
                            tmp?.stop()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewKick tmp.stop failed: ${e.message}",
                                e
                            )
                        }
                        try {
                            tmp?.release()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewKick tmp.release failed: ${e.message}",
                                e
                            )
                        }
                    }
                }
                deferred.complete(Unit)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        deferred.await()
    }

    suspend fun previewSnare(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        ensureAudioThreadStarted()
        val deferred = CompletableDeferred<Unit>()
        audioHandler!!.post {
            try {
                val previewDuration = 0.22

                // Use pre-generated snare samples if available and levelScale is 1.0
                val samples =
                    if (levelScale == 1.0 && cachedSnareSamples != null && drumSamplesCachedForSampleRate == sampleRate) {
                        cachedSnareSamples!!
                    } else {
                        // Generate on-demand for custom level scale
                        val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(64)
                        previewBuffer.fill(0.0)
                        val buf = previewBuffer
                        addSnare(buf, numSamples, levelScale)
                        buf.copyOfRange(0, numSamples).toPcmShortArray()
                    }
                val at = previewAudioTrack
                if (at != null) {
                    try {
                        at.flush()
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewSnare flush failed: ${e.message}",
                            e
                        )
                    }
                    at.write(samples, 0, samples.size)
                    shouldStopPreview = false
                    try {
                        val sleepMs = (previewDuration * 1000).toLong()
                        val checkIntervalMs = 10L
                        var elapsed = 0L
                        while (elapsed < sleepMs && !shouldStopPreview) {
                            Thread.sleep(minOf(checkIntervalMs, sleepMs - elapsed))
                            elapsed += checkIntervalMs
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewSnare sleep interrupted: ${e.message}",
                            e
                        )
                    }
                } else {
                    var tmp: AudioTrack? = null
                    try {
                        val minBuf = AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        tmp = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                            )
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                            .build()
                        tmp.play()
                        tmp.write(samples, 0, samples.size)
                        shouldStopPreview = false
                        try {
                            val sleepMs = (previewDuration * 1000).toLong()
                            val checkIntervalMs = 10L
                            var elapsed = 0L
                            while (elapsed < sleepMs && !shouldStopPreview) {
                                Thread.sleep(minOf(checkIntervalMs, sleepMs - elapsed))
                                elapsed += checkIntervalMs
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewSnare tmp sleep interrupted: ${e.message}",
                                e
                            )
                        }
                    } finally {
                        try {
                            tmp?.stop()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewSnare tmp.stop failed: ${e.message}",
                                e
                            )
                        }
                        try {
                            tmp?.release()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewSnare tmp.release failed: ${e.message}",
                                e
                            )
                        }
                    }
                }
                deferred.complete(Unit)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        deferred.await()
    }

    suspend fun previewHiHat(levelScale: Double = 1.0) = withContext(Dispatchers.IO) {
        ensureAudioThreadStarted()
        val deferred = CompletableDeferred<Unit>()
        audioHandler!!.post {
            try {
                val previewDuration = 0.12

                // Use pre-generated hi-hat samples if available and levelScale is 1.0
                val samples =
                    if (levelScale == 1.0 && cachedHiHatSamples != null && drumSamplesCachedForSampleRate == sampleRate) {
                        cachedHiHatSamples!!
                    } else {
                        // Generate on-demand for custom level scale
                        val numSamples = (sampleRate * previewDuration).toInt().coerceAtLeast(32)
                        previewBuffer.fill(0.0)
                        val buf = previewBuffer
                        addHiHat(buf, numSamples, levelScale)
                        buf.copyOfRange(0, numSamples).toPcmShortArray()
                    }
                val at = previewAudioTrack
                if (at != null) {
                    try {
                        at.flush()
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewHiHat flush failed: ${e.message}",
                            e
                        )
                    }
                    at.write(samples, 0, samples.size)
                    shouldStopPreview = false
                    try {
                        val sleepMs = (previewDuration * 1000).toLong()
                        val checkIntervalMs = 10L
                        var elapsed = 0L
                        while (elapsed < sleepMs && !shouldStopPreview) {
                            Thread.sleep(minOf(checkIntervalMs, sleepMs - elapsed))
                            elapsed += checkIntervalMs
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewHiHat sleep interrupted: ${e.message}",
                            e
                        )
                    }
                } else {
                    var tmp: AudioTrack? = null
                    try {
                        val minBuf = AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        tmp = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                            )
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 2))
                            .build()
                        tmp.play()
                        tmp.write(samples, 0, samples.size)
                        shouldStopPreview = false
                        try {
                            val sleepMs = (previewDuration * 1000).toLong()
                            val checkIntervalMs = 10L
                            var elapsed = 0L
                            while (elapsed < sleepMs && !shouldStopPreview) {
                                Thread.sleep(minOf(checkIntervalMs, sleepMs - elapsed))
                                elapsed += checkIntervalMs
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewHiHat tmp sleep interrupted: ${e.message}",
                                e
                            )
                        }
                    } finally {
                        try {
                            tmp?.stop()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewHiHat tmp.stop failed: ${e.message}",
                                e
                            )
                        }
                        try {
                            tmp?.release()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewHiHat tmp.release failed: ${e.message}",
                                e
                            )
                        }
                    }
                }
                deferred.complete(Unit)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
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
     * addCachedDrumSample: Füge gecachte oder on-demand generierte Drum-Samples zu einem Buffer hinzu
     * - Nutzt gecachte Samples, wenn cache gültig ist und levelScale=1.0
     * - Sonst generiert on-demand
     */
    private fun addCachedDrumSample(
        buffer: DoubleArray,
        cachedSamples: ShortArray?,
        generatorFn: (DoubleArray, Int, Double) -> Unit,
        duration: Int,
        levelScale: Double = 1.0
    ) {
        val samples =
            if (levelScale == 1.0 && cachedSamples != null && drumSamplesCachedForSampleRate == sampleRate) {
                cachedSamples
            } else {
                // Generate on-demand for custom level scale
                val tempBuf = DoubleArray(duration)
                generatorFn(tempBuf, duration, levelScale)
                tempBuf.toPcmShortArray()
            }

        // Mix into target buffer (convert PCM16 back to double with scaling)
        val maxSamples = minOf(buffer.size, samples.size)
        for (i in 0 until maxSamples) {
            buffer[i] += (samples[i].toDouble() / Short.MAX_VALUE)
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
    private class KarplusStrongString(
        frequency: Double,
        sampleRate: Int,
        pluckStrength: Int,
        decay: Double = 0.998
    ) {
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
            val nextSample =
                (currentSample + ringBuffer[(currentIndex + 1) % bufferSize]) * 0.5 * decayFactor
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
        android.util.Log.d(
            "AudioPlayer",
            "stop() CALLED (isPlaying=$isPlaying, shouldStopPreview=$shouldStopPreview, threadId=${Thread.currentThread().id})"
        )
        if (!isPlaying) {
            android.util.Log.d(
                "AudioPlayer",
                "stop() setting shouldStopPreview=true even though isPlaying=false"
            )
            shouldStopPreview = true
            // Remove all pending messages even if not playing, to clear preview queue
            try {
                audioHandler?.removeCallbacksAndMessages(null)
                android.util.Log.d("AudioPlayer", "stop() removed pending handler messages")
            } catch (e: Exception) {
                android.util.Log.w(
                    "AudioPlayer",
                    "stop: removeCallbacksAndMessages failed: ${e.message}",
                    e
                )
            }
            return
        }
        isPlaying = false
        shouldStopPreview = true
        // Remove all pending messages from audio handler to ensure immediate stop
        try {
            audioHandler?.removeCallbacksAndMessages(null)
            android.util.Log.d(
                "AudioPlayer",
                "stop() removed pending handler messages (isPlaying was true)"
            )
        } catch (e: Exception) {
            android.util.Log.w(
                "AudioPlayer",
                "stop: removeCallbacksAndMessages failed: ${e.message}",
                e
            )
        }
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    it.flush()
                    it.stop()
                    it.release()
                } catch (_: IllegalStateException) { /* Can happen if track is already released */
                }
            }
        }
        audioTrack = null
        // Also stop and release previewAudioTrack if present
        try {
            previewAudioTrack?.let {
                try {
                    it.flush()
                } catch (e: Exception) {
                    android.util.Log.w(
                        "AudioPlayer",
                        "stop: previewAudioTrack.flush failed: ${e.message}",
                        e
                    )
                }
                try {
                    it.stop()
                } catch (e: Exception) {
                    android.util.Log.w(
                        "AudioPlayer",
                        "stop: previewAudioTrack.stop failed: ${e.message}",
                        e
                    )
                }
                try {
                    it.release()
                } catch (e: Exception) {
                    android.util.Log.w(
                        "AudioPlayer",
                        "stop: previewAudioTrack.release failed: ${e.message}",
                        e
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "AudioPlayer",
                "stop: previewAudioTrack teardown failed: ${e.message}",
                e
            )
        }
        // Reset persisted lowpass filter state so subsequent starts/count-ins don't inherit DC/offset
        try {
            this.prevLP = 0.0
        } catch (e: Exception) {
            android.util.Log.w("AudioPlayer", "stop: resetting prevLP failed: ${e.message}", e)
        }
        // Clear reusable buffers so a subsequent start cannot reuse stale sample data
        try {
            if (reusableEighthBuffer.isNotEmpty()) java.util.Arrays.fill(reusableEighthBuffer, 0.0)
        } catch (e: Exception) {
            android.util.Log.w(
                "AudioPlayer",
                "stop: clearing reusableEighthBuffer failed: ${e.message}",
                e
            )
        }
        try {
            java.util.Arrays.fill(previewBuffer, 0.0)
        } catch (e: Exception) {
            android.util.Log.w(
                "AudioPlayer",
                "stop: clearing previewBuffer failed: ${e.message}",
                e
            )
        }
    }

    fun resetStopFlag() {
        shouldStopPreview = false
        android.util.Log.d("AudioPlayer", "resetStopFlag() - shouldStopPreview set to false")
    }


    /**
     * Pre-generate drum sound samples (kick, snare, hi-hat).
     * Called once when audio thread starts.
     * First tries to load from disk cache; if not available or sample rate mismatch, generates and saves.
     */
    fun preGenerateDrumSamples() {
        // Try to load from cache first
        if (tryLoadDrumSamplesFromCache()) {
            android.util.Log.d("AudioPlayer", "preGenerateDrumSamples: loaded from disk cache")
            return
        }

        val startTime = System.currentTimeMillis()

        // Generate kick (0.25s)
        val kickDuration = 0.25
        val kickSamples = (sampleRate * kickDuration).toInt().coerceAtLeast(64)
        val kickBuf = DoubleArray(kickSamples)
        addKick(kickBuf, kickSamples, 1.0)
        cachedKickSamples = kickBuf.toPcmShortArray()

        // Generate snare (0.22s)
        val snareDuration = 0.22
        val snareSamples = (sampleRate * snareDuration).toInt().coerceAtLeast(64)
        val snareBuf = DoubleArray(snareSamples)
        addSnare(snareBuf, snareSamples, 1.0)
        cachedSnareSamples = snareBuf.toPcmShortArray()

        // Generate hi-hat (0.12s)
        val hihatDuration = 0.12
        val hihatSamples = (sampleRate * hihatDuration).toInt().coerceAtLeast(32)
        val hihatBuf = DoubleArray(hihatSamples)
        addHiHat(hihatBuf, hihatSamples, 1.0)
        cachedHiHatSamples = hihatBuf.toPcmShortArray()

        drumSamplesCachedForSampleRate = sampleRate

        val endTime = System.currentTimeMillis()
        android.util.Log.d(
            "AudioPlayer",
            "preGenerateDrumSamples: generated in ${endTime - startTime}ms"
        )

        // Save to disk cache for future sessions (asynchronously to avoid blocking audio thread)
        try {
            Thread {
                try {
                    saveDrumSamplesToCache()
                    android.util.Log.d(
                        "AudioPlayer",
                        "preGenerateDrumSamples: saved to disk cache (async)"
                    )
                } catch (e: Exception) {
                    android.util.Log.w(
                        "AudioPlayer",
                        "preGenerateDrumSamples: failed to save cache: ${e.message}",
                        e
                    )
                }
            }.start()
        } catch (e: Exception) {
            android.util.Log.w(
                "AudioPlayer",
                "preGenerateDrumSamples: failed to start save thread: ${e.message}",
                e
            )
        }
    }

    /**
     * Load drum samples from disk cache if available and valid.
     * Returns true if successfully loaded, false otherwise.
     */
    private fun tryLoadDrumSamplesFromCache(): Boolean {
        return try {
            // Get app context from MyApplication singleton
            val context = try {
                (Class.forName("de.metaviewsoft.chordprogressionhelper.MyApplication")
                    .getDeclaredField("Companion")
                    .apply { isAccessible = true }
                    .get(null))?.let { companion ->
                        Class.forName("com.metaviewsoft.chordprogressionhelper.MyApplication\$Companion")
                            .getDeclaredMethod("getContext")
                            .invoke(companion) as? android.content.Context
                    }
            } catch (_: Exception) {
                null
            }

            val cacheDir = context?.cacheDir ?: return false

            val kickFile = java.io.File(cacheDir, "drum_kick_$sampleRate.cache")
            val snareFile = java.io.File(cacheDir, "drum_snare_$sampleRate.cache")
            val hihatFile = java.io.File(cacheDir, "drum_hihat_$sampleRate.cache")

            // All three files must exist
            if (!kickFile.exists() || !snareFile.exists() || !hihatFile.exists()) {
                return false
            }

            // Load and verify file sizes
            cachedKickSamples = loadShortArrayFromFile(kickFile)
            cachedSnareSamples = loadShortArrayFromFile(snareFile)
            cachedHiHatSamples = loadShortArrayFromFile(hihatFile)

            if (cachedKickSamples == null || cachedSnareSamples == null || cachedHiHatSamples == null) {
                return false
            }

            drumSamplesCachedForSampleRate = sampleRate
            true
        } catch (e: Exception) {
            android.util.Log.w("AudioPlayer", "tryLoadDrumSamplesFromCache failed: ${e.message}", e)
            false
        }
    }

    /**
     * Save drum samples to disk cache for future sessions.
     */
    private fun saveDrumSamplesToCache() {
        try {
            val context = try {
                (Class.forName("de.metaviewsoft.chordprogressionhelper.MyApplication")
                    .getDeclaredField("Companion")
                    .apply { isAccessible = true }
                    .get(null))?.let { companion ->
                        Class.forName("com.metaviewsoft.chordprogressionhelper.MyApplication\$Companion")
                            .getDeclaredMethod("getContext")
                            .invoke(companion) as? android.content.Context
                    }
            } catch (_: Exception) {
                null
            }

            val cacheDir = context?.cacheDir ?: return

            val kickFile = java.io.File(cacheDir, "drum_kick_$sampleRate.cache")
            val snareFile = java.io.File(cacheDir, "drum_snare_$sampleRate.cache")
            val hihatFile = java.io.File(cacheDir, "drum_hihat_$sampleRate.cache")

            // Save each sample array
            cachedKickSamples?.let { saveShortArrayToFile(it, kickFile) }
            cachedSnareSamples?.let { saveShortArrayToFile(it, snareFile) }
            cachedHiHatSamples?.let { saveShortArrayToFile(it, hihatFile) }
        } catch (e: Exception) {
            android.util.Log.w("AudioPlayer", "saveDrumSamplesToCache failed: ${e.message}", e)
        }
    }

    /**
     * Load a ShortArray from a binary file.
     */
    private fun loadShortArrayFromFile(file: java.io.File): ShortArray? {
        return try {
            file.inputStream().use { input ->
                val bytes = input.readBytes()
                if (bytes.size % 2 != 0) return null // Corrupted file
                val shorts = ShortArray(bytes.size / 2)
                for (i in shorts.indices) {
                    shorts[i] = ((bytes[i * 2].toInt() and 0xFF) or
                            ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
                }
                shorts
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "AudioPlayer",
                "loadShortArrayFromFile failed for ${file.name}: ${e.message}",
                e
            )
            null
        }
    }

    /**
     * Save a ShortArray to a binary file.
     */
    private fun saveShortArrayToFile(shorts: ShortArray, file: java.io.File) {
        file.outputStream().use { output ->
            for (s in shorts) {
                output.write((s.toInt() and 0xFF).toByte().toInt())
                output.write(((s.toInt() shr 8) and 0xFF).toByte().toInt())
            }
        }
    }

    /**
     * Spielt einen einzelnen Akkord-Strum für Template-Previews ab.
     * Blockierende Methode, wartet bis der Strum abgespielt wurde.
     */
    suspend fun playChordStrum(chord: Chord) = withContext(Dispatchers.IO) {
        // Verwende die bestehende previewChord Methode mit Soft Pluck
        previewChord(chord, 3)
    }

    // Piano synthesis using additive synthesis instead of Karplus-Strong
    private fun generatePianoSample(frequency: Double, durationSec: Double): DoubleArray {
        val numSamples = (sampleRate * durationSec).toInt()
        val samples = DoubleArray(numSamples)

        // Piano has harmonics with specific amplitude ratios
        // Reduced to 3 harmonics for better performance (was 6)
        val harmonics = listOf(
            1.0 to 1.0,      // Fundamental
            2.0 to 0.6,      // 2nd harmonic (octave)
            3.0 to 0.3       // 3rd harmonic
        )

        // Pre-calculate envelope parameters
        val attackTime = 0.002  // 2ms attack (sehr schnell für sofortige Response)
        val decayTime = 0.15   // 150ms decay
        val sustainLevel = 0.3 // 30% sustain level

        val attackSamples = (attackTime * sampleRate).toInt()
        val decaySamples = (decayTime * sampleRate).toInt()
        val attackDecaySamples = attackSamples + decaySamples

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var sample = 0.0

            // Add each harmonic
            for ((harmonic, amplitude) in harmonics) {
                val freq = frequency * harmonic
                sample += amplitude * sin(2.0 * PI * freq * t)
            }

            // ADSR envelope - optimized with pre-calculated boundaries
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

        // Normalize
        val maxVal = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0
        if (maxVal > 0.0) {
            for (i in samples.indices) {
                samples[i] /= maxVal
            }
        }

        return samples
    }

    // Mix multiple piano notes together
    private fun mixPianoNotes(midiNotes: List<Int>, durationSec: Double): DoubleArray {
        val numSamples = (sampleRate * durationSec).toInt()
        val mixed = DoubleArray(numSamples)

        for (midiNote in midiNotes) {
            val freq = midiNoteToFrequency(midiNote)
            val noteSamples = generatePianoSample(freq, durationSec)
            for (i in 0 until minOf(numSamples, noteSamples.size)) {
                mixed[i] += noteSamples[i]
            }
        }

        // Normalize mixed result
        val maxVal = mixed.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0
        if (maxVal > 0.0) {
            val normFactor = 0.8 / maxVal // Leave some headroom
            for (i in mixed.indices) {
                mixed[i] *= normFactor
            }
        }

        return mixed
    }

    /**
     * Spielt eine einzelne Solo-Note mit verbesserter Synthese ab.
     * Verwendet additive Synthese für Piano-Preset, KarplusStrong für andere.
     * Optimiert für minimale Latenz durch progressive Generierung mit kontinuierlicher Phase.
     */
    suspend fun previewSoloNote(midiNote: Int, durationSec: Double = 0.6) =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val myPreviewId = ++currentPreviewId
            android.util.Log.d(
                "AudioPlayer",
                "previewPianoNote START: midiNote=$midiNote (previewId=$myPreviewId, threadId=${Thread.currentThread().id})"
            )
            ensureAudioThreadStarted()
            val deferred = CompletableDeferred<Unit>()

            audioHandler!!.post {
                val handlerStartTime = System.currentTimeMillis()

                // Check if this preview is still the current one
                if (myPreviewId != currentPreviewId) {
                    android.util.Log.d(
                        "AudioPlayer",
                        "previewPianoNote: ABORT - superseded (myId=$myPreviewId, currentId=$currentPreviewId)"
                    )
                    deferred.complete(Unit)
                    return@post
                }

                try {
                    val freq = midiNoteToFrequency(midiNote)
                    val totalSamples = (sampleRate * durationSec).toInt()

                    // Play using previewAudioTrack
                    val at = previewAudioTrack
                    if (at != null) {
                        try {
                            at.flush()
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "AudioPlayer",
                                "previewPianoNote flush failed: ${e.message}",
                                e
                            )
                        }

                        // OPTIMIZATION: Generate and write in chunks, but maintain continuous state
                        val initialBufferMs = 20
                        val initialSampleCount = (sampleRate * initialBufferMs / 1000)
                        val chunkSize = sampleRate / 20 // 50ms chunks

                        // Create synthesis state that persists across chunks
                        // Use soloPreset instead of voicePreset for piano patterns
                        val karplusString =
                            if (soloPreset != de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                                KarplusStrongString(freq, sampleRate, 3, 0.998).apply { pluck() }
                            } else null

                        // Piano synthesis parameters (constant across chunks)
                        // Reduced to 3 harmonics for better performance
                        val harmonics = listOf(
                            1.0 to 1.0, 2.0 to 0.6, 3.0 to 0.3
                        )
                        val attackTime = 0.002  // 2ms attack - sehr schnell
                        val decayTime = 0.15
                        val sustainLevel = 0.3

                        // Pre-calculate envelope boundaries for optimization
                        val attackSamples = (attackTime * sampleRate).toInt()
                        val decaySamples = (decayTime * sampleRate).toInt()
                        val attackDecaySamples = attackSamples + decaySamples

                        // Calculate gain based on soloPreset and multiply with soloLevel setting
                        val pianoGainBase = when (soloPreset) {
                            de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN -> 1.0
                            de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.9
                            de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO -> 1.5
                        }
                        val pianoGain = pianoGainBase * soloLevel  // Apply soloLevel setting

                        var samplesWritten = 0
                        var isFirstChunk = true

                        while (samplesWritten < totalSamples && myPreviewId == currentPreviewId) {
                            val remainingSamples = totalSamples - samplesWritten
                            val currentChunkSize = if (isFirstChunk) {
                                minOf(initialSampleCount, remainingSamples)
                            } else {
                                minOf(chunkSize, remainingSamples)
                            }

                            // Generate chunk with continuous phase/state
                            val chunkBuf = DoubleArray(currentChunkSize)

                            if (soloPreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                                // Piano: Generate with continuous phase and optimized envelope
                                for (i in 0 until currentChunkSize) {
                                    val sampleIndex = samplesWritten + i
                                    val t = sampleIndex.toDouble() / sampleRate
                                    var sample = 0.0

                                    // Add all harmonics (optimized to 3 for performance)
                                    for ((harmonic, amplitude) in harmonics) {
                                        val hFreq = freq * harmonic
                                        sample += amplitude * sin(2.0 * PI * hFreq * t)
                                    }

                                    // Apply envelope - optimized with pre-calculated boundaries
                                    val envelope = when {
                                        sampleIndex < attackSamples -> sampleIndex.toDouble() / attackSamples
                                        sampleIndex < attackDecaySamples -> {
                                            val decayProgress =
                                                (sampleIndex - attackSamples).toDouble() / decaySamples
                                            1.0 - (1.0 - sustainLevel) * decayProgress
                                        }

                                        else -> {
                                            val releaseProgress =
                                                (sampleIndex - attackDecaySamples).toDouble() / (totalSamples - attackDecaySamples)
                                            sustainLevel * (1.0 - releaseProgress)
                                        }
                                    }.coerceIn(0.0, 1.0)

                                    chunkBuf[i] = sample * envelope
                                }
                            } else {
                                // Karplus-Strong: Use persistent string instance
                                for (i in 0 until currentChunkSize) {
                                    chunkBuf[i] = karplusString!!.tick()
                                }
                            }

                            // Convert to PCM16 without normalizing each chunk individually
                            // (normalization would cause volume jumps between chunks)
                            val chunkShorts = ShortArray(currentChunkSize)
                            for (i in 0 until currentChunkSize) {
                                val pcmValue = (chunkBuf[i] * pianoGain * 32767.0 * 0.8).toInt()
                                    .coerceIn(-32768, 32767)
                                chunkShorts[i] = pcmValue.toShort()
                            }

                            // Write chunk
                            at.write(chunkShorts, 0, chunkShorts.size)

                            if (isFirstChunk) {
                                android.util.Log.d(
                                    "AudioPlayer",
                                    "previewPianoNote: wrote initial ${currentChunkSize} samples - AUDIO STARTS NOW"
                                )
                                isFirstChunk = false
                            }

                            samplesWritten += currentChunkSize
                        }

                        android.util.Log.d(
                            "AudioPlayer",
                            "previewPianoNote: wrote total ${samplesWritten} samples"
                        )

                        // Sleep for remaining duration
                        val audioPlaybackMs = (durationSec * 1000).toLong()
                        val elapsedMs = System.currentTimeMillis() - handlerStartTime
                        val remainingSleepMs = maxOf(0, audioPlaybackMs - elapsedMs)

                        if (remainingSleepMs > 0) {
                            try {
                                val checkIntervalMs = 10L
                                var elapsed = 0L
                                while (elapsed < remainingSleepMs && myPreviewId == currentPreviewId) {
                                    Thread.sleep(minOf(checkIntervalMs, remainingSleepMs - elapsed))
                                    elapsed += checkIntervalMs
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(
                                    "AudioPlayer",
                                    "previewPianoNote sleep interrupted: ${e.message}",
                                    e
                                )
                            }
                        }
                    } else {
                        // Fallback
                        android.util.Log.w(
                            "AudioPlayer",
                            "previewPianoNote: no previewAudioTrack available"
                        )
                    }

                    android.util.Log.d(
                        "AudioPlayer",
                        "previewPianoNote: COMPLETED (handlerTime=${System.currentTimeMillis() - handlerStartTime}ms)"
                    )
                    deferred.complete(Unit)
                } catch (t: Throwable) {
                    android.util.Log.e("AudioPlayer", "previewPianoNote: EXCEPTION", t)
                    deferred.completeExceptionally(t)
                }
            }

            deferred.await()
            android.util.Log.d(
                "AudioPlayer",
                "previewPianoNote FINISHED: midiNote=$midiNote (totalTime=${System.currentTimeMillis() - startTime}ms, previewId=$myPreviewId)"
            )
        }

    /**
     * Fire-and-forget solo note trigger with minimal latency.
     * Posts directly to the audio handler thread — no coroutine or IO-dispatcher hop.
     * Uses pause/flush/play to clear any buffered audio immediately, and 10ms chunks
     * so a superseded note exits its write loop within at most 10ms.
     */
    fun triggerSoloNotePreview(midiNote: Int, durationSec: Double = 0.3) {
        ensureAudioThreadStarted()
        val myPreviewId = ++currentPreviewId
        shouldStopPreview = false
        audioHandler?.removeCallbacksAndMessages(null)
        audioHandler?.post {
            if (myPreviewId != currentPreviewId) return@post
            val at = previewAudioTrack ?: return@post

            // Clear any buffered audio for an immediate clean start
            try { at.pause() } catch (_: Exception) {}
            try { at.flush() } catch (_: Exception) {}
            try { at.play()  } catch (_: Exception) {}
            try { at.setVolume(masterVolume.toFloat()) } catch (_: Exception) {}

            val freq = midiNoteToFrequency(midiNote)
            val totalSamples = (sampleRate * durationSec).toInt()
            // 10ms chunks: limits delay when a new key supersedes this note
            val chunkSize = sampleRate / 100

            val karplusString =
                if (soloPreset != de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                    KarplusStrongString(freq, sampleRate, 3, 0.998).apply { pluck() }
                } else null

            val harmonics = listOf(1.0 to 1.0, 2.0 to 0.6, 3.0 to 0.3)
            val attackSamples = (0.002 * sampleRate).toInt()
            val decaySamples  = (0.15  * sampleRate).toInt()
            val sustainLevel  = 0.3
            val attackDecaySamples = attackSamples + decaySamples
            val pianoGain = when (soloPreset) {
                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.CLEAN     -> 1.0
                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.OVERDRIVE -> 0.9
                de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO     -> 1.5
            } * soloLevel

            var samplesWritten = 0
            while (samplesWritten < totalSamples && myPreviewId == currentPreviewId) {
                val n = minOf(chunkSize, totalSamples - samplesWritten)
                val chunkBuf = DoubleArray(n)
                if (soloPreset == de.metaviewsoft.chordprogressionhelper.data.SoundPreset.PIANO) {
                    for (i in 0 until n) {
                        val s = samplesWritten + i
                        val t = s.toDouble() / sampleRate
                        var sample = 0.0
                        for ((harmonic, amplitude) in harmonics) {
                            sample += amplitude * sin(2.0 * PI * freq * harmonic * t)
                        }
                        val env = when {
                            s < attackSamples -> s.toDouble() / attackSamples
                            s < attackDecaySamples -> {
                                val dp = (s - attackSamples).toDouble() / decaySamples
                                1.0 - (1.0 - sustainLevel) * dp
                            }
                            else -> {
                                val rp = (s - attackDecaySamples).toDouble() / (totalSamples - attackDecaySamples)
                                sustainLevel * (1.0 - rp)
                            }
                        }.coerceIn(0.0, 1.0)
                        chunkBuf[i] = sample * env
                    }
                } else {
                    for (i in 0 until n) chunkBuf[i] = karplusString!!.tick()
                }
                val shorts = ShortArray(n) { i ->
                    (chunkBuf[i] * pianoGain * 32767.0 * 0.8).toInt().coerceIn(-32768, 32767).toShort()
                }
                at.write(shorts, 0, shorts.size)
                samplesWritten += n
            }
        }
    }
    }

