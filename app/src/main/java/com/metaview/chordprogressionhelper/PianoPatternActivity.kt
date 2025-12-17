package com.metaview.chordprogressionhelper

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.metaview.chordprogressionhelper.databinding.DialogPianoPatternBinding
import com.metaview.chordprogressionhelper.model.PianoNote
import com.metaview.chordprogressionhelper.model.PianoPattern
import com.metaview.chordprogressionhelper.util.AudioPlayer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class PianoPatternActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEASURE_INDEX = "extra_measure_index"
        const val EXTRA_PIANO_PATTERN_JSON = "extra_piano_pattern_json"
        const val EXTRA_ALL_PATTERNS_JSON = "extra_all_patterns_json"
    }

    private lateinit var binding: DialogPianoPatternBinding

    private sealed class Slot {
        object Rest : Slot()
        object LetRing : Slot()
        data class NoteSlot(val midi: Int) : Slot()
    }

    // 8 slots for one measure (8 eighth notes)
    private val slots = Array<Slot>(8) { Slot.Rest }
    // Local preview player for single-note previews (separate from PlaybackService used for full-pattern previews)
    private val previewAudioPlayer = AudioPlayer()
    private var selectedSlot: Int = -1
    private var currentOctave: Int = 4
    // Job handle for the currently playing single-note preview so it can be cancelled
    private var previewJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogPianoPatternBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val measureIndex = intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1

        binding.titleText.text = getString(R.string.piano_editor_title, if (measureIndex >= 0) (measureIndex + 1).toString() else "-")

        // Initialize slots from incoming PianoPattern JSON if present
        intent?.getStringExtra(EXTRA_PIANO_PATTERN_JSON)?.let { json ->
            try {
                val pattern = Json.decodeFromString(PianoPattern.serializer(), json)
                expandPatternToSlots(pattern)
            } catch (_: Exception) { /* ignore parse errors and keep defaults */ }
        }

        // Render initial slot labels
        renderAllSlots()
        // Ensure slot backgrounds set and octave text initialized
        // Force the dark background on all slots to avoid theme tint overwriting it
        val slotViews = listOf(binding.slot0, binding.slot1, binding.slot2, binding.slot3, binding.slot4, binding.slot5, binding.slot6, binding.slot7)
        for (v in slotViews) {
            try {
                v.setBackgroundResource(R.drawable.purple_button_bg)
                v.backgroundTintList = null
            } catch (_: Exception) {}
        }

        // Ensure keyboard keys use their own drawables and are not tinted by the app theme
        val whiteKeyViews = listOf(binding.keyC, binding.keyD, binding.keyE, binding.keyF, binding.keyG, binding.keyA, binding.keyB)
        val whiteDrawable = ContextCompat.getDrawable(this, R.drawable.white_key_selector)
        for (k in whiteKeyViews) {
            try {
                k.background = whiteDrawable
                // clear any AppCompat/Material tint
                ViewCompat.setBackgroundTintList(k, null)
                k.invalidate()
            } catch (_: Exception) {}
        }
        val blackKeyViews = listOf(binding.keyCs, binding.keyDs, binding.keyFs, binding.keyGs, binding.keyAs)
        val blackDrawable = ContextCompat.getDrawable(this, R.drawable.black_key_selector)
        for (k in blackKeyViews) {
            try {
                k.background = blackDrawable
                ViewCompat.setBackgroundTintList(k, null)
                k.invalidate()
            } catch (_: Exception) {}
        }

        highlightSelectedSlot()
        binding.octaveText.text = getString(R.string.octave_current, currentOctave)

        binding.btnOk.setOnClickListener { performOk() }
        binding.btnCancel.setOnClickListener { performCancel() }

        // Slot selection: clicking a slot selects it for subsequent key presses.
        binding.slot0.setOnClickListener { selectSlot(0) }
        binding.slot1.setOnClickListener { selectSlot(1) }
        binding.slot2.setOnClickListener { selectSlot(2) }
        binding.slot3.setOnClickListener { selectSlot(3) }
        binding.slot4.setOnClickListener { selectSlot(4) }
        binding.slot5.setOnClickListener { selectSlot(5) }
        binding.slot6.setOnClickListener { selectSlot(6) }
        binding.slot7.setOnClickListener { selectSlot(7) }

        // Rest / LetRing buttons
        // icon buttons below keyboard
        binding.iconRest.setOnClickListener {
            if (selectedSlot >= 0) { slots[selectedSlot] = Slot.Rest; renderAllSlots(); highlightSelectedSlot() }
        }
        binding.iconLetRing.setOnClickListener {
            if (selectedSlot > 0 && slots[selectedSlot - 1] is Slot.NoteSlot) { slots[selectedSlot] = Slot.LetRing; renderAllSlots(); highlightSelectedSlot() }
        }

        // Octave controls
        binding.octaveUp.setOnClickListener {
            if (currentOctave < 6) currentOctave++
            binding.octaveText.text = getString(R.string.octave_current, currentOctave)
        }
        binding.octaveDown.setOnClickListener {
            if (currentOctave > 1) currentOctave--
            binding.octaveText.text = getString(R.string.octave_current, currentOctave)
        }

        // Keyboard key handlers (pitch classes 0..11 where C=0)
        binding.keyC.setOnClickListener { onKeyPressed(0) }
        binding.keyCs.setOnClickListener { onKeyPressed(1) }
        binding.keyD.setOnClickListener { onKeyPressed(2) }
        binding.keyDs.setOnClickListener { onKeyPressed(3) }
        binding.keyE.setOnClickListener { onKeyPressed(4) }
        binding.keyF.setOnClickListener { onKeyPressed(5) }
        binding.keyFs.setOnClickListener { onKeyPressed(6) }
        binding.keyG.setOnClickListener { onKeyPressed(7) }
        binding.keyGs.setOnClickListener { onKeyPressed(8) }
        binding.keyA.setOnClickListener { onKeyPressed(9) }
        binding.keyAs.setOnClickListener { onKeyPressed(10) }
        binding.keyB.setOnClickListener { onKeyPressed(11) }

        // Handle back press via OnBackPressedDispatcher
        try {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    try {
                        performCancel()
                    } catch (_: Exception) {
                        // fallback: close activity safely from the callback
                        try { this@PianoPatternActivity.finish() } catch (_: Exception) { /* best-effort */ }
                    }
                }
            })
        } catch (_: Exception) {}
    }

    private fun performOk() {
        // Convert slots into PianoPattern notes: create PianoNote entries for notes and count following LetRing slots to set length
        val notes = mutableListOf<PianoNote>()
        var i = 0
        while (i < 8) {
            when (val s = slots[i]) {
                is Slot.NoteSlot -> {
                    // count consecutive let ring following this note
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slots[j] is Slot.LetRing) { len++; j++ }
                    notes.add(PianoNote(s.midi, len))
                    i = j
                }
                else -> i++
            }
        }

        val pattern = PianoPattern(name = "Custom", notes = notes)
        val json = Json.encodeToString(PianoPattern.serializer(), pattern)
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_MEASURE_INDEX, intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1)
            putExtra(EXTRA_PIANO_PATTERN_JSON, json)
        })
        finish()
    }

    private fun performCancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    // Expand a PianoPattern into the 8-slot representation
    private fun expandPatternToSlots(pattern: PianoPattern) {
        // initialize all to Rest
        for (k in 0 until 8) slots[k] = Slot.Rest
        var pos = 0
        for (pn in pattern.notes) {
            if (pos >= 8) break
            val midi = pn.midi
            val len = pn.lengthEighths.coerceAtLeast(1)
            slots[pos] = Slot.NoteSlot(midi)
            // mark let ring for following positions
            for (r in 1 until len) {
                val idx = pos + r
                if (idx >= 8) break
                slots[idx] = Slot.LetRing
            }
            pos += len
        }
    }

    private fun renderAllSlots() {
        fun setLabel(idx: Int, text: String) {
            when (idx) {
                0 -> binding.slot0.text = text
                1 -> binding.slot1.text = text
                2 -> binding.slot2.text = text
                3 -> binding.slot3.text = text
                4 -> binding.slot4.text = text
                5 -> binding.slot5.text = text
                6 -> binding.slot6.text = text
                7 -> binding.slot7.text = text
            }
        }
        for (i in 0 until 8) {
            val label = when (val s = slots[i]) {
                is Slot.Rest -> ""
                is Slot.LetRing -> "-"
                is Slot.NoteSlot -> midiToName(s.midi)
            }
            setLabel(i, label)
        }
    }

    private fun selectSlot(index: Int) {
        if (selectedSlot == index) {
            // toggle off
            selectedSlot = -1
        } else selectedSlot = index
        highlightSelectedSlot()
    }

    private fun highlightSelectedSlot() {
        // simple visual feedback: elevation on selected slot
        val slotsViews = listOf(binding.slot0, binding.slot1, binding.slot2, binding.slot3, binding.slot4, binding.slot5, binding.slot6, binding.slot7)
        for ((i, v) in slotsViews.withIndex()) {
            if (i == selectedSlot) {
                v.background = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.purple_button_bg_light, theme)
                v.backgroundTintList = null
                v.elevation = 10f
                v.scaleX = 1.03f; v.scaleY = 1.03f
            } else {
                v.background = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.purple_button_bg, theme)
                v.backgroundTintList = null
                v.elevation = 0f
                v.scaleX = 1.0f; v.scaleY = 1.0f
            }
        }
    }

    private fun onKeyPressed(pitchClass: Int) {
        val midi = (currentOctave + 1) * 12 + pitchClass
        // play preview sound using AudioPlayer.previewNote for consistent timbre
        // stop any running preview immediately and cancel previous coroutine
        try {
            previewJob?.cancel()
            //previewAudioPlayer.stopPreview()
        } catch (_: Exception) {}
        // start new preview job and keep reference to cancel if needed
        previewJob = lifecycleScope.launch {
            try {
                //previewAudioPlayer.previewNote(midi, 2)
            } catch (e: CancellationException) {
                // job cancelled -> ensure preview AudioTrack stopped
                //previewAudioPlayer.stopPreview()
                throw e
            } catch (t: Throwable) {
                // fallback to local sine preview if the AudioPlayer preview fails
                try { playNotePreview(midi) } catch (_: Exception) {}
            }
        }

        // visual key press effect: elevation change
        try {
            val keyView = when (pitchClass) {
                0 -> binding.keyC
                1 -> binding.keyCs
                2 -> binding.keyD
                3 -> binding.keyDs
                4 -> binding.keyE
                5 -> binding.keyF
                6 -> binding.keyFs
                7 -> binding.keyG
                8 -> binding.keyGs
                9 -> binding.keyA
                10 -> binding.keyAs
                11 -> binding.keyB
                else -> null
            }
            keyView?.apply {
                elevation = 12f
                scaleX = 0.98f; scaleY = 0.98f
                postDelayed({ elevation = 0f; scaleX = 1f; scaleY = 1f }, 160)
            }
        } catch (_: Exception) {}

        // if a slot is selected, write the note there
        if (selectedSlot >= 0) {
            slots[selectedSlot] = Slot.NoteSlot(midi)
            // clear subsequent LetRing that belonged to a previous note
            var j = selectedSlot + 1
            while (j < 8 && slots[j] is Slot.LetRing) { slots[j] = Slot.Rest; j++ }
            renderAllSlots(); highlightSelectedSlot()
        }
    }

    /**
     * Parse note strings like C4, C#4, Cis4 (German), Eb4 or without octave (default 4)
     */
    private fun parseNoteNameToMidi(text: String): Int? {
        if (text.isBlank()) return null
        val s = text.replace("\u00A0", " ").trim() // normalize
        // Accept formats: C4, C#4, Db4, Cis4
        val regex = Regex("^([A-Ga-g])([#b]|is|es|s)?(\\d+)?$")
        val m = regex.matchEntire(s)
        if (m == null) return null
        val noteChar = m.groupValues[1].uppercase()
        val accidental = m.groupValues[2]
        val octaveStr = m.groupValues[3]
        val octave = octaveStr.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 4
        val pitchClass = when (noteChar) {
            "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; "B" -> 11
            else -> return null
        }
        val acc = when (accidental.lowercase()) {
            "#" -> 1
            "b" -> -1
            "is" -> 1 // German sharp: Cis
            "es", "s" -> -1 // German flat: Des / As represented as 'es' or trailing 's'
            else -> 0
        }
        var midi = (octave + 1) * 12 + ((pitchClass + acc) % 12 + 12) % 12
        if (midi < 0 || midi > 127) return null
        return midi
    }

    override fun onDestroy() {
        try { previewJob?.cancel() } catch (_: Exception) {}
        //try { previewAudioPlayer.stopPreview() } catch (_: Exception) {}
        try { previewAudioPlayer.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun midiToFreq(midi: Int): Double {
        var m = midi
        if (m < 36) m += 60
        return 440.0 * 2.0.pow((m - 69) / 12.0)
    }

    private fun midiToName(midi: Int): String {
        val octave = (midi / 12) - 1
        val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val name = names[midi % 12]
        return "$name$octave"
    }

    private fun playNotePreview(midi: Int, durationSec: Double = 0.5) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val numSamples = (sampleRate * durationSec).toInt().coerceAtLeast(64)
                val buf = DoubleArray(numSamples) { 0.0 }
                val freq = midiToFreq(midi)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    // simple sine with gentle envelope
                    val env = (1.0 - i.toDouble() / numSamples).pow(1.2)
                    buf[i] = sin(2.0 * PI * freq * t) * env * 0.6
                }
                val shorts = buf.toPcmShortArray()
                var at: android.media.AudioTrack? = null
                try {
                    val minBuf = android.media.AudioTrack.getMinBufferSize(sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    at = android.media.AudioTrack.Builder()
                        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(maxOf(minBuf, shorts.size * 2))
                        .build()
                    at.play()
                    at.write(shorts, 0, shorts.size)
                    // stream mode: wait briefly while data plays
                    Thread.sleep((durationSec * 1000).toLong())
                 } finally {
                     try { at?.stop() } catch (_: Exception) {}
                     try { at?.release() } catch (_: Exception) {}
                 }
            } catch (_: Exception) {}
        }
    }

    // Local helper to convert double samples (-1..1) to 16-bit PCM short array
    private fun DoubleArray.toPcmShortArray(): ShortArray {
        val out = ShortArray(this.size)
        for (i in this.indices) {
            val v = this[i].coerceIn(-1.0, 1.0)
            out[i] = ( (v * Short.MAX_VALUE).toInt() ).toShort()
        }
        return out
    }
}
