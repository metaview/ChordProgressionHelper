package com.metaview.chordprogressionhelper.model

enum class ChordQuality(val displaySuffix: String, val intervals: List<Int>) {
    MAJOR("", listOf(0, 4, 7)),
    MINOR("m", listOf(0, 3, 7)),
    DIMINISHED("dim", listOf(0, 3, 6)),
    AUGMENTED("aug", listOf(0, 4, 8)),
    DOMINANT_7("7", listOf(0, 4, 7, 10)),
    MAJOR_7("maj7", listOf(0, 4, 7, 11)),
    MINOR_7("m7", listOf(0, 3, 7, 10)),
    SUSPENDED_2("sus2", listOf(0, 2, 7)),
    SUSPENDED_4("sus4", listOf(0, 5, 7));

    fun getMidiNotes(rootNote: Int): List<Int> {
        return intervals.map { (rootNote + it) % 12 + 60 } // Start at middle C (60)
    }
}
