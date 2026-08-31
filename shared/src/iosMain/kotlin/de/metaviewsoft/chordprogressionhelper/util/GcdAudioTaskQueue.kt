package de.metaviewsoft.chordprogressionhelper.util

import kotlin.concurrent.AtomicInt
import platform.darwin.DISPATCH_QUEUE_SERIAL
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_set_target_queue
import platform.darwin.dispatch_get_global_queue
import platform.darwin.QOS_CLASS_USER_INTERACTIVE

/**
 * [AudioTaskQueue] backed by a serial GCD queue at user-interactive QoS (the iOS analogue of
 * Android's HandlerThread at urgent-audio priority).
 *
 * `clearPending` uses a generation counter: already-enqueued blocks whose generation no longer
 * matches become no-ops, mirroring `Handler.removeCallbacksAndMessages(null)` closely enough for
 * the preview path (the currently-running task is never interrupted on Android either).
 */
class GcdAudioTaskQueue(name: String) : AudioTaskQueue {

    private val queue = dispatch_queue_create(name, DISPATCH_QUEUE_SERIAL).also {
        dispatch_set_target_queue(it, dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE.toLong(), 0u))
    }
    private val generation = AtomicInt(0)
    private val alive = AtomicInt(1)

    override val isAlive: Boolean get() = alive.value == 1

    override fun post(task: () -> Unit): Boolean {
        if (!isAlive) return false
        val myGeneration = generation.value
        dispatch_async(queue) {
            if (alive.value == 1 && generation.value == myGeneration) task()
        }
        return true
    }

    override fun clearPending() {
        generation.incrementAndGet()
    }

    override fun quit() {
        alive.value = 0
    }
}
