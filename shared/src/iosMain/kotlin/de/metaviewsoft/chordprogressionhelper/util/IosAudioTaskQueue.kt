package de.metaviewsoft.chordprogressionhelper.util

import kotlin.concurrent.Volatile
import platform.Foundation.NSOperationQueue

/**
 * [AudioTaskQueue] backed by a serial [NSOperationQueue] (maxConcurrentOperationCount = 1),
 * the iOS analogue of Android's HandlerThread.
 *
 * Uses the Objective-C Foundation API rather than the raw GCD C API: the K/N GCD bindings mix
 * `CPointer` (dispatch_queue_create) and ObjC-object parameters (dispatch_async), which don't
 * type-check against each other. NSOperationQueue avoids that entirely and its
 * `cancelAllOperations` is a clean match for `clearPending` (drops not-yet-started blocks;
 * the running one is never interrupted, same as on Android).
 *
 * QoS/priority tuning is deferred; ordering is what the preview path needs and the serial queue
 * guarantees it.
 */
class IosAudioTaskQueue(name: String) : AudioTaskQueue {

    private val queue = NSOperationQueue().apply {
        maxConcurrentOperationCount = 1
        this.name = name
    }

    @Volatile
    private var aliveFlag = true

    override val isAlive: Boolean get() = aliveFlag

    override fun post(task: () -> Unit): Boolean {
        if (!aliveFlag) return false
        queue.addOperationWithBlock {
            if (aliveFlag) task()
        }
        return true
    }

    override fun clearPending() {
        queue.cancelAllOperations()
    }

    override fun quit() {
        aliveFlag = false
        queue.cancelAllOperations()
    }
}
