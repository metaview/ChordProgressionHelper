@file:OptIn(ExperimentalForeignApi::class)

package de.metaviewsoft.chordprogressionhelper.util

import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_add_hihat
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_add_kick
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_add_snare
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_apply_overdrive
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_create_karplus_string
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_destroy_karplus_string
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_double_to_pcm_short
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_generate_piano_sample
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_is_available
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_midi_note_to_frequency
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_pluck_string
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_tick_string
import de.metaviewsoft.chordprogressionhelper.nativeaudio.chordhelper_audio_tick_string_buffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * [NativeAudioBridge] backed by the same portable C++ engine Android links via JNI
 * (app/src/main/cpp/audio_engine.{h,cpp}), reached here through Kotlin/Native cinterop against
 * the plain-C façade in shared/src/nativeInterop (chordhelper_audio_bridge.{h,cpp}, compiled to a
 * static lib per iOS target by the `buildNativeAudioLib*` tasks in shared/build.gradle and linked
 * into Shared.framework — see that file for the full pipeline).
 *
 * Arrays are passed by pinning + raw address, not element-by-element, so marshalling is O(1) per
 * call regardless of buffer size — matching the JNI side's `GetDoubleArrayElements` (a direct
 * pointer into the array's backing storage). Implementing [NativeAudioBridge] in Swift instead
 * (Kotlin interfaces bridge to Objective-C protocols Swift can conform to) was considered and
 * rejected: the generated Swift-facing array type only exposes element-wise `get(index:)`/
 * `set(index:value:)`, which would make marshalling O(n) *interop calls* per buffer and likely
 * erase most of the native engine's speed advantage over the pure-Kotlin fallback.
 */
object CinteropNativeAudioBridge : NativeAudioBridge {
    override fun isAvailable(): Boolean = chordhelper_audio_is_available() != 0

    override fun addKick(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, drumLevel: Double) {
        buffer.usePinned { chordhelper_audio_add_kick(it.addressOf(0), duration, levelScale, envelopeScale, drumLevel) }
    }

    override fun addSnare(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, drumLevel: Double) {
        buffer.usePinned { chordhelper_audio_add_snare(it.addressOf(0), duration, levelScale, envelopeScale, drumLevel) }
    }

    override fun addHiHat(buffer: DoubleArray, duration: Int, levelScale: Double, envelopeScale: Double, hiHatHighpass: Double) {
        buffer.usePinned { chordhelper_audio_add_hihat(it.addressOf(0), duration, levelScale, envelopeScale, hiHatHighpass) }
    }

    override fun generatePianoSample(buffer: DoubleArray, frequency: Double) {
        buffer.usePinned { chordhelper_audio_generate_piano_sample(it.addressOf(0), buffer.size, frequency) }
    }

    override fun createKarplusString(frequency: Double, sampleRate: Int, pluckStrength: Int, decay: Double): Long =
        chordhelper_audio_create_karplus_string(frequency, sampleRate, pluckStrength, decay)

    override fun destroyKarplusString(handle: Long) = chordhelper_audio_destroy_karplus_string(handle)
    override fun pluckString(handle: Long) = chordhelper_audio_pluck_string(handle)
    override fun tickString(handle: Long): Double = chordhelper_audio_tick_string(handle)

    override fun tickStringBuffer(handle: Long, buffer: DoubleArray, length: Int) {
        buffer.usePinned { chordhelper_audio_tick_string_buffer(handle, it.addressOf(0), length) }
    }

    override fun midiNoteToFrequency(midiNote: Int): Double = chordhelper_audio_midi_note_to_frequency(midiNote)

    override fun doubleToPcmShort(input: DoubleArray, output: ShortArray) {
        input.usePinned { inPinned ->
            output.usePinned { outPinned ->
                chordhelper_audio_double_to_pcm_short(inPinned.addressOf(0), outPinned.addressOf(0), input.size)
            }
        }
    }

    override fun applyOverdrive(buffer: DoubleArray, gain: Double) {
        buffer.usePinned { chordhelper_audio_apply_overdrive(it.addressOf(0), buffer.size, gain) }
    }
}
