package com.metaview.chordprogressionhelper.model

import org.junit.Assert.*
import org.junit.Test

class ChordProgressionTest {

    @Test
    fun `progression initializes with 4 measures`() {
        val progression = ChordProgression()
        assertEquals(4, progression.measures.size)
    }

    @Test
    fun `can add measure to progression`() {
        val progression = ChordProgression()
        progression.addMeasure()
        assertEquals(5, progression.measures.size)
        assertEquals(5, progression.measures.last().number)
    }

    @Test
    fun `scale degree chords are correct for C major`() {
        val progression = ChordProgression(key = Key.C, mode = Mode.MAJOR)
        val chords = progression.getScaleDegreeChords()
        
        assertEquals(7, chords.size)
        assertEquals("C", chords[0].getDisplayName())
        assertEquals("Dm", chords[1].getDisplayName())
        assertEquals("Em", chords[2].getDisplayName())
        assertEquals("F", chords[3].getDisplayName())
        assertEquals("G", chords[4].getDisplayName())
        assertEquals("Am", chords[5].getDisplayName())
        assertEquals("Bdim", chords[6].getDisplayName())
    }

    @Test
    fun `scale degree chords are correct for A minor`() {
        val progression = ChordProgression(key = Key.A, mode = Mode.MINOR)
        val chords = progression.getScaleDegreeChords()
        
        assertEquals(7, chords.size)
        assertEquals("Am", chords[0].getDisplayName())
        assertEquals("Bdim", chords[1].getDisplayName())
        assertEquals("C", chords[2].getDisplayName())
    }

    @Test
    fun `clearing progression removes all chords from measures`() {
        val progression = ChordProgression()
        val chord = Chord(Key.C, ChordQuality.MAJOR, 1)
        progression.measures[0].addChord(chord, 0)
        
        progression.clear()
        
        assertNull(progression.measures[0].getChordAt(0))
    }
}
