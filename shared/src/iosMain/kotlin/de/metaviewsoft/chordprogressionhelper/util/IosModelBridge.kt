package de.metaviewsoft.chordprogressionhelper.util

import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.ProgressionTemplate
import de.metaviewsoft.chordprogressionhelper.model.ProgressionTemplates

/**
 * Swift-friendly accessors for things that are awkward to reach over the Kotlin/Native ObjC
 * bridge. Enum `entries`/`values()` in particular don't map cleanly to a Swift Array, so we
 * expose them as plain `List`s (which bridge to `[T]`); Kotlin `object` singletons (like
 * [ProgressionTemplates]) are also friendlier called through a top-level function than via
 * their generated `.shared`/companion accessor.
 */
fun allKeys(): List<Key> = Key.entries

/** All built-in progression templates, for the "new progression" template picker. */
fun allTemplates(): List<ProgressionTemplate> = ProgressionTemplates.getAllTemplates()

/** Chord-list description of a template in the given key (e.g. "C - Am - F - G"), or null for "Empty". */
fun templateDescription(template: ProgressionTemplate?, key: Key): String? = template?.getDescription(key)

/**
 * Builds the [ChordProgression] a "new progression" confirmation would create: from [template]
 * in [key] at [tempo], or an empty progression when [template] is null. Used both to actually
 * create the progression and to build a throwaway one for template preview playback.
 */
fun buildProgressionFromTemplate(template: ProgressionTemplate?, key: Key, tempo: Int): ChordProgression {
    val progression = if (template != null) {
        ProgressionTemplates.createProgressionFromTemplate(template, key)
    } else {
        ChordProgression(key = key)
    }
    progression.tempo = tempo.coerceIn(60, 240)
    return progression
}
