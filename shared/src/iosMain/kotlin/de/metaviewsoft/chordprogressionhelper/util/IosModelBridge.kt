package de.metaviewsoft.chordprogressionhelper.util

import de.metaviewsoft.chordprogressionhelper.model.Key

/**
 * Swift-friendly accessors for things that are awkward to reach over the Kotlin/Native ObjC
 * bridge. Enum `entries`/`values()` in particular don't map cleanly to a Swift Array, so we
 * expose them as plain `List`s (which bridge to `[T]`).
 */
fun allKeys(): List<Key> = Key.entries
