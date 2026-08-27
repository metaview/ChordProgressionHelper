package de.metaviewsoft.chordprogressionhelper.model

import kotlinx.serialization.Serializable

@Serializable
data class SongSection(
    var name: String = "Section 1",
    var progression: ChordProgression = ChordProgression(name = "Section 1")
)

@Serializable
data class Song(
    var name: String = "New Song",
    val sections: MutableList<SongSection> = mutableListOf(SongSection())
) {
    fun ensureValid() {
        if (sections.isEmpty()) {
            sections.add(SongSection())
        }
    }
}
