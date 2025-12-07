@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.metaview.chordprogressionhelper.model

import kotlinx.serialization.Serializable

@Serializable
data class ChordProgression(
    var name: String = "",
    var key: Key = Key.C,
    var mode: Mode = Mode.MAJOR,
    var tempo: Int = 120,
    val measures: MutableList<Measure> = mutableListOf(Measure(1))
) {
    fun getScaleDegreeChords(): List<Chord> {
        val scale = mode.getScale(key)
        return (1..7).map { degree ->
            val rootNote = scale[degree - 1]
            val chordType = mode.getChordTypeForDegree(degree)
            val romanNumeral = getRomanNumeral(degree, chordType)
            Chord(rootNote, chordType, romanNumeral)
        }
    }

    fun getParallelMinorChords(): List<Chord> {
        if (mode != Mode.MAJOR) return emptyList()
        val parallelMinorMode = Mode.MINOR
        val scale = parallelMinorMode.getScale(key)
        val numerals = listOf("i", "ii°", "bIII", "iv", "v", "bVI", "bVII")

        // Return degrees 2 through 7, as the tonic minor is less common for borrowing.
        return (2..7).map { degree ->
            val rootNote = scale[degree - 1]
            val chordType = parallelMinorMode.getChordTypeForDegree(degree)
            val romanNumeral = numerals[degree - 1]
            Chord(rootNote, chordType, romanNumeral)
        }
    }

    fun addMeasure(withChord: Chord? = null) {
        val newMeasure = Measure(measures.size + 1)
        withChord?.let { newMeasure.addChord(it, 0) }
        measures.add(newMeasure)
    }

    fun removeMeasure(index: Int) {
        if (index in measures.indices) {
            measures.removeAt(index)
            renumberMeasures()
        }
    }

    fun moveMeasure(from: Int, to: Int) {
        if (from in measures.indices && to in measures.indices) {
            val item = measures.removeAt(from)
            measures.add(to, item)
        }
    }

    fun renumberMeasures() {
        val renumbered = measures.mapIndexed { index, measure ->
            measure.copy(number = index + 1)
        }
        measures.clear()
        measures.addAll(renumbered)
    }

    fun clear() {
        measures.clear()
        measures.add(Measure(1))
    }

    private fun getRomanNumeral(degree: Int, chordType: ChordType): String {
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
        return when (chordType) {
            ChordType.MAJOR -> roman
            ChordType.MINOR -> roman.lowercase()
            ChordType.DIMINISHED -> roman.lowercase() + "°"
            else -> roman // For Dominant 7th etc.
        }
    }
}
