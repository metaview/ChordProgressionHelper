package de.metaviewsoft.chordprogressionhelper.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSRecursiveLock

actual class PlatformLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

// iOS has no Dispatchers.IO; Default (a bounded worker pool) is the right analogue for
// the audio module's blocking buffer generation.
actual val audioIoDispatcher: CoroutineDispatcher = Dispatchers.Default
