@file:OptIn(ExperimentalForeignApi::class)

package de.metaviewsoft.chordprogressionhelper.util

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_QUEUE_SERIAL
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

/**
 * [AudioTaskQueue] backed by a serial GCD queue (the iOS analogue of Android's HandlerThread).
 *
 * `clearPending` uses a generation counter: already-enqueued blocks whose generation no longer
 * matches become no-ops, mirroring `Handler.removeCallbacksAndMessages(null)` closely enough for
 * the preview path (the currently-running task is never interrupted on Android either).
 *
 * QoS tuning (user-interactive target queue) is intentionally omitted for now to keep the interop
 * surface minimal; the serial queue already preserves ordering. Latency tuning is a later step.
 */
class GcdAudioTaskQueue(name: String) : AudioTaskQueue {

    private val queue = dispatch_queue_create(name, DISPATCH_QUEUE_SERIAL)

    @Volatile
    private var generation = 0

    @Volatile
    private var aliveFlag = true

    override val isAlive: Boolean get() = aliveFlag

    override fun post(task: () -> Unit): Boolean {
        if (!aliveFlag) return false
        val myGeneration = generation
        dispatch_async(queue) {
            if (aliveFlag && generation == myGeneration) task()
        }
        return true
    }

    override fun clearPending() {
        generation++
    }

    override fun quit() {
        aliveFlag = false
    }
}
