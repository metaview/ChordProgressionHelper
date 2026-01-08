package de.metaviewsoft.chordprogressionhelper.data

import android.content.Context
import android.util.Log
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ProgressionRepository(context: Context) {

    private val TAG = "ProgressionRepository"
    private val dir = File(context.filesDir, "progressions")
    private val indexFile = File(dir, "index.json")
    private val lastSessionFile = File(dir, "last_session.json")

    init {
        try { if (!dir.exists()) dir.mkdirs() } catch (e: Exception) { Log.w(TAG, "Failed to create progressions dir: ${e.message}") }

        // Migrate existing SharedPreferences-based storage (if present) into files so users don't lose saved progressions.
        try {
            val oldPrefs = context.getSharedPreferences("progression_prefs", Context.MODE_PRIVATE)
            val namesKey = "saved_progression_names"
            val lastSessionKey = "internal_last_session"
            val savedNames = oldPrefs.getStringSet(namesKey, null)
            if (!savedNames.isNullOrEmpty()) {
                Log.i(TAG, "Migration: found ${savedNames.size} saved progressions in SharedPreferences; migrating to file storage")
                val index = readIndex()
                for (name in savedNames) {
                    try {
                        val json = oldPrefs.getString(name, null)
                        if (json.isNullOrEmpty()) continue
                        // determine filename (reuse existing if index already points to one)
                        val filename = index[name] ?: makeSafeFilename(name).also { index[name] = it }
                        val target = File(dir, filename)
                        target.writeText(json)
                        // remove old entry
                        try { oldPrefs.edit().remove(name).apply() } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.w(TAG, "Migration: failed to migrate $name: ${e.message}")
                    }
                }
                // Migrate last session if present
                try {
                    val lastJson = oldPrefs.getString(lastSessionKey, null)
                    if (!lastJson.isNullOrEmpty()) {
                        try { lastSessionFile.writeText(lastJson) } catch (e: Exception) { Log.w(TAG, "Migration: failed to write last session: ${e.message}") }
                        try { oldPrefs.edit().remove(lastSessionKey).apply() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}

                // Persist updated index and remove the names set
                writeIndex(index)
                try { oldPrefs.edit().remove(namesKey).apply() } catch (_: Exception) {}
                Log.i(TAG, "Migration complete")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Migration check failed: ${e.message}")
        }
    }

    // Index maps user-visible progression name -> backing filename
    private fun readIndex(): MutableMap<String, String> {
        return try {
            if (!indexFile.exists()) return mutableMapOf()
            val text = indexFile.readText()
            if (text.isBlank()) return mutableMapOf()
            try {
                Json.decodeFromString<Map<String, String>>(text).toMutableMap()
            } catch (e: Exception) {
                Log.w(TAG, "readIndex: failed to decode index.json: ${e.message}")
                mutableMapOf()
            }
        } catch (e: Exception) {
            Log.w(TAG, "readIndex failed: ${e.message}")
            mutableMapOf()
        }
    }

    private fun writeIndex(index: Map<String, String>) {
        try {
            indexFile.writeText(Json.encodeToString(index))
        } catch (e: Exception) {
            Log.w(TAG, "writeIndex failed: ${e.message}")
        }
    }

    private fun makeSafeFilename(name: String): String {
        // Create a mostly human-friendly filename but include hashCode for uniqueness
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return "${name.hashCode()}_$sanitized.json"
    }

    fun saveNamedProgression(name: String, progression: ChordProgression) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val index = readIndex()
            val filename = index[name] ?: makeSafeFilename(name).also { index[name] = it }
            val target = File(dir, filename)
            val jsonString = Json.encodeToString(progression)
            target.writeText(jsonString)
            writeIndex(index)
        } catch (e: Exception) {
            Log.w(TAG, "saveNamedProgression failed: ${e.message}")
        }
    }

    fun saveLastSession(progression: ChordProgression) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val jsonString = Json.encodeToString(progression)
            lastSessionFile.writeText(jsonString)
        } catch (e: Exception) {
            Log.w(TAG, "saveLastSession failed: ${e.message}")
        }
    }

    fun loadProgression(name: String): ChordProgression? {
        try {
            val index = readIndex()
            val filename = index[name] ?: return null
            val f = File(dir, filename)
            if (!f.exists()) return null
            val jsonString = f.readText()
            return try {
                Json.decodeFromString<ChordProgression>(jsonString)
            } catch (e: Exception) {
                Log.w(TAG, "loadProgression decode failed for $name: ${e.message}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadProgression failed: ${e.message}")
            return null
        }
    }

    fun loadLastSession(): ChordProgression {
        try {
            if (!lastSessionFile.exists()) return ChordProgression()
            val jsonString = lastSessionFile.readText()
            return try {
                Json.decodeFromString<ChordProgression>(jsonString)
            } catch (e: Exception) {
                Log.w(TAG, "loadLastSession decode failed: ${e.message}")
                ChordProgression()
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadLastSession failed: ${e.message}")
            return ChordProgression()
        }
    }

    fun getSavedProgressionNames(): List<String> {
        return try {
            val index = readIndex()
            index.keys.toList().sorted()
        } catch (e: Exception) {
            Log.w(TAG, "getSavedProgressionNames failed: ${e.message}")
            emptyList()
        }
    }

    fun deleteProgression(name: String) {
        try {
            val index = readIndex()
            val filename = index.remove(name)
            if (filename != null) {
                val f = File(dir, filename)
                try { if (f.exists()) f.delete() } catch (_: Exception) {}
                writeIndex(index)
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteProgression failed: ${e.message}")
        }
    }

    // Export a named progression to an arbitrary file. Returns true on success.
    fun exportProgressionToFile(name: String, outFile: File): Boolean {
        try {
            val prog = loadProgression(name) ?: return false
            outFile.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
            outFile.writeText(Json.encodeToString(prog))
            return true
        } catch (e: Exception) {
            Log.w(TAG, "exportProgressionToFile failed: ${e.message}")
            return false
        }
    }

    // Export all saved progressions to a directory. Returns list of exported files.
    fun exportAllProgressionsToDir(outDir: File): List<File> {
        val exported = mutableListOf<File>()
        try {
            if (!outDir.exists()) outDir.mkdirs()
            val index = readIndex()
            for ((name, filename) in index) {
                try {
                    val src = File(dir, filename)
                    if (!src.exists()) continue
                    val dest = File(outDir, filename)
                    src.copyTo(dest, overwrite = true)
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

    // Import a single progression JSON file. If overwrite=false and a progression with the same name exists,
    // a unique name will be generated ("name (n)"). Returns the name under which the progression was saved or null on failure.
    fun importProgressionFromFile(inFile: File, overwrite: Boolean = false): String? {
        try {
            if (!inFile.exists()) return null
            val json = inFile.readText()
            val prog = try { Json.decodeFromString<ChordProgression>(json) } catch (e: Exception) { Log.w(TAG, "importProgressionFromFile: decode failed: ${e.message}"); return null }
            var name = prog.name.ifBlank { "Imported_${System.currentTimeMillis()}" }
            val existing = getSavedProgressionNames().toMutableSet()
            if (!overwrite && existing.contains(name)) {
                var i = 1
                var candidate = "$name ($i)"
                while (existing.contains(candidate)) { i++; candidate = "$name ($i)" }
                name = candidate
            }
            // ensure the progression object stores the chosen name
            prog.name = name
            saveNamedProgression(name, prog)
            return name
        } catch (e: Exception) {
            Log.w(TAG, "importProgressionFromFile failed: ${e.message}")
            return null
        }
    }

    // Import all JSON files from a directory. Returns list of imported progression names.
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

    // Returns a short preview string for the progression name, e.g. "(Am, C, G, F)" or null if not available
    fun getPreviewFor(name: String, maxChords: Int = 4): String? {
        try {
            val prog = loadProgression(name) ?: return null
            var chords = listOf<String>()
            var last = ""
            for (m in prog.measures) {
                for (ev in m.chordEvents) {
                    val chord = ev.chord
                    // Use display name (root + quality suffix) if available
                    val disp = chord.getDisplayName()
                    if (last != disp) {
                        last = disp
                        chords += disp
                        if (chords.size >= maxChords) break
                    }
                }
                if (chords.size >= maxChords) break
            }
            if (chords.isEmpty()) return null
            return "(${chords.joinToString(", ")})"
        } catch (e: Exception) {
            Log.w(TAG, "getPreviewFor failed for $name: ${e.message}")
            return null
        }
    }
}
