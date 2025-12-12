package com.metaview.chordprogressionhelper.model

import kotlinx.serialization.Serializable

/**
 * PianoNote
 * - midi: MIDI-Notennummer (0..127)
 * - lengthEighths: Länge der Note in Achtel-Noten (1 = Achtel, 2 = Viertel, 8 = ganze 4/4-Note)
 */
@Serializable
data class PianoNote(
    val midi: Int,
    val lengthEighths: Int = 1
) {
    init {
        require(midi in 0..127) { "midi must be in 0..127" }
        require(lengthEighths >= 1) { "lengthEighths must be >= 1" }
    }

    /**
     * human-readable name (small helper) — returns e.g. "C4 (8e)"
     */
    fun displayName(): String {
        val octave = (midi / 12) - 1
        val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val name = noteNames[midi % 12]
        return "$name$octave (${lengthEighths}e)"
    }
}

/**
 * PianoPattern
 * - name: beschreibender Name des Patterns
 * - notes: list of PianoNote in sequence
 *
 * Contract:
 * - inputs: Konstruktor (name + notes)
 * - outputs: serialisierbares Pattern
 * - Fehler: Konstruktion wirft bei invaliden MIDI- oder Längenwerten (via PianoNote)
 */
@Serializable
data class PianoPattern(
    val name: String = "Default",
    val notes: List<PianoNote> = emptyList()
) {
    /**
     * Summe der Längen in Achtelnoten
     */
    fun totalEighths(): Int = notes.sumOf { it.lengthEighths }

    /**
     * Hilfsfunktion: ist das Pattern leer?
     */
    fun isEmpty(): Boolean = notes.isEmpty()

    companion object {
        /**
         * Default pattern: Middle C (MIDI 60) ganze Note (8 Achtel = 4/4)
         */
        val DEFAULT = PianoPattern("Default", listOf(PianoNote(60, 8)))

        /**
         * Sammlung vordefinierter Patterns (erweiterbar)
         */
        val defaultPatterns: List<PianoPattern> = listOf(DEFAULT)
    }
}

