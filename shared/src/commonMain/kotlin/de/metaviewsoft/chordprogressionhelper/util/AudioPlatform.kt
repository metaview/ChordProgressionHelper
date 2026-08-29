package de.metaviewsoft.chordprogressionhelper.util

import kotlin.concurrent.Volatile
import okio.FileSystem
import okio.Path

/**
 * Single-threaded queue executing audio work at elevated priority, in submission order.
 * On Android this wraps a `HandlerThread` (THREAD_PRIORITY_URGENT_AUDIO) + `Handler`;
 * iOS will back it with a dedicated high-priority thread.
 */
interface AudioTaskQueue {
    /** True while the underlying thread is running (like `HandlerThread.isAlive`). */
    val isAlive: Boolean

    /** Enqueue [task]; returns false if the queue is shutting down (like `Handler.post`). */
    fun post(task: () -> Unit): Boolean

    /** Drop all queued-but-not-started tasks (like `Handler.removeCallbacksAndMessages(null)`). */
    fun clearPending()

    /** Finish the running/queued tasks, then stop the thread (like `HandlerThread.quitSafely`). */
    fun quit()
}

/**
 * Everything platform-specific the portable audio playback code needs.
 * Android installs `AndroidAudioPlatform` in MyApplication.onCreate before any audio code runs.
 */
interface AudioPlatformSupport {
    val sinkFactory: AudioSinkFactory
    val nativeBridge: NativeAudioBridge

    fun newAudioTaskQueue(name: String): AudioTaskQueue

    /** Blocking sleep on the calling thread (audio pacing relies on this being blocking). */
    fun sleepMillis(ms: Long)

    val fileSystem: FileSystem

    /** Directory for cached drum samples, or null to disable the disk cache. */
    val drumSampleCacheDir: Path?
}

/** Installation point for the platform backend (same pattern as [AppLog.backend]). */
object AudioPlatform {
    @Volatile
    var support: AudioPlatformSupport? = null

    val requireSupport: AudioPlatformSupport
        get() = requireNotNull(support) { "AudioPlatform.support not installed" }
}
