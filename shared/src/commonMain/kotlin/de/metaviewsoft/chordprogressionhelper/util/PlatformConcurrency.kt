package de.metaviewsoft.chordprogressionhelper.util

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Reentrant lock replacing `synchronized(this)` in portable code (which is JVM-only).
 * Android backs it with `kotlin.synchronized`, iOS with `NSRecursiveLock`.
 */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}

/**
 * Dispatcher for the audio module's blocking/offloaded work (was `Dispatchers.IO`, which is a
 * JVM/Android extension and not available in commonMain). Android maps it to `Dispatchers.IO`;
 * iOS uses `Dispatchers.Default`.
 */
expect val audioIoDispatcher: CoroutineDispatcher
