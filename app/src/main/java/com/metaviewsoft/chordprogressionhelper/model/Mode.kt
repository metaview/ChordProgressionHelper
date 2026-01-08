package de.metaviewsoft.chordprogressionhelper.model

import kotlinx.serialization.Serializable

@Serializable
enum class Mode(val displayName: String, private val scaleIntervals: List<Int>) {
    MAJOR("Major", listOf(2, 2, 1, 2, 2, 2, 1)),
    MINOR("Minor", listOf(2, 1, 2, 2, 1, 2, 2));

    fun getScale(key: Key): List<Note> {
        val scale = mutableListOf(key.rootNote)
        var currentOffset = key.rootNote.midiOffset
        for (interval in scaleIntervals.dropLast(1)) {
            currentOffset = (currentOffset + interval) % 12
            scale.add(Note.entries.first { it.midiOffset == currentOffset })
        }
        return scale
    }

    fun getChordTypeForDegree(degree: Int): ChordType {
        return when (this) {
            MAJOR -> when (degree) {
                1, 4, 5 -> ChordType.MAJOR
                2, 3, 6 -> ChordType.MINOR
                7 -> ChordType.DIMINISHED
                else -> throw IllegalArgumentException("Invalid degree: $degree")
            }
            MINOR -> when (degree) {
                1, 4 -> ChordType.MINOR
                3, 6, 7 -> ChordType.MAJOR
                2 -> ChordType.DIMINISHED
                5 -> ChordType.MINOR // Or Major in harmonic minor
                else -> throw IllegalArgumentException("Invalid degree: $degree")
            }
        }
    }

    private fun getRomanNumeralForDegree(degree: Int, type: ChordType): String {
        val roman = when (degree) {
            1 -> "I"
            2 -> "II"
            3 -> "III"
            4 -> "IV"
            5 -> "V"
            6 -> "VI"
            7 -> "VII"
            else -> ""
        }
        return when (type) {
            ChordType.MAJOR -> roman
            ChordType.MINOR -> roman.lowercase()
            ChordType.DIMINISHED -> roman.lowercase() + "°"
            else -> roman // For dominant 7th etc.
        }
    }

    fun getAllScaleDegreeChords(key: Key): List<Chord> {
        val scale = getScale(key)
        return (1..7).map { degree ->
            val chordType = getChordTypeForDegree(degree)
            Chord(
                root = scale[degree - 1],
                quality = chordType,
                scaleDegreeName = getRomanNumeralForDegree(degree, chordType)
            )
        }
    }
}
