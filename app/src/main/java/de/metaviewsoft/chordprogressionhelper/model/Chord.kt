package de.metaviewsoft.chordprogressionhelper.model

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
    MAJOR("", listOf(-12, 0, 4, 7)),
    MINOR("m", listOf(-12, 0, 3, 7)),
    DIMINISHED("°", listOf(-12, 0, 3, 6)),
    DOMINANT_SEVENTH("7", listOf(-12, 0, 4, 7, 10)),
    // POWER chords like C5 are typically just root + perfect fifth. We include the lower octave (-12)
    // to keep the sound full and consistent with other chord definitions in this app.
    POWER("5", listOf(-12, 0, 7))
}
