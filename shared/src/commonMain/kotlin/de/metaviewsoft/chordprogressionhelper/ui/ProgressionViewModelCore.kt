@file:OptIn(InternalSerializationApi::class)

package de.metaviewsoft.chordprogressionhelper.ui

import de.metaviewsoft.chordprogressionhelper.data.ProgressionStorage
import de.metaviewsoft.chordprogressionhelper.data.SettingsStore
import de.metaviewsoft.chordprogressionhelper.data.SettingsWatch
import de.metaviewsoft.chordprogressionhelper.data.SongSession
import de.metaviewsoft.chordprogressionhelper.model.*
import de.metaviewsoft.chordprogressionhelper.util.AppLog
import de.metaviewsoft.chordprogressionhelper.util.AudioPlayer
import de.metaviewsoft.chordprogressionhelper.util.TimeSupport
import de.metaviewsoft.chordprogressionhelper.util.Transposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

/**
 * Coordination seam for exclusive preview ownership. On Android this is adapted to the
 * app-side `PreviewCoordinator` (which also arbitrates with the PlaybackService).
 */
interface PreviewGate {
    fun requestStart(ownerId: String, isLooping: Boolean, onStop: () -> Unit)
    fun requestStop(ownerId: String)
}

/**
 * Portable core of the progression editor's view model. The Android `ProgressionViewModel`
 * wraps it with the app-wide singletons, its `viewModelScope`, a Toast-backed [onUserMessage]
 * and a `PreviewCoordinator` adapter; iOS will drive it directly.
 */
