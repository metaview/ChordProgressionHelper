package com.metaview.chordprogressionhelper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

// Simple sound preset enum for selectable instrument voice
enum class SoundPreset { CLEAN, OVERDRIVE, PIANO }

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_CHORD_PREVIEW = "key_chord_preview"
        const val KEY_COUNT_IN_BEATS = "key_count_in_beats"
        const val KEY_PLUCK_STRENGTH = "key_pluck_strength"
        const val KEY_IS_LOOPING = "key_is_looping"
        // Sound subgroup keys
        const val KEY_DRUM_LEVEL = "key_drum_level"
        const val KEY_ENVELOPE_SCALE = "key_envelope_scale"
        const val KEY_HIHAT_HIGHPASS = "key_hihat_highpass"
        const val KEY_SOUND_PRESET = "key_sound_preset"
        // Per-preset gain multipliers (percent stored as float 0.0..2.0)
        const val KEY_SOUND_GAIN_CLEAN = "key_sound_gain_clean"
        const val KEY_SOUND_GAIN_OVERDRIVE = "key_sound_gain_overdrive"
        const val KEY_SOUND_GAIN_PIANO = "key_sound_gain_piano"
        // New key: pattern preview toggle
        const val KEY_PATTERN_PREVIEW = "key_pattern_preview"
    }

    // Chord Preview setting
    var isChordPreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHORD_PREVIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_CHORD_PREVIEW, value) }

    // Pattern Preview setting (new)
    var isPatternPreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_PATTERN_PREVIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_PATTERN_PREVIEW, value) }

    // Count In setting (storing number of beats)
    var countInBeats: Int
        get() = prefs.getInt(KEY_COUNT_IN_BEATS, 4) // Default to 4 beats
        set(value) = prefs.edit { putInt(KEY_COUNT_IN_BEATS, value) }

    // Pluck Strength setting -- fixed default to Soft (3); UI removed
    var pluckStrength: Int
        get() = prefs.getInt(KEY_PLUCK_STRENGTH, 3) // Default to Soft
        set(value) = prefs.edit { putInt(KEY_PLUCK_STRENGTH, value) }

    // Looping setting
    var isLoopingEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_LOOPING, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_LOOPING, value) }

    // Sound subgroup: drum level (multiplier), envelope scale, hi-hat highpass multiplier
    @Suppress("unused")
    var drumLevel: Float
        get() = prefs.getFloat(KEY_DRUM_LEVEL, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_DRUM_LEVEL, value) }

    @Suppress("unused")
    var envelopeScale: Float
        get() = prefs.getFloat(KEY_ENVELOPE_SCALE, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_ENVELOPE_SCALE, value) }

    @Suppress("unused")
    var hiHatHighpass: Float
        get() = prefs.getFloat(KEY_HIHAT_HIGHPASS, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_HIHAT_HIGHPASS, value) }

    // Sound preset selection persisted as ordinal
    var soundPreset: SoundPreset
        get() = SoundPreset.entries.getOrElse(prefs.getInt(KEY_SOUND_PRESET, SoundPreset.CLEAN.ordinal)) { SoundPreset.CLEAN }
        set(value) = prefs.edit { putInt(KEY_SOUND_PRESET, value.ordinal) }

    // Per-preset gain multipliers (0.0 .. 2.0), defaults to 1.0
    var soundGainClean: Float
        get() = prefs.getFloat(KEY_SOUND_GAIN_CLEAN, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_SOUND_GAIN_CLEAN, value.coerceIn(0.0f, 2.0f)) }

    var soundGainOverdrive: Float
        get() = prefs.getFloat(KEY_SOUND_GAIN_OVERDRIVE, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_SOUND_GAIN_OVERDRIVE, value.coerceIn(0.0f, 2.0f)) }

    var soundGainPiano: Float
        get() = prefs.getFloat(KEY_SOUND_GAIN_PIANO, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_SOUND_GAIN_PIANO, value.coerceIn(0.0f, 2.0f)) }

    // Allow external registration for preference change notifications
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
