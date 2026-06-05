package de.metaviewsoft.chordprogressionhelper.ui

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import de.metaviewsoft.chordprogressionhelper.MyApplication
import de.metaviewsoft.chordprogressionhelper.data.ProgressionRepository
import de.metaviewsoft.chordprogressionhelper.data.SettingsRepository
import de.metaviewsoft.chordprogressionhelper.model.*
import de.metaviewsoft.chordprogressionhelper.util.AudioPlayer
import de.metaviewsoft.chordprogressionhelper.util.PreviewCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(InternalSerializationApi::class)
class ProgressionViewModel(application: Application) : AndroidViewModel(application) {

    private val progressionRepository: ProgressionRepository = (application as MyApplication).progressionRepository
    private val settingsRepository: SettingsRepository = (application as MyApplication).settingsRepository
    private var song: Song
    private var currentSectionIndex: Int = 0

    var progression: ChordProgression
        private set

    private val previewAudioPlayer = AudioPlayer()
    private var previewJob: Job? = null
    private lateinit var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener

    private val _scaleDegreeChords = MutableLiveData<List<Chord>>()
    val scaleDegreeChords: LiveData<List<Chord>> = _scaleDegreeChords

    private val _relatedChords = MutableLiveData<List<Chord>>()
    val relatedChords: LiveData<List<Chord>> = _relatedChords

    private val _borrowedMinorChords = MutableLiveData<List<Chord>>()
    val borrowedMinorChords: LiveData<List<Chord>> = _borrowedMinorChords

    private val _borrowedMajorChords = MutableLiveData<List<Chord>>()
    val borrowedMajorChords: LiveData<List<Chord>> = _borrowedMajorChords

    private val _primaryChords = MutableLiveData<List<Chord>>()
    val primaryChords: LiveData<List<Chord>> = _primaryChords

    private val _suggestedChord = MutableLiveData<Chord?>()
    val suggestedChord: LiveData<Chord?> = _suggestedChord

    private val _targetChord = MutableLiveData<Chord?>()
    val targetChord: LiveData<Chord?> = _targetChord

    private val _measures = MutableLiveData<List<Measure>>()
    val measures: LiveData<List<Measure>> = _measures

    private val _selectedChord = MutableLiveData<Chord?>()
    val selectedChord: LiveData<Chord?> = _selectedChord

    private val _showDeleteConfirmation = MutableLiveData<Int?>()
    val showDeleteConfirmation: LiveData<Int?> = _showDeleteConfirmation

    private val _showClearConfirmationDialog = MutableLiveData<Boolean>()
    val showClearConfirmationDialog: LiveData<Boolean> = _showClearConfirmationDialog

    private val _showNewProgressionConfirmation = MutableLiveData<Boolean>()
    val showNewProgressionConfirmation: LiveData<Boolean> = _showNewProgressionConfirmation

    // Transposition dialog: stores the new key to transpose to
    private val _showTransposeConfirmation = MutableLiveData<Key?>()
    val showTransposeConfirmation: LiveData<Key?> = _showTransposeConfirmation

    private val _songSectionNames = MutableLiveData<List<String>>()
    val songSectionNames: LiveData<List<String>> = _songSectionNames

    private val _selectedSongSectionIndex = MutableLiveData<Int>()
    val selectedSongSectionIndex: LiveData<Int> = _selectedSongSectionIndex

    private val _songName = MutableLiveData<String>()
    val songName: LiveData<String> = _songName

    val tempo: MutableLiveData<Int>
    private val _key = MutableLiveData<Key>()
    val key: LiveData<Key> = _key

    val isLooping: MutableLiveData<Boolean> = MutableLiveData(false)
    val isLoopingSong: MutableLiveData<Boolean> = MutableLiveData(false)

    private val TAG = "ProgressionViewModel"

