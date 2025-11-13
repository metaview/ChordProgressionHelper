package com.metaview.chordprogressionhelper.model

data class Measure(
    val number: Int,
    val chordEvents: MutableList<ChordEvent> = mutableListOf(),
    var strummingPattern: StrummingPattern = StrummingPattern.DOWN_DOWN_DOWN_DOWN,
    val id: Long = java.util.UUID.randomUUID().mostSignificantBits
) {
    data class ChordEvent(
        val chord: Chord,
        val quarterNote: Int // 0-3 for a 4/4 measure
    )

    fun addChord(chord: Chord, quarterNote: Int) {
        // Remove any existing chord at this position
        chordEvents.removeAll { it.quarterNote == quarterNote }
        chordEvents.add(ChordEvent(chord, quarterNote))
        chordEvents.sortBy { it.quarterNote }
    }

    fun removeChordAt(quarterNote: Int) {
        chordEvents.removeAll { it.quarterNote == quarterNote }
    }

    fun getChordAt(quarterNote: Int): Chord? {
        // Get the last chord before or at this quarter note
        return chordEvents
            .filter { it.quarterNote <= quarterNote }
            .maxByOrNull { it.quarterNote }
            ?.chord
    }

    fun clear() {
        chordEvents.clear()
    }
}
