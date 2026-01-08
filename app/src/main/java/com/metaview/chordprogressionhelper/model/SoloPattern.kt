package com.metaview.chordprogressionhelper.model

import kotlinx.serialization.Serializable

/**
 * PianoElement - sealed class für verschiedene Piano-Pattern-Elemente
 * - Note: MIDI-Note mit Länge
 * - Rest: Pause mit Länge
 * - LetRing: Vorherige Note ausklingen lassen (Länge)
 */
@Serializable
sealed class SoloElement {
    abstract val lengthEighths: Int

    @Serializable
    data class Note(
        val midi: Int,
        override val lengthEighths: Int = 1
    ) : SoloElement() {
        init {
            require(midi in 0..127) { "midi must be in 0..127" }
            require(lengthEighths >= 1) { "lengthEighths must be >= 1" }
        }

        fun displayName(): String {
            val octave = (midi / 12) - 1
            val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            val name = noteNames[midi % 12]
            return "$name$octave (${lengthEighths}e)"
        }
    }

    @Serializable
    data class Rest(
        override val lengthEighths: Int = 1
    ) : SoloElement() {
        init {
            require(lengthEighths >= 1) { "lengthEighths must be >= 1" }
        }

        fun displayName(): String = "Rest (${lengthEighths}e)"
    }

    @Serializable
    data class LetRing(
        override val lengthEighths: Int = 1
    ) : SoloElement() {
        init {
            require(lengthEighths >= 1) { "lengthEighths must be >= 1" }
        }

        fun displayName(): String = "Let Ring (${lengthEighths}e)"
    }
}

/**
 * SoloPattern
 * - name: beschreibender Name des Patterns
 * - elements: list of SoloElement (Note, Rest, LetRing)
 *
 * Contract:
 * - inputs: Konstruktor (name + elements)
 * - outputs: serialisierbares Pattern
 * - Fehler: Konstruktion wirft bei invaliden MIDI- oder Längenwerten
 */
@Serializable
data class SoloPattern(
    val name: String = "Default",
    val elements: List<SoloElement> = emptyList()
) {
    /**
     * Summe der Längen in Achtelnoten
     */
    fun totalEighths(): Int = elements.sumOf { it.lengthEighths }

    /**
     * Hilfsfunktion: ist das Pattern leer?
     */
    fun isEmpty(): Boolean = elements.isEmpty()

    companion object {
        /**
         * Default pattern: Leer (keine Solo-Parts standardmäßig)
         */
        val DEFAULT = SoloPattern("Default", emptyList())

        /**
         * Sammlung vordefinierter Patterns (erweiterbar)
         */
        val defaultPatterns: List<SoloPattern> = listOf(DEFAULT)
    }
}

