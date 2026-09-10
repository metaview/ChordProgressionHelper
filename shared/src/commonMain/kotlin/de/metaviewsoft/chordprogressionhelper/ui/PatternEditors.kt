@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package de.metaviewsoft.chordprogressionhelper.ui

import de.metaviewsoft.chordprogressionhelper.model.DrumPattern
import de.metaviewsoft.chordprogressionhelper.model.DrumStep
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.Mode
import de.metaviewsoft.chordprogressionhelper.model.SoloElement
import de.metaviewsoft.chordprogressionhelper.model.SoloPattern
import de.metaviewsoft.chordprogressionhelper.model.Strum
import de.metaviewsoft.chordprogressionhelper.model.StrummingPattern

/**
 * Portable editing logic for the per-measure pattern editors (drums / strumming / solo),
 * extracted from Android's `DrumPatternActivity`, `StrummingPatternActivity` and
 * `SoloPatternActivity` so iOS and Android can share it. These classes hold only editing
 * state and pure transforms — no audio, no coroutines, no platform types. The host builds
 * the final pattern with [DrumPatternEditor.build] / [StrummingPatternEditor.build] /
 * [SoloPatternEditor.buildPatterns] and writes it back through
 * [ProgressionViewModelCore.setDrumPattern] etc.
 */

// ---------------------------------------------------------------------------
// Drum
// ---------------------------------------------------------------------------

/** 8-step drum grid with three lanes (kick / snare / hi-hat) per step. */
class DrumPatternEditor(initial: DrumPattern, usedPatterns: List<DrumPattern>) {

    private var steps: MutableList<DrumStep> =
        (initial.steps.ifEmpty { DrumPattern.DEFAULT.steps }).map { it.copy() }.toMutableList()
    private var name: String = initial.name

    /** Distinct drum patterns already used somewhere in the progression (may include [initial]). */
    val usedPatterns: List<DrumPattern> = usedPatterns
    val defaultPatterns: List<DrumPattern> = DrumPattern.defaultPatterns

    fun stepCount(): Int = steps.size
    fun currentSteps(): List<DrumStep> = steps.toList()

    fun toggleKick(index: Int) = mutate(index) { it.copy(kick = !it.kick) }
    fun toggleSnare(index: Int) = mutate(index) { it.copy(snare = !it.snare) }
    fun toggleHiHat(index: Int) = mutate(index) { it.copy(hiHat = !it.hiHat) }

    private fun mutate(index: Int, f: (DrumStep) -> DrumStep) {
        if (index in steps.indices) {
            steps[index] = f(steps[index])
            name = "Custom"
        }
    }

    fun selectPreset(pattern: DrumPattern) {
        steps = pattern.steps.map { it.copy() }.toMutableList()
        name = pattern.name
    }

    fun build(): DrumPattern = DrumPattern(name, steps.toList())
}

// ---------------------------------------------------------------------------
// Strumming
// ---------------------------------------------------------------------------

/** 8-step strumming grid; tapping a step cycles through the [Strum] values. */
class StrummingPatternEditor(initial: StrummingPattern, usedPatterns: List<StrummingPattern>) {

    private var strums: MutableList<Strum> =
        (initial.strums.ifEmpty { StrummingPattern.DEFAULT.strums }).toMutableList()
    private var name: String = initial.name

    val usedPatterns: List<StrummingPattern> = usedPatterns
    val defaultPatterns: List<StrummingPattern> = StrummingPattern.defaultPatterns

    fun currentStrums(): List<Strum> = strums.toList()

    /** DOWN -> UP -> MUTE -> REST -> LETRING -> DOWN (matches Android). */
    fun cycle(index: Int) {
        if (index !in strums.indices) return
        strums[index] = when (strums[index]) {
            Strum.DOWN -> Strum.UP
            Strum.UP -> Strum.MUTE
            Strum.MUTE -> Strum.REST
            Strum.REST -> Strum.LETRING
            Strum.LETRING -> Strum.DOWN
        }
        name = "Custom"
    }

    fun setStrum(index: Int, strum: Strum) {
        if (index in strums.indices) {
            strums[index] = strum
            name = "Custom"
        }
    }

    fun selectPreset(pattern: StrummingPattern) {
        strums = pattern.strums.toMutableList()
        name = pattern.name
    }

