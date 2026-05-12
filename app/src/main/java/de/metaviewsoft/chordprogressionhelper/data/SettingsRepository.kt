package de.metaviewsoft.chordprogressionhelper.data

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
        const val KEY_COUNT_IN_BEATS_SONG = "key_count_in_beats_song"
        const val KEY_PLUCK_STRENGTH = "key_pluck_strength"
        const val KEY_IS_LOOPING = "key_is_looping"
        const val KEY_IS_LOOPING_SONG = "key_is_looping_song"
        // Sound subgroup keys
        const val KEY_DRUM_LEVEL = "key_drum_level"
        const val KEY_SOLO_LEVEL = "key_solo_level"  // Solo Pattern Lautstärke
        const val KEY_STRUM_LEVEL = "key_strum_level"  // Strumming Lautstärke
        const val KEY_ENVELOPE_SCALE = "key_envelope_scale"
        const val KEY_HIHAT_HIGHPASS = "key_hihat_highpass"
        const val KEY_STRUM_PRESET = "key_strum_preset"
        // New key: separate solo instrument preset
        const val KEY_SOLO_PRESET = "key_solo_preset"
        // Per-preset gain multipliers (percent stored as float 0.0..2.0)
        const val KEY_SOUND_GAIN_CLEAN = "key_sound_gain_clean"
        const val KEY_SOUND_GAIN_OVERDRIVE = "key_sound_gain_overdrive"
        const val KEY_SOUND_GAIN_PIANO = "key_sound_gain_piano"
        // New key: pattern preview toggle
        const val KEY_PATTERN_PREVIEW = "key_pattern_preview"
        // New key: drum preview toggle (controls per-step immediate drum click preview)
        const val KEY_DRUM_PREVIEW = "key_drum_preview"
        // New key: template preview toggle (controls preview when selecting templates)
        const val KEY_TEMPLATE_PREVIEW = "key_template_preview"
        // Stroke offset for UP strokes in milliseconds
        const val KEY_STROKE_OFFSET_MS = "key_stroke_offset_ms"
        const val KEY_STRING_STAGGER_MS = "key_string_stagger_ms"
        // Down-stroke equivalents
        const val KEY_DOWN_STROKE_OFFSET_MS = "key_down_stroke_offset_ms"
        const val KEY_DOWN_STRING_STAGGER_MS = "key_down_string_stagger_ms"
        // Shuffle rhythm factor (adjustable)
        const val KEY_SHUFFLE_FACTOR = "key_shuffle_factor"
        // Crunch/Overdrive gain levels (separate for strum and solo)
        const val KEY_STRUM_CRUNCH_LEVEL = "key_strum_crunch_level"
        const val KEY_SOLO_CRUNCH_LEVEL = "key_solo_crunch_level"
        // Defaults used in New dialog
        const val KEY_DEFAULT_KEY_NAME = "key_default_key_name"
        const val KEY_DEFAULT_BPM = "key_default_bpm"
    }

    // Chord Preview setting
    var isChordPreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHORD_PREVIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_CHORD_PREVIEW, value) }

    // Pattern Preview setting (new)
    var isStrumPatternPreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_PATTERN_PREVIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_PATTERN_PREVIEW, value) }

    // Drum Preview setting: controls immediate per-step drum sound when editing patterns
    var isDrumPreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_DRUM_PREVIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_DRUM_PREVIEW, value) }

    // Template Preview setting: controls preview when selecting templates in New dialog
    var isTemplatePreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_TEMPLATE_PREVIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_TEMPLATE_PREVIEW, value) }

    // Stroke offset for UP strokes in milliseconds (0..200 typical)
    var strokeOffsetMs: Int
        get() = prefs.getInt(KEY_STROKE_OFFSET_MS, 10)
        set(value) = prefs.edit { putInt(KEY_STROKE_OFFSET_MS, value.coerceIn(0, 100)) }

    var stringStaggerMs: Int
        get() = prefs.getInt(KEY_STRING_STAGGER_MS, 8)
        set(value) = prefs.edit { putInt(KEY_STRING_STAGGER_MS, value.coerceIn(0, 20)) }

    // Down-stroke settings
    var downStrokeOffsetMs: Int
        get() = prefs.getInt(KEY_DOWN_STROKE_OFFSET_MS, 0)
        set(value) = prefs.edit { putInt(KEY_DOWN_STROKE_OFFSET_MS, value.coerceIn(0, 100)) }

    var downStringStaggerMs: Int
        get() = prefs.getInt(KEY_DOWN_STRING_STAGGER_MS, 4)
        set(value) = prefs.edit { putInt(KEY_DOWN_STRING_STAGGER_MS, value.coerceIn(0, 20)) }

    // Count In setting for single-progression playback (storing number of beats)
    var countInBeats: Int
        get() = prefs.getInt(KEY_COUNT_IN_BEATS, 4) // Default to 4 beats
        set(value) = prefs.edit { putInt(KEY_COUNT_IN_BEATS, value) }

    // Count In setting for song playback (all sections concatenated)
    var countInBeatsSong: Int
        get() = prefs.getInt(KEY_COUNT_IN_BEATS_SONG, 4) // Default to 4 beats
        set(value) = prefs.edit { putInt(KEY_COUNT_IN_BEATS_SONG, value) }

    // Pluck Strength setting -- fixed default to Soft (3); UI removed
    var pluckStrength: Int
        get() = prefs.getInt(KEY_PLUCK_STRENGTH, 3) // Default to Soft
        set(value) = prefs.edit { putInt(KEY_PLUCK_STRENGTH, value) }

    // Looping setting for single progression/section
    var isLoopingEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_LOOPING, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_LOOPING, value) }

    // Looping setting for full song
    var isLoopingSongEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_LOOPING_SONG, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_LOOPING_SONG, value) }

    // Sound subgroup: drum level (multiplier), solo level, envelope scale, hi-hat highpass multiplier
    @Suppress("unused")
    var drumLevel: Float
        get() = prefs.getFloat(KEY_DRUM_LEVEL, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_DRUM_LEVEL, value) }

    @Suppress("unused")
    var soloLevel: Float
        get() = prefs.getFloat(KEY_SOLO_LEVEL, 1.5f)  // Default 1.5 für mehr Lautstärke
        set(value) = prefs.edit { putFloat(KEY_SOLO_LEVEL, value) }

    @Suppress("unused")
    var strumLevel: Float
        get() = prefs.getFloat(KEY_STRUM_LEVEL, 1.0f)  // Default 1.0 für normale Lautstärke
        set(value) = prefs.edit { putFloat(KEY_STRUM_LEVEL, value) }

    @Suppress("unused")
    var envelopeScale: Float
        get() = prefs.getFloat(KEY_ENVELOPE_SCALE, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_ENVELOPE_SCALE, value) }

    @Suppress("unused")
    var hiHatHighpass: Float
        get() = prefs.getFloat(KEY_HIHAT_HIGHPASS, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_HIHAT_HIGHPASS, value) }

    // Strum preset selection persisted as ordinal (for strumming patterns)
    var strumPreset: SoundPreset
        get() = SoundPreset.entries.getOrElse(prefs.getInt(KEY_STRUM_PRESET, SoundPreset.CLEAN.ordinal)) { SoundPreset.CLEAN }
        set(value) = prefs.edit { putInt(KEY_STRUM_PRESET, value.ordinal) }

    // Piano instrument preset (separate from strumming) - defaults to PIANO
    var soloPreset: SoundPreset
        get() = SoundPreset.entries.getOrElse(prefs.getInt(KEY_SOLO_PRESET, SoundPreset.PIANO.ordinal)) { SoundPreset.PIANO }
        set(value) = prefs.edit { putInt(KEY_SOLO_PRESET, value.ordinal) }

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

    // Shuffle rhythm factor setting
    var shuffleFactor: Float
        get() = prefs.getFloat(KEY_SHUFFLE_FACTOR, 0.0f) // Default to 0.0 (no shuffle)
        set(value) = prefs.edit { putFloat(KEY_SHUFFLE_FACTOR, value) }

    // Crunch/Overdrive gain levels (separate for strum and solo)
    // Range 0.0 (no crunch) to 2.0 (heavy crunch), default 1.0 (medium)
    var strumCrunchLevel: Float
        get() = prefs.getFloat(KEY_STRUM_CRUNCH_LEVEL, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_STRUM_CRUNCH_LEVEL, value.coerceIn(0.0f, 2.0f)) }

    var soloCrunchLevel: Float
        get() = prefs.getFloat(KEY_SOLO_CRUNCH_LEVEL, 1.0f)
        set(value) = prefs.edit { putFloat(KEY_SOLO_CRUNCH_LEVEL, value.coerceIn(0.0f, 2.0f)) }

    var defaultKeyName: String
        get() = prefs.getString(KEY_DEFAULT_KEY_NAME, "C") ?: "C"
        set(value) = prefs.edit { putString(KEY_DEFAULT_KEY_NAME, value) }

    var defaultBpm: Int
        get() = prefs.getInt(KEY_DEFAULT_BPM, 120).coerceIn(60, 240)
        set(value) = prefs.edit { putInt(KEY_DEFAULT_BPM, value.coerceIn(60, 240)) }

    // Allow external registration for preference change notifications
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
