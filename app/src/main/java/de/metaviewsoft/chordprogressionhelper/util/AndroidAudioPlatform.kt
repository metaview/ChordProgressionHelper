package de.metaviewsoft.chordprogressionhelper.util

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import okio.FileSystem
import okio.Path

/** [AudioTaskQueue] backed by a HandlerThread at urgent-audio priority (the previous inline setup). */
private class HandlerAudioTaskQueue(name: String) : AudioTaskQueue {
    private val thread = HandlerThread(name, Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)

    override val isAlive: Boolean get() = thread.isAlive
    override fun post(task: () -> Unit): Boolean = handler.post(task)
    override fun clearPending() = handler.removeCallbacksAndMessages(null)
    override fun quit() {
        thread.quitSafely()
    }
}

/** [AudioPlatformSupport] backend; installed by MyApplication.onCreate. */
object AndroidAudioPlatform : AudioPlatformSupport {
    override val sinkFactory: AudioSinkFactory = AndroidAudioSinkFactory
    override val nativeBridge: NativeAudioBridge = AndroidNativeAudioBridge

    override fun newAudioTaskQueue(name: String): AudioTaskQueue = HandlerAudioTaskQueue(name)

    override fun sleepMillis(ms: Long) = Thread.sleep(ms)

    override val fileSystem: FileSystem = FileSystem.SYSTEM

    /** Set from MyApplication.onCreate (context.cacheDir). */
    override var drumSampleCacheDir: Path? = null
}
