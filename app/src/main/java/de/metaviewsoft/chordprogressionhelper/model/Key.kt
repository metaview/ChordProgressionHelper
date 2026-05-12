package de.metaviewsoft.chordprogressionhelper.model

import kotlinx.serialization.Serializable

@Serializable
enum class Key(val displayName: String, val rootNote: Note) {
    C("C / Am", Note.C),
    C_SHARP("C# / A#m", Note.C_SHARP),
    D("D / Bm", Note.D),
    E_FLAT("Eb / Cm", Note.E_FLAT),
    E("E / C#m", Note.E),
    F("F / Dm", Note.F),
    F_SHARP("F# / D#m", Note.F_SHARP),
    G("G / Em", Note.G),
    A_FLAT("Ab / Fm", Note.A_FLAT),
    A("A / F#m", Note.A),
    B_FLAT("Bb / Gm", Note.B_FLAT),
    B("B / G#m", Note.B)
}
