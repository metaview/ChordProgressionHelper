package de.metaviewsoft.chordprogressionhelper.util

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.metaviewsoft.chordprogressionhelper.R

/**
 * Shared UI/plumbing for the multi-track MIDI export, used by both the progression and the song
 * screen. The actual MIDI byte generation lives in the portable [MidiExporter] (:shared).
 */
object MidiExportHelper {

    private const val TAG = "MidiExportHelper"

    /** Track types in the fixed order shown in the checkbox dialog. */
    private val TRACK_ORDER = listOf(
        MidiTrackType.CHORDS,
        MidiTrackType.DRUMS,
        MidiTrackType.SOLO,
    )

    /**
     * Show a checkbox dialog (all tracks pre-selected) and invoke [onConfirm] with the chosen set
     * when the user confirms. Does nothing (beyond a toast) if the user selects no track.
     */
    fun showTrackSelectionDialog(activity: Activity, onConfirm: (Set<MidiTrackType>) -> Unit) {
        val labels = arrayOf(
            activity.getString(R.string.midi_track_chords),
            activity.getString(R.string.midi_track_drums),
            activity.getString(R.string.midi_track_solo),
        )
        val checked = booleanArrayOf(true, true, true)

        MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(activity.getString(R.string.midi_select_tracks_title))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .setPositiveButton(activity.getString(R.string.export_midi_title)) { _, _ ->
                val selected = TRACK_ORDER.filterIndexed { index, _ -> checked[index] }.toSet()
                if (selected.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.midi_no_tracks_selected), Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(selected)
                }
            }
            .show()
    }

    /** Sanitize a song/progression name into a safe ".mid" filename. */
    fun suggestedFileName(name: String): String {
        val base = name.trim().ifBlank { "song" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        return "$base.mid"
    }

    /** Write MIDI [bytes] to the SAF [uri]. Returns true on success. */
    fun writeToUri(context: Context, uri: Uri, bytes: ByteArray): Boolean = try {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        true
    } catch (e: Exception) {
        Log.w(TAG, "writeToUri failed: ${e.message}")
        false
    }
}
