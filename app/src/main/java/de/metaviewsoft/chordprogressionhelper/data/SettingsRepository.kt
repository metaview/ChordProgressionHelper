package de.metaviewsoft.chordprogressionhelper.data

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings

// SoundPreset and all setting keys/defaults live in :shared commonMain (SettingsStore).

/**
 * Android settings store: a [SettingsStore] backed by SharedPreferences ("settings_prefs").
 *
 * All typed properties and the change-listener API are inherited from [SettingsStore]; this class
 * only supplies the Android persistence backend, so the on-disk data is identical to before.
 */
class SettingsRepository(context: Context) : SettingsStore(
    SharedPreferencesSettings(context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE))
)
