package de.metaviewsoft.chordprogressionhelper.data

import android.content.Context
import java.io.File
import java.util.UUID

object ProgressionStore {
    private const val DIR_NAME = "progressions"

    fun saveProgression(context: Context, id: String?, json: String): String {
        val storeDir = File(context.cacheDir, DIR_NAME)
        if (!storeDir.exists()) storeDir.mkdirs()
        val finalId = id ?: UUID.randomUUID().toString()
        val f = File(storeDir, "$finalId.json")
        f.writeText(json)
        return finalId
    }

    fun loadProgression(context: Context, id: String): String? {
        val f = File(File(context.cacheDir, DIR_NAME), "$id.json")
        return if (f.exists()) f.readText() else null
    }

    fun deleteProgression(context: Context, id: String) {
        val f = File(File(context.cacheDir, DIR_NAME), "$id.json")
        try { if (f.exists()) f.delete() } catch (_: Exception) {}
    }
}

