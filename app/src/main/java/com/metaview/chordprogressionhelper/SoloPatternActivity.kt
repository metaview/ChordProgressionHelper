package com.metaview.chordprogressionhelper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import android.widget.TextView
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.metaview.chordprogressionhelper.model.Chord
import com.metaview.chordprogressionhelper.model.ChordProgression
import com.metaview.chordprogressionhelper.model.DrumPattern
import com.metaview.chordprogressionhelper.model.DrumStep
import com.metaview.chordprogressionhelper.model.Key
import com.metaview.chordprogressionhelper.model.Measure
import com.metaview.chordprogressionhelper.model.Mode
import com.metaview.chordprogressionhelper.model.SoloPattern
import com.metaview.chordprogressionhelper.model.StrummingPattern
import com.metaview.chordprogressionhelper.model.Strum
import com.metaview.chordprogressionhelper.service.PlaybackService
import com.metaview.chordprogressionhelper.data.SettingsRepository
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

class SoloPatternActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEASURE_INDEX = "extra_measure_index"
        const val EXTRA_SOLO_PATTERN_JSON = "extra_solo_pattern_json"
        const val EXTRA_ALL_PATTERNS_JSON = "extra_all_patterns_json"
        const val EXTRA_TONIC_CHORD_JSON = "extra_tonic_chord_json"
        const val EXTRA_KEY = "extra_key"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TEMPO = "extra_tempo"
        private const val TAG = "SoloPatternActivity"
    }

    // UI Views
    private lateinit var titleText: TextView
    private lateinit var btnOk: MaterialButton
    private lateinit var btnPreview: MaterialButton
    private lateinit var slot0: Button
    private lateinit var slot1: Button
    private lateinit var slot2: Button
    private lateinit var slot3: Button
    private lateinit var slot4: Button
    private lateinit var slot5: Button
    private lateinit var slot6: Button
    private lateinit var slot7: Button
    private lateinit var iconRest: MaterialButton
    private lateinit var iconLetRing: MaterialButton
    private lateinit var octaveUp: ImageButton
    private lateinit var octaveDown: ImageButton
    private lateinit var octaveText: TextView
    private lateinit var keyBLow: Button
    private lateinit var keyC: Button
    private lateinit var keyCs: Button
    private lateinit var keyD: Button
    private lateinit var keyDs: Button
    private lateinit var keyE: Button
    private lateinit var keyF: Button
    private lateinit var keyFs: Button
    private lateinit var keyG: Button
    private lateinit var keyGs: Button
    private lateinit var keyA: Button
    private lateinit var keyAs: Button
    private lateinit var keyB: Button
    private lateinit var keyCHigh: Button
    private lateinit var keyCsHigh: Button
    private lateinit var keyDHigh: Button

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

    // Service binding for preview
    private var playbackService: PlaybackService? = null
    private var isServiceBound = false
    private var isPreviewActive = false
    private var pendingPreviewProgression: ChordProgression? = null
    private var pendingPreviewLooping = false

    // Context for preview (tonic chord, key, mode, tempo)
    private var tonicChord: Chord? = null
    private var keyVal: String = "C"
    private var modeVal: String = "Major"
    private var tempoVal: Int = 120

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? PlaybackService.LocalBinder)?.getService()
            playbackService = service
            isServiceBound = true
            Log.i(TAG, "Service bound to SoloPatternActivity")

            // If a preview was requested before binding completed, start it now
            pendingPreviewProgression?.let { prog ->
                if (!isFinishing && !isDestroyed) {
                    try {
                        Log.i(TAG, "Service bound: starting pending preview")
                        PlaybackService.play(this@SoloPatternActivity, prog, pendingPreviewLooping, true)
                        pendingPreviewProgression = null
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to start pending preview: ${e.message}")
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
            Log.i(TAG, "Service disconnected from SoloPatternActivity")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_solo_pattern)

        // Initialize all views
        titleText = findViewById(R.id.titleText)
        btnOk = findViewById(R.id.btnOk)
        btnPreview = findViewById(R.id.btnPreview)
        slot0 = findViewById(R.id.slot0)
        slot1 = findViewById(R.id.slot1)
        slot2 = findViewById(R.id.slot2)
        slot3 = findViewById(R.id.slot3)
        slot4 = findViewById(R.id.slot4)
        slot5 = findViewById(R.id.slot5)
        slot6 = findViewById(R.id.slot6)
        slot7 = findViewById(R.id.slot7)
        iconRest = findViewById(R.id.iconRest)
        iconLetRing = findViewById(R.id.iconLetRing)
        octaveUp = findViewById(R.id.octaveUp)
        octaveDown = findViewById(R.id.octaveDown)
        octaveText = findViewById(R.id.octaveText)
        keyBLow = findViewById(R.id.keyBLow)
        keyC = findViewById(R.id.keyC)
        keyCs = findViewById(R.id.keyCs)
        keyD = findViewById(R.id.keyD)
        keyDs = findViewById(R.id.keyDs)
        keyE = findViewById(R.id.keyE)
        keyF = findViewById(R.id.keyF)
        keyFs = findViewById(R.id.keyFs)
        keyG = findViewById(R.id.keyG)
        keyGs = findViewById(R.id.keyGs)
        keyA = findViewById(R.id.keyA)
        keyAs = findViewById(R.id.keyAs)
        keyB = findViewById(R.id.keyB)
        keyCHigh = findViewById(R.id.keyCHigh)
        keyCsHigh = findViewById(R.id.keyCsHigh)
        keyDHigh = findViewById(R.id.keyDHigh)

        // Initialize previewAudioPlayer with solo preset from settings
        val settingsRepo = SettingsRepository(this)
        previewAudioPlayer.soloPreset = settingsRepo.soloPreset

        val measureIndex = intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1

        titleText.text = getString(R.string.solo_pattern_editor_title, if (measureIndex >= 0) (measureIndex + 1).toString() else "-")

        // Load context for preview
        intent?.getStringExtra(EXTRA_TONIC_CHORD_JSON)?.let { json ->
            try {
                tonicChord = Json.decodeFromString(Chord.serializer(), json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tonic chord: ${e.message}")
            }
        }
        keyVal = intent?.getStringExtra(EXTRA_KEY) ?: "C"
        modeVal = intent?.getStringExtra(EXTRA_MODE) ?: "Major"
        tempoVal = intent?.getIntExtra(EXTRA_TEMPO, 120) ?: 120

        // Bind to PlaybackService for preview playback
        try {
            val bindIntent = Intent(this, PlaybackService::class.java)
            bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind PlaybackService: ${e.message}")
        }

        // Initialize slots from incoming SoloPattern JSON if present
        intent?.getStringExtra(EXTRA_SOLO_PATTERN_JSON)?.let { json ->
            try {
                val pattern = Json.decodeFromString(SoloPattern.serializer(), json)
                expandPatternToSlots(pattern)
            } catch (_: Exception) { /* ignore parse errors and keep defaults */ }
        }

        // Render initial slot labels
        renderAllSlots()
        // Ensure slot backgrounds set and octave text initialized
        // Force the dark background on all slots to avoid theme tint overwriting it
        val slotViews = listOf(slot0, slot1, slot2, slot3, slot4, slot5, slot6, slot7)
        for (v in slotViews) {
            try {
                v.setBackgroundResource(R.drawable.purple_button_bg)
                v.backgroundTintList = null
            } catch (_: Exception) {}
        }

        // Ensure keyboard keys use their own drawables and are not tinted by the app theme
        val whiteKeyViews = listOf(keyBLow, keyC, keyD, keyE, keyF, keyG, keyA, keyB, keyCHigh, keyDHigh)
        val whiteDrawable = ContextCompat.getDrawable(this, R.drawable.white_key_selector)
        for (k in whiteKeyViews) {
            try {
                k.background = whiteDrawable
                // clear any AppCompat/Material tint
                ViewCompat.setBackgroundTintList(k, null)
                k.invalidate()
            } catch (_: Exception) {}
        }
        val blackKeyViews = listOf(keyCs, keyDs, keyFs, keyGs, keyAs, keyCsHigh)
        val blackDrawable = ContextCompat.getDrawable(this, R.drawable.black_key_selector)
        for (k in blackKeyViews) {
            try {
                k.background = blackDrawable
                ViewCompat.setBackgroundTintList(k, null)
                k.invalidate()
            } catch (_: Exception) {}
        }

        highlightSelectedSlot()
        octaveText.text = getString(R.string.octave_current, currentOctave)

        btnOk.setOnClickListener { performOk() }
        btnPreview.setOnClickListener { performPreview() }

        // Slot selection: clicking a slot selects it for subsequent key presses.
        slot0.setOnClickListener { selectSlot(0) }
        slot1.setOnClickListener { selectSlot(1) }
        slot2.setOnClickListener { selectSlot(2) }
        slot3.setOnClickListener { selectSlot(3) }
        slot4.setOnClickListener { selectSlot(4) }
        slot5.setOnClickListener { selectSlot(5) }
        slot6.setOnClickListener { selectSlot(6) }
        slot7.setOnClickListener { selectSlot(7) }

        // Rest / LetRing buttons
        // Buttons below keyboard
        iconRest.setOnClickListener {
            if (selectedSlot >= 0) {
                slots[selectedSlot] = Slot.Rest
                renderAllSlots()
                highlightSelectedSlot()
                updatePreviewIfActive()
            }
        }
        iconLetRing.setOnClickListener {
            if (selectedSlot >= 0) {
                slots[selectedSlot] = Slot.LetRing
                renderAllSlots()
                highlightSelectedSlot()
                updatePreviewIfActive()
            }
        }

        // Octave controls
        octaveUp.setOnClickListener {
            if (currentOctave < 6) currentOctave++
            octaveText.text = getString(R.string.octave_current, currentOctave)
        }
        octaveDown.setOnClickListener {
            if (currentOctave > 1) currentOctave--
            octaveText.text = getString(R.string.octave_current, currentOctave)
        }

        // Keyboard key handlers (pitch classes 0..11 where C=0)
        // Low octave: A(-3) and B(-1) from the octave below
        keyBLow.setOnClickListener { onKeyPressed(11, octaveOffset = -1) }

        // Current octave
        keyC.setOnClickListener { onKeyPressed(0) }
        keyCs.setOnClickListener { onKeyPressed(1) }
        keyD.setOnClickListener { onKeyPressed(2) }
        keyDs.setOnClickListener { onKeyPressed(3) }
        keyE.setOnClickListener { onKeyPressed(4) }
        keyF.setOnClickListener { onKeyPressed(5) }
        keyFs.setOnClickListener { onKeyPressed(6) }
        keyG.setOnClickListener { onKeyPressed(7) }
        keyGs.setOnClickListener { onKeyPressed(8) }
        keyA.setOnClickListener { onKeyPressed(9) }
        keyAs.setOnClickListener { onKeyPressed(10) }
        keyB.setOnClickListener { onKeyPressed(11) }

        keyCHigh.setOnClickListener { onKeyPressed(0, octaveOffset = 1) }
        keyCsHigh.setOnClickListener { onKeyPressed(1, octaveOffset = 1) }
        keyDHigh.setOnClickListener { onKeyPressed(2, octaveOffset = 1) }

        // Handle back press via OnBackPressedDispatcher
        try {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    try {
                        finish()
                    } catch (_: Exception) {
                        // fallback: close activity safely from the callback
                        try { this@SoloPatternActivity.finish() } catch (_: Exception) { /* best-effort */ }
                    }
                }
            })
        } catch (_: Exception) {}
    }

    private fun performOk() {
        // Convert slots into SoloPattern elements: create SoloElement entries for notes/rest/letring
        val elements = mutableListOf<com.metaview.chordprogressionhelper.model.SoloElement>()
        var i = 0
        while (i < 8) {
            when (val s = slots[i]) {
                is Slot.NoteSlot -> {
                    // count consecutive let ring following this note
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slots[j] is Slot.LetRing) { len++; j++ }
                    elements.add(com.metaview.chordprogressionhelper.model.SoloElement.Note(s.midi, len))
                    i = j
                }
                is Slot.Rest -> {
                    // count consecutive rests
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slots[j] is Slot.Rest) { len++; j++ }
                    elements.add(com.metaview.chordprogressionhelper.model.SoloElement.Rest(len))
                    i = j
                }
                is Slot.LetRing -> {
                    // Standalone LetRing (not following a note) - treat as rest
                    i++
                }
            }
        }

        val pattern = SoloPattern(name = "Custom", elements = elements)
        val json = Json.encodeToString(SoloPattern.serializer(), pattern)
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_MEASURE_INDEX, intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1)
            putExtra(EXTRA_SOLO_PATTERN_JSON, json)
        })

        // Stop any active preview
        if (isPreviewActive) {
            try {
                PlaybackService.stopPreview(this)
            } catch (e: Exception) {
                Log.w(TAG, "performOk: stopPreview failed: ${e.message}")
            }
        }

        finish()
    }

    private fun performPreview() {
        // Convert slots to SoloPattern and play preview
        val elements = mutableListOf<com.metaview.chordprogressionhelper.model.SoloElement>()
        var i = 0
        while (i < 8) {
            when (val s = slots[i]) {
                is Slot.NoteSlot -> {
                    // count consecutive let ring following this note
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slots[j] is Slot.LetRing) { len++; j++ }
                    elements.add(com.metaview.chordprogressionhelper.model.SoloElement.Note(s.midi, len))
                    i = j
                }
                is Slot.Rest -> {
                    // count consecutive rests
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slots[j] is Slot.Rest) { len++; j++ }
                    elements.add(com.metaview.chordprogressionhelper.model.SoloElement.Rest(len))
                    i = j
                }
                is Slot.LetRing -> {
                    // Standalone LetRing
                    i++
                }
            }
        }

        if (elements.isEmpty()) {
            Toast.makeText(this, "Pattern is empty, cannot preview", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (!isPreviewActive) {
                // Start looping preview
                startPreviewWithCurrentPattern(elements)

                // Update button icon to stop
                try {
                    btnPreview.apply {
                        setIconResource(R.drawable.ic_stop)
                        contentDescription = getString(R.string.stop)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set stop icon: ${e.message}")
                }
            } else {
                // Stop looping preview
                stopPreview()

                // Update button icon to play
                try {
                    btnPreview.apply {
                        setIconResource(R.drawable.ic_play_arrow)
                        contentDescription = getString(R.string.test)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set play icon: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preview toggle failed: ${e.message}")
            Toast.makeText(this, "Preview failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPreviewWithCurrentPattern(elements: List<com.metaview.chordprogressionhelper.model.SoloElement>) {
        val pattern = SoloPattern(name = "Preview", elements = elements)
        val key = try { Key.valueOf(keyVal) } catch (_: Exception) { Key.C }
        val mode = try { Mode.valueOf(modeVal.uppercase()) } catch (_: Exception) { Mode.MAJOR }
        val tempProg = ChordProgression(name = "Preview", key = key, mode = mode, tempo = tempoVal)
        tempProg.measures.clear()
        val m = Measure(1)

        // Add tonic chord if available
        try {
            tonicChord?.let { m.addChord(it, 0) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add tonic chord to preview measure: ${e.message}")
        }

        m.soloPattern = pattern

        // Ensure no drums are played during solo-only preview
        try {
            m.drumPattern = DrumPattern("Silent", List(8) { DrumStep() })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set silent drum pattern: ${e.message}")
        }

        // Ensure no strumming is played
        try {
            m.strummingPattern = StrummingPattern("Silent", List(8) { Strum.REST })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set silent strumming pattern: ${e.message}")
        }

        tempProg.measures.add(m)

        try {
            PlaybackService.stopPreview(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop previous preview: ${e.message}")
        }

        try {
            if (isServiceBound) {
                PlaybackService.play(this, tempProg, true, true)
                isPreviewActive = true
            } else {
                // Save pending preview and bind
                pendingPreviewProgression = tempProg
                pendingPreviewLooping = true
                try {
                    val bindIntent = Intent(this, PlaybackService::class.java)
                    bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to bind service for pending preview: ${e.message}")
                }
                isPreviewActive = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "PlaybackService.play failed: ${e.message}")
        }
    }

    private fun stopPreview() {
        try {
            if (isServiceBound && playbackService != null) {
                playbackService?.stopPreviewNow()
            } else {
                PlaybackService.stopPreview(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop preview: ${e.message}")
        }
        isPreviewActive = false
    }

    private fun updatePreviewIfActive() {
        // If preview is currently playing, restart it with the updated pattern
        if (isPreviewActive) {
            val elements = mutableListOf<com.metaview.chordprogressionhelper.model.SoloElement>()
            var i = 0
            while (i < 8) {
                when (val s = slots[i]) {
                    is Slot.NoteSlot -> {
                        var len = 1
                        var j = i + 1
                        while (j < 8 && slots[j] is Slot.LetRing) { len++; j++ }
                        elements.add(com.metaview.chordprogressionhelper.model.SoloElement.Note(s.midi, len))
                        i = j
                    }
                    is Slot.Rest -> {
                        var len = 1
                        var j = i + 1
                        while (j < 8 && slots[j] is Slot.Rest) { len++; j++ }
                        elements.add(com.metaview.chordprogressionhelper.model.SoloElement.Rest(len))
                        i = j
                    }
                    is Slot.LetRing -> {
                        i++
                    }
                }
            }

            if (elements.isNotEmpty()) {
                startPreviewWithCurrentPattern(elements)
            }
        }
    }

    // Expand a SoloPattern into the 8-slot representation
    private fun expandPatternToSlots(pattern: SoloPattern) {
        // initialize all to Rest
        for (k in 0 until 8) slots[k] = Slot.Rest

        var pos = 0

        // Use elements from pattern
        val elementsList = pattern.elements

        for (element in elementsList) {
            if (pos >= 8) break
            val len = element.lengthEighths.coerceAtLeast(1)

            when (element) {
                is com.metaview.chordprogressionhelper.model.SoloElement.Note -> {
                    slots[pos] = Slot.NoteSlot(element.midi)
                    // mark let ring for following positions
                    for (r in 1 until len) {
                        val idx = pos + r
                        if (idx >= 8) break
                        slots[idx] = Slot.LetRing
                    }
                }
                is com.metaview.chordprogressionhelper.model.SoloElement.Rest -> {
                    // Fill with Rest slots
                    for (r in 0 until len) {
                        val idx = pos + r
                        if (idx >= 8) break
                        slots[idx] = Slot.Rest
                    }
                }
                is com.metaview.chordprogressionhelper.model.SoloElement.LetRing -> {
                    // Standalone LetRing element (uncommon but valid)
                    for (r in 0 until len) {
                        val idx = pos + r
                        if (idx >= 8) break
                        slots[idx] = Slot.LetRing
                    }
                }
            }
            pos += len
        }
    }

    private fun renderAllSlots() {
        fun setLabel(idx: Int, text: String) {
            when (idx) {
                /*
                0 -> binding.slot0.text = text
                1 -> binding.slot1.text = text
                2 -> binding.slot2.text = text
                3 -> binding.slot3.text = text
                4 -> binding.slot4.text = text
                5 -> binding.slot5.text = text
                6 -> binding.slot6.text = text
                7 -> binding.slot7.text = text
                 */
                0 -> slot0.text = text
                1 -> slot1.text = text
                2 -> slot2.text = text
                3 -> slot3.text = text
                4 -> slot4.text = text
                5 -> slot5.text = text
                6 -> slot6.text = text
                7 -> slot7.text = text
            }
        }
        for (i in 0 until 8) {
            val label = when (val s = slots[i]) {
                is Slot.Rest -> "-"
                is Slot.LetRing -> " "  // Leerzeichen für LetRing
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
        val slotsViews = listOf(slot0, slot1, slot2, slot3, slot4, slot5, slot6, slot7)
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

    private fun onKeyPressed(pitchClass: Int, octaveOffset: Int = 0) {
        val midi = (currentOctave + octaveOffset + 1) * 12 + pitchClass
        // Play preview sound using AudioPlayer.previewSoloNote
        // Stop any running preview immediately and cancel previous coroutine
        try {
            previewJob?.cancel()
            previewAudioPlayer.stop()
        } catch (_: Exception) {}
        // Start new preview job and keep reference to cancel if needed
        previewJob = lifecycleScope.launch {
            try {
                // Kürzere Dauer (0.3s) für schnellere Response bei schnellen Tastenanschlägen
                previewAudioPlayer.previewSoloNote(midi, 0.3)
            } catch (e: CancellationException) {
                // job cancelled -> ensure preview AudioTrack stopped
                previewAudioPlayer.stop()
                throw e
            } catch (t: Throwable) {
                // fallback to local sine preview if the AudioPlayer preview fails
                try { playNotePreview(midi, 0.3) } catch (_: Exception) {}
            }
        }

        // visual key press effect: elevation change
        try {
            val keyView = when (pitchClass) {
                0 -> keyC
                1 -> keyCs
                2 -> keyD
                3 -> keyDs
                4 -> keyE
                5 -> keyF
                6 -> keyFs
                7 -> keyG
                8 -> keyGs
                9 -> keyA
                10 -> keyAs
                11 -> keyB
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
            updatePreviewIfActive()
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
        // Stop any active preview
        try {
            if (isPreviewActive) {
                if (isServiceBound && playbackService != null) {
                    playbackService?.stopPreviewNow()
                } else {
                    PlaybackService.stopPreview(this)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop preview in onDestroy: ${e.message}")
        }
        isPreviewActive = false

        // Unbind from service
        try {
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unbind service: ${e.message}")
        }

        // Cancel any running single-note preview jobs
        try { previewJob?.cancel() } catch (_: Exception) {}
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
