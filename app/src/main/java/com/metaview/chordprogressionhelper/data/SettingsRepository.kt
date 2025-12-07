package com.metaview.chordprogressionhelper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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
    }

    // Chord Preview setting
    var isChordPreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHORD_PREVIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_CHORD_PREVIEW, value) }

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

    // Allow external registration for preference change notifications
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
