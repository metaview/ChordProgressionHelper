package de.metaviewsoft.chordprogressionhelper.data

import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.Song
import de.metaviewsoft.chordprogressionhelper.model.SongSection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Platform-independent file storage for saved progressions and songs.
 *
 * The platform (Android now, iOS later) supplies the [fileSystem] and base [dir]; the on-disk
 * layout — `index.json`, `songs_index.json`, `last_session.json`, `last_song_session.json` and
 * one JSON file per named item — is identical across platforms, so existing Android data is read
 * and written unchanged. This is the portable core extracted from the Android `ProgressionRepository`.
 */
class ProgressionStorage(
    private val fileSystem: FileSystem,
    private val dir: Path,
    private val logWarn: (String) -> Unit = {},
) {
    private val indexFile = dir / "index.json"
    private val songIndexFile = dir / "songs_index.json"
    private val lastSessionFile = dir / "last_session.json"
    private val lastSongSessionFile = dir / "last_song_session.json"

    private fun ensureDir() {
        try {
            fileSystem.createDirectories(dir)
        } catch (e: Exception) {
            logWarn("Failed to create progressions dir: ${e.message}")
        }
    }

    private fun readTextOrNull(path: Path): String? =
        try {
            if (fileSystem.exists(path)) fileSystem.read(path) { readUtf8() } else null
        } catch (e: Exception) {
            logWarn("read failed ${path.name}: ${e.message}"); null
        }

    private fun writeText(path: Path, text: String) {
        try {
            fileSystem.write(path) { writeUtf8(text) }
        } catch (e: Exception) {
            logWarn("write failed ${path.name}: ${e.message}")
        }
    }

    // Index maps user-visible progression name -> backing filename
    private fun readIndex(): MutableMap<String, String> {
        val text = readTextOrNull(indexFile)?.takeIf { it.isNotBlank() } ?: return mutableMapOf()
        return try {
            Json.decodeFromString<Map<String, String>>(text).toMutableMap()
        } catch (e: Exception) {
            logWarn("readIndex: failed to decode index.json: ${e.message}"); mutableMapOf()
        }
    }

    private fun writeIndex(index: Map<String, String>) = writeText(indexFile, Json.encodeToString(index))

    private fun readSongIndex(): MutableMap<String, String> {
        val text = readTextOrNull(songIndexFile)?.takeIf { it.isNotBlank() } ?: return mutableMapOf()
        return try {
            Json.decodeFromString<Map<String, String>>(text).toMutableMap()
        } catch (e: Exception) {
            logWarn("readSongIndex: failed to decode songs_index.json: ${e.message}"); mutableMapOf()
        }
    }

    private fun writeSongIndex(index: Map<String, String>) = writeText(songIndexFile, Json.encodeToString(index))

    /** Human-friendly, unique-ish filename for a user-visible name. Stable across platforms on the JVM. */
    fun makeSafeFilename(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return "${name.hashCode()}_$sanitized.json"
    }

    fun saveNamedProgression(name: String, progression: ChordProgression) {
        ensureDir()
        val index = readIndex()
        val filename = index[name] ?: makeSafeFilename(name).also { index[name] = it }
        writeText(dir / filename, Json.encodeToString(progression))
        writeIndex(index)
    }

    fun loadProgression(name: String): ChordProgression? {
        val index = readIndex()
        val filename = index[name] ?: return null
        val text = readTextOrNull(dir / filename) ?: return null
        return try {
            Json.decodeFromString<ChordProgression>(text)
        } catch (e: Exception) {
            logWarn("loadProgression decode failed for $name: ${e.message}"); null
        }
    }

    /**
     * LEGACY ONLY: reads the old standalone "last progression" store, kept so [loadLastSongSession]
     * can migrate pre-song data on first launch after upgrade.
     */
    private fun loadLastSession(): ChordProgression {
        val text = readTextOrNull(lastSessionFile) ?: return ChordProgression()
        return try {
            Json.decodeFromString<ChordProgression>(text)
        } catch (e: Exception) {
            logWarn("loadLastSession decode failed: ${e.message}"); ChordProgression()
        }
    }

    fun saveLastSongSession(song: Song) {
        ensureDir()
        writeText(lastSongSessionFile, Json.encodeToString(song))
    }

    fun saveNamedSong(name: String, song: Song) {
        ensureDir()
        val index = readSongIndex()
        val filename = index[name] ?: "song_${makeSafeFilename(name)}".also { index[name] = it }
        writeText(dir / filename, Json.encodeToString(song.copy(name = name)))
        writeSongIndex(index)
    }

    fun loadSong(name: String): Song? {
        val index = readSongIndex()
        val filename = index[name] ?: return null
        val text = readTextOrNull(dir / filename) ?: return null
        return try {
            Json.decodeFromString<Song>(text).also { it.ensureValid() }
        } catch (e: Exception) {
            logWarn("loadSong decode failed for $name: ${e.message}"); null
        }
    }

    fun getSavedSongNames(): List<String> = readSongIndex().keys.toList().sorted()

    fun deleteSong(name: String) {
        val index = readSongIndex()
        val filename = index.remove(name) ?: return
        try {
            fileSystem.delete(dir / filename, mustExist = false)
        } catch (e: Exception) {
            logWarn("deleteSong file failed: ${e.message}")
        }
        writeSongIndex(index)
    }

    fun loadLastSongSession(): Song {
        val text = readTextOrNull(lastSongSessionFile)
        if (text != null) {
            try {
                return Json.decodeFromString<Song>(text).also { it.ensureValid() }
            } catch (e: Exception) {
                logWarn("loadLastSongSession decode failed: ${e.message}")
            }
        }
        // Fallback: migrate from the legacy standalone last-progression store.
        val legacy = loadLastSession()
        return Song(
            sections = mutableListOf(
                SongSection(
                    name = legacy.name.ifBlank { "Section 1" },
                    progression = legacy
                )
            )
        )
    }

    fun getSavedProgressionNames(): List<String> = readIndex().keys.toList().sorted()

    fun deleteProgression(name: String) {
        val index = readIndex()
        val filename = index.remove(name) ?: return
        try {
            fileSystem.delete(dir / filename, mustExist = false)
        } catch (e: Exception) {
            logWarn("deleteProgression file failed: ${e.message}")
        }
        writeIndex(index)
    }

    /** Raw JSON of a stored progression by name (used by Android export). */
    fun exportProgressionJson(name: String): String? =
        loadProgression(name)?.let { Json.encodeToString(it) }

    /** Save a progression supplied as raw JSON (used by Android's SharedPreferences migration). */
    fun saveRawProgression(name: String, json: String) {
        ensureDir()
        val index = readIndex()
        val filename = index[name] ?: makeSafeFilename(name).also { index[name] = it }
        writeText(dir / filename, json)
        writeIndex(index)
    }

    /** Write the legacy last-session store directly (used by Android's SharedPreferences migration). */
    fun saveLastSessionRaw(json: String) {
        ensureDir()
        writeText(lastSessionFile, json)
    }

    /** Short preview string for a progression name, e.g. "(Am, C, G, F)", or null if unavailable. */
    fun getPreviewFor(name: String, maxChords: Int = 4): String? {
        val prog = loadProgression(name) ?: return null
        val chords = mutableListOf<String>()
        var last = ""
        for (m in prog.measures) {
            for (ev in m.chordEvents) {
                val disp = ev.chord.getDisplayName()
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
    }
}
