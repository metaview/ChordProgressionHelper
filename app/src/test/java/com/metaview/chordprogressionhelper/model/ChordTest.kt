package com.metaview.chordprogressionhelper.model

import org.junit.Assert.*
import org.junit.Test

class ChordTest {

    @Test
    fun `chord display name is correct for major chord`() {
        val chord = Chord(Key.C, ChordQuality.MAJOR, 1)
        assertEquals("C", chord.getDisplayName())
    }

    @Test
    fun `chord display name is correct for minor chord`() {
        val chord = Chord(Key.A, ChordQuality.MINOR, 6)
        assertEquals("Am", chord.getDisplayName())
    }

    @Test
    fun `chord display name is correct for seventh chord`() {
        val chord = Chord(Key.G, ChordQuality.DOMINANT_7, 5)
        assertEquals("G7", chord.getDisplayName())
    }

    @Test
    fun `roman numeral is correct for major chord`() {
        val chord = Chord(Key.C, ChordQuality.MAJOR, 1)
        assertEquals("I", chord.getRomanNumeral())
    }

    @Test
    fun `roman numeral is correct for minor chord`() {
        val chord = Chord(Key.D, ChordQuality.MINOR, 2)
        assertEquals("ii", chord.getRomanNumeral())
    }

    @Test
    fun `roman numeral is correct for diminished chord`() {
        val chord = Chord(Key.B, ChordQuality.DIMINISHED, 7)
        assertEquals("vii°", chord.getRomanNumeral())
    }

    @Test
    fun `related chords include seventh version for major chord`() {
        val chord = Chord(Key.G, ChordQuality.MAJOR, 5)
        val related = Chord.getRelatedChords(chord, Key.C, Mode.MAJOR)
        
        assertTrue(related.any { it.quality == ChordQuality.DOMINANT_7 && it.root == Key.G })
    }

    @Test
    fun `related chords include suspended chords`() {
        val chord = Chord(Key.C, ChordQuality.MAJOR, 1)
        val related = Chord.getRelatedChords(chord, Key.C, Mode.MAJOR)
        
        assertTrue(related.any { it.quality == ChordQuality.SUSPENDED_2 })
        assertTrue(related.any { it.quality == ChordQuality.SUSPENDED_4 })
    }
}
