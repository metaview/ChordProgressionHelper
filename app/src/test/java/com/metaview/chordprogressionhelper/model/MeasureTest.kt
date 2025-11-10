package com.metaview.chordprogressionhelper.model

import org.junit.Assert.*
import org.junit.Test

class MeasureTest {

    @Test
    fun `can add chord to measure at specific quarter note`() {
        val measure = Measure(1)
        val chord = Chord(Key.C, ChordQuality.MAJOR, 1)
        
        measure.addChord(chord, 0)
        
        assertEquals(chord, measure.getChordAt(0))
    }

    @Test
    fun `chord persists across quarter notes until next chord`() {
        val measure = Measure(1)
        val chord1 = Chord(Key.C, ChordQuality.MAJOR, 1)
        val chord2 = Chord(Key.G, ChordQuality.MAJOR, 5)
        
        measure.addChord(chord1, 0)
        measure.addChord(chord2, 2)
        
        assertEquals(chord1, measure.getChordAt(0))
        assertEquals(chord1, measure.getChordAt(1))
        assertEquals(chord2, measure.getChordAt(2))
        assertEquals(chord2, measure.getChordAt(3))
    }

    @Test
    fun `can remove chord at specific quarter note`() {
        val measure = Measure(1)
        val chord = Chord(Key.C, ChordQuality.MAJOR, 1)
        
        measure.addChord(chord, 0)
        measure.removeChordAt(0)
        
        assertNull(measure.getChordAt(0))
    }

    @Test
    fun `adding chord at same position replaces previous chord`() {
        val measure = Measure(1)
        val chord1 = Chord(Key.C, ChordQuality.MAJOR, 1)
        val chord2 = Chord(Key.G, ChordQuality.MAJOR, 5)
        
        measure.addChord(chord1, 0)
        measure.addChord(chord2, 0)
        
        assertEquals(chord2, measure.getChordAt(0))
        assertEquals(1, measure.chordEvents.size)
    }

    @Test
    fun `clear removes all chords from measure`() {
        val measure = Measure(1)
        val chord1 = Chord(Key.C, ChordQuality.MAJOR, 1)
        val chord2 = Chord(Key.G, ChordQuality.MAJOR, 5)
        
        measure.addChord(chord1, 0)
        measure.addChord(chord2, 2)
        measure.clear()
        
        assertEquals(0, measure.chordEvents.size)
        assertNull(measure.getChordAt(0))
    }
}
