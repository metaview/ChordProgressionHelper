package de.metaviewsoft.chordprogressionhelper.util

import kotlin.concurrent.Volatile

/** Platform log backend behind [AppLog]; Android installs an `android.util.Log` adapter at app start. */
interface AppLogger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable?)
    fun e(tag: String, msg: String, t: Throwable?)
}

/**
 * Logging facade for portable code. Mirrors the `android.util.Log` call shape so migrating a call
 * site is a pure rename. Without an installed [backend] all calls are silently dropped.
 */
object AppLog {
    @Volatile
    var backend: AppLogger? = null

    fun d(tag: String, msg: String) {
        backend?.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        backend?.i(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        backend?.w(tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        backend?.e(tag, msg, t)
    }
}
