package com.metaview.chordprogressionhelper.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Chord(
    val root: Note,
    val quality: ChordType,
    val scaleDegreeName: String
) {
    @Transient
    val name = root.displayName

    fun getDisplayName(): String = root.displayName + quality.suffix

    fun getRomanNumeral(): String? = scaleDegreeName

    fun getMidiNotes(): List<Int> {
        val rootMidi = root.midiOffset
        val intervals = quality.intervals
        return intervals.map { rootMidi + it }
    }
}

@Serializable
enum class ChordType(val suffix: String, val intervals: List<Int>) {
    MAJOR("", listOf(0, 4, 7, -12)),
    MINOR("m", listOf(0, 3, 7, -12)),
    DIMINISHED("°", listOf(0, 3, 6, -12)),
    DOMINANT_SEVENTH("7", listOf(0, 4, 7, 10, -12))
}
