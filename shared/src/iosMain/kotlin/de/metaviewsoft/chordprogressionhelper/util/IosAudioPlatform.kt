package de.metaviewsoft.chordprogressionhelper.util

import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.usleep

/** [AudioPlatformSupport] backend for iOS. Installed by [initIosPlatform]. */
@OptIn(ExperimentalForeignApi::class)
object IosAudioPlatform : AudioPlatformSupport {
    override val sinkFactory: AudioSinkFactory = IosAudioSinkFactory
    override val nativeBridge: NativeAudioBridge = UnavailableNativeAudioBridge

    override fun newAudioTaskQueue(name: String): AudioTaskQueue = GcdAudioTaskQueue(name)

    override fun sleepMillis(ms: Long) {
        if (ms > 0) usleep((ms * 1000).toUInt())
    }

    override val fileSystem: FileSystem = FileSystem.SYSTEM

    override val drumSampleCacheDir: Path? by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        caches?.toPath()
    }
}
