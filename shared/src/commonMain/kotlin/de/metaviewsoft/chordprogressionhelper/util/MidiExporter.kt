package de.metaviewsoft.chordprogressionhelper.util

import de.metaviewsoft.chordprogressionhelper.model.Chord
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.DrumStep
import de.metaviewsoft.chordprogressionhelper.model.SoloElement
import de.metaviewsoft.chordprogressionhelper.model.Song
import de.metaviewsoft.chordprogressionhelper.model.Strum
import kotlin.math.roundToInt

/**
 * The instrument tracks that can be selected for a multi-track MIDI export.
 * Each maps to exactly one MTrk chunk (plus the shared tempo/conductor track 0).
 */
enum class MidiTrackType {
    /** Chords + strumming pattern (rendered as sustained chord voicings). */
    CHORDS,

    /** Drum pattern on the General MIDI percussion channel (channel 10). */
    DRUMS,

    /** Solo / melody pattern. */
    SOLO,
}

/**
 * Renders a [Song] or a single [ChordProgression] into a Standard MIDI File (SMF, format 1)
 * with one track per selected [MidiTrackType]. Pure Kotlin, no platform dependencies, so it
 * works on Android and iOS and produces byte-identical output for identical input
 * (reproducible builds friendly — no timestamps embedded).
 *
 * The note/timing mapping mirrors what the app plays back:
 * - A measure is 4/4 = 8 eighth notes.
 * - [ChordProgression.shuffleFactor] swings each eighth pair while keeping the pair one quarter long.
 * - Chord offset notes (stored around C=0) are lifted into the C4 register, exactly like
 *   [DspSupport.midiNoteToFrequency]; real solo notes are already absolute MIDI values.
 */
object MidiExporter {

    /** Pulses (ticks) per quarter note. */
    private const val TPQ = 480

    private const val EIGHTHS_PER_MEASURE = 8

    // General MIDI programs (0-based) used as sensible starting points; users can re-assign in a DAW.
    private const val PROGRAM_CHORDS = 25 // Acoustic Guitar (steel)
    private const val PROGRAM_SOLO = 0    // Acoustic Grand Piano

    // Channels (0-based). Channel index 9 is the GM percussion channel ("channel 10").
    private const val CHANNEL_CHORDS = 0
    private const val CHANNEL_SOLO = 1
    private const val CHANNEL_DRUMS = 9

    // General MIDI percussion note numbers.
    private const val DRUM_KICK = 36
    private const val DRUM_SNARE = 38
    private const val DRUM_HIHAT = 42
    private const val DRUM_NOTE_LEN = TPQ / 8 // short percussive hit

    private const val VEL_DOWN = 100
    private const val VEL_UP = 80
    private const val VEL_MUTE = 70
    private const val VEL_SOLO = 100
    private const val VEL_DRUM = 100

    /** Export a whole song (all sections concatenated) as multi-track MIDI. */
    fun exportSong(song: Song, tracks: Set<MidiTrackType>): ByteArray {
        val sections = song.sections.map { it.progression }
        return build(sections, tracks)
    }

    /** Export a single progression as multi-track MIDI. */
    fun exportProgression(progression: ChordProgression, tracks: Set<MidiTrackType>): ByteArray =
        build(listOf(progression), tracks)

    // ---- internal ----------------------------------------------------------

    private fun build(sections: List<ChordProgression>, tracks: Set<MidiTrackType>): ByteArray {
        val orderedTracks = MidiTrackType.entries.filter { it in tracks }

        val chunks = mutableListOf<ByteArray>()
        chunks += buildTempoTrack(sections)
        for (type in orderedTracks) {
            chunks += when (type) {
                MidiTrackType.CHORDS -> buildChordsTrack(sections)
                MidiTrackType.DRUMS -> buildDrumsTrack(sections)
                MidiTrackType.SOLO -> buildSoloTrack(sections)
            }
        }

        val out = mutableListOf<Byte>()
        // MThd header chunk
        out += "MThd".encodeToByteArray().toList()
        out += int32(6)
        out += int16(1) // format 1
        out += int16(chunks.size)
        out += int16(TPQ)
        for (c in chunks) out += c.toList()
        return out.toByteArray()
    }

