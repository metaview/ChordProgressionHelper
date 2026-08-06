package de.metaviewsoft.chordprogressionhelper.util

import android.util.Log
import de.metaviewsoft.chordprogressionhelper.model.Chord
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.Note
import de.metaviewsoft.chordprogressionhelper.model.SoloElement
import de.metaviewsoft.chordprogressionhelper.model.SoloPattern

/**
 * Shared transposition logic used both by the single-progression editor
 * ([de.metaviewsoft.chordprogressionhelper.ui.ProgressionViewModel]) and by the app settings
 * key change (transpose a whole song). Keeping it in one place avoids the two flows drifting apart.
 */
object Transposer {

    private const val TAG = "Transposer"

    /** Upward semitone shift (0..11) needed to move from [oldKey] to [newKey]. */
    fun semitoneShift(oldKey: Key, newKey: Key): Int =
        (newKey.rootNote.noteOffset - oldKey.rootNote.noteOffset + 12) % 12

    /**
     * Transpose all chords and solo notes of [progression] in place by [semitoneShift] semitones.
     * A shift of 0 is a no-op.
     */
    fun transpose(progression: ChordProgression, semitoneShift: Int) {
        if (semitoneShift == 0) return

        progression.measures.forEach { measure ->
            val transposedEvents = measure.chordEvents.map { event ->
                Measure.ChordEvent(transposeChord(event.chord, semitoneShift), event.quarterNote)
            }
            measure.chordEvents.clear()
            measure.chordEvents.addAll(transposedEvents)

            // Transpose solo pattern notes (rests / let-ring elements stay unchanged)
            try {
                val soloPattern = measure.soloPattern
                if (!soloPattern.isEmpty()) {
                    val transposedElements = soloPattern.elements.map { element ->
                        when (element) {
                            is SoloElement.Note ->
                                SoloElement.Note((element.midi + semitoneShift) % 128, element.lengthEighths)
                            else -> element
                        }
                    }
                    measure.soloPattern = SoloPattern(soloPattern.name, transposedElements)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to transpose solo pattern: ${e.message}")
            }
        }
    }

    private fun transposeChord(chord: Chord, semitoneShift: Int): Chord {
        val newRootMidi = (chord.root.noteOffset + semitoneShift) % 12
        val newRoot = Note.entries.first { it.noteOffset == newRootMidi }
        // Keep the same chord quality and scale degree name
        return Chord(newRoot, chord.quality, chord.scaleDegreeName)
    }
}
