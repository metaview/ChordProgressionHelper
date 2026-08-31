package de.metaviewsoft.chordprogressionhelper.ui

/** A chord at a normalized position within a section's timeline (0 = start, 1 = end). */
data class ChordMark(val fraction: Float, val label: String)
