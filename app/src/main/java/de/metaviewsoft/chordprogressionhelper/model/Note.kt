package de.metaviewsoft.chordprogressionhelper.model

import kotlinx.serialization.Serializable

@Serializable
enum class Note(val displayName: String, val midiOffset: Int, val noteOffset: Int) {
    E("E", -8, 4),
    F("F", -7, 5),
    F_SHARP("F#", -6, 6),
    G("G", -5, 7),
    A_FLAT("Ab", -4, 8),
    A("A", -3, 9),
    B_FLAT("Bb", -2, 10),
    B("B", -1, 11),
    C("C", 0,0),
    C_SHARP("C#", 1,1),
    D("D", 2,2),
    E_FLAT("Eb", 3,3)

}
