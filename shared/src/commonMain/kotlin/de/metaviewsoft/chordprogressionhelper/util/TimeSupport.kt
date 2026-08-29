package de.metaviewsoft.chordprogressionhelper.util

import kotlin.time.TimeSource

/** Portable replacement for `System.currentTimeMillis()` in timing/log code. */
object TimeSupport {
    private val origin = TimeSource.Monotonic.markNow()

    /** Milliseconds since an arbitrary fixed origin — only differences are meaningful. */
    fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}
