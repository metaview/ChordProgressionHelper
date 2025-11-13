package com.metaview.chordprogressionhelper.model

import java.util.Collections

data class ChordProgression(
    var key: Key = Key.C,
    var mode: Mode = Mode.MAJOR,
    val measures: MutableList<Measure> = mutableListOf(),
    var tempo: Int = 120 // BPM
) {
    init {
        // Initialize with 4 empty measures
        if (measures.isEmpty()) {
            repeat(4) { i ->
                measures.add(Measure(i + 1))
            }
        }
    }

    fun addMeasure() {
        measures.add(Measure(measures.size + 1))
    }

    fun removeMeasure(index: Int) {
        if (measures.size > 1 && index in measures.indices) {
            measures.removeAt(index)
            // Renumber remaining measures
            for (i in index until measures.size) {
                measures[i] = measures[i].copy(number = i + 1)
            }
        }
    }

    fun moveMeasure(fromPosition: Int, toPosition: Int) {
        if (fromPosition in measures.indices && toPosition in measures.indices) {
            Collections.swap(measures, fromPosition, toPosition)
            // Renumber all measures
            for (i in measures.indices) {
                measures[i] = measures[i].copy(number = i + 1)
            }
        }
    }

    fun getScaleDegreeChords(): List<Chord> {
        return mode.getAllScaleDegreeChords(key)
    }

    fun clear() {
        measures.forEach { it.clear() }
    }
}
