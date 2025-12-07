package com.metaview.chordprogressionhelper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.metaview.chordprogressionhelper.MyApplication
import com.metaview.chordprogressionhelper.data.ProgressionRepository
import com.metaview.chordprogressionhelper.data.SettingsRepository
import com.metaview.chordprogressionhelper.model.*
import com.metaview.chordprogressionhelper.util.AudioPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)
class ProgressionViewModel(application: Application) : AndroidViewModel(application) {

    private val progressionRepository: ProgressionRepository = (application as MyApplication).progressionRepository
    private val settingsRepository: SettingsRepository = (application as MyApplication).settingsRepository
    var progression: ChordProgression
        private set

    private val previewAudioPlayer = AudioPlayer()
    private var previewJob: Job? = null

    private val _scaleDegreeChords = MutableLiveData<List<Chord>>()
    val scaleDegreeChords: LiveData<List<Chord>> = _scaleDegreeChords

    private val _relatedChords = MutableLiveData<List<Chord>>()
    val relatedChords: LiveData<List<Chord>> = _relatedChords

    private val _borrowedChords = MutableLiveData<List<Chord>>()
    val borrowedChords: LiveData<List<Chord>> = _borrowedChords

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

    val tempo: MutableLiveData<Int>
    private val _key = MutableLiveData<Key>()
    val key: LiveData<Key> = _key

    val isLooping: MutableLiveData<Boolean> = MutableLiveData(false)

    init {
        progression = progressionRepository.loadLastSession()
        tempo = MutableLiveData(progression.tempo)
        _key.value = progression.key
        isLooping.value = settingsRepository.isLoopingEnabled
        updateAllChords()
        updateMeasures()
    }

    private fun saveCurrentSession() {
        progressionRepository.saveLastSession(progression)
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
        progressionRepository.saveNamedProgression(name, progression)
    }

    fun loadProgression(name: String) {
        progressionRepository.loadProgression(name)?.let {
            progression = it
            tempo.value = it.tempo
            _key.value = it.key
            updateAllChords()
            updateMeasures()
        }
    }

    fun deleteProgression(name: String) {
        progressionRepository.deleteProgression(name)
    }

    fun setKey(key: Key) {
        if (progression.key == key) return
        progression.key = key
        _key.value = key
        updateAllChords()
        _selectedChord.value = null
        _targetChord.value = null
        _suggestedChord.value = null
        saveCurrentSession()
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

    fun setSelectedChord(chord: Chord) {
        _targetChord.value = null
        _suggestedChord.value = null

        val scaleChords = _scaleDegreeChords.value ?: emptyList()
        val relatedChords = _relatedChords.value ?: emptyList()
        val borrowedChords = _borrowedChords.value ?: emptyList()

        val scaleIndex = scaleChords.indexOf(chord)
        if (scaleIndex != -1) {
            if (scaleIndex > 0 && (scaleIndex - 1) < borrowedChords.size) {
                _suggestedChord.value = borrowedChords[scaleIndex - 1]
            }
        } else {
            val borrowedIndex = borrowedChords.indexOf(chord)
            if (borrowedIndex != -1) {
                if ((borrowedIndex + 1) < scaleChords.size) {
                    _suggestedChord.value = scaleChords[borrowedIndex + 1]
                }
            } else if (chord in relatedChords) {
                val targetRootMidi = (chord.root.midiOffset + 5) % 12
                _targetChord.value = scaleChords.find { it.root.midiOffset == targetRootMidi }
            }
        }

        _selectedChord.value = chord

        if (settingsRepository.isChordPreviewEnabled) {
            previewChord(chord)
        }
    }

    private fun previewChord(chord: Chord) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            // Apply current sound settings to the preview player so changes are audible immediately
            previewAudioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
            previewAudioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
            previewAudioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
            previewAudioPlayer.previewChord(chord, settingsRepository.pluckStrength)
        }
    }

    private fun updateRelatedChords(allChords: List<Chord>) {
        _relatedChords.value = allChords.mapNotNull { targetChord ->
            if (targetChord.quality == ChordType.DIMINISHED) return@mapNotNull null
            val dominantRootMidi = (targetChord.root.midiOffset + 7) % 12
            val dominantRootNote = Note.entries.first { it.midiOffset == dominantRootMidi }
            Chord(dominantRootNote, ChordType.DOMINANT_SEVENTH, "V7/${targetChord.scaleDegreeName}")
        }
    }

    private fun updateBorrowedChords() {
         _borrowedChords.value = progression.getParallelMinorChords()
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

    fun onDeleteConfirmationHandled() {
        _showDeleteConfirmation.value = null
    }

    fun requestNewProgression() {
        _showNewProgressionConfirmation.value = true
    }

    fun confirmNewProgression() {
        progression = ChordProgression()
        tempo.value = progression.tempo
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
        isLooping.value = isToggled
        settingsRepository.isLoopingEnabled = isToggled
    }

    @OptIn(InternalSerializationApi::class)
    private fun updateMeasures() {
        _measures.value = progression.measures.map { it.copy(chordEvents = it.chordEvents.toMutableList()) }
    }
}
