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

    fun clearProgression() {
        progression.clear()
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
        _measures.value = ArrayList(progression.measures)
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }
}
