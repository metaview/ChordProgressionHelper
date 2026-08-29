package de.metaviewsoft.chordprogressionhelper.util

/** How the platform should treat an [AudioSink]: normal media playback vs. low-latency preview. */
enum class AudioUsage { MUSIC, LOW_LATENCY }

/** Configuration for creating an [AudioSink] (always 16-bit PCM, mono). */
data class AudioSinkConfig(
    val sampleRate: Int,
    val bufferSizeBytes: Int,
    val usage: AudioUsage = AudioUsage.MUSIC,
)

/**
 * Platform PCM output sink (16-bit, mono). On Android this wraps an `AudioTrack` in streaming mode;
 * iOS will later back it with AVAudioEngine. Portable playback code depends only on this interface.
 */
interface AudioSink {
    fun play()

    /**
     * Blocking write of 16-bit PCM samples; returns the number of shorts written (or a negative
     * error code), matching `AudioTrack.write(short[], int, int)`.
     */
    fun write(data: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int

    fun setVolume(volume: Float)

    /** Pause playback without discarding buffered data (like `AudioTrack.pause`). */
    fun pause()

    fun flush()
    fun stop()
    fun release()

    /** Frames played since the last flush/start (like `AudioTrack.getPlaybackHeadPosition`). */
    val playbackHeadPosition: Int

    /** True while actually playing. */
    val isPlaying: Boolean

    /** True if the underlying track initialized successfully. */
    val isInitialized: Boolean
}

/** Creates [AudioSink]s and reports the platform minimum buffer size. */
interface AudioSinkFactory {
    /** Minimum buffer size in bytes for [sampleRate] (16-bit mono), or <= 0 if unsupported. */
    fun minBufferSizeBytes(sampleRate: Int): Int

    /** Build a sink, or null if the underlying track could not be initialized. */
    fun create(config: AudioSinkConfig): AudioSink?
}