    /** Absolute tick of a chord/drum/solo event at the given global eighth position, honoring swing. */
    private fun eighthTick(measureStartTick: Int, eighthIndex: Int, shuffleFactor: Float): Int {
        val sr = 1.0 + shuffleFactor.coerceIn(0f, 2f) // on:off ratio, 1..3
        val onLen = (TPQ * sr / (sr + 1.0)).roundToInt()
        val pair = eighthIndex / 2
        val base = pair * TPQ
        val within = if (eighthIndex % 2 == 0) base else base + onLen
        return measureStartTick + within
    }

    private fun buildTempoTrack(sections: List<ChordProgression>): ByteArray {
        val events = mutableListOf<Ev>()
        events += Ev(0, ORDER_META, metaText(0x03, "Chord Progression Helper"))
        // 4/4 time signature.
        events += Ev(0, ORDER_META, byteArrayOf(0xFF.toByte(), 0x58, 0x04, 0x04, 0x02, 0x18, 0x08))

        var tick = 0
        var lastTempo = -1
        for (section in sections) {
            val bpm = section.tempo.coerceIn(1, 400)
            if (bpm != lastTempo) {
                events += Ev(tick, ORDER_META, metaTempo(bpm))
                lastTempo = bpm
            }
            tick += section.measures.size * 4 * TPQ
        }
        return trackChunk(events)
    }

    private fun buildChordsTrack(sections: List<ChordProgression>): ByteArray {
        val events = mutableListOf<Ev>()
        events += Ev(0, ORDER_META, metaText(0x03, "Chords"))
        events += Ev(0, ORDER_PROGRAM, byteArrayOf((0xC0 or CHANNEL_CHORDS).toByte(), PROGRAM_CHORDS.toByte()))

        var measureStart = 0
        var active: List<Int> = emptyList() // currently sounding chord pitches

        fun noteOff(atTick: Int) {
            for (n in active) events += Ev(atTick, ORDER_NOTE_OFF, byteArrayOf((0x80 or CHANNEL_CHORDS).toByte(), n.toByte(), 0))
            active = emptyList()
        }

        for (section in sections) {
            val shuffle = section.shuffleFactor
            for (measure in section.measures) {
                val strums = measure.strummingPattern.strums
                val count = minOf(strums.size, EIGHTHS_PER_MEASURE)
                for (e in 0 until count) {
                    val startTick = eighthTick(measureStart, e, shuffle)
                    when (strums[e]) {
                        Strum.DOWN, Strum.UP, Strum.MUTE -> {
                            val chord = measure.getChordAt(e)
                            noteOff(startTick)
                            if (chord != null) {
                                val (vel, sustain) = when (strums[e]) {
                                    Strum.UP -> VEL_UP to true
                                    Strum.MUTE -> VEL_MUTE to false
                                    else -> VEL_DOWN to true
                                }
                                val pitches = chordPitches(chord)
                                for (n in pitches) events += Ev(startTick, ORDER_NOTE_ON, byteArrayOf((0x90 or CHANNEL_CHORDS).toByte(), n.toByte(), vel.toByte()))
                                if (sustain) {
                                    active = pitches
                                } else {
                                    // Muted: short staccato hit ending mid-eighth.
                                    val endTick = eighthTick(measureStart, e + 1, shuffle)
                                    val muteEnd = startTick + ((endTick - startTick) * 0.5).roundToInt().coerceAtLeast(1)
                                    for (n in pitches) events += Ev(muteEnd, ORDER_NOTE_OFF, byteArrayOf((0x80 or CHANNEL_CHORDS).toByte(), n.toByte(), 0))
                                    active = emptyList()
                                }
                            }
                        }
                        Strum.REST -> noteOff(startTick)
                        Strum.LETRING -> { /* keep active notes ringing */ }
                    }
                }
                measureStart += 4 * TPQ
            }
        }
        noteOff(measureStart)
        return trackChunk(events)
    }

