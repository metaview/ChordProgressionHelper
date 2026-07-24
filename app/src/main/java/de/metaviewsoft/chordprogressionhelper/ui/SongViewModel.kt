package de.metaviewsoft.chordprogressionhelper.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import de.metaviewsoft.chordprogressionhelper.MyApplication
import de.metaviewsoft.chordprogressionhelper.data.ProgressionRepository
import de.metaviewsoft.chordprogressionhelper.data.SettingsRepository
import de.metaviewsoft.chordprogressionhelper.model.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(InternalSerializationApi::class)
class SongViewModel(application: Application) : AndroidViewModel(application) {

    private val progressionRepository: ProgressionRepository = (application as MyApplication).progressionRepository
    private val settingsRepository: SettingsRepository = (application as MyApplication).settingsRepository
    
    private var song: Song
    private var currentSectionIndex: Int = 0

    private val _songSectionNames = MutableLiveData<List<String>>()
    val songSectionNames: LiveData<List<String>> = _songSectionNames

    private val _selectedSongSectionIndex = MutableLiveData<Int>()
    val selectedSongSectionIndex: LiveData<Int> = _selectedSongSectionIndex

    private val _songName = MutableLiveData<String>()
    val songName: LiveData<String> = _songName

    val isSongLooping: MutableLiveData<Boolean> = MutableLiveData(false)

    private val TAG = "SongViewModel"

    init {
        song = progressionRepository.loadLastSongSession().also { it.ensureValid() }
        isSongLooping.value = settingsRepository.isLoopingSongEnabled
        updateSongSectionsState()
    }

    /** Save current song state to repository. */
    private fun saveCurrentSession() {
        progressionRepository.saveLastSongSession(song)
    }

    /** Update all song-related LiveData based on current song state. */
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

    /** Get the current progression (from current section). */
    fun getCurrentProgression(): ChordProgression {
        song.ensureValid()
        if (currentSectionIndex !in song.sections.indices) {
            currentSectionIndex = 0
        }
        return song.sections[currentSectionIndex].progression
    }

