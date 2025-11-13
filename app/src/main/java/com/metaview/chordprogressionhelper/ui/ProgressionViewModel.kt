package com.metaview.chordprogressionhelper.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metaview.chordprogressionhelper.model.*
import com.metaview.chordprogressionhelper.util.AudioPlayer
import kotlinx.coroutines.launch

class ProgressionViewModel : ViewModel() {
    private val progression = ChordProgression()
    private val audioPlayer = AudioPlayer()

    private val _scaleDegreeChords = MutableLiveData<List<Chord>>()
    val scaleDegreeChords: LiveData<List<Chord>> = _scaleDegreeChords

    private val _measures = MutableLiveData<List<Measure>>()
    val measures: LiveData<List<Measure>> = _measures

    private val _selectedChord = MutableLiveData<Chord?>()
    val selectedChord: LiveData<Chord?> = _selectedChord

    private val _showDeleteConfirmation = MutableLiveData<Int?>()
    val showDeleteConfirmation: LiveData<Int?> = _showDeleteConfirmation

    private val _showClearConfirmationDialog = MutableLiveData<Boolean>()
    val showClearConfirmationDialog: LiveData<Boolean> = _showClearConfirmationDialog

    init {
        updateScaleDegreeChords()
        updateMeasures()
    }

    fun setKey(key: Key) {
        progression.key = key
        updateScaleDegreeChords()
    }

    fun setMode(mode: Mode) {
        progression.mode = mode
        updateScaleDegreeChords()
    }

    fun setSelectedChord(chord: Chord) {
        _selectedChord.value = chord
    }

    fun addChordToMeasure(measureIndex: Int, quarterNote: Int, chord: Chord) {
        if (measureIndex in progression.measures.indices) {
            progression.measures[measureIndex].addChord(chord, quarterNote)
            updateMeasures()
        }
    }

    fun removeChordFromMeasure(measureIndex: Int, quarterNote: Int) {
        if (measureIndex in progression.measures.indices) {
            progression.measures[measureIndex].removeChordAt(quarterNote)
            updateMeasures()
        }
    }

    fun setStrummingPattern(measureIndex: Int, pattern: StrummingPattern) {
        if (measureIndex in progression.measures.indices) {
            progression.measures[measureIndex].strummingPattern = pattern
            updateMeasures()
        }
    }

    fun addMeasure() {
        progression.addMeasure()
        updateMeasures()
    }

    fun removeMeasure(measureIndex: Int) {
        if (measureIndex in progression.measures.indices) {
            val measure = progression.measures[measureIndex]
            if (measure.chordEvents.isNotEmpty()) {
                _showDeleteConfirmation.value = measureIndex
            } else {
                progression.removeMeasure(measureIndex)
                updateMeasures()
            }
        }
    }

    fun confirmRemoveMeasure(measureIndex: Int) {
        if (measureIndex in progression.measures.indices) {
            progression.removeMeasure(measureIndex)
            updateMeasures()
        }
    }

    fun onDeleteConfirmationHandled() {
        _showDeleteConfirmation.value = null
    }

    fun clearProgression() {
        val isNotEmpty = progression.measures.any { it.chordEvents.isNotEmpty() }
        if (isNotEmpty) {
            _showClearConfirmationDialog.value = true
        } else {
            progression.clear()
            updateMeasures()
        }
    }

    fun confirmClearProgression() {
        progression.clear()
        updateMeasures()
    }

    fun onClearConfirmationDialogHandled() {
        _showClearConfirmationDialog.value = false
    }

    fun moveMeasure(fromPosition: Int, toPosition: Int) {
        progression.moveMeasure(fromPosition, toPosition)
        updateMeasures()
    }

    fun getRelatedChords(chord: Chord): List<Chord> {
        return Chord.getRelatedChords(chord, progression.key, progression.mode)
    }

    fun play() {
        viewModelScope.launch {
            audioPlayer.playProgression(progression)
        }
    }

    fun stop() {
        audioPlayer.stop()
    }

    private fun updateScaleDegreeChords() {
        _scaleDegreeChords.value = progression.getScaleDegreeChords()
    }

    private fun updateMeasures() {
        _measures.value = progression.measures.map { measure ->
            measure.copy(chordEvents = measure.chordEvents.toMutableList())
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }
}
