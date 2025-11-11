package com.metaview.chordprogressionhelper.model

data class Chord(
    val root: Key,
    val quality: ChordQuality,
    val scaleDegree: Int? = null
) {
    val scaleDegreeName: String = ""

    fun getDisplayName(): String {
        return "${root.displayName}${quality.displaySuffix}"
    }

    fun getRomanNumeral(): String? {
        if (scaleDegree == null) return null
        val roman = when (scaleDegree) {
            1 -> "I"
            2 -> "II"
            3 -> "III"
            4 -> "IV"
            5 -> "V"
            6 -> "VI"
            7 -> "VII"
            else -> scaleDegree.toString()
        }
        return when (quality) {
            ChordQuality.MINOR, ChordQuality.MINOR_7 -> roman.lowercase()
            ChordQuality.DIMINISHED -> "${roman.lowercase()}°"
            else -> roman
        }
    }

    fun getMidiNotes(): List<Int> {
        return quality.getMidiNotes(root.rootNote)
    }

    companion object {
        // Get additional chords that work well with the current chord
        fun getRelatedChords(currentChord: Chord, key: Key, mode: Mode): List<Chord> {
            val related = mutableListOf<Chord>()
            
            // Add dominant 7th version if applicable
            if (currentChord.quality == ChordQuality.MAJOR) {
                related.add(Chord(currentChord.root, ChordQuality.DOMINANT_7, currentChord.scaleDegree))
            } else if (currentChord.quality == ChordQuality.MINOR) {
                related.add(Chord(currentChord.root, ChordQuality.MINOR_7, currentChord.scaleDegree))
            }
            
            // Add suspended chords
            related.add(Chord(currentChord.root, ChordQuality.SUSPENDED_2, currentChord.scaleDegree))
            related.add(Chord(currentChord.root, ChordQuality.SUSPENDED_4, currentChord.scaleDegree))
            
            return related
        }
    }
}