    /** Get the current section index. */
    fun getCurrentSectionIndex(): Int = currentSectionIndex

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
        val progression = getCurrentProgression()
        return (section?.progression?.tempo ?: progression.tempo).coerceIn(60, 240)
    }

    /** Returns distinct progressions used in the song (by identity, preserving order). */
    fun getUniqueSongProgressions(): List<ChordProgression> =
        song.sections.map { it.progression }.distinctBy { System.identityHashCode(it) }

    /** Add a new empty section to the song. */
    fun addSongSection(name: String?, currentKey: Key, currentMode: Mode, currentTempo: Int) {
        val sectionName = name?.trim().orEmpty().ifBlank { "Section ${song.sections.size + 1}" }
        val newProgression = ChordProgression(
            name = sectionName,
            key = currentKey,
            mode = currentMode,
            tempo = currentTempo,
            shuffleFactor = settingsRepository.shuffleFactor
        )
        song.sections.add(SongSection(name = sectionName, progression = newProgression))
        currentSectionIndex = song.sections.lastIndex
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Adds a new section linked to an existing [progression] instance (shared reference). */
    fun addSongSectionWithProgression(name: String?, existingProgression: ChordProgression) {
        val sectionName = name?.trim().orEmpty().ifBlank { "Section ${song.sections.size + 1}" }
        song.sections.add(SongSection(name = sectionName, progression = existingProgression))
        currentSectionIndex = song.sections.lastIndex
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Duplicate the current song section (deep copy via JSON). */
    fun duplicateCurrentSongSection(): ChordProgression? {
        if (currentSectionIndex !in song.sections.indices) return null
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
        updateSongSectionsState()
        saveCurrentSession()
        return copied
    }

    /** Rename the current section. */
    fun renameCurrentSongSection(newName: String) {
        if (currentSectionIndex !in song.sections.indices) return
        val normalized = newName.trim().ifBlank { "Section ${currentSectionIndex + 1}" }
        val section = song.sections[currentSectionIndex]
        section.name = normalized
        section.progression.name = normalized
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Update the progression of the current section (saves changes back to the song). */
    fun updateCurrentSectionProgression(progression: ChordProgression) {
        if (currentSectionIndex in song.sections.indices) {
            song.sections[currentSectionIndex].progression = progression
            saveCurrentSession()
        }
    }

    /** Select a section by index (returns the progression of selected section). */
    fun selectSongSection(index: Int): ChordProgression? {
        if (index !in song.sections.indices || index == currentSectionIndex) return null
        currentSectionIndex = index
        updateSongSectionsState()
        saveCurrentSession()
        return song.sections[currentSectionIndex].progression
    }

    /** Move current section by offset (-1 = up, +1 = down). */
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

    /** Move a section from one index to another. */
    fun moveSongSection(fromIndex: Int, toIndex: Int): ChordProgression? {
        if (fromIndex !in song.sections.indices || toIndex !in song.sections.indices || fromIndex == toIndex) return null
        val moving = song.sections.removeAt(fromIndex)
        song.sections.add(toIndex, moving)
        currentSectionIndex = toIndex
        updateSongSectionsState()
        saveCurrentSession()
        return song.sections[currentSectionIndex].progression
    }

    /** Rename a section at a specific index. */
    fun renameSongSection(index: Int, newName: String) {
        if (index !in song.sections.indices) return
        currentSectionIndex = index
        renameCurrentSongSection(newName)
    }

    /** Delete a section at a specific index. Returns the new current progression. */
    fun deleteSongSection(index: Int, currentKey: Key, currentMode: Mode, currentTempo: Int): ChordProgression? {
        if (index !in song.sections.indices) return null
        if (song.sections.size == 1) {
            val section = song.sections.first()
            section.name = song.name.ifBlank { "Section 1" }
            section.progression = ChordProgression(
                name = section.name,
                key = currentKey,
                mode = currentMode,
                tempo = currentTempo,
                shuffleFactor = settingsRepository.shuffleFactor
            )
            currentSectionIndex = 0
        } else {
            song.sections.removeAt(index)
            currentSectionIndex = index.coerceAtMost(song.sections.lastIndex)
        }
        updateSongSectionsState()
        saveCurrentSession()
        return song.sections[currentSectionIndex].progression
    }

    /** Check if a section name is already taken (optionally excluding a specific position for rename). */
    fun isSectionNameTaken(name: String, excludePosition: Int = -1): Boolean {
        return song.sections.mapIndexed { index, section ->
            if (index == excludePosition) null else section.name
        }.filterNotNull().contains(name)
    }

    /** Get a section by index for duplication purposes. */
    fun getSectionAt(index: Int): SongSection? = song.sections.getOrNull(index)

    /** Create a combined playback progression from all sections. */
    fun createSongPlaybackProgression(): ChordProgression {
        val sections = song.sections.ifEmpty {
            val currentProgression = getCurrentProgression()
            mutableListOf(SongSection(progression = currentProgression))
        }
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

    /** Set the song name. */
    fun setSongName(name: String) {
        song.name = name.trim().ifBlank { "New Song" }
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Get list of saved song names. */
    fun getSavedSongNames(): List<String> {
        return progressionRepository.getSavedSongNames()
    }

    /** Save the current song with a specific name. */
    fun saveNamedSong(name: String) {
        val normalized = name.trim().ifBlank { "New Song" }
        song.name = normalized
        progressionRepository.saveNamedSong(normalized, song)
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Load a song by name (returns the first section's progression). */
    fun loadSong(name: String): ChordProgression? {
        progressionRepository.loadSong(name)?.let { loaded ->
            song = loaded
            song.ensureValid()
            currentSectionIndex = 0
            updateSongSectionsState()
            saveCurrentSession()
            return song.sections.first().progression
        }
        return null
    }

    /** Delete a saved song by name. */
    fun deleteSong(name: String) {
        progressionRepository.deleteSong(name)
    }

    /** Forces all LiveData to re-emit the current state. */
    fun forceRefresh() {
        updateSongSectionsState()
    }

    /** Update the current section index (used when returning from sub-activities). */
    fun updateCurrentSectionIndex(progression: ChordProgression) {
        currentSectionIndex = findSectionIndexForProgression(progression)
        updateSongSectionsState()
    }

    /** Create a new empty song. */
    fun newSong(defaultKey: Key, defaultTempo: Int) {
        val newProgression = ChordProgression(key = defaultKey, tempo = defaultTempo).apply {
            shuffleFactor = settingsRepository.shuffleFactor
        }
        val section = SongSection(name = "Section 1", progression = newProgression)
        song = Song(name = "New Song", sections = mutableListOf(section))
        currentSectionIndex = 0
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Toggle song looping on/off. */
    fun onSongRepeatToggle(isToggled: Boolean) {
        isSongLooping.value = isToggled
        settingsRepository.isLoopingSongEnabled = isToggled
    }
}
