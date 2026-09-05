package de.metaviewsoft.chordprogressionhelper

import de.metaviewsoft.chordprogressionhelper.model.Chord
import de.metaviewsoft.chordprogressionhelper.model.ChordType
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.Note
import de.metaviewsoft.chordprogressionhelper.model.Song
import de.metaviewsoft.chordprogressionhelper.model.SongSection
import de.metaviewsoft.chordprogressionhelper.util.MidiExporter
import de.metaviewsoft.chordprogressionhelper.util.MidiTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiExporterTest {

    private fun cMajorProgression(): ChordProgression {
        val measure = Measure(1)
        measure.addChord(Chord(Note.C, ChordType.MAJOR, "I"), 0)
        return ChordProgression(name = "Test", measures = mutableListOf(measure))
    }

    private fun countChunks(bytes: ByteArray, tag: String): Int {
        val marker = tag.toByteArray(Charsets.US_ASCII)
        var count = 0
        var i = 0
        while (i <= bytes.size - marker.size) {
            var match = true
            for (j in marker.indices) if (bytes[i + j] != marker[j]) { match = false; break }
            if (match) count++
            i++
        }
        return count
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    @Test
    fun `header is a valid format 1 SMF with correct track count`() {
        val bytes = MidiExporter.exportProgression(
            cMajorProgression(),
            setOf(MidiTrackType.CHORDS, MidiTrackType.DRUMS, MidiTrackType.SOLO),
        )

        assertEquals("MThd", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(6, readInt32(bytes, 4))            // header length
        assertEquals(1, readInt16(bytes, 8))            // format 1
        assertEquals(4, readInt16(bytes, 10))           // tempo track + 3 instrument tracks
        assertTrue(readInt16(bytes, 12) > 0)            // division / TPQ
        assertEquals(4, countChunks(bytes, "MTrk"))
    }

    @Test
    fun `track selection controls the number of MTrk chunks`() {
        val prog = cMajorProgression()
        val onlyDrums = MidiExporter.exportProgression(prog, setOf(MidiTrackType.DRUMS))
        // Tempo/conductor track + exactly one selected instrument track.
        assertEquals(2, readInt16(onlyDrums, 10))
        assertEquals(2, countChunks(onlyDrums, "MTrk"))
    }

    @Test
    fun `chord notes are lifted into an audible register and are valid MIDI`() {
        val bytes = MidiExporter.exportProgression(cMajorProgression(), setOf(MidiTrackType.CHORDS))
        // Every note-on/off data byte must be a valid MIDI note (0..127). A regression that forgot
        // to lift the offset-encoded chord notes would produce negative -> wrapped bytes.
        var i = 14
        var sawNoteOn = false
        while (i < bytes.size - 2) {
            val status = bytes[i].toInt() and 0xFF
            if (status and 0xF0 == 0x90 && (bytes[i + 2].toInt() and 0xFF) > 0) {
                val note = bytes[i + 1].toInt() and 0xFF
                assertTrue("note $note out of range", note in 0..127)
                // C major lifted: root C offset 0 -> 60. Notes should sit around middle C.
                assertTrue("note $note unexpectedly low", note in 40..96)
                sawNoteOn = true
            }
            i++
        }
        assertTrue("expected at least one chord note-on", sawNoteOn)
    }

    @Test
    fun `song export concatenates sections`() {
        val song = Song(
            name = "Two",
            sections = mutableListOf(
                SongSection("A", cMajorProgression()),
                SongSection("B", cMajorProgression()),
            ),
        )
        val bytes = MidiExporter.exportSong(song, setOf(MidiTrackType.CHORDS))
        assertEquals("MThd", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(2, countChunks(bytes, "MTrk"))
    }

    @Test
    fun `export is deterministic for identical input`() {
        val a = MidiExporter.exportProgression(cMajorProgression(), setOf(MidiTrackType.CHORDS, MidiTrackType.DRUMS))
        val b = MidiExporter.exportProgression(cMajorProgression(), setOf(MidiTrackType.CHORDS, MidiTrackType.DRUMS))
        assertTrue(a.contentEquals(b))
    }
}
