package de.metaviewsoft.chordprogressionhelper.data

import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Song
import kotlinx.serialization.InternalSerializationApi

/**
 * The single in-memory source of truth for the currently edited song.
 *
 * Both [de.metaviewsoft.chordprogressionhelper.ui.SongViewModel] (song/section editor) and
 * [de.metaviewsoft.chordprogressionhelper.ui.ProgressionViewModel] (progression/chord/drum/solo
 * editor) share THIS instance, so there is exactly one `Song` object in memory. Every view is just
 * an editor onto part of it:
 *
 *   Song -> sections -> a progression (measures) -> chords, drum & solo patterns
 *
 * Persistence goes exclusively through the song store ([ProgressionRepository.saveLastSongSession]).
 * The former standalone "last progression" store (`saveLastSession`/`loadLastSession`) is no longer
 * an authoritative location — keeping data in two places is exactly what corrupted sections before.
 */
@OptIn(InternalSerializationApi::class)
class SongSession(
    private val repo: ProgressionRepository
) {
    /** The one authoritative song. Reassigned only on load/new-song. */
    var song: Song = repo.loadLastSongSession().also { it.ensureValid() }
        set(value) {
            field = value.also { it.ensureValid() }
            if (currentSectionIndex !in field.sections.indices) currentSectionIndex = 0
        }

    /** Index of the section currently being edited. Always clamped to a valid range. */
    var currentSectionIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, (song.sections.size - 1).coerceAtLeast(0))
        }

    /** The progression of the current section — the object every editor mutates in place. */
    val currentProgression: ChordProgression
        get() {
            song.ensureValid()
            if (currentSectionIndex !in song.sections.indices) currentSectionIndex = 0
            return song.sections[currentSectionIndex].progression
        }

    /** Replace the current section's progression (used by load-progression / new-progression). */
    fun replaceCurrentProgression(progression: ChordProgression) {
        song.ensureValid()
        if (currentSectionIndex !in song.sections.indices) currentSectionIndex = 0
        song.sections[currentSectionIndex].progression = progression
    }

    /** Persist the whole song to the single authoritative store. */
    fun save() {
        repo.saveLastSongSession(song)
    }
}