class ProgressionViewModelCore(
    private val storage: ProgressionStorage,
    private val settings: SettingsStore,
    private val session: SongSession,
    private val scope: CoroutineScope,
    private val previewGate: PreviewGate,
    private val onUserMessage: (String) -> Unit,
) {

    /**
     * The progression being edited IS the current section of the shared song — the single source of
     * truth. It is no longer a separately-loaded/stored copy, so it can never diverge from the song.
     * To change which progression is edited, change the section via SongViewModelCore.selectSongSection
     * (or replace the current section's progression via [loadProgression] / [confirmNewProgression]).
     */
    val progression: ChordProgression
        get() = session.currentProgression

    private val previewAudioPlayer = AudioPlayer()
    private var previewJob: Job? = null
    private var settingsWatch: SettingsWatch? = null

    private val _scaleDegreeChords = MutableStateFlow<List<Chord>>(emptyList())
    val scaleDegreeChords: StateFlow<List<Chord>> = _scaleDegreeChords.asStateFlow()

    private val _relatedChords = MutableStateFlow<List<Chord>>(emptyList())
    val relatedChords: StateFlow<List<Chord>> = _relatedChords.asStateFlow()

    private val _borrowedMinorChords = MutableStateFlow<List<Chord>>(emptyList())
    val borrowedMinorChords: StateFlow<List<Chord>> = _borrowedMinorChords.asStateFlow()

    private val _borrowedMajorChords = MutableStateFlow<List<Chord>>(emptyList())
    val borrowedMajorChords: StateFlow<List<Chord>> = _borrowedMajorChords.asStateFlow()

    private val _primaryChords = MutableStateFlow<List<Chord>>(emptyList())
    val primaryChords: StateFlow<List<Chord>> = _primaryChords.asStateFlow()

    private val _suggestedChord = MutableStateFlow<Chord?>(null)
    val suggestedChord: StateFlow<Chord?> = _suggestedChord.asStateFlow()

    private val _targetChord = MutableStateFlow<Chord?>(null)
    val targetChord: StateFlow<Chord?> = _targetChord.asStateFlow()

    private val _measures = MutableStateFlow<List<Measure>>(emptyList())
    val measures: StateFlow<List<Measure>> = _measures.asStateFlow()

    private val _selectedChord = MutableStateFlow<Chord?>(null)
    val selectedChord: StateFlow<Chord?> = _selectedChord.asStateFlow()

    private val _showDeleteConfirmation = MutableStateFlow<Int?>(null)
    val showDeleteConfirmation: StateFlow<Int?> = _showDeleteConfirmation.asStateFlow()

    private val _showNewProgressionConfirmation = MutableStateFlow(false)
    val showNewProgressionConfirmation: StateFlow<Boolean> = _showNewProgressionConfirmation.asStateFlow()

    // Transposition dialog: stores the new key to transpose to
    private val _showTransposeConfirmation = MutableStateFlow<Key?>(null)
    val showTransposeConfirmation: StateFlow<Key?> = _showTransposeConfirmation.asStateFlow()

    private val _tempo: MutableStateFlow<Int>
    val tempo: StateFlow<Int> get() = _tempo.asStateFlow()

    private val _key = MutableStateFlow(Key.C)
    val key: StateFlow<Key> = _key.asStateFlow()

    private val _isProgressionLooping = MutableStateFlow(false)
    val isProgressionLooping: StateFlow<Boolean> = _isProgressionLooping.asStateFlow()

    private val TAG = "ProgressionViewModel"

    init {
        // progression is derived from the shared SongSession — no separate load here.
        _tempo = MutableStateFlow(progression.tempo)
        _key.value = progression.key
        _isProgressionLooping.value = settings.isLoopingProgressionEnabled
        updateAllChords()
        updateMeasures()

        // Listen to settings changes and apply them to previewAudioPlayer in real-time
        settingsWatch = settings.registerChangeListener { key ->
            when (key) {
                SettingsStore.KEY_DRUM_LEVEL -> previewAudioPlayer.drumLevel = settings.drumLevel.toDouble()
                SettingsStore.KEY_SOLO_LEVEL -> previewAudioPlayer.soloLevel = settings.soloLevel.toDouble()
                SettingsStore.KEY_ENVELOPE_SCALE -> previewAudioPlayer.envelopeScale = settings.envelopeScale.toDouble()
                SettingsStore.KEY_HIHAT_HIGHPASS -> previewAudioPlayer.hiHatHighpass = settings.hiHatHighpass.toDouble()
                SettingsStore.KEY_STRUM_PRESET -> previewAudioPlayer.voicePreset = settings.strumPreset
                SettingsStore.KEY_DEFAULT_BPM -> setTempo(settings.defaultBpm)
                SettingsStore.KEY_MASTER_VOLUME -> previewAudioPlayer.masterVolume = settings.masterVolume.toDouble()
            }
        }
    }

    /** Call from the platform wrapper when the screen's view model is cleared. */
    fun dispose() {
        try {
            settingsWatch?.cancel()
        } catch (e: Exception) {
            AppLog.w(TAG, "dispose: unregisterChangeListener failed: ${e.message}", e)
        }
    }

    private fun saveCurrentSession() {
        // Persist the whole song through the single authoritative store. Because `progression`
        // is the current section's progression, editing it already mutated the song in place.
        session.save()
    }

    private fun updateAllChords() {
        val scaleChords = progression.getScaleDegreeChords()
        _scaleDegreeChords.value = scaleChords
        _primaryChords.value = listOfNotNull(scaleChords.getOrNull(0), scaleChords.getOrNull(5))
        updateRelatedChords(scaleChords)
        updateBorrowedChords()
    }

    fun getSavedProgressionNames(): List<String> {
        return storage.getSavedProgressionNames()
    }

    fun saveNamedProgression(name: String) {
        progression.name = name
        saveCurrentSession()
        storage.saveNamedProgression(name, progression)
    }

    fun loadProgression(name: String) {
        storage.loadProgression(name)?.let {
            session.replaceCurrentProgression(it)
            _tempo.value = it.tempo
            _key.value = it.key
            updateAllChords()
            updateMeasures()
            saveCurrentSession()
        }
    }

    fun deleteProgression(name: String) {
        storage.deleteProgression(name)
    }

    /** Refresh all UI state after externally changing the progression property.
     * Call this after setting progression from SongViewModelCore or other external sources. */
    fun refreshUIAfterProgressionChange() {
        _tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
    }

    fun setKey(key: Key) {
        if (progression.key == key) return

        // Check if there are any chords in the progression
        val hasChords = progression.measures.any { measure ->
            measure.chordEvents.isNotEmpty()
        }

        if (hasChords) {
            // Show transpose confirmation dialog
            _showTransposeConfirmation.value = key
        } else {
            // No chords, just change the key
            applyKeyChange(key, transpose = false)
        }
    }

    fun confirmTranspose(newKey: Key, transpose: Boolean) {
        applyKeyChange(newKey, transpose)
        _showTransposeConfirmation.value = null
    }

    fun onTransposeConfirmationHandled() {
        _showTransposeConfirmation.value = null
    }

    private fun applyKeyChange(newKey: Key, transpose: Boolean) {
        val oldKey = progression.key
        progression.key = newKey
        _key.value = newKey

        if (transpose) {
            transposeProgression(oldKey, newKey)
        }

        updateAllChords()
        _selectedChord.value = null
        _targetChord.value = null
        _suggestedChord.value = null
        saveCurrentSession()
    }

    private fun transposeProgression(oldKey: Key, newKey: Key) {
        Transposer.transpose(progression, Transposer.semitoneShift(oldKey, newKey))
    }

    fun setTempo(newTempo: Int) {
        val clampedTempo = newTempo.coerceIn(60, 240)
        if (clampedTempo != progression.tempo) {
            progression.tempo = clampedTempo
            _tempo.value = clampedTempo
            saveCurrentSession()
        }
    }

    fun incrementTempo() {
        setTempo(progression.tempo + 1)
    }

    fun decrementTempo() {
        setTempo(progression.tempo - 1)
    }

    fun setSelectedChord(chord: Chord, ownerId: String = "VIEWMODEL", startPreviewImmediately: Boolean = false) {
        // If startPreviewImmediately=true, start preview BEFORE updating UI state for lower latency
        if (startPreviewImmediately && settings.isChordPreviewEnabled) {
            previewChord(chord, ownerId)
        }

        _targetChord.value = null
        _suggestedChord.value = null

        val scaleChords = _scaleDegreeChords.value
        val relatedChords = _relatedChords.value
        val borrowedMinorChords = _borrowedMinorChords.value

        val scaleIndex = scaleChords.indexOf(chord)
        if (scaleIndex != -1) {
            if (scaleIndex > 0 && (scaleIndex - 1) < borrowedMinorChords.size) {
                _suggestedChord.value = borrowedMinorChords[scaleIndex - 1]
            }
        } else {
            val borrowedIndex = borrowedMinorChords.indexOf(chord)
            if (borrowedIndex != -1) {
                if ((borrowedIndex + 1) < scaleChords.size) {
                    _suggestedChord.value = scaleChords[borrowedIndex + 1]
                }
            } else if (chord in relatedChords) {
                val targetRootMidi = (chord.root.noteOffset + 5) % 12
                _targetChord.value = scaleChords.find { it.root.noteOffset == targetRootMidi }
            }
        }

        _selectedChord.value = chord

        // If not started immediately, start preview after UI update (old behavior)
        if (!startPreviewImmediately && settings.isChordPreviewEnabled) {
            previewChord(chord, ownerId)
        }
    }

    private fun previewChord(chord: Chord, ownerId: String = "VIEWMODEL") {
        val startTime = TimeSupport.nowMillis()
        AppLog.w(TAG, "previewChord$chord START (ownerId=$ownerId, previewJob=${previewJob?.isActive})")
        // First, immediately stop any currently running preview audio
        // This ensures the AudioPlayer's buffer is cleared before starting a new preview
        val hadActiveJob = previewJob?.isActive == true
        previewJob?.cancel()
        AppLog.d(TAG, "previewChord: cancelled previous job (hadActiveJob=$hadActiveJob)")
        try {
            val stopStartTime = TimeSupport.nowMillis()
            previewAudioPlayer.stop()
            val stopEndTime = TimeSupport.nowMillis()
            AppLog.d(TAG, "previewChord: called previewAudioPlayer.stop() (took=${stopEndTime - stopStartTime}ms)")
        } catch (e: Exception) {
            AppLog.w(TAG, "previewChord: failed to stop previewAudioPlayer: ${e.message}", e)
        }

        if (settings.isChordPreviewEnabled) {
            // Apply current sound settings to the preview player IMMEDIATELY (outside coroutine) for lower latency
            previewAudioPlayer.drumLevel = settings.drumLevel.toDouble()
            previewAudioPlayer.soloLevel = settings.soloLevel.toDouble()
            previewAudioPlayer.strumLevel = settings.strumLevel.toDouble()
            previewAudioPlayer.envelopeScale = settings.envelopeScale.toDouble()
            previewAudioPlayer.hiHatHighpass = settings.hiHatHighpass.toDouble()

            // Register as preview owner so the Coordinator can stop us if another owner starts
            // onStop should cancel the running coroutine AND stop the audio buffer immediately
            val onStop: () -> Unit = {
                try {
                    previewJob?.cancel()
                } catch (e: Exception) {
                    AppLog.w(TAG, "onStop: previewJob.cancel failed: ${e.message}", e)
                }
                try {
                    previewAudioPlayer.stop()
                } catch (e: Exception) {
                    AppLog.w(TAG, "onStop: previewAudioPlayer.stop failed: ${e.message}", e)
                }
            }
            val coordStartTime = TimeSupport.nowMillis()
            previewGate.requestStart(ownerId, false, onStop)
            AppLog.d(TAG, "previewChord: PreviewCoordinator.requestStart returned (took=${TimeSupport.nowMillis() - coordStartTime}ms)")

            // Use Dispatchers.Main.immediate for minimal latency.
            // Start a SUSTAINED chord that keeps sounding until the key is released (releaseChordPreview).
            // Coordinator ownership is kept until a new preview supersedes it or stop() is called, so
            // an external preview can still interrupt us via onStop.
            previewJob = scope.launch(Dispatchers.Main.immediate) {
                try {
                    previewAudioPlayer.startSustainedChord(chord, settings.pluckStrength)
                } catch (e: Exception) {
                    AppLog.w(TAG, "previewChord: startSustainedChord failed: ${e.message}", e)
                }
            }
        }
        AppLog.d(TAG, "previewChord$chord END (totalSetupTime=${TimeSupport.nowMillis() - startTime}ms)")
    }

    /** Key-up from a chord button: let the currently-held chord preview ring out and stop. */
    fun releaseChordPreview() {
        try {
            previewAudioPlayer.releaseSustainedChord()
        } catch (e: Exception) {
            AppLog.w(TAG, "releaseChordPreview failed: ${e.message}", e)
        }
    }

    private fun updateRelatedChords(allChords: List<Chord>) {
        _relatedChords.value = allChords.mapNotNull { targetChord ->
            if (targetChord.quality == ChordType.DIMINISHED) return@mapNotNull null
            val dominantRootMidi = (targetChord.root.noteOffset + 7) % 12
            val dominantRootNote = Note.entries.first { it.noteOffset == dominantRootMidi }
            Chord(dominantRootNote, ChordType.DOMINANT_SEVENTH, "V7/${targetChord.scaleDegreeName}")
        }
    }

    private fun updateBorrowedChords() {
        // Borrowed minor chords: parallel minor chords from current major key (e.g., C -> Cm chords)
        _borrowedMinorChords.value = progression.getParallelMinorChords()

        // Borrowed major chords: parallel major chords from parallel minor key (e.g., C -> Am -> A major chords)
        _borrowedMajorChords.value = progression.getParallelMajorChords()
    }

    fun addChordToMeasure(measureIndex: Int, eighthNoteIndex: Int, chord: Chord) {
        progression.measures[measureIndex].addChord(chord, eighthNoteIndex)
        updateMeasures()
        saveCurrentSession()
    }

    fun addChordToMeasure(measureIndex: Int, eighthNoteIndex: Int) {
        _selectedChord.value?.let {
            addChordToMeasure(measureIndex, eighthNoteIndex, it)
        }
    }

    fun removeChordFromMeasure(measureIndex: Int, eighthNoteIndex: Int) {
        if (measureIndex in progression.measures.indices) {
            progression.measures[measureIndex].removeChordAt(eighthNoteIndex)
            updateMeasures()
            saveCurrentSession()
        }
    }

    fun setStrummingPattern(measureIndex: Int, pattern: StrummingPattern) {
        if (measureIndex in progression.measures.indices) {
            progression.measures[measureIndex].strummingPattern = pattern
            updateMeasures()
            saveCurrentSession()
        }
    }

    fun setDrumPattern(measureIndex: Int, pattern: DrumPattern) {
        if (measureIndex in progression.measures.indices) {
            progression.measures[measureIndex].drumPattern = pattern
            updateMeasures()
            saveCurrentSession()
        }
    }

    fun setSoloPattern(measureIndex: Int, pattern: SoloPattern) {
        if (measureIndex in progression.measures.indices) {
            progression.measures[measureIndex].soloPattern = pattern
            updateMeasures()
            saveCurrentSession()
        }
    }

    fun addMeasure() {
        progression.addMeasure(withChord = _selectedChord.value)
        updateMeasures()
        saveCurrentSession()
    }

    fun removeMeasure(measureIndex: Int) {
        if (measureIndex in progression.measures.indices) {
            val measure = progression.measures[measureIndex]
            if (measure.chordEvents.isNotEmpty()) {
                _showDeleteConfirmation.value = measureIndex
            } else {
                progression.removeMeasure(measureIndex)
                updateMeasures()
                saveCurrentSession()
            }
        }
    }

    fun confirmRemoveMeasure(measureIndex: Int) {
        if (measureIndex in progression.measures.indices) {
            progression.removeMeasure(measureIndex)
            updateMeasures()
            saveCurrentSession()
        }
    }

    fun duplicateMeasure(measureIndex: Int) {
        if (measureIndex in progression.measures.indices) {
            val originalMeasure = progression.measures[measureIndex]
            // Create a deep copy of the measure
            val newMeasure = Measure(progression.measures.size + 1)

            // Copy chord events (convert quarterNote back to eighthNoteIndex for addChord)
            originalMeasure.chordEvents.forEach { event ->
                newMeasure.addChord(event.chord, event.quarterNote * 2)
            }

            // Copy strumming pattern
            try {
                newMeasure.strummingPattern = StrummingPattern(
                    originalMeasure.strummingPattern.name,
                    originalMeasure.strummingPattern.strums.toList()
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to copy strumming pattern: ${e.message}")
            }

            // Copy drum pattern
            try {
                newMeasure.drumPattern = DrumPattern(
                    originalMeasure.drumPattern.name,
                    originalMeasure.drumPattern.steps.map {
                        DrumStep(it.kick, it.snare, it.hiHat)
                    }
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to copy drum pattern: ${e.message}")
            }

            // Copy solo pattern (new elements only)
            try {
                newMeasure.soloPattern = SoloPattern(
                    originalMeasure.soloPattern.name,
                    // Copy elements
                    originalMeasure.soloPattern.elements.map { element ->
                        when (element) {
                            is SoloElement.Note -> SoloElement.Note(element.midi, element.lengthEighths)
                            is SoloElement.Rest -> SoloElement.Rest(element.lengthEighths)
                            is SoloElement.LetRing -> SoloElement.LetRing(element.lengthEighths)
                        }
                    }
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to copy solo pattern: ${e.message}")
            }

            // Insert the new measure right after the original
            progression.measures.add(measureIndex + 1, newMeasure)

            // Renumber all measures
            progression.measures.forEachIndexed { index, measure ->
                measure.number = index + 1
            }

            updateMeasures()
            saveCurrentSession()
        }
    }

    fun onDeleteConfirmationHandled() {
        _showDeleteConfirmation.value = null
    }

    fun requestNewProgression() {
        _showNewProgressionConfirmation.value = true
    }

    fun confirmNewProgression(template: ProgressionTemplate?, newKey: Key? = null, newTempo: Int? = null) {
        val selectedKey = newKey ?: _key.value
        val selectedTempo = (newTempo ?: progression.tempo).coerceIn(60, 240)

        val newProgression = if (template != null) {
            ProgressionTemplates.createProgressionFromTemplate(template, selectedKey).apply {
                tempo = selectedTempo
            }
        } else {
            ChordProgression(
                key = selectedKey,
                tempo = selectedTempo
            ).apply {
                shuffleFactor = settings.shuffleFactor
            }
        }
        session.replaceCurrentProgression(newProgression)
        _tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        saveCurrentSession()
    }

    fun onNewProgressionConfirmationHandled() {
        _showNewProgressionConfirmation.value = false
    }

    fun moveMeasure(fromPosition: Int, toPosition: Int) {
        progression.moveMeasure(fromPosition, toPosition)
        updateMeasures()
    }

    fun finalizeMeasureMove() {
        progression.renumberMeasures()
        updateMeasures()
        saveCurrentSession()
    }

    fun onRepeatToggle(isToggled: Boolean) {
        _isProgressionLooping.value = isToggled
        settings.isLoopingProgressionEnabled = isToggled
    }

    private fun updateMeasures() {
        _measures.value = progression.measures.map { it.copy(chordEvents = it.chordEvents.toMutableList()) }
    }

    fun stopAnyLocalPreview() {
        try {
            AppLog.d(TAG, "stopAnyLocalPreview: requested")
            onUserMessage("Stopping local preview...")
            previewJob?.cancel()
            previewJob = null
            try {
                previewAudioPlayer.stop()
            } catch (e: Exception) {
                AppLog.e(TAG, "stopAnyLocalPreview: previewAudioPlayer.stop() failed: ${e.message}", e)
                onUserMessage("Error stopping preview audio: ${e.message}")
            }
            // Ensure coordinator knows viewmodel isn't owner anymore
            try {
                previewGate.requestStop("VIEWMODEL")
            } catch (e: Exception) {
                AppLog.e(TAG, "stopAnyLocalPreview: PreviewCoordinator.requestStop failed: ${e.message}", e)
                onUserMessage("Error notifying PreviewCoordinator: ${e.message}")
            }
            AppLog.d(TAG, "stopAnyLocalPreview: completed")
        } catch (e: Exception) {
            AppLog.e(TAG, "stopAnyLocalPreview: unexpected error: ${e.message}", e)
            onUserMessage("Unexpected error stopping preview: ${e.message}")
        }
    }

    fun stopPreviewNow() {
        // Immediate, synchronous stop of the ViewModel-local preview player.
        // This mirrors how dialog code talks directly to AudioPlayer: cancel coroutine and stop audio.
        try {
            previewJob?.cancel()
            previewJob = null
        } catch (e: Exception) {
            AppLog.e(TAG, "stopPreviewNow: failed to cancel previewJob: ${e.message}", e)
        }
        try {
            previewAudioPlayer.stop()
        } catch (e: Exception) {
            AppLog.e(TAG, "stopPreviewNow: previewAudioPlayer.stop() failed: ${e.message}", e)
        }
    }
}
