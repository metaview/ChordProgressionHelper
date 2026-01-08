package com.metaview.chordprogressionhelper.util

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * PreviewCoordinator
 *
 * Lightweight in-process singleton to coordinate "preview" ownership between the
 * PlaybackService (service-based previews) and UI-local preview players (ViewModel/AudioPlayer).
 *
 * Responsibilities:
 * - Ensure at most one preview owner is active at a time.
 * - When a new owner requests start, synchronously ask the previous owner to stop
 *   by invoking its registered stopCallback (outside the internal lock).
 * - Provide listeners (main-thread callbacks) so UI can react to owner changes.
 */
object PreviewCoordinator {
    private val TAG = "PreviewCoordinator"
    private val lock = Any()
    private var currentOwner: String? = null
    private var currentIsLooping: Boolean = false
    private var stopCallback: (() -> Unit)? = null
    private val listeners = mutableListOf<(owner: String?, isLooping: Boolean) -> Unit>()

    /**
     * Request to become the active preview owner. If another owner is active, its stopCallback
     * will be invoked (best-effort) before the new owner is active.
     *
     * @param ownerId unique id for caller (e.g. "SERVICE", "VIEWMODEL", "MAIN_...")
     * @param isLooping whether the requested preview will loop
     * @param onStop callback the coordinator will call to request this owner to stop
     */
    fun requestStart(ownerId: String, isLooping: Boolean, onStop: () -> Unit) {
        var prevToStop: (() -> Unit)? = null
        var prevOwner: String? = null
        synchronized(lock) {
            // if same owner re-requests, just update flags & callback
            if (currentOwner == ownerId) {
                currentIsLooping = isLooping
                stopCallback = onStop
                notifyListenersLocked()
                Log.d(TAG, "requestStart: owner re-requested: $ownerId (loop=$isLooping)")
                return
            }
            prevToStop = stopCallback
            prevOwner = currentOwner
            currentOwner = ownerId
            currentIsLooping = isLooping
            stopCallback = onStop
            Log.d(TAG, "requestStart: switching owner from $prevOwner to $ownerId (loop=$isLooping)")
        }
        // ask previous owner to stop outside lock
        if (prevToStop != null) {
            val cb = prevToStop
            try {
                Log.d(TAG, "requestStart: invoking stopCallback of previous owner $prevOwner")
                cb?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "requestStart: previous stopCallback threw: ${e.message}")
            }
        }
        notifyListeners()
    }

    /**
     * Request that the given owner be removed as active owner. If it is not the current owner,
     * this is a no-op.
     */
    fun requestStop(ownerId: String) {
        var changed = false
        synchronized(lock) {
            if (currentOwner == ownerId) {
                Log.d(TAG, "requestStop: owner $ownerId stopping")
                currentOwner = null
                currentIsLooping = false
                stopCallback = null
                changed = true
            } else {
                Log.d(TAG, "requestStop: owner $ownerId asked to stop but not current owner (current=$currentOwner)")
            }
        }
        if (changed) notifyListeners()
    }

    /**
     * Force-stop any active preview owner: call its stop callback and clear state.
     */
    fun forceStopAll() {
        var prevToStop: (() -> Unit)? = null
        var prevOwner: String? = null
        synchronized(lock) {
            prevToStop = stopCallback
            prevOwner = currentOwner
            currentOwner = null
            currentIsLooping = false
            stopCallback = null
            Log.d(TAG, "forceStopAll: cleared owner (was=$prevOwner)")
        }
        if (prevToStop != null) {
            val cb = prevToStop
            try {
                Log.d(TAG, "forceStopAll: invoking stopCallback of previous owner $prevOwner")
                cb?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "forceStopAll: previous stopCallback threw: ${e.message}")
            }
        }
        notifyListeners()
    }

    fun addListener(listener: (owner: String?, isLooping: Boolean) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: (owner: String?, isLooping: Boolean) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val owner: String?
        val loop: Boolean
        synchronized(lock) { owner = currentOwner; loop = currentIsLooping }
        Handler(Looper.getMainLooper()).post {
            val snapshot: List<(owner: String?, isLooping: Boolean) -> Unit>
            synchronized(lock) { snapshot = listeners.toList() }
            for (l in snapshot) {
                try {
                    l(owner, loop)
                } catch (e: Exception) {
                    Log.w(TAG, "notifyListeners: listener threw: ${e.message}", e)
                }
            }
        }
    }

    // internal helper when holding lock
    private fun notifyListenersLocked() {
        val owner = currentOwner
        val loop = currentIsLooping
        Handler(Looper.getMainLooper()).post {
            val snapshot: List<(owner: String?, isLooping: Boolean) -> Unit>
            synchronized(lock) { snapshot = listeners.toList() }
            for (l in snapshot) {
                try {
                    l(owner, loop)
                } catch (e: Exception) {
                    Log.w(TAG, "notifyListenersLocked: listener threw: ${e.message}", e)
                }
            }
        }
    }
}