    fun build(): StrummingPattern = StrummingPattern(name, strums.toList())
}

// ---------------------------------------------------------------------------
// Solo
// ---------------------------------------------------------------------------

enum class SoloSlotKind { NOTE, REST, LETRING }

/** One of the 8 eighth-note slots in a measure. [midi] is only meaningful for [SoloSlotKind.NOTE]. */
data class SoloSlot(val kind: SoloSlotKind, val midi: Int) {
    companion object {
        val REST = SoloSlot(SoloSlotKind.REST, 0)
        val LETRING = SoloSlot(SoloSlotKind.LETRING, 0)
        fun note(midi: Int) = SoloSlot(SoloSlotKind.NOTE, midi)
    }
}

enum class SoloEditMode { PREVIEW, EDIT, LIVE }

/** The root note of a chord change within a measure, as a pitch class 0..11. */
data class SoloChordRoot(val quarterNote: Int, val rootPitchClass: Int)

/**
 * Multi-measure solo/piano editor. Every measure is 8 eighth-note [SoloSlot]s. A cursor
 * ([activeMeasure] + [cursor]) drives step entry with auto-advance in [SoloEditMode.EDIT].
 * The piano keyboard's scale/root dots come from [scalePitchClasses] / [rootPitchClassAt].
 */
class SoloPatternEditor(
    initialPatterns: List<SoloPattern>,
    activeMeasureIndex: Int,
    private val key: Key,
    private val musicMode: Mode,
    private val chordRootsByMeasure: List<List<SoloChordRoot>>,
) {

    private val data: MutableList<MutableList<SoloSlot>> = run {
        val expanded = initialPatterns.map { expand(it) }.toMutableList()
        if (expanded.isEmpty()) expanded.add(freshMeasure())
        expanded
    }

    var activeMeasure: Int = activeMeasureIndex.coerceIn(0, data.size - 1)
        private set
    var cursor: Int = 0
        private set
    var octave: Int = 4
        private set
    var editMode: SoloEditMode = SoloEditMode.PREVIEW
        private set

    fun measureCount(): Int = data.size
    fun slots(measureIndex: Int): List<SoloSlot> =
        data.getOrElse(measureIndex) { emptyList<SoloSlot>().toMutableList() }.toList()

    fun selectSlot(measureIndex: Int, slotIndex: Int) {
        if (measureIndex in data.indices) activeMeasure = measureIndex
        cursor = slotIndex.coerceIn(0, 7)
    }

    fun cycleEditMode() {
        editMode = when (editMode) {
            SoloEditMode.PREVIEW -> SoloEditMode.EDIT
            SoloEditMode.EDIT -> SoloEditMode.LIVE
            SoloEditMode.LIVE -> SoloEditMode.PREVIEW
        }
    }

    fun setEditMode(mode: SoloEditMode) {
        editMode = mode
    }

    fun octaveUp() {
        if (octave < 6) octave++
    }

    fun octaveDown() {
        if (octave > 1) octave--
    }

    /** MIDI value for a key press at the current octave. Does not write anything. */
    fun midiFor(pitchClass: Int, octaveOffset: Int): Int =
        (octave + octaveOffset + 1) * 12 + pitchClass

    /**
     * Handle a keyboard press. In [SoloEditMode.EDIT] this writes a note at the cursor and
     * auto-advances; in other modes it only returns the MIDI value (for the audio preview).
     */
    fun pressKey(pitchClass: Int, octaveOffset: Int): Int {
        val midi = midiFor(pitchClass, octaveOffset)
        if (editMode == SoloEditMode.EDIT) {
            writeNoteAt(activeMeasure, cursor, midi)
            advanceCursor()
        }
        return midi
    }

    /** Write a note at an explicit slot (used by LIVE recording at the playback position). */
    fun writeNoteAt(measureIndex: Int, slotIndex: Int, midi: Int) {
        val measure = data.getOrNull(measureIndex) ?: return
        if (slotIndex !in 0..7) return
        measure[slotIndex] = SoloSlot.note(midi)
        var j = slotIndex + 1
        while (j < 8 && measure[j].kind == SoloSlotKind.LETRING) {
            measure[j] = SoloSlot.REST
            j++
        }
    }

    fun setRestAtCursor() = setSlotAtCursor(SoloSlot.REST)
    fun setLetRingAtCursor() = setSlotAtCursor(SoloSlot.LETRING)

    private fun setSlotAtCursor(slot: SoloSlot) {
        if (editMode != SoloEditMode.EDIT) return
        val measure = data.getOrNull(activeMeasure) ?: return
        if (cursor !in 0..7) return
        measure[cursor] = slot
        advanceCursor()
    }

    private fun advanceCursor() {
        if (cursor >= 7) {
            if (activeMeasure < data.size - 1) {
                activeMeasure++
                cursor = 0
            }
        } else {
            cursor++
        }
    }

    fun copyAll() {
        clipboard = data.map { row -> row.map { it.copy() } }
    }

    fun pasteAll(): Boolean {
        val source = clipboard ?: return false
        for (i in 0 until minOf(data.size, source.size)) {
            data[i] = source[i].map { it.copy() }.toMutableList()
        }
        return true
    }

    fun hasClipboard(): Boolean = clipboard != null

    /** Pitch classes (0..11) of every note in the current key's scale. */
    fun scalePitchClasses(): List<Int> =
        musicMode.getScale(key).map { ((it.noteOffset % 12) + 12) % 12 }

    /**
     * Pitch class (0..11) of the root that should be highlighted green for a cursor at
     * [measureIndex]/[slotIndex]: the last chord at or before the cursor, else the key tonic.
     */
    fun rootPitchClassAt(measureIndex: Int, slotIndex: Int): Int {
        val cursorQuarter = (if (slotIndex >= 0) slotIndex else 0) / 2
        for (mi in measureIndex downTo 0) {
            val events = chordRootsByMeasure.getOrNull(mi) ?: continue
            val candidate = if (mi == measureIndex) {
                events.filter { it.quarterNote <= cursorQuarter }.maxByOrNull { it.quarterNote }
            } else {
                events.maxByOrNull { it.quarterNote }
            }
            if (candidate != null) return ((candidate.rootPitchClass % 12) + 12) % 12
        }
        return ((key.rootNote.noteOffset % 12) + 12) % 12
    }

    fun buildPatterns(): List<SoloPattern> = data.map { collapse(it) }
    fun buildActivePattern(): SoloPattern = collapse(data.getOrElse(activeMeasure) { freshMeasure() })

    private fun expand(pattern: SoloPattern): MutableList<SoloSlot> {
        val target = freshMeasure()
        var pos = 0
        for (element in pattern.elements) {
            if (pos >= 8) break
            val len = element.lengthEighths.coerceAtLeast(1)
            when (element) {
                is SoloElement.Note -> {
                    target[pos] = SoloSlot.note(element.midi)
                    for (r in 1 until len) {
                        val idx = pos + r
                        if (idx >= 8) break
                        target[idx] = SoloSlot.LETRING
                    }
                }
                is SoloElement.Rest -> for (r in 0 until len) {
                    val idx = pos + r
                    if (idx >= 8) break
                    target[idx] = SoloSlot.REST
                }
                is SoloElement.LetRing -> for (r in 0 until len) {
                    val idx = pos + r
                    if (idx >= 8) break
                    target[idx] = SoloSlot.LETRING
                }
            }
            pos += len
        }
        return target
    }

    private fun collapse(slots: List<SoloSlot>): SoloPattern {
        val elements = mutableListOf<SoloElement>()
        var i = 0
        while (i < 8) {
            when (slots[i].kind) {
                SoloSlotKind.NOTE -> {
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slots[j].kind == SoloSlotKind.LETRING) {
                        len++; j++
                    }
                    elements.add(SoloElement.Note(slots[i].midi, len))
                    i = j
                }
                SoloSlotKind.REST -> {
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slots[j].kind == SoloSlotKind.REST) {
                        len++; j++
                    }
                    elements.add(SoloElement.Rest(len))
                    i = j
                }
                SoloSlotKind.LETRING -> i++
            }
        }
        return SoloPattern("Custom", elements)
    }

    companion object {
        private var clipboard: List<List<SoloSlot>>? = null
        private fun freshMeasure(): MutableList<SoloSlot> = MutableList(8) { SoloSlot.REST }
    }
}
