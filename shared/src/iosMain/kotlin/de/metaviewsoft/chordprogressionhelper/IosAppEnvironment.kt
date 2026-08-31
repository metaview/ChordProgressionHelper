package de.metaviewsoft.chordprogressionhelper

import com.russhwolf.settings.NSUserDefaultsSettings
import de.metaviewsoft.chordprogressionhelper.data.ProgressionStorage
import de.metaviewsoft.chordprogressionhelper.data.SettingsStore
import de.metaviewsoft.chordprogressionhelper.data.SongSession
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.ui.PreviewGate
import de.metaviewsoft.chordprogressionhelper.ui.ProgressionViewModelCore
import de.metaviewsoft.chordprogressionhelper.ui.SongViewModelCore
import de.metaviewsoft.chordprogressionhelper.util.AppLog
import de.metaviewsoft.chordprogressionhelper.util.AudioPlatform
import de.metaviewsoft.chordprogressionhelper.util.AudioPlayer
import de.metaviewsoft.chordprogressionhelper.util.IosAppLogger
import de.metaviewsoft.chordprogressionhelper.util.IosAudioPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

/**
 * Composition root for the iOS app: installs the platform seams and exposes the shared
 * singletons + view-model cores to Swift. Call [IosAppEnvironment.shared] once at app start
 * (its lazy init performs the platform installation).
 */
class IosAppEnvironment private constructor() {

    val settings: SettingsStore
    val storage: ProgressionStorage
    val session: SongSession
    val songViewModel: SongViewModelCore
    val progressionViewModel: ProgressionViewModelCore
    val playback: IosPlaybackController

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // Platform seams first — audio/view-model code requires them.
        AppLog.backend = IosAppLogger
        AudioPlatform.support = IosAudioPlatform
        try {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
            AVAudioSession.sharedInstance().setActive(true, null)
        } catch (t: Throwable) {
            AppLog.w("IosAppEnvironment", "AVAudioSession setup failed: ${t.message}", t)
        }

        settings = SettingsStore(NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults))

        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: "."
        storage = ProgressionStorage(
            fileSystem = FileSystem.SYSTEM,
            dir = "$documents/progressions".toPath(),
            logWarn = { AppLog.w("ProgressionStorage", it) }
        )

        session = SongSession(storage)
        songViewModel = SongViewModelCore(storage, settings, session)
        playback = IosPlaybackController(settings, songViewModel)
        progressionViewModel = ProgressionViewModelCore(
            storage = storage,
            settings = settings,
            session = session,
            scope = mainScope,
            previewGate = SingleOwnerPreviewGate,
            onUserMessage = { AppLog.i("UserMessage", it) },
        )
    }

    companion object {
        val shared: IosAppEnvironment by lazy { IosAppEnvironment() }
    }
}

/**
 * iOS has no PlaybackService: at most one local preview owner exists, so the gate only has to
 * stop the previous owner when a new one starts.
 */
object SingleOwnerPreviewGate : PreviewGate {
    private var currentOwner: String? = null
    private var currentOnStop: (() -> Unit)? = null

    override fun requestStart(ownerId: String, isLooping: Boolean, onStop: () -> Unit) {
        if (currentOwner != null && currentOwner != ownerId) {
            currentOnStop?.invoke()
        }
        currentOwner = ownerId
        currentOnStop = onStop
    }

    override fun requestStop(ownerId: String) {
        if (currentOwner == ownerId) {
            currentOwner = null
            currentOnStop = null
        }
    }
}

/**
 * Song playback for iOS, replacing Android's PlaybackService: drives the shared [AudioPlayer]
 * directly and mirrors the service's play/stop/position surface for the UI.
 */
class IosPlaybackController(
    private val settings: SettingsStore,
    private val songViewModel: SongViewModelCore,
) {
    private val audioPlayer = AudioPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** (measureIndex, strumIndex) of the strum currently sounding, or null when stopped. */
    private val _position = MutableStateFlow<Pair<Int, Int>?>(null)
    val position: StateFlow<Pair<Int, Int>?> = _position.asStateFlow()

    init {
        applyLiveSoundSettings()
    }

    private fun applyLiveSoundSettings() {
        audioPlayer.drumLevel = settings.drumLevel.toDouble()
        audioPlayer.soloLevel = settings.soloLevel.toDouble()
        audioPlayer.strumLevel = settings.strumLevel.toDouble()
        audioPlayer.envelopeScale = settings.envelopeScale.toDouble()
        audioPlayer.hiHatHighpass = settings.hiHatHighpass.toDouble()
        audioPlayer.voicePreset = settings.strumPreset
        audioPlayer.soloPreset = settings.soloPreset
        audioPlayer.shuffleFactor = settings.shuffleFactor
        audioPlayer.strumCrunchLevel = settings.strumCrunchLevel
        audioPlayer.soloCrunchLevel = settings.soloCrunchLevel
        audioPlayer.masterVolume = settings.masterVolume.toDouble()
    }

    /** Plays the whole song (all sections combined, practice-speed applied). */
    fun playSong() {
        if (_isPlaying.value) return
        applyLiveSoundSettings()
        val progression: ChordProgression = songViewModel.createSongPlaybackProgression()
        _isPlaying.value = true
        playbackJob = scope.launch {
            try {
                audioPlayer.playProgression(
                    progression = progression,
                    shouldLoop = { songViewModel.isSongLooping.value },
                    pluckStrength = settings.pluckStrength,
                    countInBeats = settings.countInBeatsSong,
                    onPositionChanged = { measureIndex, strumIndex ->
                        _position.value = measureIndex to strumIndex
                        progression.tempo = songViewModel.getPlaybackTempoForMeasure(measureIndex)
                    },
                )
            } finally {
                _isPlaying.value = false
                _position.value = null
            }
        }
    }

    fun stop() {
        audioPlayer.stop()
        playbackJob?.cancel()
        playbackJob = null
        audioPlayer.resetStopFlag()
        _isPlaying.value = false
        _position.value = null
    }
}
