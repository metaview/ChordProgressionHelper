package de.metaviewsoft.chordprogressionhelper.data

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SettingsListener

/**
 * Handle for a batch of registered per-key settings listeners. Call [cancel] to stop observing.
 */
class SettingsWatch(private val listeners: List<SettingsListener>) {
    fun cancel() = listeners.forEach { it.deactivate() }
}

/**
 * Platform-independent settings, backed by multiplatform-settings' [ObservableSettings].
 *
 * Holds all keys, defaults, value coercion and enum mapping — the portable domain logic. The
 * Android `SettingsRepository` subclasses this with a SharedPreferences-backed store; iOS can
 * later subclass with NSUserDefaults. Keys and value layout are identical to the previous Android
 * SharedPreferences schema, so existing settings are read unchanged.
 */
open class SettingsStore(private val settings: ObservableSettings) {

    companion object {
        const val KEY_CHORD_PREVIEW = "key_chord_preview"
        const val KEY_COUNT_IN_BEATS = "key_count_in_beats"
        const val KEY_COUNT_IN_BEATS_SONG = "key_count_in_beats_song"
        const val KEY_PLUCK_STRENGTH = "key_pluck_strength"
        const val KEY_IS_LOOPING_PROGRESSION = "key_is_looping"
        const val KEY_IS_LOOPING_SONG = "key_is_looping_song"
        const val KEY_DRUM_LEVEL = "key_drum_level"
        const val KEY_SOLO_LEVEL = "key_solo_level"
        const val KEY_STRUM_LEVEL = "key_strum_level"
        const val KEY_ENVELOPE_SCALE = "key_envelope_scale"
        const val KEY_HIHAT_HIGHPASS = "key_hihat_highpass"
        const val KEY_STRUM_PRESET = "key_strum_preset"
        const val KEY_SOLO_PRESET = "key_solo_preset"
        const val KEY_SOUND_GAIN_CLEAN = "key_sound_gain_clean"
        const val KEY_SOUND_GAIN_OVERDRIVE = "key_sound_gain_overdrive"
        const val KEY_SOUND_GAIN_PIANO = "key_sound_gain_piano"
        const val KEY_PATTERN_PREVIEW = "key_pattern_preview"
        const val KEY_DRUM_PREVIEW = "key_drum_preview"
        const val KEY_TEMPLATE_PREVIEW = "key_template_preview"
        const val KEY_STROKE_OFFSET_MS = "key_stroke_offset_ms"
        const val KEY_STRING_STAGGER_MS = "key_string_stagger_ms"
        const val KEY_DOWN_STROKE_OFFSET_MS = "key_down_stroke_offset_ms"
        const val KEY_DOWN_STRING_STAGGER_MS = "key_down_string_stagger_ms"
        const val KEY_SHUFFLE_FACTOR = "key_shuffle_factor"
        const val KEY_STRUM_CRUNCH_LEVEL = "key_strum_crunch_level"
        const val KEY_SOLO_CRUNCH_LEVEL = "key_solo_crunch_level"
        const val KEY_MASTER_VOLUME = "key_master_volume"
        const val KEY_DEFAULT_KEY_NAME = "key_default_key_name"
        const val KEY_DEFAULT_BPM = "key_default_bpm"

        // Grouped by stored type so we can register the type-matching observable listener.
        // Enum presets are stored as Int (ordinal), so they belong to INT_KEYS.
        private val BOOL_KEYS = listOf(
            KEY_CHORD_PREVIEW, KEY_PATTERN_PREVIEW, KEY_DRUM_PREVIEW, KEY_TEMPLATE_PREVIEW,
            KEY_IS_LOOPING_PROGRESSION, KEY_IS_LOOPING_SONG,
        )
        private val INT_KEYS = listOf(
            KEY_COUNT_IN_BEATS, KEY_COUNT_IN_BEATS_SONG, KEY_PLUCK_STRENGTH, KEY_STROKE_OFFSET_MS,
            KEY_STRING_STAGGER_MS, KEY_DOWN_STROKE_OFFSET_MS, KEY_DOWN_STRING_STAGGER_MS,
            KEY_DEFAULT_BPM, KEY_STRUM_PRESET, KEY_SOLO_PRESET,
        )
        private val FLOAT_KEYS = listOf(
            KEY_DRUM_LEVEL, KEY_SOLO_LEVEL, KEY_STRUM_LEVEL, KEY_ENVELOPE_SCALE, KEY_HIHAT_HIGHPASS,
            KEY_SOUND_GAIN_CLEAN, KEY_SOUND_GAIN_OVERDRIVE, KEY_SOUND_GAIN_PIANO, KEY_SHUFFLE_FACTOR,
            KEY_STRUM_CRUNCH_LEVEL, KEY_SOLO_CRUNCH_LEVEL, KEY_MASTER_VOLUME,
        )
        private val STRING_KEYS = listOf(KEY_DEFAULT_KEY_NAME)
    }

