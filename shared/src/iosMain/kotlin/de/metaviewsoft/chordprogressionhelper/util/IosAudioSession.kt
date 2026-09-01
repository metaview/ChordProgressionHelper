@file:OptIn(ExperimentalForeignApi::class)

package de.metaviewsoft.chordprogressionhelper.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSError

/**
 * Configures the process-wide iOS audio session so the shared [AudioPlayer]'s output is actually
 * routed to the speaker. Called once from [de.metaviewsoft.chordprogressionhelper.IosAppEnvironment].
 *
 * Category `Playback` = "this app plays audio that matters" (keeps playing when the ringer is
 * silent, mixes according to the system). Activation is what actually turns the route on.
 *
 * NOTE on the K/N binding: the ObjC selectors are `setCategory:error:` and `setActive:error:`, so
 * BOTH require an NSError** out-param — calling `setActive(true)` with one argument is what left it
 * "unresolved" in the earlier attempt. We pass a real error pointer and log any failure, but never
 * throw: a routing hiccup must not take down app startup (audio can still be retried on play).
 */
fun configureIosAudioSession() {
    try {
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()

            val categoryOk = session.setCategory(AVAudioSessionCategoryPlayback, err.ptr)
            if (!categoryOk) {
                AppLog.w("IosAudioSession", "setCategory(Playback) failed: ${err.value?.localizedDescription}")
            }

            val activeOk = session.setActive(true, err.ptr)
            if (!activeOk) {
                AppLog.w("IosAudioSession", "setActive(true) failed: ${err.value?.localizedDescription}")
            }

            if (categoryOk && activeOk) {
                AppLog.i("IosAudioSession", "AVAudioSession ready: category=Playback, active=true")
            }
        }
    } catch (t: Throwable) {
        // Defensive: never let audio-session setup crash launch.
        AppLog.e("IosAudioSession", "AVAudioSession configuration threw: ${t.message}", t)
    }
}