    private fun buildDrumsTrack(sections: List<ChordProgression>): ByteArray {
        val events = mutableListOf<Ev>()
        events += Ev(0, ORDER_META, metaText(0x03, "Drums"))

        var measureStart = 0
        for (section in sections) {
            val shuffle = section.shuffleFactor
            for (measure in section.measures) {
                val steps = measure.drumPattern.steps
                if (steps.isNotEmpty()) {
                    for (e in 0 until EIGHTHS_PER_MEASURE) {
                        val step = steps[e % steps.size]
                        val startTick = eighthTick(measureStart, e, shuffle)
                        addDrumHits(events, step, startTick)
                    }
                }
                measureStart += 4 * TPQ
            }
        }
        return trackChunk(events)
    }

    private fun addDrumHits(events: MutableList<Ev>, step: DrumStep, startTick: Int) {
        fun hit(note: Int) {
            events += Ev(startTick, ORDER_NOTE_ON, byteArrayOf((0x90 or CHANNEL_DRUMS).toByte(), note.toByte(), VEL_DRUM.toByte()))
            events += Ev(startTick + DRUM_NOTE_LEN, ORDER_NOTE_OFF, byteArrayOf((0x80 or CHANNEL_DRUMS).toByte(), note.toByte(), 0))
        }
        if (step.kick) hit(DRUM_KICK)
        if (step.snare) hit(DRUM_SNARE)
        if (step.hiHat) hit(DRUM_HIHAT)
    }

    private fun buildSoloTrack(sections: List<ChordProgression>): ByteArray {
        val events = mutableListOf<Ev>()
        events += Ev(0, ORDER_META, metaText(0x03, "Solo"))
        events += Ev(0, ORDER_PROGRAM, byteArrayOf((0xC0 or CHANNEL_SOLO).toByte(), PROGRAM_SOLO.toByte()))

        var measureStart = 0
        for (section in sections) {
            val shuffle = section.shuffleFactor
            for (measure in section.measures) {
                val elements = measure.soloPattern.elements
                var pos = 0 // eighth position within measure
                var lastNote: Int? = null      // pitch of the note currently held (for LetRing)
                var lastNoteOnTick = 0
                for (element in elements) {
                    if (pos >= EIGHTHS_PER_MEASURE) break
                    val len = element.lengthEighths
                    val endPos = (pos + len).coerceAtMost(EIGHTHS_PER_MEASURE)
                    val startTick = eighthTick(measureStart, pos, shuffle)
                    val endTick = eighthTick(measureStart, endPos, shuffle)
                    when (element) {
                        is SoloElement.Note -> {
                            // Close any previously ringing note before starting a new one.
                            lastNote?.let { n ->
                                events += Ev(startTick, ORDER_NOTE_OFF, byteArrayOf((0x80 or CHANNEL_SOLO).toByte(), n.toByte(), 0))
                            }
                            val pitch = liftOffset(element.midi)
                            events += Ev(startTick, ORDER_NOTE_ON, byteArrayOf((0x90 or CHANNEL_SOLO).toByte(), pitch.toByte(), VEL_SOLO.toByte()))
                            events += Ev(endTick, ORDER_NOTE_OFF, byteArrayOf((0x80 or CHANNEL_SOLO).toByte(), pitch.toByte(), 0))
                            lastNote = pitch
                            lastNoteOnTick = startTick
                        }
                        is SoloElement.Rest -> {
                            lastNote?.let { n ->
                                events += Ev(startTick, ORDER_NOTE_OFF, byteArrayOf((0x80 or CHANNEL_SOLO).toByte(), n.toByte(), 0))
                            }
                            lastNote = null
                        }
                        is SoloElement.LetRing -> {
                            // Extend the currently held note: replace its note-off with a later one.
                            lastNote?.let { n ->
                                removeNoteOff(events, n, CHANNEL_SOLO, afterTick = lastNoteOnTick)
                                events += Ev(endTick, ORDER_NOTE_OFF, byteArrayOf((0x80 or CHANNEL_SOLO).toByte(), n.toByte(), 0))
                            }
                        }
                    }
                    pos = endPos
                }
                measureStart += 4 * TPQ
            }
        }
        return trackChunk(events)
    }

