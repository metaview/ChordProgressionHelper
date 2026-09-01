package de.metaviewsoft.chordprogressionhelper.util

/**
 * [AppLogger] backend for iOS. Installed by [IosAppEnvironment].
 *
 * NOTE: it deliberately does NOT use `NSLog("...%@...", tag, msg)`. In Kotlin/Native the C-variadic
 * `%@` conversion does not bridge Kotlin `String`s to Obj-C objects, so the runtime dereferences the
 * raw string bytes as an `id` and crashes in `objc_opt_respondsToSelector` (EXC_BAD_ACCESS). We build
 * the full line in Kotlin and print it — `println` goes to stdout, which the simulator console and
 * device logs capture just as well, without any format-string interpretation.
 */
object IosAppLogger : AppLogger {
    override fun d(tag: String, msg: String) = println("D/$tag: $msg")

    override fun i(tag: String, msg: String) = println("I/$tag: $msg")

    override fun w(tag: String, msg: String, t: Throwable?) =
        println("W/$tag: $msg${t?.let { " (${it.message})" } ?: ""}")

    override fun e(tag: String, msg: String, t: Throwable?) =
        println("E/$tag: $msg${t?.let { " (${it.message})" } ?: ""}")
}
