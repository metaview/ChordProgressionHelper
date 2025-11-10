package com.metaview.chordprogressionhelper.model

enum class StrummingPattern(val displayName: String, val pattern: List<Strum>) {
    DOWN_DOWN_DOWN_DOWN(
        "D D D D",
        listOf(Strum.DOWN, Strum.DOWN, Strum.DOWN, Strum.DOWN)
    ),
    DOWN_DOWN_UP_UP_DOWN_UP(
        "D D U U D U",
        listOf(Strum.DOWN, Strum.DOWN, Strum.UP, Strum.UP, Strum.DOWN, Strum.UP)
    ),
    DOWN_UP_DOWN_UP(
        "D U D U",
        listOf(Strum.DOWN, Strum.UP, Strum.DOWN, Strum.UP)
    ),
    DOWN_MUTE_UP_DOWN_UP(
        "D X U D U",
        listOf(Strum.DOWN, Strum.MUTE, Strum.UP, Strum.DOWN, Strum.UP)
    ),
    DOWN(
        "D",
        listOf(Strum.DOWN)
    ),
    FINGERPICKING(
        "Fingerpicking",
        listOf(Strum.ARPEGGIO)
    );

    enum class Strum {
        DOWN,
        UP,
        MUTE,
        ARPEGGIO
    }
}
