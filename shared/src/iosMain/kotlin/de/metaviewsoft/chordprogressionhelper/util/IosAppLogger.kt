package de.metaviewsoft.chordprogressionhelper.util

import platform.Foundation.NSLog

/** [AppLogger] backend for iOS, delegating to NSLog. Installed by [initIosPlatform]. */
object IosAppLogger : AppLogger {
    override fun d(tag: String, msg: String) {
        NSLog("D/%@: %@", tag, msg)
    }

    override fun i(tag: String, msg: String) {
        NSLog("I/%@: %@", tag, msg)
    }

    override fun w(tag: String, msg: String, t: Throwable?) {
        NSLog("W/%@: %@%@", tag, msg, t?.let { " (${it.message})" } ?: "")
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        NSLog("E/%@: %@%@", tag, msg, t?.let { " (${it.message})" } ?: "")
    }
}
