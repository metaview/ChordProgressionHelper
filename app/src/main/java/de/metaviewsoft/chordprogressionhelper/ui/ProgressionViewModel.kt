package de.metaviewsoft.chordprogressionhelper.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.metaviewsoft.chordprogressionhelper.MyApplication
import de.metaviewsoft.chordprogressionhelper.model.*
import de.metaviewsoft.chordprogressionhelper.util.PreviewCoordinator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.InternalSerializationApi

/**
 * Thin Android wrapper around the portable [ProgressionViewModelCore] (:shared commonMain).
 * Supplies the app-wide singletons, the viewModelScope, a Toast-backed user-message sink and
 * the PreviewCoordinator adapter; all logic and state (StateFlow) live in the core.
 */
@OptIn(InternalSerializationApi::class)
class ProgressionViewModel(application: Application) : AndroidViewModel(application) {

    private val core = ProgressionViewModelCore(
        storage = (application as MyApplication).progressionRepository.storage,
        settings = application.settingsRepository,
        session = application.songSession,
        scope = viewModelScope,
        previewGate = object : PreviewGate {
            override fun requestStart(ownerId: String, isLooping: Boolean, onStop: () -> Unit) {
                PreviewCoordinator.requestStart(ownerId, isLooping, onStop)
            }

            override fun requestStop(ownerId: String) {
                PreviewCoordinator.requestStop(ownerId)
            }
        },
        onUserMessage = { message ->
            Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
        },
    )

    val progression: ChordProgression get() = core.progression

    val scaleDegreeChords: StateFlow<List<Chord>> get() = core.scaleDegreeChords
    val relatedChords: StateFlow<List<Chord>> get() = core.relatedChords
    val borrowedMinorChords: StateFlow<List<Chord>> get() = core.borrowedMinorChords
    val borrowedMajorChords: StateFlow<List<Chord>> get() = core.borrowedMajorChords
    val primaryChords: StateFlow<List<Chord>> get() = core.primaryChords
    val suggestedChord: StateFlow<Chord?> get() = core.suggestedChord
    val targetChord: StateFlow<Chord?> get() = core.targetChord
    val measures: StateFlow<List<Measure>> get() = core.measures
    val selectedChord: StateFlow<Chord?> get() = core.selectedChord
    val showDeleteConfirmation: StateFlow<Int?> get() = core.showDeleteConfirmation
    val showNewProgressionConfirmation: StateFlow<Boolean> get() = core.showNewProgressionConfirmation
    val showTransposeConfirmation: StateFlow<Key?> get() = core.showTransposeConfirmation
    val tempo: StateFlow<Int> get() = core.tempo
    val key: StateFlow<Key> get() = core.key
    val isProgressionLooping: StateFlow<Boolean> get() = core.isProgressionLooping

    override fun onCleared() {
        super.onCleared()
        core.dispose()
    }

    fun getSavedProgressionNames(): List<String> = core.getSavedProgressionNames()
    fun saveNamedProgression(name: String) = core.saveNamedProgression(name)
    fun loadProgression(name: String) = core.loadProgression(name)
    fun deleteProgression(name: String) = core.deleteProgression(name)
    fun refreshUIAfterProgressionChange() = core.refreshUIAfterProgressionChange()
    fun setKey(key: Key) = core.setKey(key)
    fun confirmTranspose(newKey: Key, transpose: Boolean) = core.confirmTranspose(newKey, transpose)
    fun onTransposeConfirmationHandled() = core.onTransposeConfirmationHandled()
    fun setTempo(newTempo: Int) = core.setTempo(newTempo)
    fun incrementTempo() = core.incrementTempo()
    fun decrementTempo() = core.decrementTempo()
    fun setSelectedChord(chord: Chord, ownerId: String = "VIEWMODEL", startPreviewImmediately: Boolean = false) =
        core.setSelectedChord(chord, ownerId, startPreviewImmediately)
    fun releaseChordPreview() = core.releaseChordPreview()
    fun addChordToMeasure(measureIndex: Int, eighthNoteIndex: Int, chord: Chord) =
        core.addChordToMeasure(measureIndex, eighthNoteIndex, chord)
    fun addChordToMeasure(measureIndex: Int, eighthNoteIndex: Int) =
        core.addChordToMeasure(measureIndex, eighthNoteIndex)
    fun removeChordFromMeasure(measureIndex: Int, eighthNoteIndex: Int) =
        core.removeChordFromMeasure(measureIndex, eighthNoteIndex)
    fun setStrummingPattern(measureIndex: Int, pattern: StrummingPattern) = core.setStrummingPattern(measureIndex, pattern)
    fun setDrumPattern(measureIndex: Int, pattern: DrumPattern) = core.setDrumPattern(measureIndex, pattern)
    fun setSoloPattern(measureIndex: Int, pattern: SoloPattern) = core.setSoloPattern(measureIndex, pattern)
    fun addMeasure() = core.addMeasure()
    fun removeMeasure(measureIndex: Int) = core.removeMeasure(measureIndex)
    fun confirmRemoveMeasure(measureIndex: Int) = core.confirmRemoveMeasure(measureIndex)
    fun duplicateMeasure(measureIndex: Int) = core.duplicateMeasure(measureIndex)
    fun onDeleteConfirmationHandled() = core.onDeleteConfirmationHandled()
    fun requestNewProgression() = core.requestNewProgression()
    fun confirmNewProgression(template: ProgressionTemplate?, newKey: Key? = null, newTempo: Int? = null) =
        core.confirmNewProgression(template, newKey, newTempo)
    fun onNewProgressionConfirmationHandled() = core.onNewProgressionConfirmationHandled()
    fun moveMeasure(fromPosition: Int, toPosition: Int) = core.moveMeasure(fromPosition, toPosition)
    fun finalizeMeasureMove() = core.finalizeMeasureMove()
    fun onRepeatToggle(isToggled: Boolean) = core.onRepeatToggle(isToggled)
    fun stopAnyLocalPreview() = core.stopAnyLocalPreview()
    fun stopPreviewNow() = core.stopPreviewNow()
}