    init {
        song = progressionRepository.loadLastSongSession().also { it.ensureValid() }
        progression = song.sections.first().progression
        tempo = MutableLiveData(progression.tempo)
        _key.value = progression.key
        isLooping.value = settingsRepository.isLoopingEnabled
        isLoopingSong.value = settingsRepository.isLoopingSongEnabled
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()

        // Listen to settings changes and apply them to previewAudioPlayer in real-time
        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SettingsRepository.KEY_DRUM_LEVEL -> previewAudioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
                SettingsRepository.KEY_SOLO_LEVEL -> previewAudioPlayer.soloLevel = settingsRepository.soloLevel.toDouble()
                SettingsRepository.KEY_ENVELOPE_SCALE -> previewAudioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
                SettingsRepository.KEY_HIHAT_HIGHPASS -> previewAudioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
                SettingsRepository.KEY_STRUM_PRESET -> previewAudioPlayer.voicePreset = settingsRepository.strumPreset
                SettingsRepository.KEY_DEFAULT_BPM -> setTempo(settingsRepository.defaultBpm)
            }
        }
        settingsRepository.registerChangeListener(prefsListener)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            settingsRepository.unregisterChangeListener(prefsListener)
        } catch (e: Exception) {
            Log.w(TAG, "onCleared: unregisterChangeListener failed: ${e.message}", e)
        }
    }

    private fun saveCurrentSession() {
        progressionRepository.saveLastSongSession(song)
        progressionRepository.saveLastSession(progression)
    }

    private fun updateSongSectionsState() {
        song.ensureValid()
        if (currentSectionIndex !in song.sections.indices) {
            currentSectionIndex = 0
        }
        _songName.value = song.name.ifBlank { "New Song" }
        _songSectionNames.value = song.sections.mapIndexed { index, section ->
            val label = section.name.ifBlank { "Section ${index + 1}" }
            "${index + 1}. $label"
        }
        _selectedSongSectionIndex.value = currentSectionIndex
    }

    /** Maps a global measure index (as used in createSongPlaybackProgression) back to the section index. */
    fun getSectionIndexForMeasure(measureIndex: Int): Int {
        var offset = 0
        for ((i, section) in song.sections.withIndex()) {
            val count = section.progression.measures.size.coerceAtLeast(1)
            if (measureIndex < offset + count) return i
            offset += count
        }
        return song.sections.lastIndex.coerceAtLeast(0)
    }

    /** Returns the tempo (BPM) of the section that contains the given global measure index. */
    fun getTempoForMeasure(measureIndex: Int): Int {
        val sectionIndex = getSectionIndexForMeasure(measureIndex)
        val section = song.sections.getOrNull(sectionIndex)
        return (section?.progression?.tempo ?: progression.tempo).coerceIn(60, 240)
    }

    /** Returns distinct progressions used in the song (by identity, preserving order). */
    fun getUniqueSongProgressions(): List<ChordProgression> =
        song.sections.map { it.progression }.distinctBy { System.identityHashCode(it) }

    fun addSongSection(name: String?) {
        val sectionName = name?.trim().orEmpty().ifBlank { "Section ${song.sections.size + 1}" }
        val baseKey = _key.value ?: progression.key
        val newProgression = ChordProgression(
            name = sectionName,
            key = baseKey,
            mode = progression.mode,
            tempo = progression.tempo,
            shuffleFactor = settingsRepository.shuffleFactor
        )
        song.sections.add(SongSection(name = sectionName, progression = newProgression))
        currentSectionIndex = song.sections.lastIndex
        progression = song.sections[currentSectionIndex].progression
        tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Adds a new section linked to an existing [progression] instance (shared reference). */
    fun addSongSectionWithProgression(name: String?, existingProgression: ChordProgression) {
        val sectionName = name?.trim().orEmpty().ifBlank { "Section ${song.sections.size + 1}" }
        song.sections.add(SongSection(name = sectionName, progression = existingProgression))
        currentSectionIndex = song.sections.lastIndex
        progression = existingProgression
        tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()
        saveCurrentSession()
    }

    fun duplicateCurrentSongSection() {
        if (currentSectionIndex !in song.sections.indices) return
        val source = song.sections[currentSectionIndex]
        val copied = try {
            Json.decodeFromString(ChordProgression.serializer(), Json.encodeToString(ChordProgression.serializer(), source.progression))
        } catch (_: Exception) {
            source.progression.copy(measures = source.progression.measures.toMutableList())
        }
        val newName = "${source.name.ifBlank { "Section" }} Copy"
        copied.name = newName
        val insertIndex = currentSectionIndex + 1
        song.sections.add(insertIndex, SongSection(name = newName, progression = copied))
        currentSectionIndex = insertIndex
        progression = copied
        tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()
        saveCurrentSession()
    }

    fun renameCurrentSongSection(newName: String) {
        if (currentSectionIndex !in song.sections.indices) return
        val normalized = newName.trim().ifBlank { "Section ${currentSectionIndex + 1}" }
        val section = song.sections[currentSectionIndex]
        section.name = normalized
        section.progression.name = normalized
        updateSongSectionsState()
        saveCurrentSession()
    }

    fun selectSongSection(index: Int) {
        if (index !in song.sections.indices || index == currentSectionIndex) return
        currentSectionIndex = index
        progression = song.sections[currentSectionIndex].progression
        tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()
        saveCurrentSession()
    }

    fun moveCurrentSongSectionBy(offset: Int) {
        if (currentSectionIndex !in song.sections.indices) return
        val targetIndex = (currentSectionIndex + offset).coerceIn(0, song.sections.lastIndex)
        if (targetIndex == currentSectionIndex) return
        val moving = song.sections.removeAt(currentSectionIndex)
        song.sections.add(targetIndex, moving)
        currentSectionIndex = targetIndex
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Returns the index of the first section whose progression matches [progression] (by identity, then by name). */
    fun findSectionIndexForProgression(progression: ChordProgression): Int {
        val byRef = song.sections.indexOfFirst { it.progression === progression }
        if (byRef >= 0) return byRef
        val byName = song.sections.indexOfFirst { it.progression.name == progression.name }
        return if (byName >= 0) byName else currentSectionIndex
    }

    fun moveSongSection(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in song.sections.indices || toIndex !in song.sections.indices || fromIndex == toIndex) return
        val moving = song.sections.removeAt(fromIndex)
        song.sections.add(toIndex, moving)
        currentSectionIndex = toIndex
        progression = song.sections[currentSectionIndex].progression
        tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()
        saveCurrentSession()
    }

    fun renameSongSection(index: Int, newName: String) {
        if (index !in song.sections.indices) return
        currentSectionIndex = index
        renameCurrentSongSection(newName)
    }

    fun deleteSongSection(index: Int) {
        if (index !in song.sections.indices) return
        if (song.sections.size == 1) {
            val section = song.sections.first()
            section.name = song.name.ifBlank { "Section 1" }
            section.progression = ChordProgression(name = section.name, key = progression.key, mode = progression.mode, tempo = progression.tempo, shuffleFactor = settingsRepository.shuffleFactor)
            currentSectionIndex = 0
        } else {
            song.sections.removeAt(index)
            currentSectionIndex = index.coerceAtMost(song.sections.lastIndex)
        }
        progression = song.sections[currentSectionIndex].progression
        tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()
        saveCurrentSession()
    }

    fun createSongPlaybackProgression(): ChordProgression {
        val sections = song.sections.ifEmpty { mutableListOf(SongSection(progression = progression)) }
        val firstProgression = sections.first().progression
        val combinedMeasures = sections.flatMap { section ->
            val measures = section.progression.measures
            if (measures.isEmpty()) listOf(Measure(1)) else measures.map { it.copy() }
        }.toMutableList()
        if (combinedMeasures.isEmpty()) {
            combinedMeasures.add(Measure(1))
        }
        combinedMeasures.forEachIndexed { index, measure ->
            measure.number = index + 1
        }
        return ChordProgression(
            name = song.name.ifBlank { "New Song" },
            key = firstProgression.key,
            mode = firstProgression.mode,
            tempo = firstProgression.tempo,
            shuffleFactor = firstProgression.shuffleFactor,
            measures = combinedMeasures
        )
    }

    fun setSongName(name: String) {
        song.name = name.trim().ifBlank { "New Song" }
        updateSongSectionsState()
        saveCurrentSession()
    }

    fun getSavedSongNames(): List<String> {
        return progressionRepository.getSavedSongNames()
    }

    fun saveNamedSong(name: String) {
        val normalized = name.trim().ifBlank { "New Song" }
        song.name = normalized
        progressionRepository.saveNamedSong(normalized, song)
        updateSongSectionsState()
        saveCurrentSession()
    }

    fun loadSong(name: String) {
        progressionRepository.loadSong(name)?.let { loaded ->
            song = loaded
            song.ensureValid()
            currentSectionIndex = 0
            progression = song.sections.first().progression
            tempo.value = progression.tempo
            _key.value = progression.key
            updateAllChords()
            updateMeasures()
            updateSongSectionsState()
            saveCurrentSession()
        }
    }

    fun deleteSong(name: String) {
        progressionRepository.deleteSong(name)
    }

    /** Forces all LiveData to re-emit the current section's state. Used when returning from SongActivity. */
    fun forceRefreshCurrentProgression() {
        progression = song.sections[currentSectionIndex].progression
        tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()
    }

    private fun updateAllChords() {
        val scaleChords = progression.getScaleDegreeChords()
        _scaleDegreeChords.value = scaleChords
        _primaryChords.value = listOfNotNull(scaleChords.getOrNull(0), scaleChords.getOrNull(5))
        updateRelatedChords(scaleChords)
        updateBorrowedChords()
    }

    fun getSavedProgressionNames(): List<String> {
        return progressionRepository.getSavedProgressionNames()
    }

    fun saveNamedProgression(name: String) {
        progression.name = name
        if (currentSectionIndex in song.sections.indices) {
            song.sections[currentSectionIndex].name = name
        }
        updateSongSectionsState()
        saveCurrentSession()
        progressionRepository.saveNamedProgression(name, progression)
    }

    fun loadProgression(name: String) {
        progressionRepository.loadProgression(name)?.let {
            if (currentSectionIndex !in song.sections.indices) {
                currentSectionIndex = 0
                song.ensureValid()
            }
            song.sections[currentSectionIndex] = SongSection(name = name, progression = it)
            progression = it
            tempo.value = it.tempo
            _key.value = it.key
            updateAllChords()
            updateMeasures()
            updateSongSectionsState()
            saveCurrentSession()
        }
    }

    fun deleteProgression(name: String) {
        progressionRepository.deleteProgression(name)
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
        // Calculate semitone difference
        val oldRootMidi = oldKey.rootNote.noteOffset
        val newRootMidi = newKey.rootNote.noteOffset
        val semitoneShift = (newRootMidi - oldRootMidi + 12) % 12

        if (semitoneShift == 0) return // No transposition needed

        // Transpose all chords in all measures
        progression.measures.forEach { measure ->
            val transposedEvents = measure.chordEvents.map { event ->
                val transposedChord = transposeChord(event.chord, semitoneShift)
                Measure.ChordEvent(transposedChord, event.quarterNote)
            }
            measure.chordEvents.clear()
            measure.chordEvents.addAll(transposedEvents)

            // Transpose solo pattern notes
            try {
                val soloPattern = measure.soloPattern
                if (soloPattern != null && !soloPattern.isEmpty()) {
                    val transposedElements = soloPattern.elements.map { element ->
                        when (element) {
                            is SoloElement.Note -> {
                                val transposedMidi = (element.midi + semitoneShift) % 128
                                SoloElement.Note(transposedMidi, element.lengthEighths)
                            }
                            else -> element // Rest and LetRing remain unchanged
                        }
                    }
                    measure.soloPattern = SoloPattern(soloPattern.name, transposedElements)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to transpose solo pattern: ${e.message}")
            }
        }
    }

    private fun transposeChord(chord: Chord, semitoneShift: Int): Chord {
        // Calculate new root note
        val oldRootMidi = chord.root.noteOffset
        val newRootMidi = (oldRootMidi + semitoneShift) % 12
        val newRoot = Note.entries.first { it.noteOffset == newRootMidi }

        // Keep the same chord quality and scale degree name
        return Chord(newRoot, chord.quality, chord.scaleDegreeName)
    }

    fun setTempo(newTempo: Int) {
        val clampedTempo = newTempo.coerceIn(60, 240)
        if (clampedTempo != progression.tempo) {
            progression.tempo = clampedTempo
            tempo.value = clampedTempo
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
        if (startPreviewImmediately && settingsRepository.isChordPreviewEnabled) {
            previewChord(chord, ownerId)
        }

        _targetChord.value = null
        _suggestedChord.value = null

        val scaleChords = _scaleDegreeChords.value ?: emptyList()
        val relatedChords = _relatedChords.value ?: emptyList()
        val borrowedMinorChords = _borrowedMinorChords.value ?: emptyList()

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
        if (!startPreviewImmediately && settingsRepository.isChordPreviewEnabled) {
            previewChord(chord, ownerId)
        }
    }

    private fun previewChord(chord: Chord, ownerId: String = "VIEWMODEL") {
        val startTime = System.currentTimeMillis()
        Log.w(TAG, "previewChord$chord START (ownerId=$ownerId, previewJob=${previewJob?.isActive})")
        // First, immediately stop any currently running preview audio
        // This ensures the AudioPlayer's buffer is cleared before starting a new preview
        val hadActiveJob = previewJob?.isActive == true
        previewJob?.cancel()
        Log.d(TAG, "previewChord: cancelled previous job (hadActiveJob=$hadActiveJob)")
        try {
            val stopStartTime = System.currentTimeMillis()
            previewAudioPlayer.stop()
            val stopEndTime = System.currentTimeMillis()
            Log.d(TAG, "previewChord: called previewAudioPlayer.stop() (took=${stopEndTime - stopStartTime}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "previewChord: failed to stop previewAudioPlayer: ${e.message}", e)
        }


        if (settingsRepository.isChordPreviewEnabled) {
            // Register as preview owner so the Coordinator can stop us if another owner starts
            // onStop should cancel the running coroutine AND stop the audio buffer immediately
            val onStop: () -> Unit = {
                try {
                    previewJob?.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "onStop: previewJob.cancel failed: ${e.message}", e)
                }
                try {
                    previewAudioPlayer.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "onStop: previewAudioPlayer.stop failed: ${e.message}", e)
                }
            }
            val coordStartTime = System.currentTimeMillis()
            PreviewCoordinator.requestStart(ownerId, false, onStop)
            Log.d(TAG, "previewChord: PreviewCoordinator.requestStart returned (took=${System.currentTimeMillis() - coordStartTime}ms)")

            previewJob = viewModelScope.launch {
                val launchTime = System.currentTimeMillis()
                Log.d(TAG, "previewChord: coroutine STARTED for $chord (launchDelay=${launchTime - startTime}ms)")
                try {
                    // Apply current sound settings to the preview player so changes are audible immediately
                    previewAudioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
                    previewAudioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
                    previewAudioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
                    val playerCallTime = System.currentTimeMillis()
                    Log.d(TAG, "previewChord: calling previewAudioPlayer.previewChord for $chord")
                    previewAudioPlayer.previewChord(chord, settingsRepository.pluckStrength)
                    val playerReturnTime = System.currentTimeMillis()
                    Log.d(TAG, "previewChord: previewAudioPlayer.previewChord RETURNED for $chord (took=${playerReturnTime - playerCallTime}ms)")
                } finally {
                    // Ensure we clear coordinator ownership when preview finishes
                    Log.d(TAG, "previewChord: coroutine FINISHED for $chord, calling requestStop($ownerId)")
                    PreviewCoordinator.requestStop(ownerId)
                }
            }
        }
        Log.d(TAG, "previewChord$chord END (totalSetupTime=${System.currentTimeMillis() - startTime}ms)")
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

    @OptIn(InternalSerializationApi::class)
    fun setStrummingPattern(measureIndex: Int, pattern: StrummingPattern) {
        if (measureIndex in progression.measures.indices) {
            progression.measures[measureIndex].strummingPattern = pattern
            updateMeasures()
            saveCurrentSession()
        }
    }

    @OptIn(InternalSerializationApi::class)
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
                android.util.Log.w("ProgressionViewModel", "Failed to copy strumming pattern: ${e.message}")
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
                android.util.Log.w("ProgressionViewModel", "Failed to copy drum pattern: ${e.message}")
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
                android.util.Log.w("ProgressionViewModel", "Failed to copy solo pattern: ${e.message}")
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
        val selectedKey = newKey ?: (_key.value ?: Key.C)
        val selectedTempo = (newTempo ?: progression.tempo).coerceIn(60, 240)

        progression = if (template != null) {
            ProgressionTemplates.createProgressionFromTemplate(template, selectedKey).apply {
                tempo = selectedTempo
            }
        } else {
            ChordProgression(
                key = selectedKey,
                tempo = selectedTempo
            ).apply {
                shuffleFactor = settingsRepository.shuffleFactor
            }
        }
        val sectionName = song.sections.getOrNull(currentSectionIndex)?.name ?: "Section ${currentSectionIndex + 1}"
        progression.name = sectionName
        if (currentSectionIndex in song.sections.indices) {
            song.sections[currentSectionIndex] = SongSection(name = sectionName, progression = progression)
        }
        tempo.value = progression.tempo
        _key.value = progression.key
        updateAllChords()
        updateMeasures()
        updateSongSectionsState()
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
        isLooping.value = isToggled
        settingsRepository.isLoopingEnabled = isToggled
    }

    fun onRepeatSongToggle(isToggled: Boolean) {
        isLoopingSong.value = isToggled
        settingsRepository.isLoopingSongEnabled = isToggled
    }

    @OptIn(InternalSerializationApi::class)
    private fun updateMeasures() {
        _measures.value = progression.measures.map { it.copy(chordEvents = it.chordEvents.toMutableList()) }
    }

    fun stopAnyLocalPreview() {
        val app = getApplication<Application>()
        try {
            Log.d(TAG, "stopAnyLocalPreview: requested")
            Toast.makeText(app, "Stopping local preview...", Toast.LENGTH_SHORT).show()
            previewJob?.cancel()
            previewJob = null
            try {
                previewAudioPlayer.stop()
            } catch (e: Exception) {
                Log.e(TAG, "stopAnyLocalPreview: previewAudioPlayer.stop() failed: ${e.message}", e)
                try {
                    Toast.makeText(app, "Error stopping preview audio: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (t: Exception) {
                    Log.w(TAG, "Failed to show Toast for preview stop error: ${t.message}", t)
                }
            }
            // Ensure coordinator knows viewmodel isn't owner anymore
            try {
                PreviewCoordinator.requestStop("VIEWMODEL")
            } catch (e: Exception) {
                Log.e(TAG, "stopAnyLocalPreview: PreviewCoordinator.requestStop failed: ${e.message}", e)
                try {
                    Toast.makeText(app, "Error notifying PreviewCoordinator: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (t: Exception) {
                    Log.w(TAG, "Failed to show Toast for PreviewCoordinator error: ${t.message}", t)
                }
            }
            Log.d(TAG, "stopAnyLocalPreview: completed")
        } catch (e: Exception) {
            Log.e(TAG, "stopAnyLocalPreview: unexpected error: ${e.message}", e)
            try {
                Toast.makeText(app, "Unexpected error stopping preview: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (t: Exception) {
                Log.w(TAG, "Failed to show Toast for unexpected preview stop error: ${t.message}", t)
            }
        }
    }

    fun stopPreviewNow() {
        // Immediate, synchronous stop of the ViewModel-local preview player.
        // This mirrors how dialog code talks directly to AudioPlayer: cancel coroutine and stop audio.
        try {
            previewJob?.cancel()
            previewJob = null
        } catch (e: Exception) {
            Log.e(TAG, "stopPreviewNow: failed to cancel previewJob: ${e.message}", e)
        }
        try {
            previewAudioPlayer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreviewNow: previewAudioPlayer.stop() failed: ${e.message}", e)
        }
    }
}
