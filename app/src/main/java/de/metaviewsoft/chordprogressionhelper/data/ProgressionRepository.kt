package de.metaviewsoft.chordprogressionhelper.data

import android.content.Context
import android.util.Log
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Song
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

/**
 * Android front-end for saved progressions and songs.
 *
 * The portable storage core lives in [ProgressionStorage] (:shared commonMain, okio-based). This
 * class only keeps the Android-specific glue: the base directory (from [Context.filesDir]), the
 * one-time SharedPreferences legacy migration, and file-based export/import used by the Storage
 * Access Framework. All core CRUD is delegated unchanged, so callers keep the same API.
 */
class ProgressionRepository(context: Context) {

    private val TAG = "ProgressionRepository"
    private val dir = File(context.filesDir, "progressions")

    val storage = ProgressionStorage(
        fileSystem = FileSystem.SYSTEM,
        dir = dir.absolutePath.toPath(),
        logWarn = { Log.w(TAG, it) }
    )

    init {
        try { if (!dir.exists()) dir.mkdirs() } catch (e: Exception) { Log.w(TAG, "Failed to create progressions dir: ${e.message}") }
        migrateFromSharedPreferences(context)
    }

    // ---- Delegated core API (signatures unchanged) ----------------------------------------------
    fun saveNamedProgression(name: String, progression: ChordProgression) = storage.saveNamedProgression(name, progression)
    fun loadProgression(name: String): ChordProgression? = storage.loadProgression(name)
    fun saveLastSongSession(song: Song) = storage.saveLastSongSession(song)
    fun saveNamedSong(name: String, song: Song) = storage.saveNamedSong(name, song)
    fun loadSong(name: String): Song? = storage.loadSong(name)
    fun getSavedSongNames(): List<String> = storage.getSavedSongNames()
    fun deleteSong(name: String) = storage.deleteSong(name)
    fun loadLastSongSession(): Song = storage.loadLastSongSession()
    fun getSavedProgressionNames(): List<String> = storage.getSavedProgressionNames()
    fun deleteProgression(name: String) = storage.deleteProgression(name)
    fun getPreviewFor(name: String, maxChords: Int = 4): String? = storage.getPreviewFor(name, maxChords)

    // ---- Android-only: export/import to arbitrary files (SAF) ------------------------------------
    fun exportProgressionToFile(name: String, outFile: File): Boolean {
        return try {
            val json = storage.exportProgressionJson(name) ?: return false
            outFile.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
            outFile.writeText(json)
            true
        } catch (e: Exception) {
            Log.w(TAG, "exportProgressionToFile failed: ${e.message}"); false
        }
    }

    fun exportAllProgressionsToDir(outDir: File): List<File> {
        val exported = mutableListOf<File>()
        try {
            if (!outDir.exists()) outDir.mkdirs()
            for (name in storage.getSavedProgressionNames()) {
                try {
                    val json = storage.exportProgressionJson(name) ?: continue
                    val dest = File(outDir, storage.makeSafeFilename(name))
                    dest.writeText(json)
                    exported.add(dest)
                } catch (e: Exception) {
                    Log.w(TAG, "exportAllProgressionsToDir: failed exporting $name: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "exportAllProgressionsToDir failed: ${e.message}")
        }
        return exported
    }

    fun importProgressionFromFile(inFile: File, overwrite: Boolean = false): String? {
        try {
            if (!inFile.exists()) return null
            val json = inFile.readText()
            val prog = try {
                Json.decodeFromString<ChordProgression>(json)
            } catch (e: Exception) {
                Log.w(TAG, "importProgressionFromFile: decode failed: ${e.message}"); return null
            }
            var name = prog.name.ifBlank { "Imported_${System.currentTimeMillis()}" }
            val existing = storage.getSavedProgressionNames().toMutableSet()
            if (!overwrite && existing.contains(name)) {
                var i = 1
                var candidate = "$name ($i)"
                while (existing.contains(candidate)) { i++; candidate = "$name ($i)" }
                name = candidate
            }
            prog.name = name
            storage.saveNamedProgression(name, prog)
            return name
        } catch (e: Exception) {
            Log.w(TAG, "importProgressionFromFile failed: ${e.message}"); return null
        }
    }

    fun importProgressionsFromDir(inDir: File, overwrite: Boolean = false): List<String> {
        val imported = mutableListOf<String>()
        try {
            if (!inDir.exists() || !inDir.isDirectory) return imported
            val files = inDir.listFiles() ?: return imported
            for (f in files) {
                try {
                    val name = importProgressionFromFile(f, overwrite)
                    if (name != null) imported.add(name)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "importProgressionsFromDir failed: ${e.message}")
        }
        return imported
    }

    // ---- Android-only: one-time legacy SharedPreferences migration -------------------------------
    // Migrates a pre-file-storage version's SharedPreferences into the file store so users don't
    // lose saved progressions. No-op once migrated (the prefs keys are removed).
    private fun migrateFromSharedPreferences(context: Context) {
        try {
            val oldPrefs = context.getSharedPreferences("progression_prefs", Context.MODE_PRIVATE)
            val namesKey = "saved_progression_names"
            val lastSessionKey = "internal_last_session"
            val savedNames = oldPrefs.getStringSet(namesKey, null)
            if (savedNames.isNullOrEmpty()) return

            Log.i(TAG, "Migration: found ${savedNames.size} saved progressions in SharedPreferences; migrating to file storage")
            for (name in savedNames) {
                try {
                    val json = oldPrefs.getString(name, null)
                    if (json.isNullOrEmpty()) continue
                    storage.saveRawProgression(name, json)
                    try { oldPrefs.edit().remove(name).apply() } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.w(TAG, "Migration: failed to migrate $name: ${e.message}")
                }
            }
            try {
                val lastJson = oldPrefs.getString(lastSessionKey, null)
                if (!lastJson.isNullOrEmpty()) {
                    storage.saveLastSessionRaw(lastJson)
                    try { oldPrefs.edit().remove(lastSessionKey).apply() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            try { oldPrefs.edit().remove(namesKey).apply() } catch (_: Exception) {}
            Log.i(TAG, "Migration complete")
        } catch (e: Exception) {
            Log.w(TAG, "Migration check failed: ${e.message}")
        }
    }
}
