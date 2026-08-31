package de.metaviewsoft.chordprogressionhelper.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual class PlatformLock actual constructor() {
    private val lock = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(lock, block)
}

actual val audioIoDispatcher: CoroutineDispatcher = Dispatchers.IO
