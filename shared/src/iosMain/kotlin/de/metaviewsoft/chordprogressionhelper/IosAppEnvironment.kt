@file:OptIn(
    com.russhwolf.settings.ExperimentalSettingsImplementation::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package de.metaviewsoft.chordprogressionhelper

import com.russhwolf.settings.NSUserDefaultsSettings
import de.metaviewsoft.chordprogressionhelper.data.ProgressionStorage
import de.metaviewsoft.chordprogressionhelper.data.SettingsStore
import de.metaviewsoft.chordprogressionhelper.data.SongSession
import de.metaviewsoft.chordprogressionhelper.data.SoundPreset
import de.metaviewsoft.chordprogressionhelper.model.Chord
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.ChordType
import de.metaviewsoft.chordprogressionhelper.model.DrumPattern
import de.metaviewsoft.chordprogressionhelper.model.DrumStep
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.Mode
import de.metaviewsoft.chordprogressionhelper.model.Note
import de.metaviewsoft.chordprogressionhelper.model.SoloPattern
import de.metaviewsoft.chordprogressionhelper.model.Strum
import de.metaviewsoft.chordprogressionhelper.model.StrummingPattern
import de.metaviewsoft.chordprogressionhelper.ui.DrumPatternEditor
import de.metaviewsoft.chordprogressionhelper.ui.PreviewGate
import de.metaviewsoft.chordprogressionhelper.ui.ProgressionViewModelCore
import de.metaviewsoft.chordprogressionhelper.ui.SoloChordRoot
import de.metaviewsoft.chordprogressionhelper.ui.SoloPatternEditor
import de.metaviewsoft.chordprogressionhelper.ui.SongViewModelCore
import de.metaviewsoft.chordprogressionhelper.ui.StrummingPatternEditor
import de.metaviewsoft.chordprogressionhelper.util.AppLog
import de.metaviewsoft.chordprogressionhelper.util.AudioPlatform
import de.metaviewsoft.chordprogressionhelper.util.AudioPlayer
import de.metaviewsoft.chordprogressionhelper.util.IosAppLogger
import de.metaviewsoft.chordprogressionhelper.util.IosAudioPlatform
import de.metaviewsoft.chordprogressionhelper.util.configureIosAudioSession
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
    val progressionPlayback: IosProgressionPlaybackController
    val patternPreview: IosPatternPreviewController
    val templatePreview: IosTemplatePreviewController
    val settingsPreview: IosSettingsPreviewController

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // Platform seams first — audio/view-model code requires them.
        AppLog.backend = IosAppLogger
        AudioPlatform.support = IosAudioPlatform
        // Route audio to the speaker (category Playback + activate). Defensive: never throws.
        configureIosAudioSession()

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
        progressionPlayback = IosProgressionPlaybackController(
            settings = settings,
            // The progression is always the current section of the shared song (session.currentProgression),
            // read lazily at play time so edits made just before pressing play are picked up.
            progressionProvider = { progressionViewModel.progression },
            shouldLoop = { progressionViewModel.isProgressionLooping.value },
        )
        patternPreview = IosPatternPreviewController(settings)
        templatePreview = IosTemplatePreviewController(settings)
        settingsPreview = IosSettingsPreviewController(settings)
    }

    // ---- Per-measure pattern editors (drums / strumming / solo) ----------------
    // Each `make…Editor` snapshots the current section so the editor holds a working copy;
    // the Swift sheet writes the result back via ProgressionViewModelCore.set…Pattern.

    fun makeDrumEditor(measureIndex: Int): DrumPatternEditor {
        val measures = session.currentProgression.measures
        val current = measures.getOrNull(measureIndex)?.drumPattern ?: DrumPattern.DEFAULT
        val used = measures.map { it.drumPattern }
            .distinctBy { p -> p.steps.joinToString(";") { "${it.kick}:${it.snare}:${it.hiHat}" } }
        return DrumPatternEditor(current, used)
    }

    fun makeStrummingEditor(measureIndex: Int): StrummingPatternEditor {
        val measures = session.currentProgression.measures
        val current = measures.getOrNull(measureIndex)?.strummingPattern ?: StrummingPattern.DEFAULT
        val used = measures.map { it.strummingPattern }
            .distinctBy { p -> p.strums.joinToString(",") { it.name } }
        return StrummingPatternEditor(current, used)
    }

    fun makeSoloEditor(measureIndex: Int): SoloPatternEditor {
        val progression = session.currentProgression
        val patterns = progression.measures.map { it.soloPattern }
        val chordRoots = progression.measures.map { measure ->
            measure.chordEvents.sortedBy { it.quarterNote }.map { event ->
                SoloChordRoot(event.quarterNote, ((event.chord.root.noteOffset % 12) + 12) % 12)
            }
        }
        return SoloPatternEditor(patterns, measureIndex, progression.key, progression.mode, chordRoots)
    }

    /**
     * Chord changes for the solo editor's chord row above a measure's note slots (one label at
     * the slot where each chord starts, e.g. Android's SoloPatternActivity chordRow) — a plain
     * read-only [List] so it bridges to a typed `[Measure.ChordEvent]` on iOS, unlike
     * `Measure.chordEvents` itself (see kmp-swift-bridging notes on MutableList props).
     */
    fun getMeasureChordEvents(measureIndex: Int): List<Measure.ChordEvent> {
        val measure = session.currentProgression.measures.getOrNull(measureIndex) ?: return emptyList()
        return measure.chordEvents.sortedBy { it.quarterNote }
    }

    companion object {
        val shared: IosAppEnvironment by lazy { IosAppEnvironment() }
    }
}

