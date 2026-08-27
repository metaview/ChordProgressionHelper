package de.metaviewsoft.chordprogressionhelper.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@InternalSerializationApi
@Serializable
data class DrumStep(
    val kick: Boolean = false,
    val snare: Boolean = false,
    val hiHat: Boolean = false
)

@InternalSerializationApi
@Serializable
data class DrumPattern(val name: String, val steps: List<DrumStep>) {
    @Transient
    val displayName: String = name

    companion object {
        val DEFAULT = DrumPattern("Basic Rock", listOf(
            DrumStep(kick = true, hiHat = true), // 1
            DrumStep(hiHat = true),               // 2
            DrumStep(snare = true, hiHat = true), // 3
            DrumStep(hiHat = true),               // 4
            DrumStep(kick = true, hiHat = true),  // 5
            DrumStep(hiHat = true),               // 6
            DrumStep(snare = true, hiHat = true), // 7
            DrumStep(hiHat = true)                // 8
        ))

        val defaultPatterns = listOf(
            DEFAULT,
            DrumPattern("HiHat Only", List(8) { DrumStep(hiHat = true) }),
            DrumPattern("Kick/Snare Basic", listOf(
                DrumStep(kick = true), DrumStep(hiHat = true), DrumStep(snare = true), DrumStep(hiHat = true),
                DrumStep(kick = true), DrumStep(hiHat = true), DrumStep(snare = true), DrumStep(hiHat = true)
            ))
        )
    }
}

