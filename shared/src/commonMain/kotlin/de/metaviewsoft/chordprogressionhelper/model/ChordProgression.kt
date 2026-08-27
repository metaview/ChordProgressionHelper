@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package de.metaviewsoft.chordprogressionhelper.model

import kotlinx.serialization.Serializable

@Serializable
data class ChordProgression(
    var name: String = "",
    var key: Key = Key.C,
    var mode: Mode = Mode.MAJOR,
    @Volatile var tempo: Int = 120,
    var shuffleFactor: Float = 0.0f, // 0.0 = straight, 1.0 = swing, 2.0 = extreme
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

    fun getParallelMajorChords(): List<Chord> {
        if (mode != Mode.MAJOR) return emptyList()
        // Get the relative minor root note (e.g., C major -> A minor, which is 3 semitones down)
        val relativeMinorMidiOffset = (key.rootNote.noteOffset - 3 + 12) % 12
        val relativeMinorRootNote = Note.entries.first { it.noteOffset == relativeMinorMidiOffset }

        // Find the Key enum that has this root note (we need a Key for getScale)
        val relativeMinorKey = Key.entries.first { it.rootNote == relativeMinorRootNote }

        // Now get major chords from that relative minor's parallel major (e.g., A minor -> A major)
        val parallelMajorMode = Mode.MAJOR
        val scale = parallelMajorMode.getScale(relativeMinorKey)
        val numerals = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")

        // Return degrees 2 through 7, as the tonic major might conflict
        return (2..7).map { degree ->
            val rootNote = scale[degree - 1]
            val chordType = parallelMajorMode.getChordTypeForDegree(degree)
            val romanNumeral = numerals[degree - 1]
            Chord(rootNote, chordType, romanNumeral)
        }
    }

    fun addMeasure(withChord: Chord? = null) {
        // Create new measure and copy patterns from the last measure if present.
        val newMeasure = Measure(measures.size + 1)
        // Copy last measure's patterns if available, otherwise keep defaults on the first measure
        if (measures.isNotEmpty()) {
            try {
                val last = measures.last()
                // copy StrummingPattern (create new instance to avoid accidental shared mutable refs)
                try { newMeasure.strummingPattern = StrummingPattern(last.strummingPattern.name, last.strummingPattern.strums.toList()) } catch (_: Exception) {}
                // copy DrumPattern
                try { newMeasure.drumPattern = DrumPattern(last.drumPattern.name, last.drumPattern.steps.map { de.metaviewsoft.chordprogressionhelper.model.DrumStep(it.kick, it.snare, it.hiHat) }) } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
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
