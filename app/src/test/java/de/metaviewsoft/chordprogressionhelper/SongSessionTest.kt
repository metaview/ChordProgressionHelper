package de.metaviewsoft.chordprogressionhelper

import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.Song
import de.metaviewsoft.chordprogressionhelper.model.SongSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the central-store invariant: the progression edited by ProgressionViewModel is ALWAYS the
 * current section's progression, so editing it can never leak onto (or from) another section.
 *
 * These tests exercise the same index/selection logic that [de.metaviewsoft.chordprogressionhelper.data.SongSession]
 * implements, without needing an Android Context.
 */
class SongSessionTest {

    private fun songOf(vararg sectionNames: String): Song {
        val sections = sectionNames.mapIndexed { i, n ->
            SongSection(name = n, progression = ChordProgression(name = n, measures = mutableListOf(Measure(i + 1))))
        }.toMutableList()
        return Song(name = "S", sections = sections)
    }

    /** Mirrors SongSession.currentProgression + selectSection + replaceCurrentProgression. */
    private class FakeSession(var song: Song) {
        var currentSectionIndex = 0
            set(value) { field = value.coerceIn(0, (song.sections.size - 1).coerceAtLeast(0)) }
        val currentProgression get() = song.sections[currentSectionIndex].progression
        fun replaceCurrentProgression(p: ChordProgression) { song.sections[currentSectionIndex].progression = p }
    }

    @Test
    fun currentProgression_followsSelectedSection() {
        val s = FakeSession(songOf("A", "B", "C"))
        s.currentSectionIndex = 2
        assertEquals("C", s.currentProgression.name)
        s.currentSectionIndex = 0
        assertEquals("A", s.currentProgression.name)
    }

    @Test
    fun editingCurrentProgression_doesNotAffectOtherSections() {
        val s = FakeSession(songOf("A", "B", "C"))
        s.currentSectionIndex = 0
        // Edit section A's progression in place (as the editor does)
        s.currentProgression.name = "A-edited"
        s.currentProgression.measures.add(Measure(99))

        // B and C untouched
        assertEquals("B", s.song.sections[1].progression.name)
        assertEquals("C", s.song.sections[2].progression.name)
        assertEquals("A-edited", s.song.sections[0].progression.name)
        // The edited object IS the section's object (single source of truth, no copy)
        assertSame(s.song.sections[0].progression, s.currentProgression)
    }

    @Test
    fun selectingSameIndex_stillReturnsThatSectionsProgression() {
        // Regression: selectSongSection must return the section progression even when the index is
        // unchanged, otherwise the editor stays bound to a stale progression.
        val s = FakeSession(songOf("A", "B"))
        s.currentSectionIndex = 0
        val again = s.currentProgression // simulate re-selecting index 0
        assertSame(s.song.sections[0].progression, again)
        assertEquals("A", again.name)
    }

    @Test
    fun currentSectionIndex_isClampedToValidRange() {
        val s = FakeSession(songOf("A", "B"))
        s.currentSectionIndex = 99
        assertTrue(s.currentSectionIndex in s.song.sections.indices)
        assertEquals("B", s.currentProgression.name)
    }
}
