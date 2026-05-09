package de.metaviewsoft.chordprogressionhelper.model

/**
 * Vordefinierte Akkordprogressionen als Startvorlagen.
 * Die Templates verwenden römische Stufen, die sich automatisch an die Tonart anpassen.
 */
data class ProgressionTemplate(
    val name: String,
    val degrees: List<ScaleDegree>, // Liste von Stufen (I, ii, iii, IV, V, vi, vii°)
    val durations: List<Int>, // Liste von Dauern in viertel Noten
    val shuffleFactor: Float = 0.0f // Shuffle-Rhythmus: 0.0 = straight, 1.0 = swing, 2.0 = extreme
) {
    fun getDescription(key: Key): String {
        // Generiere die Akkordnamen für die aktuelle Tonart
        return degrees.joinToString(" - ") { degree ->
            val (root, quality) = degree.getChordInKey(key)
            root.displayName + quality.suffix
        }
    }
}

/**
 * Repräsentiert eine Stufe in einer Tonleiter mit ihrer Qualität.
 */
enum class ScaleDegree(val interval: Int, val defaultQuality: ChordType) {
    I(0, ChordType.MAJOR),
    II(2, ChordType.MINOR),
    III(4, ChordType.MINOR),
    IV(5, ChordType.MAJOR),
    V(7, ChordType.MAJOR),
    VI(9, ChordType.MINOR),
    VII(11, ChordType.DIMINISHED),

    // Alternativen mit expliziter Qualität
    I_MAJOR(0, ChordType.MAJOR),
    II_MINOR(2, ChordType.MINOR),
    II_MAJOR(2, ChordType.MAJOR),
    II_MAJOR_SEVENTH(2, ChordType.DOMINANT_SEVENTH),
    III_MINOR(4, ChordType.MINOR),
    III_MAJOR(4, ChordType.MAJOR),
    III_MAJOR_SEVENTH(4, ChordType.DOMINANT_SEVENTH),
    IV_MAJOR(5, ChordType.MAJOR),
    V_MAJOR(7, ChordType.MAJOR),
    V_SEVENTH(7, ChordType.DOMINANT_SEVENTH),
    VI_MINOR(9, ChordType.MINOR),
    VI_MAJOR(9, ChordType.MAJOR),
    VII_DIMINISHED(11, ChordType.DIMINISHED),

    // Borrowed chords (häufig aus paralleler Moll-Tonart)
    FLAT_III(3, ChordType.MAJOR),
    FLAT_VI(8, ChordType.MAJOR),
    FLAT_VII(10, ChordType.MAJOR);

    /**
     * Berechnet den konkreten Akkord (Note + ChordType) für eine gegebene Tonart.
     */
    fun getChordInKey(key: Key): Pair<Note, ChordType> {
        val keyRootMidi = key.rootNote.midiOffset
        val targetMidi = (keyRootMidi + interval) % 12

        val note = Note.entries.first { it.midiOffset == targetMidi }
        return Pair(note, defaultQuality)
    }

    /**
     * Gibt den römischen Stufennamen zurück.
     */
    fun getRomanNumeral(): String {
        return when (this) {
            I, I_MAJOR -> "I"
            II, II_MINOR -> "ii"
            II_MAJOR, II_MAJOR_SEVENTH -> "II"
            III, III_MINOR -> "iii"
            III_MAJOR, III_MAJOR_SEVENTH -> "III"
            IV, IV_MAJOR -> "IV"
            V, V_MAJOR -> "V"
            V_SEVENTH -> "V7"
            VI, VI_MINOR -> "vi"
            VI_MAJOR -> "VI"
            VII, VII_DIMINISHED -> "vii°"
            FLAT_III -> "♭III"
            FLAT_VI -> "♭VI"
            FLAT_VII -> "♭VII"
        }
    }
}

object ProgressionTemplates {

