@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package de.metaviewsoft.chordprogressionhelper.model

import kotlinx.serialization.Serializable

@Serializable
data class Measure(
    var number: Int,
    val chordEvents: MutableList<ChordEvent> = mutableListOf(),
    var strummingPattern: StrummingPattern = StrummingPattern.DEFAULT,
    var drumPattern: DrumPattern = DrumPattern.DEFAULT,
    var soloPattern: SoloPattern = SoloPattern.DEFAULT,
    val id: Long = kotlin.random.Random.nextLong()
) {
    @Serializable
    data class ChordEvent(
        val chord: Chord,
        val quarterNote: Int // 0-3 for a 4/4 measure
    )

    fun addChord(chord: Chord, eighthNoteIndex: Int) {
        val quarterNote = eighthNoteIndex / 2
        // Remove any existing chord at this position
        chordEvents.removeAll { it.quarterNote == quarterNote }
        chordEvents.add(ChordEvent(chord, quarterNote))
        chordEvents.sortBy { it.quarterNote }
        mergeAdjacentEqual()
    }

    fun removeChordAt(eighthNoteIndex: Int) {
        val quarterNote = eighthNoteIndex / 2
        chordEvents.removeAll { it.quarterNote == quarterNote }
        mergeAdjacentEqual()
    }

    /**
     * Collapse runs of identical chords into a single event. [getChordAt] carries the most recent
     * chord forward, so an event whose chord equals the previous event's chord is redundant: it
     * changes nothing audible or visible, but it splits the measure. That split is why re-setting a
     * slot back to the chord that already fills the measure, and then changing the first slot, only
     * updated the slots up to the hidden boundary. Keeping just the first event of each run means a
     * measure filled with a single chord is stored as one event at its start, so setting a new
     * chord there changes the whole run.
     */
    private fun mergeAdjacentEqual() {
        if (chordEvents.size < 2) return
        val merged = mutableListOf<ChordEvent>()
        for (event in chordEvents) {
            if (merged.isEmpty() || merged.last().chord != event.chord) {
                merged.add(event)
            }
        }
        if (merged.size != chordEvents.size) {
            chordEvents.clear()
            chordEvents.addAll(merged)
        }
    }

    fun getChordAt(eighthNoteIndex: Int): Chord? {
        val currentQuarter = eighthNoteIndex / 2.0
        return chordEvents.findLast { it.quarterNote <= currentQuarter }?.chord
    }

    fun clear() {
        chordEvents.clear()
    }
}