/**
 * Loops a single measure so the pattern editors can be auditioned in isolation. Builds a
 * one-measure [ChordProgression] from the given prototype and drives the shared [AudioPlayer]
 * directly (iOS has no PlaybackService). Lanes can be silenced individually so e.g. the drum
 * editor is heard without strumming/solo on top.
 */
class IosPatternPreviewController(private val settings: SettingsStore) {
    private val audioPlayer = AudioPlayer()
    /** Separate player for one-shot key/drum taps so they don't fight the loop. */
    private val tapPlayer = AudioPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playbackJob: Job? = null

    /** The [ChordProgression] currently looping via [playSoloWithAccompaniment]/[playMeasure], if
     * any — kept so [updateSoloPattern] can patch a measure's pattern in place while it plays. */
    private var activeProgression: ChordProgression? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Eighth-note slot (0..7) currently sounding, or -1 when stopped. */
    private val _currentSlot = MutableStateFlow(-1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()

    /** Measure index currently sounding (only varies during [playSoloWithAccompaniment]'s
     * multi-measure loop; always 0 during [playMeasure]'s single-measure loop), or -1 stopped. */
    private val _currentMeasure = MutableStateFlow(-1)
    val currentMeasure: StateFlow<Int> = _currentMeasure.asStateFlow()

    /// Preview-only solo preset override (for testing different instruments in SoloPatternSheet)
    var previewSoloPreset: SoundPreset? = null

    private fun applyLiveSoundSettings() {
        applySoundSettings(audioPlayer)
    }

    private fun applySoundSettings(player: AudioPlayer) {
        player.drumLevel = settings.drumLevel.toDouble()
        player.soloLevel = settings.soloLevel.toDouble()
        player.strumLevel = settings.strumLevel.toDouble()
        player.envelopeScale = settings.envelopeScale.toDouble()
        player.hiHatHighpass = settings.hiHatHighpass.toDouble()
        player.voicePreset = settings.strumPreset
        player.soloPreset = previewSoloPreset ?: settings.soloPreset
        player.shuffleFactor = settings.shuffleFactor
        player.strumCrunchLevel = settings.strumCrunchLevel
        player.soloCrunchLevel = settings.soloCrunchLevel
        player.masterVolume = settings.masterVolume.toDouble()
    }

    /** Warm the tap player's audio track so the first key press is not delayed. */
    fun warmUp() {
        applySoundSettings(tapPlayer)
        tapPlayer.ensurePreviewTrackReady()
    }

    /** Solo keyboard: start a sustained note, held until [releaseNote]. */
    fun startNote(midi: Int) {
        applySoundSettings(tapPlayer)
        tapPlayer.startSustainedNote(midi)
    }

    fun releaseNote() {
        tapPlayer.releaseSustainedNote()
    }

    /** Drum editor: audible feedback for toggling a lane. */
    fun tapKick() {
        applySoundSettings(tapPlayer)
        scope.launch { tapPlayer.previewKick(1.0) }
    }

    fun tapSnare() {
        applySoundSettings(tapPlayer)
        scope.launch { tapPlayer.previewSnare(1.0) }
    }

    fun tapHiHat() {
        applySoundSettings(tapPlayer)
        scope.launch { tapPlayer.previewHiHat(1.0) }
    }

    /**
     * Loop [prototypeMeasureIndex] of the current section with the supplied patterns applied.
     * Passing a pattern null keeps that lane silent.
     */
    fun playMeasure(
        session: SongSession,
        prototypeMeasureIndex: Int,
        drums: DrumPattern?,
        strumming: StrummingPattern?,
        solo: SoloPattern?,
    ) {
        stop()
        applyLiveSoundSettings()

        val source = session.currentProgression
        val prototype = source.measures.getOrNull(prototypeMeasureIndex)
        val progression = ChordProgression(
            name = "Preview",
            key = source.key,
            mode = source.mode,
            tempo = source.tempo,
        )
        progression.measures.clear()

        val measure = Measure(1)
        prototype?.chordEvents?.forEach { measure.addChord(it.chord, it.quarterNote * 2) }
        if (measure.chordEvents.isEmpty()) {
            progression.getScaleDegreeChords().firstOrNull()?.let { measure.addChord(it, 0) }
        }
        measure.drumPattern = drums ?: DrumPattern("Silent", List(8) { DrumStep() })
        measure.strummingPattern = strumming ?: StrummingPattern("Silent", List(8) { Strum.REST })
        measure.soloPattern = solo ?: SoloPattern("Silent", emptyList())
        progression.measures.add(measure)

        activeProgression = progression
        _isPlaying.value = true
        playbackJob = scope.launch {
            try {
                audioPlayer.playProgression(
                    progression = progression,
                    shouldLoop = { true },
                    pluckStrength = settings.pluckStrength,
                    countInBeats = 0,
                    onPositionChanged = { measureIndex, strumIndex ->
                        _currentMeasure.value = measureIndex
                        _currentSlot.value = strumIndex
                    },
                )
            } finally {
                activeProgression = null
                _isPlaying.value = false
                _currentMeasure.value = -1
                _currentSlot.value = -1
            }
        }
    }

    /**
     * Loop the whole current progression for the solo editor's "hear it with the band" preview:
     * every measure's REAL chords and strumming accompaniment, but the solo lane comes from
     * [soloPatterns] (the editor's live, possibly-unsaved patterns) rather than what's saved.
     * Drums are silenced — matches Android's SoloPatternActivity.startPreviewWithCurrentPattern,
     * which keeps the focus on solo + chords rather than the full mix.
     */
    fun playSoloWithAccompaniment(session: SongSession, soloPatterns: List<SoloPattern>) {
        stop()
        applyLiveSoundSettings()

        val source = session.currentProgression
        val progression = ChordProgression(
            name = "Preview",
            key = source.key,
            mode = source.mode,
            tempo = source.tempo,
        )
        progression.measures.clear()
        source.measures.forEachIndexed { index, sourceMeasure ->
            val measure = Measure(index + 1)
            sourceMeasure.chordEvents.forEach { measure.addChord(it.chord, it.quarterNote * 2) }
            measure.strummingPattern = sourceMeasure.strummingPattern
            measure.drumPattern = DrumPattern("Silent", List(8) { DrumStep() })
            measure.soloPattern = soloPatterns.getOrNull(index) ?: SoloPattern("Silent", emptyList())
            progression.measures.add(measure)
        }
        if (progression.measures.isEmpty()) return

        activeProgression = progression
        _isPlaying.value = true
        playbackJob = scope.launch {
            try {
                audioPlayer.playProgression(
                    progression = progression,
                    shouldLoop = { true },
                    pluckStrength = settings.pluckStrength,
                    countInBeats = 0,
                    onPositionChanged = { measureIndex, strumIndex ->
                        _currentMeasure.value = measureIndex
                        _currentSlot.value = strumIndex
                    },
                )
            } finally {
                activeProgression = null
                _isPlaying.value = false
                _currentMeasure.value = -1
                _currentSlot.value = -1
            }
        }
    }

    /**
     * Patches the solo pattern for [measureIndex] of the currently looping accompaniment in
     * place — no stop/restart, so the next time the loop reaches that measure it plays the new
     * pattern while the Stop button and the playing-position highlight keep going undisturbed.
     * Used by the solo editor keyboard (EDIT/LIVE) so playing along doesn't interrupt playback
     * (see PatternEditorModels.pressKey). No-op if nothing is playing or the index is out of range.
     */
    fun updateSoloPattern(measureIndex: Int, pattern: SoloPattern) {
        activeProgression?.measures?.getOrNull(measureIndex)?.soloPattern = pattern
    }

    fun stop() {
        audioPlayer.stop()
        playbackJob?.cancel()
        playbackJob = null
        audioPlayer.resetStopFlag()
        activeProgression = null
        _currentMeasure.value = -1
        _isPlaying.value = false
        _currentSlot.value = -1
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

    /** Global measure/strum index of the strum currently sounding, or -1 when stopped. Two plain
     * Int flows (rather than a Pair) so they bridge to Swift without KotlinPair casting. */
    private val _currentMeasureIndex = MutableStateFlow(-1)
    val currentMeasureIndex: StateFlow<Int> = _currentMeasureIndex.asStateFlow()
    private val _currentStrumIndex = MutableStateFlow(-1)
    val currentStrumIndex: StateFlow<Int> = _currentStrumIndex.asStateFlow()

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
                        _currentMeasureIndex.value = measureIndex
                        _currentStrumIndex.value = strumIndex
                        progression.tempo = songViewModel.getPlaybackTempoForMeasure(measureIndex)
                    },
                )
            } finally {
                _isPlaying.value = false
                _currentMeasureIndex.value = -1
                _currentStrumIndex.value = -1
            }
        }
    }

    fun stop() {
        audioPlayer.stop()
        playbackJob?.cancel()
        playbackJob = null
        audioPlayer.resetStopFlag()
        _isPlaying.value = false
        _currentMeasureIndex.value = -1
        _currentStrumIndex.value = -1
    }
}

