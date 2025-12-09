package com.metaview.chordprogressionhelper.model

/**
 * Strum types used throughout the app.
 * displayName: kurze Darstellung (z.B. für Chips)
 * description: ausführliche Beschreibung / Tooltip
 */
@Suppress("unused")
enum class Strum(val displayName: String, val description: String) {
    DOWN("D", "Downstroke"),
    UP("U", "Upstroke"),
    MUTE("M", "Palmmuting mit Downstroke"),
    LETRING("_", "ausklingen lassen"),
    REST("-", "Saiten werden abgestoppt")
}
