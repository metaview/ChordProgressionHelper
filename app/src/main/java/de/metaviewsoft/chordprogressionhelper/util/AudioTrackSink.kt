package de.metaviewsoft.chordprogressionhelper.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

/**
 * Android [AudioSink] backed by an [AudioTrack] in streaming mode. Reproduces exactly the AudioTrack
 * configuration previously built inline in AudioPlayer; behaviour is unchanged.
 */
class AudioTrackSink internal constructor(private val track: AudioTrack) : AudioSink {
    override fun play() = track.play()

    override fun write(data: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int =
        track.write(data, offsetInShorts, sizeInShorts)

    override fun setVolume(volume: Float) {
        track.setVolume(volume)
    }

    override fun flush() = track.flush()
    override fun stop() = track.stop()
    override fun release() = track.release()

    override val playbackHeadPosition: Int get() = track.playbackHeadPosition
    override val isPlaying: Boolean get() = track.playState == AudioTrack.PLAYSTATE_PLAYING
    override val isInitialized: Boolean get() = track.state == AudioTrack.STATE_INITIALIZED
}

/** Builds [AudioTrackSink]s. Mirrors the two AudioTrack profiles used by AudioPlayer. */
object AndroidAudioSinkFactory : AudioSinkFactory {
    override fun minBufferSizeBytes(sampleRate: Int): Int =
        AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

    override fun create(config: AudioSinkConfig): AudioSink? {
        val attributes = when (config.usage) {
            AudioUsage.MUSIC -> AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            AudioUsage.LOW_LATENCY -> AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(config.bufferSizeBytes)
        if (config.usage == AudioUsage.LOW_LATENCY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = try {
            builder.build()
        } catch (e: Exception) {
            return null
        }
        return AudioTrackSink(track)
    }
}
