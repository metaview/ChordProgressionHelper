package de.metaviewsoft.chordprogressionhelper.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Cancels an active [watch] collection. Call from Swift in `onDisappear`/`deinit`. */
class WatchHandle internal constructor(private val job: Job) {
    fun close() = job.cancel()
}

/**
 * Swift-friendly bridge for collecting a [Flow] on the main thread. SwiftUI code calls
 * `SomeFlowKt.watch(flow) { value in ... }` instead of dealing with coroutines directly.
 */
private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

fun <T> watch(flow: Flow<T>, block: (T) -> Unit): WatchHandle {
    val job = watchScope.launch {
        flow.collect { block(it) }
    }
    return WatchHandle(job)
}
