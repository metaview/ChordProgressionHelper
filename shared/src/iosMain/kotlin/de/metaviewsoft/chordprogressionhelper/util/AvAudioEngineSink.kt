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
 * schedules it on the player node and BLOCKS via a counting semaphore once [MAX_QUEUED_BUFFERS]
 * are in flight — that backpressure is what paces the portable playback loop, exactly like
 * `AudioTrack.write`. `flush`/`stop` discard scheduled buffers (their completion handlers fire
 * and re-signal the semaphore, so writers never deadlock).
 */
@OptIn(ExperimentalForeignApi::class)
class AvAudioEngineSink(private val config: AudioSinkConfig) : AudioSink {

    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val format = AVAudioFormat(AVAudioPCMFormatFloat32, config.sampleRate.toDouble(), 1u, false)
    private val queueSlots = dispatch_semaphore_create(MAX_QUEUED_BUFFERS)
    private var initialized = false
    private var framesWritten = 0L

    init {
        try {
            engine.attachNode(player)
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

        // Block until a queue slot is free (AudioTrack-style backpressure), then schedule.
        dispatch_semaphore_wait(queueSlots, DISPATCH_TIME_FOREVER)
        player.scheduleBuffer(buffer) {
            dispatch_semaphore_signal(queueSlots)
        }
        framesWritten += sizeInShorts
        return sizeInShorts
    }

    override fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun flush() {
        // Stopping the node discards scheduled buffers; completion handlers fire and free slots.
        player.stop()
        framesWritten = 0
    }

    override fun stop() {
        player.stop()
    }

    override fun release() {
        try {
            player.stop()
            engine.stop()
        } catch (t: Throwable) {
            AppLog.w("AvAudioEngineSink", "release failed: ${t.message}", t)
        }
        initialized = false
    }

    override val playbackHeadPosition: Int
        get() {
            val nodeTime = player.lastRenderTime ?: return 0
            val playerTime = player.playerTimeForNodeTime(nodeTime) ?: return 0
            return playerTime.sampleTime.toInt().coerceAtLeast(0)
        }

    override val isPlaying: Boolean get() = player.playing

    override val isInitialized: Boolean get() = initialized

    private companion object {
        const val MAX_QUEUED_BUFFERS = 8L
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