    fun getAllTemplates(): List<ProgressionTemplate> {
        return listOf(
            // 1. Pop Progression (I-V-vi-IV)
            ProgressionTemplate(
                name = "Pop Progression",
                degrees = listOf(ScaleDegree.I, ScaleDegree.V, ScaleDegree.VI, ScaleDegree.IV),
                durations = listOf(4, 4, 4, 4)
            ),

            // 2. 12-Bar Blues
            ProgressionTemplate(
                name = "12-Bar Blues",
                degrees = listOf(
                    ScaleDegree.I, ScaleDegree.I, ScaleDegree.I, ScaleDegree.I,
                    ScaleDegree.IV, ScaleDegree.IV, ScaleDegree.I, ScaleDegree.I,
                    ScaleDegree.V, ScaleDegree.IV, ScaleDegree.I, ScaleDegree.V
                ),
                durations = listOf(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4),
                shuffleFactor = 1.0f // Blues mit Swing-Rhythmus
            ),

            // 3. Jazz ii-V-I
            ProgressionTemplate(
                name = "Jazz ii-V-I",
                degrees = listOf(ScaleDegree.II, ScaleDegree.V, ScaleDegree.I, ScaleDegree.I),
                durations = listOf(4, 4, 4, 4)
            ),

            // 4. Canon in D (I-V-vi-iii-IV-I-IV-V)
            ProgressionTemplate(
                name = "Canon in D",
                degrees = listOf(
                    ScaleDegree.I, ScaleDegree.V, ScaleDegree.VI, ScaleDegree.III,
                    ScaleDegree.IV, ScaleDegree.I, ScaleDegree.IV, ScaleDegree.V
                ),
                durations = listOf(4, 4, 4, 4, 4, 4, 4, 4)
            ),

            // 5. Andalusian Cadence (vi-V-IV-III)
            ProgressionTemplate(
                name = "Andalusian Cadence",
                degrees = listOf(ScaleDegree.VI_MINOR, ScaleDegree.V, ScaleDegree.IV, ScaleDegree.III_MAJOR),
                durations = listOf(4, 4, 4, 4)
            ),

            // 6. Doo-Wop (I-vi-IV-V)
            ProgressionTemplate(
                name = "Doo-Wop",
                degrees = listOf(ScaleDegree.I, ScaleDegree.VI, ScaleDegree.IV, ScaleDegree.V),
                durations = listOf(4, 4, 4, 4)
            ),

            // 7. Pop (vi-IV-ii-V)
            ProgressionTemplate(
                name = "Pop",
                degrees = listOf(ScaleDegree.VI, ScaleDegree.IV, ScaleDegree.II, ScaleDegree.II_MAJOR_SEVENTH, ScaleDegree.V, ScaleDegree.III_MAJOR_SEVENTH),
                durations = listOf(4, 4, 3, 1, 3, 1)
            ),

            // 8. Royal Road (IV-V-iii-vi)
            ProgressionTemplate(
                name = "Royal Road",
                degrees = listOf(ScaleDegree.IV, ScaleDegree.V, ScaleDegree.III, ScaleDegree.VI),
                durations = listOf(4, 4, 4, 4)
            ),

            // 9. Sad Progression (vi-IV-I-V)
            ProgressionTemplate(
                name = "Sad Progression",
                degrees = listOf(ScaleDegree.VI, ScaleDegree.IV, ScaleDegree.I, ScaleDegree.V),
                durations = listOf(4, 4, 4, 4)
            ),

            // 10. Flamenco (vi-V-IV-III)
            ProgressionTemplate(
                name = "Flamenco",
                degrees = listOf(ScaleDegree.VI_MINOR, ScaleDegree.V, ScaleDegree.IV, ScaleDegree.III_MAJOR),
                durations = listOf(4, 4, 4, 4)
            ),

            // 11. 50s Classics (I-IV-I-V)
            ProgressionTemplate(
                name = "50s Classics",
                degrees = listOf(ScaleDegree.I, ScaleDegree.IV, ScaleDegree.I, ScaleDegree.V),
                durations = listOf(4, 4, 4, 4)
            ),

            // 12. Pop-Rock (I-V-IV-V)
            ProgressionTemplate(
                name = "Pop-Rock",
                degrees = listOf(ScaleDegree.I, ScaleDegree.V, ScaleDegree.IV, ScaleDegree.V),
                durations = listOf(4, 4, 4, 4)
            ),

            // 13. Soft Pop (I-IV-V-vi)
            ProgressionTemplate(
                name = "Soft Pop",
                degrees = listOf(ScaleDegree.I, ScaleDegree.IV_MAJOR, ScaleDegree.V, ScaleDegree.VI),
                durations = listOf(4, 4, 4, 4)
            ),

            // 13. Soft Pop (I-vi-ii-V)
            ProgressionTemplate(
                name = "??????",
                degrees = listOf(ScaleDegree.I, ScaleDegree.VI, ScaleDegree.II, ScaleDegree.V),
                durations = listOf(4, 4, 4, 4)
            ),

            // 14. Modern Pop (IV-I-V-vi)
            ProgressionTemplate(
                name = "Modern Pop",
                degrees = listOf(ScaleDegree.IV, ScaleDegree.I, ScaleDegree.V, ScaleDegree.VI_MINOR),
                durations = listOf(4, 4, 4, 4)
            )
        )
    }

    /**
     * Konvertiert ein Template in eine ChordProgression für eine gegebene Tonart.
     */
    fun createProgressionFromTemplate(template: ProgressionTemplate, key: Key): ChordProgression {
        val progression = ChordProgression()
        progression.key = key
        progression.measures.clear()

        // Verwende den Shuffle-Faktor aus dem Template
        progression.shuffleFactor = template.shuffleFactor

        var measureIdx = 1
        var chordIdx = 0
        val chordIterator = template.degrees.iterator()
        while (chordIterator.hasNext()) {
            var degree = chordIterator.next()
            var pair = degree.getChordInKey(key)
            var scaleDegreeName = degree.getRomanNumeral()
            var duration = template.durations[chordIdx]
            chordIdx++

            val measure = Measure(measureIdx)
            var chord = Chord(pair.first, pair.second, scaleDegreeName)
            measure.addChord(chord, 0) // Akkord am Anfang des Taktes
            var sumDuration = duration
            while (sumDuration < 4)
            {
                degree = chordIterator.next()
                pair = degree.getChordInKey(key)
                scaleDegreeName = degree.getRomanNumeral()
                duration = template.durations[chordIdx]
                chordIdx++
                chord = Chord(pair.first, pair.second, scaleDegreeName)
                measure.addChord(chord, sumDuration*2) // Akkord
                sumDuration += duration
            }
            measure.strummingPattern = StrummingPattern("DUDUDUDU", List(8) { if (it % 2 == 0) Strum.DOWN else Strum.UP })
            measure.drumPattern = DrumPattern("Basic Rock", listOf(
                DrumStep(kick = true, hiHat = true), // 1
                DrumStep(hiHat = true),               // 2
                DrumStep(snare = true, hiHat = true), // 3
                DrumStep(hiHat = true),               // 4
                DrumStep(kick = true, hiHat = true),  // 5
                DrumStep(hiHat = true),               // 6
                DrumStep(snare = true, hiHat = true), // 7
                DrumStep(hiHat = true)                // 8
            ))
            progression.measures.add(measure)
            measureIdx++
        }

        return progression
    }
}

