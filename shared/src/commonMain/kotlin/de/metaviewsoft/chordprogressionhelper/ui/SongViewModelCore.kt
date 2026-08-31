@file:OptIn(InternalSerializationApi::class)

package de.metaviewsoft.chordprogressionhelper.ui

import de.metaviewsoft.chordprogressionhelper.data.ProgressionStorage
import de.metaviewsoft.chordprogressionhelper.data.SettingsStore
import de.metaviewsoft.chordprogressionhelper.data.SongSession
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.Mode
import de.metaviewsoft.chordprogressionhelper.model.Song
import de.metaviewsoft.chordprogressionhelper.model.SongSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Portable core of the song screen's view model. The Android `SongViewModel` is a thin
 * `AndroidViewModel` wrapper that constructs this with the app-wide singletons and re-exposes
 * everything; iOS will drive it directly. State is exposed as [StateFlow] so reads of `.value`
 * immediately after a mutation are always fresh (unlike LiveData's posted updates).
 */
class SongViewModelCore(
    private val storage: ProgressionStorage,
    private val settings: SettingsStore,
    private val session: SongSession,
) {

    // Delegate to the shared session so there is exactly one Song object in memory,
    // shared with the progression view model.
    private var song: Song
        get() = session.song
        set(value) { session.song = value }
    private var currentSectionIndex: Int
        get() = session.currentSectionIndex
        set(value) { session.currentSectionIndex = value }

    private val _songSectionNames = MutableStateFlow<List<String>>(emptyList())
    val songSectionNames: StateFlow<List<String>> = _songSectionNames.asStateFlow()

    private val _selectedSongSectionIndex = MutableStateFlow(0)
    val selectedSongSectionIndex: StateFlow<Int> = _selectedSongSectionIndex.asStateFlow()

    private val _songName = MutableStateFlow("")
    val songName: StateFlow<String> = _songName.asStateFlow()

    private val _isSongLooping = MutableStateFlow(false)
    val isSongLooping: StateFlow<Boolean> = _isSongLooping.asStateFlow()

    init {
        // The song is already loaded once by the shared SongSession; do not reload here.
        _isSongLooping.value = settings.isLoopingSongEnabled
        updateSongSectionsState()
    }

    /** Save current song state to the single authoritative store (via the shared session). */
    private fun saveCurrentSession() {
        session.save()
    }

    /** Update all song-related state based on current song state. */
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

    /** Returns 0..1 progress through the section containing the given global measure/strum position. */
    fun getSectionProgress(measureIndex: Int, strumIndex: Int): Float {
        var offset = 0
        for (section in song.sections) {
            val measures = section.progression.measures
            val count = measures.size.coerceAtLeast(1)
            if (measureIndex < offset + count) {
                val localMeasureIndex = (measureIndex - offset).coerceIn(0, count - 1)
                val totalStrums = measures.getOrNull(localMeasureIndex)
                    ?.strummingPattern?.strums?.size?.coerceAtLeast(1) ?: 1
                val strumFraction = strumIndex.coerceIn(0, totalStrums - 1).toFloat() / totalStrums
                return (localMeasureIndex + strumFraction) / count
            }
            offset += count
        }
        return 0f
    }

    /**
     * Chord labels of a section positioned on the same 0..1 timeline as
     * [getSectionProgress], so they align pixel-exact with the progress bar.
     * A chord at quarter-note q in measure m maps to (m + q/4) / measureCount.
     */
    fun getSectionChordMarks(index: Int): List<ChordMark> {
        val section = song.sections.getOrNull(index) ?: return emptyList()
        val measures = section.progression.measures
        val count = measures.size.coerceAtLeast(1)
        val marks = mutableListOf<ChordMark>()
        measures.forEachIndexed { m, measure ->
            measure.chordEvents.forEach { event ->
                val fraction = (m + event.quarterNote / 4f) / count
                marks += ChordMark(fraction, event.chord.getDisplayName())
            }
        }
        return marks
    }

    /** Returns distinct progressions used in the song (by identity, preserving order). */
    fun getUniqueSongProgressions(): List<ChordProgression> {
        val unique = mutableListOf<ChordProgression>()
        for (section in song.sections) {
            val progression = section.progression
            if (unique.none { it === progression }) unique += progression
        }
        return unique
    }

    /** Add a new empty section to the song. */
    fun addSongSection(name: String?, currentKey: Key, currentMode: Mode, currentTempo: Int) {
        val sectionName = name?.trim().orEmpty().ifBlank { "Section ${song.sections.size + 1}" }
        val newProgression = ChordProgression(
            name = sectionName,
            key = currentKey,
            mode = currentMode,
            tempo = currentTempo,
            shuffleFactor = settings.shuffleFactor
        )
        song.sections.add(SongSection(name = sectionName, progression = newProgression))
        currentSectionIndex = song.sections.lastIndex
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Adds a new section linked to an existing [existingProgression] instance (shared reference). */
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

    /**
     * Select a section by index and return that section's progression.
     *
     * IMPORTANT: this always returns the selected section's progression (only `null` for an
     * out-of-range index). It previously returned `null` when the index equalled the current one,
     * which caused every caller's `selectSongSection(i)?.let { progression = it }` sync to be
     * silently skipped — leaving the editor bound to a stale progression that was then written
     * back onto the wrong section. Never short-circuit the return again.
     */
    fun selectSongSection(index: Int): ChordProgression? {
        if (index !in song.sections.indices) return null
        if (index != currentSectionIndex) {
            currentSectionIndex = index
            updateSongSectionsState()
            saveCurrentSession()
        }
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
                shuffleFactor = settings.shuffleFactor
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
        return storage.getSavedSongNames()
    }

    /** Save the current song with a specific name. */
    fun saveNamedSong(name: String) {
        val normalized = name.trim().ifBlank { "New Song" }
        song.name = normalized
        storage.saveNamedSong(normalized, song)
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Load a song by name (returns the first section's progression). */
    fun loadSong(name: String): ChordProgression? {
        storage.loadSong(name)?.let { loaded ->
            song = loaded
            song.ensureValid()
            currentSectionIndex = 0
            // Reflect the loaded song's key in Settings so the key spinner shows the song's key.
            settings.defaultKeyName = song.sections.first().progression.key.name
            updateSongSectionsState()
            saveCurrentSession()
            return song.sections.first().progression
        }
        return null
    }

    /** Delete a saved song by name. */
    fun deleteSong(name: String) {
        storage.deleteSong(name)
    }

    /** Re-publishes the current state (StateFlow skips emissions whose value is unchanged). */
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
            shuffleFactor = settings.shuffleFactor
        }
        val section = SongSection(name = "Section 1", progression = newProgression)
        song = Song(name = "New Song", sections = mutableListOf(section))
        currentSectionIndex = 0
        updateSongSectionsState()
        saveCurrentSession()
    }

    /** Toggle song looping on/off. */
    fun onSongRepeatToggle(isToggled: Boolean) {
        _isSongLooping.value = isToggled
        settings.isLoopingSongEnabled = isToggled
    }
}
