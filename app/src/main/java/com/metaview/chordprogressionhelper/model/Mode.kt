package com.metaview.chordprogressionhelper.model

enum class Mode(
    val displayName: String,
    val intervals: List<Int>,
    val scaleDegreeQualities: List<ChordQuality>
) {
    MAJOR(
        "Major",
        listOf(0, 2, 4, 5, 7, 9, 11), // Major scale intervals
        listOf(
            ChordQuality.MAJOR,      // I
            ChordQuality.MINOR,      // ii
            ChordQuality.MINOR,      // iii
            ChordQuality.MAJOR,      // IV
            ChordQuality.MAJOR,      // V
            ChordQuality.MINOR,      // vi
            ChordQuality.DIMINISHED  // vii°
        )
    ),
    MINOR(
        "Minor",
        listOf(0, 2, 3, 5, 7, 8, 10), // Natural minor scale intervals
        listOf(
            ChordQuality.MINOR,      // i
            ChordQuality.DIMINISHED, // ii°
            ChordQuality.MAJOR,      // III
            ChordQuality.MINOR,      // iv
            ChordQuality.MINOR,      // v
            ChordQuality.MAJOR,      // VI
            ChordQuality.MAJOR       // VII
        )
    ),
    DORIAN(
        "Dorian",
        listOf(0, 2, 3, 5, 7, 9, 10),
        listOf(
            ChordQuality.MINOR,      // i
            ChordQuality.MINOR,      // ii
            ChordQuality.MAJOR,      // III
            ChordQuality.MAJOR,      // IV
            ChordQuality.MINOR,      // v
            ChordQuality.DIMINISHED, // vi°
            ChordQuality.MAJOR       // VII
        )
    ),
    MIXOLYDIAN(
        "Mixolydian",
        listOf(0, 2, 4, 5, 7, 9, 10),
        listOf(
            ChordQuality.MAJOR,      // I
            ChordQuality.MINOR,      // ii
            ChordQuality.DIMINISHED, // iii°
            ChordQuality.MAJOR,      // IV
            ChordQuality.MINOR,      // v
            ChordQuality.MINOR,      // vi
            ChordQuality.MAJOR       // VII
        )
    );

    fun getScaleDegreeChord(key: Key, degree: Int): Chord {
        val noteIndex = (key.rootNote + intervals[degree - 1]) % 12
        val rootKey = Key.fromRootNote(noteIndex)
        return Chord(rootKey, scaleDegreeQualities[degree - 1], degree)
    }

    fun getAllScaleDegreeChords(key: Key): List<Chord> {
        return (1..7).map { degree -> getScaleDegreeChord(key, degree) }
    }
}