    var isChordPreviewEnabled: Boolean
        get() = settings.getBoolean(KEY_CHORD_PREVIEW, true)
        set(value) = settings.putBoolean(KEY_CHORD_PREVIEW, value)

    var isStrumPatternPreviewEnabled: Boolean
        get() = settings.getBoolean(KEY_PATTERN_PREVIEW, true)
        set(value) = settings.putBoolean(KEY_PATTERN_PREVIEW, value)

    var isDrumPreviewEnabled: Boolean
        get() = settings.getBoolean(KEY_DRUM_PREVIEW, true)
        set(value) = settings.putBoolean(KEY_DRUM_PREVIEW, value)

    var isTemplatePreviewEnabled: Boolean
        get() = settings.getBoolean(KEY_TEMPLATE_PREVIEW, true)
        set(value) = settings.putBoolean(KEY_TEMPLATE_PREVIEW, value)

    var strokeOffsetMs: Int
        get() = settings.getInt(KEY_STROKE_OFFSET_MS, 10)
        set(value) = settings.putInt(KEY_STROKE_OFFSET_MS, value.coerceIn(0, 100))

    var stringStaggerMs: Int
        get() = settings.getInt(KEY_STRING_STAGGER_MS, 8)
        set(value) = settings.putInt(KEY_STRING_STAGGER_MS, value.coerceIn(0, 20))

    var downStrokeOffsetMs: Int
        get() = settings.getInt(KEY_DOWN_STROKE_OFFSET_MS, 0)
        set(value) = settings.putInt(KEY_DOWN_STROKE_OFFSET_MS, value.coerceIn(0, 100))

    var downStringStaggerMs: Int
        get() = settings.getInt(KEY_DOWN_STRING_STAGGER_MS, 4)
        set(value) = settings.putInt(KEY_DOWN_STRING_STAGGER_MS, value.coerceIn(0, 20))

    var countInBeats: Int
        get() = settings.getInt(KEY_COUNT_IN_BEATS, 4)
        set(value) = settings.putInt(KEY_COUNT_IN_BEATS, value)

    var countInBeatsSong: Int
        get() = settings.getInt(KEY_COUNT_IN_BEATS_SONG, 4)
        set(value) = settings.putInt(KEY_COUNT_IN_BEATS_SONG, value)

    var pluckStrength: Int
        get() = settings.getInt(KEY_PLUCK_STRENGTH, 3)
        set(value) = settings.putInt(KEY_PLUCK_STRENGTH, value)

    var isLoopingProgressionEnabled: Boolean
        get() = settings.getBoolean(KEY_IS_LOOPING_PROGRESSION, false)
        set(value) = settings.putBoolean(KEY_IS_LOOPING_PROGRESSION, value)

    var isLoopingSongEnabled: Boolean
        get() = settings.getBoolean(KEY_IS_LOOPING_SONG, false)
        set(value) = settings.putBoolean(KEY_IS_LOOPING_SONG, value)

    var drumLevel: Float
        get() = settings.getFloat(KEY_DRUM_LEVEL, 1.0f)
        set(value) = settings.putFloat(KEY_DRUM_LEVEL, value)

    var soloLevel: Float
        get() = settings.getFloat(KEY_SOLO_LEVEL, 1.5f)
        set(value) = settings.putFloat(KEY_SOLO_LEVEL, value)

    var strumLevel: Float
        get() = settings.getFloat(KEY_STRUM_LEVEL, 1.0f)
        set(value) = settings.putFloat(KEY_STRUM_LEVEL, value)

