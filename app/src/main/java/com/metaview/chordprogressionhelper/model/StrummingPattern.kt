package com.metaview.chordprogressionhelper.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@InternalSerializationApi @Serializable
data class StrummingPattern(val name: String, val strums: List<Strum>) {
    @Transient
    val displayName: String = name

    companion object {
        val DEFAULT = StrummingPattern("DDDDDDDD", List(8) { Strum.DOWN })

        val defaultPatterns = listOf(
            StrummingPattern("DDDDDDDD", List(8) { Strum.DOWN }),
            StrummingPattern("UUUUUUUU", List(8) { Strum.UP }),
            StrummingPattern("DUDUDUDU", List(8) { if (it % 2 == 0) Strum.DOWN else Strum.UP }),
            StrummingPattern("D U D U ", listOf(Strum.DOWN, Strum.REST, Strum.UP, Strum.REST, Strum.DOWN, Strum.REST, Strum.UP, Strum.REST)),
            StrummingPattern("D DUD DU", listOf(Strum.DOWN, Strum.REST, Strum.DOWN, Strum.UP, Strum.DOWN, Strum.REST, Strum.DOWN, Strum.UP)),
        )
    }
}
