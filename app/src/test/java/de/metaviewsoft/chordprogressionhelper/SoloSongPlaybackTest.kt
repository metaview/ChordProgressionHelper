package de.metaviewsoft.chordprogressionhelper

import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.SoloElement
import de.metaviewsoft.chordprogressionhelper.model.SoloPattern
import de.metaviewsoft.chordprogressionhelper.model.Song
import de.metaviewsoft.chordprogressionhelper.model.SongSection
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoloSongPlaybackTest {

    // Replicates ProgressionViewModel.createSongPlaybackProgression()
    private fun createSongPlaybackProgression(song: Song): ChordProgression {
        val sections = song.sections
        val firstProgression = sections.first().progression
        val combinedMeasures = sections.flatMap { section ->
            val measures = section.progression.measures
            if (measures.isEmpty()) listOf(Measure(1)) else measures.map { it.copy() }
        }.toMutableList()
        combinedMeasures.forEachIndexed { index, measure -> measure.number = index + 1 }
        return ChordProgression(
            name = song.name,
            key = firstProgression.key,
            mode = firstProgression.mode,
            tempo = firstProgression.tempo,
            shuffleFactor = firstProgression.shuffleFactor,
            measures = combinedMeasures
        )
    }

    @Test
    fun soloPattern_survives_editorJsonRoundTrip() {
        val pattern = SoloPattern("Custom", listOf(SoloElement.Note(60, 2), SoloElement.Rest(1), SoloElement.Note(64, 1)))
        val json = Json.encodeToString(SoloPattern.serializer(), pattern)
        val restored = Json.decodeFromString(SoloPattern.serializer(), json)
        assertEquals(3, restored.elements.size)
        assertTrue(!restored.isEmpty())
    }

    @Test
    fun soloPattern_survives_createSongPlaybackProgression_and_playbackJson() {
        // Two sections; solo on section 2, measure 0
        val prog1 = ChordProgression(name = "A", measures = mutableListOf(Measure(1)))
        val prog2 = ChordProgression(name = "B", measures = mutableListOf(Measure(1)))
        prog2.measures[0].soloPattern = SoloPattern("Custom", listOf(SoloElement.Note(67, 4)))
        val song = Song(name = "S", sections = mutableListOf(
            SongSection("A", prog1),
            SongSection("B", prog2)
        ))

        val combined = createSongPlaybackProgression(song)
        assertEquals(2, combined.measures.size)

        // Solo must be present in combined measure 1 (from section B)
        val comboSolo = combined.measures[1].soloPattern
        assertTrue("solo lost in createSongPlaybackProgression", !comboSolo.isEmpty())

        // Playback path serializes then deserializes
        val playbackJson = Json.encodeToString(combined)
        val restored = Json.decodeFromString<ChordProgression>(playbackJson)
        val restoredSolo = restored.measures[1].soloPattern
        assertTrue("solo lost in playback JSON round-trip", !restoredSolo.isEmpty())
        assertEquals(67, (restoredSolo.elements[0] as SoloElement.Note).midi)
    }

    // Mirrors PlaybackService.copyMeasureContent — the per-measure merge used when a new
    // progression is started over already-active playback. It must preserve the solo (and chords),
    // not just drum/strumming patterns.
    private fun copyMeasureContent(dst: Measure, src: Measure) {
        dst.strummingPattern = src.strummingPattern
        dst.drumPattern = src.drumPattern
        dst.soloPattern = src.soloPattern
        dst.chordEvents.clear()
        dst.chordEvents.addAll(src.chordEvents)
    }

    @Test
    fun mergeIntoActivePlayback_preservesSolo() {
        // target = stale progression already in the service (no solo)
        val target = Measure(1)
        // src = incoming song measure carrying a solo
        val src = Measure(1).apply {
            soloPattern = SoloPattern("Custom", listOf(SoloElement.Note(72, 2)))
        }
        copyMeasureContent(target, src)
        assertTrue("merge dropped the solo", !target.soloPattern.isEmpty())
        assertEquals(72, (target.soloPattern.elements[0] as SoloElement.Note).midi)
    }
}