/**
 * Playback for a single progression in the editor screen (iOS counterpart of Android's
 * progression preview). Drives the shared [AudioPlayer] directly, looping while [shouldLoop]
 * returns true. Exposes [currentMeasureIndex] (-1 when stopped) so the UI can highlight the
 * measure that is currently sounding.
 */
class IosProgressionPlaybackController(
    private val settings: SettingsStore,
    private val progressionProvider: () -> ChordProgression,
    private val shouldLoop: () -> Boolean,
) {
    private val audioPlayer = AudioPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Index of the measure currently sounding, or -1 when stopped. */
    private val _currentMeasureIndex = MutableStateFlow(-1)
    val currentMeasureIndex: StateFlow<Int> = _currentMeasureIndex.asStateFlow()

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

    fun play() {
        if (_isPlaying.value) return
        applyLiveSoundSettings()
        val progression = progressionProvider()
        _isPlaying.value = true
        playbackJob = scope.launch {
            try {
                audioPlayer.playProgression(
                    progression = progression,
                    shouldLoop = { shouldLoop() },
                    pluckStrength = settings.pluckStrength,
                    countInBeats = settings.countInBeats,
                    onPositionChanged = { measureIndex, _ ->
                        _currentMeasureIndex.value = measureIndex
                    },
                )
            } finally {
                _isPlaying.value = false
                _currentMeasureIndex.value = -1
            }
        }
    }

    fun stop() {
        audioPlayer.stop()
        playbackJob?.cancel()
        playbackJob = null
        audioPlayer.resetStopFlag()
        _isPlaying.value = false
        _currentMeasureIndex.value = -1
    }
}