    /** Remove the pending note-off for [pitch] (used when a LetRing extends the previous note). */
    private fun removeNoteOff(events: MutableList<Ev>, pitch: Int, channel: Int, afterTick: Int) {
        val statusOff = (0x80 or channel).toByte()
        val idx = events.indexOfLast { it.order == ORDER_NOTE_OFF && it.tick >= afterTick && it.bytes[0] == statusOff && it.bytes[1] == pitch.toByte() }
        if (idx >= 0) events.removeAt(idx)
    }

    /** MIDI pitches of a chord, lifted into an audible register exactly like playback. */
    private fun chordPitches(chord: Chord): List<Int> = chord.getMidiNotes().map { liftOffset(it) }

    /** Mirror of [DspSupport.midiNoteToFrequency]: offset-encoded low notes are raised an octave range. */
    private fun liftOffset(midi: Int): Int {
        val m = if (midi < 18) midi + 60 else midi
        return m.coerceIn(0, 127)
    }

    // ---- SMF encoding helpers ---------------------------------------------

    // Ordering at identical ticks: meta/program first, note-offs before note-ons (avoids clipping).
    private const val ORDER_META = 0
    private const val ORDER_PROGRAM = 1
    private const val ORDER_NOTE_OFF = 2
    private const val ORDER_NOTE_ON = 3

    private class Ev(val tick: Int, val order: Int, val bytes: ByteArray)

    private fun trackChunk(events: MutableList<Ev>): ByteArray {
        val sorted = events.sortedWith(compareBy({ it.tick }, { it.order }))
        val body = mutableListOf<Byte>()
        var last = 0
        for (ev in sorted) {
            val delta = ev.tick - last
            last = ev.tick
            body += vlq(delta)
            body += ev.bytes.toList()
        }
        // End of track meta event.
        body += vlq(0)
        body += listOf(0xFF.toByte(), 0x2F.toByte(), 0x00.toByte())

        val out = mutableListOf<Byte>()
        out += "MTrk".encodeToByteArray().toList()
        out += int32(body.size)
        out += body
        return out.toByteArray()
    }

    private fun metaText(type: Int, text: String): ByteArray {
        val t = text.encodeToByteArray()
        val out = mutableListOf<Byte>()
        out += 0xFF.toByte()
        out += type.toByte()
        out += vlq(t.size)
        out += t.toList()
        return out.toByteArray()
    }

    private fun metaTempo(bpm: Int): ByteArray {
        val usPerQuarter = (60_000_000 / bpm)
        return byteArrayOf(
            0xFF.toByte(), 0x51, 0x03,
            ((usPerQuarter shr 16) and 0xFF).toByte(),
            ((usPerQuarter shr 8) and 0xFF).toByte(),
            (usPerQuarter and 0xFF).toByte(),
        )
    }

    private fun int16(v: Int): List<Byte> = listOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun int32(v: Int): List<Byte> = listOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    /** Variable-length quantity encoding used for delta times and meta lengths. */
    private fun vlq(value: Int): List<Byte> {
        var v = value.coerceAtLeast(0)
        val bytes = ArrayDeque<Byte>()
        bytes.addFirst((v and 0x7F).toByte())
        v = v shr 7
        while (v > 0) {
            bytes.addFirst(((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        return bytes.toList()
    }
}
