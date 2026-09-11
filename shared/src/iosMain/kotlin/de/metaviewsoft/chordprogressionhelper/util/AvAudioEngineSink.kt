@file:OptIn(ExperimentalForeignApi::class)

package de.metaviewsoft.chordprogressionhelper.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * [AudioSink] backed by AVAudioEngine + AVAudioPlayerNode.
 *
 * Emulates AudioTrack's streaming semantics: [write] converts 16-bit PCM to a Float32 buffer,
 * schedules it on the player node and BLOCKS via a counting semaphore once [MAX_QUEUED_UNITS]
 * reference-sized units' worth of audio are in flight — that backpressure is what paces the
 * portable playback loop, exactly like `AudioTrack.write`. `flush`/`stop` discard scheduled
 * buffers (their completion handlers fire and re-signal the semaphore, so writers never
 * deadlock).
 *
 * The unit is [config]'s `bufferSizeBytes` (Android's own real buffer depth, ~double its
 * hardware minimum) rather than one permit per call: the portable playback loop writes
 * eighth-note-sized chunks during normal playback but much larger quarter-note chunks during
 * the count-in (see `AudioPlayer.playProgression`), and the count-in's hi-hat-only synthesis is
 * far cheaper than per-strum chord/drum/solo mixing. Counting raw calls let a whole count-in —
 * several seconds of cheap-to-generate audio — get scheduled almost instantly, so far ahead of
 * what's actually sounding that `onPositionChanged` (called right before each `write`) reported
 * measure 1 as current while the count-in was still audible. Weighting permits by duration keeps
 * the look-ahead bounded to roughly the same real-time window regardless of how large or cheap
 * an individual write is.
 *
 * `AVAudioPlayerNode` stops itself the moment it runs dry, and there is always a gap between
 * [play] and the first [write] (count-in generation, sample pre-warm, …). So [play] alone is
 * not enough — [write] re-`play()`s the node whenever it finds it stopped, which is what makes
 * playback actually audible after that initial gap.
 */
class AvAudioEngineSink(private val config: AudioSinkConfig) : AudioSink {

    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val format = AVAudioFormat(AVAudioPCMFormatFloat32, config.sampleRate.toDouble(), 1u, false)
    private val referenceUnitSamples = (config.bufferSizeBytes / 2).coerceAtLeast(1)
    private val queueSlots = dispatch_semaphore_create(MAX_QUEUED_UNITS)
    private var initialized = false
    private var started = false
    private var restartCount = 0L

    init {
        try {
            engine.attachNode(player)
            // Realize the output IO unit against the (already active) audio session before wiring
            // the graph — on the simulator the mixer-only path can otherwise leave the output at 0 Hz.
            val output = engine.outputNode
            engine.connect(engine.mainMixerNode, output, output.inputFormatForBus(0u))
            engine.connect(player, engine.mainMixerNode, format)
            engine.prepare()
            initialized = engine.startAndReturnError(null)
        } catch (t: Throwable) {
            AppLog.e("AvAudioEngineSink", "engine init failed: ${t.message}", t)
            initialized = false
        }
    }

    override fun play() {
        if (!engine.running) engine.startAndReturnError(null)
        player.play()
        started = true
    }

    override fun pause() {
        player.pause()
    }

    override fun write(data: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        if (!initialized || sizeInShorts <= 0) return -1
        val buffer = AVAudioPCMBuffer(format, sizeInShorts.toUInt())
        val channel = buffer.floatChannelData?.get(0) ?: return -1
        for (i in 0 until sizeInShorts) {
            channel[i] = data[offsetInShorts + i] / 32768.0f
        }
        buffer.frameLength = sizeInShorts.toUInt()

        // Block until enough queue slots are free (AudioTrack-style backpressure), then schedule.
        // A buffer spanning several reference units (e.g. a count-in quarter-note) costs that many
        // permits, so look-ahead stays bounded by duration, not by raw call count. Capped at the
        // semaphore's total capacity: a single buffer longer than the whole look-ahead window still
        // only ever needs to wait for everything currently in flight, never for its own (not yet
        // scheduled) completion.
        val units = ((sizeInShorts + referenceUnitSamples - 1) / referenceUnitSamples)
            .coerceIn(1, MAX_QUEUED_UNITS.toInt())
        repeat(units) { dispatch_semaphore_wait(queueSlots, DISPATCH_TIME_FOREVER) }
        player.scheduleBuffer(buffer) {
            repeat(units) { dispatch_semaphore_signal(queueSlots) }
        }

        // The node stops itself whenever it drains; restart it now that there is data again.
        if (started && !player.playing) {
            if (!engine.running) engine.startAndReturnError(null)
            player.play()
            if (restartCount++ == 0L) {
                AppLog.d("AvAudioEngineSink", "player node restarted after draining")
            }
        }
        return sizeInShorts
    }

    override fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun flush() {
        // Stopping the node discards scheduled buffers; completion handlers fire and free slots.
        player.stop()
    }

    override fun stop() {
        player.stop()
        started = false
    }

    override fun release() {
        try {
            player.stop()
            engine.stop()
        } catch (t: Throwable) {
            AppLog.w("AvAudioEngineSink", "release failed: ${t.message}", t)
        }
        started = false
        initialized = false
    }

    override val playbackHeadPosition: Int
        get() {
            val nodeTime = player.lastRenderTime ?: return 0
            val playerTime = player.playerTimeForNodeTime(nodeTime) ?: return 0
            return playerTime.sampleTime.toInt().coerceAtLeast(0)
        }

    override val isPlaying: Boolean get() = player.playing || (started && engine.running)

    override val isInitialized: Boolean get() = initialized

    private companion object {
        const val MAX_QUEUED_UNITS = 8L
    }
}

/** Builds [AvAudioEngineSink]s. Mirrors the Android factory; iOS has no meaningful min buffer query. */
object IosAudioSinkFactory : AudioSinkFactory {
    override fun minBufferSizeBytes(sampleRate: Int): Int {
        // ~20ms of 16-bit mono, in the same ballpark as typical AudioTrack minimums.
        return (sampleRate / 50) * 2
    }

    override fun create(config: AudioSinkConfig): AudioSink? =
        AvAudioEngineSink(config).takeIf { it.isInitialized }
}
