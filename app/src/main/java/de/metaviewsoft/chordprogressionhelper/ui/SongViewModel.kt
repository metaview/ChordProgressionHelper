package de.metaviewsoft.chordprogressionhelper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.metaviewsoft.chordprogressionhelper.MyApplication
import de.metaviewsoft.chordprogressionhelper.model.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.InternalSerializationApi

/**
 * Thin Android wrapper around the portable [SongViewModelCore] (:shared commonMain).
 * Only supplies the app-wide singletons and the ViewModel lifecycle; all logic and
 * state (StateFlow) live in the core.
 */
@OptIn(InternalSerializationApi::class)
class SongViewModel(application: Application) : AndroidViewModel(application) {

    private val core = SongViewModelCore(
        storage = (application as MyApplication).progressionRepository.storage,
        settings = application.settingsRepository,
        session = application.songSession,
    )

    val songSectionNames: StateFlow<List<String>> get() = core.songSectionNames
    val selectedSongSectionIndex: StateFlow<Int> get() = core.selectedSongSectionIndex
    val songName: StateFlow<String> get() = core.songName
    val isSongLooping: StateFlow<Boolean> get() = core.isSongLooping
    val tempoPercent: StateFlow<Int> get() = core.tempoPercent

    fun getCurrentSong(): de.metaviewsoft.chordprogressionhelper.model.Song = core.getCurrentSong()
    fun getCurrentProgression(): ChordProgression = core.getCurrentProgression()
    fun getSectionIndexForMeasure(measureIndex: Int): Int = core.getSectionIndexForMeasure(measureIndex)
    fun getTempoForMeasure(measureIndex: Int): Int = core.getTempoForMeasure(measureIndex)
    fun getPlaybackTempoForMeasure(measureIndex: Int): Int = core.getPlaybackTempoForMeasure(measureIndex)
    fun setTempoPercent(percent: Int) = core.setTempoPercent(percent)
    fun incrementTempoPercent() = core.incrementTempoPercent()
    fun decrementTempoPercent() = core.decrementTempoPercent()
    fun getSectionProgress(measureIndex: Int, strumIndex: Int): Float = core.getSectionProgress(measureIndex, strumIndex)
    fun getSectionChordMarks(index: Int): List<ChordMark> = core.getSectionChordMarks(index)
    fun getUniqueSongProgressions(): List<ChordProgression> = core.getUniqueSongProgressions()
    fun addSongSection(name: String?, currentKey: Key, currentMode: Mode, currentTempo: Int) =
        core.addSongSection(name, currentKey, currentMode, currentTempo)
    fun addSongSectionWithProgression(name: String?, existingProgression: ChordProgression) =
        core.addSongSectionWithProgression(name, existingProgression)
    fun duplicateCurrentSongSection(): ChordProgression? = core.duplicateCurrentSongSection()
    fun renameCurrentSongSection(newName: String) = core.renameCurrentSongSection(newName)
    fun updateCurrentSectionProgression(progression: ChordProgression) = core.updateCurrentSectionProgression(progression)
    fun selectSongSection(index: Int): ChordProgression? = core.selectSongSection(index)
    fun moveCurrentSongSectionBy(offset: Int) = core.moveCurrentSongSectionBy(offset)
    fun findSectionIndexForProgression(progression: ChordProgression): Int = core.findSectionIndexForProgression(progression)
    fun moveSongSection(fromIndex: Int, toIndex: Int): ChordProgression? = core.moveSongSection(fromIndex, toIndex)
    fun renameSongSection(index: Int, newName: String) = core.renameSongSection(index, newName)
    fun deleteSongSection(index: Int, currentKey: Key, currentMode: Mode, currentTempo: Int): ChordProgression? =
        core.deleteSongSection(index, currentKey, currentMode, currentTempo)
    fun isSectionNameTaken(name: String, excludePosition: Int = -1): Boolean = core.isSectionNameTaken(name, excludePosition)
    fun getSectionAt(index: Int): SongSection? = core.getSectionAt(index)
    fun createSongPlaybackProgression(): ChordProgression = core.createSongPlaybackProgression()
    fun setSongName(name: String) = core.setSongName(name)
    fun getSavedSongNames(): List<String> = core.getSavedSongNames()
    fun saveNamedSong(name: String) = core.saveNamedSong(name)
    fun loadSong(name: String): ChordProgression? = core.loadSong(name)
    fun deleteSong(name: String) = core.deleteSong(name)
    fun forceRefresh() = core.forceRefresh()
    fun updateCurrentSectionIndex(progression: ChordProgression) = core.updateCurrentSectionIndex(progression)
    fun newSong(defaultKey: Key, defaultTempo: Int) = core.newSong(defaultKey, defaultTempo)
    fun onSongRepeatToggle(isToggled: Boolean) = core.onSongRepeatToggle(isToggled)
}