/**
 * Loops an arbitrary, not-yet-committed [ChordProgression] — used by the "new progression"
 * template picker to audition a template (built via [de.metaviewsoft.chordprogressionhelper.util.buildProgressionFromTemplate])
 * before it replaces the current section.
 */
class IosTemplatePreviewController(private val settings: SettingsStore) {
    private val audioPlayer = AudioPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

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

    fun play(progression: ChordProgression) {
        stop()
        applyLiveSoundSettings()
        _isPlaying.value = true
        playbackJob = scope.launch {
            try {
                audioPlayer.playProgression(
                    progression = progression,
                    shouldLoop = { true },
                    pluckStrength = settings.pluckStrength,
                    countInBeats = 0,
                    onPositionChanged = { _, _ -> },
                )
            } finally {
                _isPlaying.value = false
            }
        }
    }

    fun stop() {
        audioPlayer.stop()
        playbackJob?.cancel()
        playbackJob = null
        audioPlayer.resetStopFlag()
        _isPlaying.value = false
    }
}

/**
 * One-shot instrument previews for the Settings screen ("hear the current solo/strum sound").
 * A dedicated [AudioPlayer] so a preview never fights a running song/pattern preview.
 */
class IosSettingsPreviewController(private val settings: SettingsStore) {
    private val audioPlayer = AudioPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Plays a single solo note (middle A) with the currently-configured solo instrument/level. */
    fun previewSolo() {
        audioPlayer.soloPreset = settings.soloPreset
        // Keep the keyboard-style minimum so the note is clearly audible even at low level settings.
        audioPlayer.soloLevel = settings.soloLevel.toDouble().coerceAtLeast(0.5)
        audioPlayer.soloCrunchLevel = settings.soloCrunchLevel
        audioPlayer.masterVolume = 1.0
        audioPlayer.ensurePreviewTrackReady()
        audioPlayer.triggerSoloNotePreview(midiNote = 69, durationSec = 1.8)
    }

    /** Strums a C major chord with the currently-configured strumming instrument/level. */
    fun previewStrum() {
        audioPlayer.voicePreset = settings.strumPreset
        audioPlayer.strumLevel = settings.strumLevel.toDouble()
        audioPlayer.strumCrunchLevel = settings.strumCrunchLevel
        audioPlayer.masterVolume = 1.0
        audioPlayer.ensurePreviewTrackReady()
        val chord = Chord(Note.C, ChordType.MAJOR, "I")
        scope.launch {
            audioPlayer.previewChord(chord, settings.pluckStrength)
        }
    }
}
