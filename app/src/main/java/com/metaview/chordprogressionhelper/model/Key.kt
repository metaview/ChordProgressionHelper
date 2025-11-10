package com.metaview.chordprogressionhelper.model

enum class Key(val displayName: String, val rootNote: Int) {
    C("C", 0),
    C_SHARP("C#", 1),
    D("D", 2),
    D_SHARP("D#", 3),
    E("E", 4),
    F("F", 5),
    F_SHARP("F#", 6),
    G("G", 7),
    G_SHARP("G#", 8),
    A("A", 9),
    A_SHARP("A#", 10),
    B("B", 11);

    companion object {
        fun fromRootNote(note: Int): Key {
            return values().first { it.rootNote == note % 12 }
        }
    }
}
