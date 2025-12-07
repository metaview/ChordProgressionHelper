package com.metaview.chordprogressionhelper.data

import android.content.Context
import android.content.SharedPreferences
import com.metaview.chordprogressionhelper.model.ChordProgression
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProgressionRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("progression_prefs", Context.MODE_PRIVATE)
    private val namesKey = "saved_progression_names"
    private val lastSessionKey = "internal_last_session"

    fun saveNamedProgression(name: String, progression: ChordProgression) {
        val jsonString = Json.encodeToString(progression)
        
        prefs.edit().apply {
            putString(name, jsonString)
            val currentNames = getSavedProgressionNames().toMutableSet()
            if (currentNames.add(name)) {
                putStringSet(namesKey, currentNames)
            }
            apply()
        }
    }

    fun saveLastSession(progression: ChordProgression) {
        val jsonString = Json.encodeToString(progression)
        prefs.edit().putString(lastSessionKey, jsonString).apply()
    }

    fun loadProgression(name: String): ChordProgression? {
        val jsonString = prefs.getString(name, null)
        return if (jsonString != null) {
            try {
                Json.decodeFromString<ChordProgression>(jsonString)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    fun loadLastSession(): ChordProgression {
        val jsonString = prefs.getString(lastSessionKey, null)
        return if (jsonString != null) {
            try {
                Json.decodeFromString<ChordProgression>(jsonString)
            } catch (e: Exception) {
                ChordProgression() // On error, return default
            }
        } else {
            ChordProgression()
        }
    }

    fun getSavedProgressionNames(): List<String> {
        return prefs.getStringSet(namesKey, emptySet())?.toList()?.sorted() ?: emptyList()
    }

    fun deleteProgression(name: String) {
        val currentNames = getSavedProgressionNames().toMutableSet()
        if (currentNames.remove(name)) {
            prefs.edit().apply{
                remove(name)
                putStringSet(namesKey, currentNames)
                apply()
            }
        }
    }
}
