@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.metaview.chordprogressionhelper.model

import kotlinx.serialization.Serializable

@Serializable
data class Measure(
    var number: Int,
    val chordEvents: MutableList<ChordEvent> = mutableListOf(),
    var strummingPattern: StrummingPattern = StrummingPattern.DEFAULT,
    var drumPattern: DrumPattern = DrumPattern.DEFAULT,
    var soloPattern: SoloPattern = SoloPattern.DEFAULT,
    val id: Long = java.util.UUID.randomUUID().mostSignificantBits
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
    }

    fun removeChordAt(eighthNoteIndex: Int) {
        val quarterNote = eighthNoteIndex / 2
        chordEvents.removeAll { it.quarterNote == quarterNote }
    }

    fun getChordAt(eighthNoteIndex: Int): Chord? {
        val currentQuarter = eighthNoteIndex / 2.0
        return chordEvents.findLast { it.quarterNote <= currentQuarter }?.chord
    }

    fun clear() {
        chordEvents.clear()
    }
}