    var envelopeScale: Float
        get() = settings.getFloat(KEY_ENVELOPE_SCALE, 1.0f)
        set(value) = settings.putFloat(KEY_ENVELOPE_SCALE, value)

    var hiHatHighpass: Float
        get() = settings.getFloat(KEY_HIHAT_HIGHPASS, 1.0f)
        set(value) = settings.putFloat(KEY_HIHAT_HIGHPASS, value)

    var strumPreset: SoundPreset
        get() = SoundPreset.entries.getOrElse(settings.getInt(KEY_STRUM_PRESET, SoundPreset.CLEAN.ordinal)) { SoundPreset.CLEAN }
        set(value) = settings.putInt(KEY_STRUM_PRESET, value.ordinal)

    var soloPreset: SoundPreset
        get() = SoundPreset.entries.getOrElse(settings.getInt(KEY_SOLO_PRESET, SoundPreset.PIANO.ordinal)) { SoundPreset.PIANO }
        set(value) = settings.putInt(KEY_SOLO_PRESET, value.ordinal)

    var soundGainClean: Float
        get() = settings.getFloat(KEY_SOUND_GAIN_CLEAN, 1.0f)
        set(value) = settings.putFloat(KEY_SOUND_GAIN_CLEAN, value.coerceIn(0.0f, 2.0f))

    var soundGainOverdrive: Float
        get() = settings.getFloat(KEY_SOUND_GAIN_OVERDRIVE, 1.0f)
        set(value) = settings.putFloat(KEY_SOUND_GAIN_OVERDRIVE, value.coerceIn(0.0f, 2.0f))

    var soundGainPiano: Float
        get() = settings.getFloat(KEY_SOUND_GAIN_PIANO, 1.0f)
        set(value) = settings.putFloat(KEY_SOUND_GAIN_PIANO, value.coerceIn(0.0f, 2.0f))

    var shuffleFactor: Float
        get() = settings.getFloat(KEY_SHUFFLE_FACTOR, 0.0f)
        set(value) = settings.putFloat(KEY_SHUFFLE_FACTOR, value)

    var strumCrunchLevel: Float
        get() = settings.getFloat(KEY_STRUM_CRUNCH_LEVEL, 1.0f)
        set(value) = settings.putFloat(KEY_STRUM_CRUNCH_LEVEL, value.coerceIn(0.0f, 2.0f))

    var soloCrunchLevel: Float
        get() = settings.getFloat(KEY_SOLO_CRUNCH_LEVEL, 1.0f)
        set(value) = settings.putFloat(KEY_SOLO_CRUNCH_LEVEL, value.coerceIn(0.0f, 2.0f))

    var masterVolume: Float
        get() = settings.getFloat(KEY_MASTER_VOLUME, 1.0f).coerceIn(0.0f, 1.0f)
        set(value) = settings.putFloat(KEY_MASTER_VOLUME, value.coerceIn(0.0f, 1.0f))

    var defaultKeyName: String
        get() = settings.getString(KEY_DEFAULT_KEY_NAME, "C")
        set(value) = settings.putString(KEY_DEFAULT_KEY_NAME, value)

    var defaultBpm: Int
        get() = settings.getInt(KEY_DEFAULT_BPM, 120).coerceIn(60, 240)
        set(value) = settings.putInt(KEY_DEFAULT_BPM, value.coerceIn(60, 240))

    /**
     * Observe changes to any known setting. [onChange] is invoked with the changed key, mirroring
     * the previous global SharedPreferences listener. Cancel via the returned [SettingsWatch].
     */
    fun registerChangeListener(onChange: (key: String) -> Unit): SettingsWatch {
        val listeners = buildList {
            BOOL_KEYS.forEach { k -> add(settings.addBooleanListener(k, false) { onChange(k) }) }
            INT_KEYS.forEach { k -> add(settings.addIntListener(k, 0) { onChange(k) }) }
            FLOAT_KEYS.forEach { k -> add(settings.addFloatListener(k, 0f) { onChange(k) }) }
            STRING_KEYS.forEach { k -> add(settings.addStringListener(k, "") { onChange(k) }) }
        }
        return SettingsWatch(listeners)
    }
}
