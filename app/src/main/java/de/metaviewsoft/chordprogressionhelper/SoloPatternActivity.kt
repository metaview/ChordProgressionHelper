package de.metaviewsoft.chordprogressionhelper

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
import de.metaviewsoft.chordprogressionhelper.model.Chord
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.ChordType
import de.metaviewsoft.chordprogressionhelper.model.DrumPattern
import de.metaviewsoft.chordprogressionhelper.model.DrumStep
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.Mode
import de.metaviewsoft.chordprogressionhelper.model.Note
import de.metaviewsoft.chordprogressionhelper.model.SoloPattern
import de.metaviewsoft.chordprogressionhelper.model.StrummingPattern
import de.metaviewsoft.chordprogressionhelper.model.Strum
import de.metaviewsoft.chordprogressionhelper.service.PlaybackService
import de.metaviewsoft.chordprogressionhelper.data.SettingsRepository
import de.metaviewsoft.chordprogressionhelper.util.AudioPlayer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.google.android.material.card.MaterialCardView
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
        const val EXTRA_ALL_MEASURES_CHORDS = "extra_all_measures_chords"
        const val EXTRA_ALL_MEASURES_SOLO_PATTERNS_JSON = "extra_all_measures_solo_patterns_json"
        private const val TAG = "SoloPatternActivity"
        // Opacity for keyboard keys outside the current key's scale.
        private const val OUT_OF_SCALE_KEY_ALPHA = 0.4f
        
        // Clipboard for copy/paste of solo patterns across sections
        private var clipboard: List<Array<Slot>>? = null
    }

    // Editing modes
    enum class EditingMode {
        PREVIEW,  // Keine Aufnahme, nur Anhören
        EDIT,     // Schrittweise Eingabe am Cursor mit Auto-Advance
        LIVE      // Aufnahme nur während Playback an Abspielposition
    }

    // UI Views
    private lateinit var btnOk: MaterialButton
    private lateinit var btnPreview: MaterialButton
    private lateinit var btnEditMode: MaterialButton
    private lateinit var btnCopy: MaterialButton
    private lateinit var btnPaste: MaterialButton
    private lateinit var measureListContainer: android.widget.LinearLayout
    private lateinit var measureScrollView: android.widget.ScrollView
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

    // Multi-measure state
    private val allSlotsData = mutableListOf<Array<Slot>>()          // one 8-slot array per measure
    private val rowSlotViews = mutableListOf<List<android.widget.Button>>() // slot button views per row
    private val rowCards = mutableListOf<MaterialCardView>()          // card per row for highlighting
    private var activeMeasureIndex = 0
    private var measureChordNames: List<String> = emptyList()
    
    // Current editing mode
    private var currentMode = EditingMode.PREVIEW  // Start in preview mode by default

    // Computed property: always points to the active measure's slot array
    private val slots: Array<Slot>
        get() = if (allSlotsData.isNotEmpty()) allSlotsData[activeMeasureIndex] else Array(8) { Slot.Rest as Slot }
    // Local preview player for single-note previews (separate from PlaybackService used for full-pattern previews)
    private val previewAudioPlayer = AudioPlayer()
    private var selectedSlot: Int = -1
    private var currentOctave: Int = 4
    // Job handle for the currently playing single-note preview so it can be cancelled
    private var previewJob: Job? = null
    
    // Cached colors for highlighting (avoid repeated lookups during playback)
    private var highlightColor: Int = 0
    private var slotDefaultColor: Int = 0

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

            // Start observing playback position for visual feedback
            observePlaybackPosition()

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

        btnEditMode = findViewById(R.id.btnEditMode)
        // Initialize all views
        btnOk = findViewById(R.id.btnOk)
        btnPreview = findViewById(R.id.btnPreview)
        btnCopy = findViewById(R.id.btnCopy)
        btnPaste = findViewById(R.id.btnPaste)
        measureListContainer = findViewById(R.id.measureListContainer)
        measureScrollView = findViewById(R.id.measureScrollView)
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

        // Initialize previewAudioPlayer with solo preset and volume settings
        val settingsRepo = SettingsRepository(this)
        previewAudioPlayer.soloPreset = settingsRepo.soloPreset
        // Apply volume settings (always set them, they have sensible defaults)
        val soloLevel = settingsRepo.soloLevel.toDouble().coerceAtLeast(0.5)  // Minimum 0.5 for keyboard preview
        previewAudioPlayer.soloLevel = soloLevel
        previewAudioPlayer.strumLevel = settingsRepo.strumLevel.toDouble()
        previewAudioPlayer.drumLevel = settingsRepo.drumLevel.toDouble()
        previewAudioPlayer.masterVolume = 1.0
        Log.i(TAG, "PreviewAudioPlayer initialized: soloLevel=$soloLevel (from settings: ${settingsRepo.soloLevel}), masterVolume=${previewAudioPlayer.masterVolume}, preset=${previewAudioPlayer.soloPreset}")
        
        // CRITICAL: Initialize the audio track synchronously here at startup
        // This ensures it's ready before any key can be pressed
        previewAudioPlayer.ensurePreviewTrackReady()
        Log.i(TAG, "Preview audio track initialized at startup")
        
        // Cache colors for highlighting to avoid repeated lookups during playback
        highlightColor = ContextCompat.getColor(this, R.color.highlight_active)
        slotDefaultColor = ContextCompat.getColor(this, R.color.slot_default)

        val measureIndex = intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1

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

        // Build multi-measure slot data
        val allPatternsJson = intent?.getStringExtra(EXTRA_ALL_MEASURES_SOLO_PATTERNS_JSON)
        if (allPatternsJson != null) {
            // New multi-measure mode: load all measures' patterns
            val chordsStr = intent?.getStringExtra(EXTRA_ALL_MEASURES_CHORDS) ?: ""
            measureChordNames = if (chordsStr.isEmpty()) emptyList() else chordsStr.split("|")
            try {
                val patterns = Json.decodeFromString(ListSerializer(SoloPattern.serializer()), allPatternsJson)
                for (pattern in patterns) {
                    val arr = Array<Slot>(8) { Slot.Rest }
                    expandPatternToSlots(pattern, arr)
                    allSlotsData.add(arr)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse all solo patterns: ${e.message}")
            }
        }
        // Fallback / single-measure mode
        if (allSlotsData.isEmpty()) {
            val arr = Array<Slot>(8) { Slot.Rest }
            intent?.getStringExtra(EXTRA_SOLO_PATTERN_JSON)?.let { json ->
                try {
                    val pattern = Json.decodeFromString(SoloPattern.serializer(), json)
                    expandPatternToSlots(pattern, arr)
                } catch (_: Exception) {}
            }
            allSlotsData.add(arr)
        }
        activeMeasureIndex = (measureIndex.coerceAtLeast(0)).coerceAtMost(allSlotsData.size - 1)

        // Ensure keyboard keys use their own drawables and are not tinted by the app theme
        val whiteKeyViews = listOf(keyBLow, keyC, keyD, keyE, keyF, keyG, keyA, keyB, keyCHigh, keyDHigh)
        val whiteDrawable = ContextCompat.getDrawable(this, R.drawable.white_key_selector)
        for (k in whiteKeyViews) {
            try {
                k.background = whiteDrawable
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

        // Dim keys outside the current key's scale so the diatonic notes stand out.
        applyScaleHighlighting()

        // Build all measure rows and select first slot of the active row
        buildMeasureRows()
        selectedSlot = 0
        highlightSelectedSlot()
        // Scroll to the initially opened measure after layout is complete
        measureScrollView.post {
            try {
                rowCards.getOrNull(activeMeasureIndex)?.let { card ->
                    measureScrollView.smoothScrollTo(0, card.top)
                }
            } catch (_: Exception) {}
        }
        octaveText.text = getString(R.string.octave_current, currentOctave)

        btnOk.setOnClickListener { performOk() }
        btnEditMode.setOnClickListener { toggleEditMode() }
        btnCopy.setOnClickListener { performCopy() }
        btnPaste.setOnClickListener { performPaste() }
        
        // Initialize edit mode button appearance
        updateEditModeButton()
        btnPreview.setOnClickListener { performPreview() }

        // Rest / LetRing buttons
        // Buttons below keyboard
        iconRest.setOnClickListener {
            if (currentMode != EditingMode.EDIT) return@setOnClickListener  // Only work in edit mode
            
            if (selectedSlot >= 0) {
                slots[selectedSlot] = Slot.Rest
                renderAllSlots()
                
                // FEATURE: Auto-advance to next slot/measure after Rest input
                if (selectedSlot == 7) {
                    // Jump to next measure if available
                    if (activeMeasureIndex < allSlotsData.size - 1) {
                        activateAndSelectSlot(activeMeasureIndex + 1, 0)
                    } else {
                        selectedSlot = 7
                        highlightSelectedSlot()
                    }
                } else {
                    selectedSlot += 1
                    highlightSelectedSlot()
                }
                updatePreviewIfActive()
            }
        }
        iconLetRing.setOnClickListener {
            if (currentMode != EditingMode.EDIT) return@setOnClickListener  // Only work in edit mode
            
            if (selectedSlot >= 0) {
                slots[selectedSlot] = Slot.LetRing
                renderAllSlots()
                
                // FEATURE: Auto-advance to next slot/measure after LetRing input
                if (selectedSlot == 7) {
                    // Jump to next measure if available
                    if (activeMeasureIndex < allSlotsData.size - 1) {
                        activateAndSelectSlot(activeMeasureIndex + 1, 0)
                    } else {
                        selectedSlot = 7
                        highlightSelectedSlot()
                    }
                } else {
                    selectedSlot += 1
                    highlightSelectedSlot()
                }
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
        // Fire on ACTION_DOWN for minimum latency (not on click/up)
        fun android.view.View.setKeyDownListener(pitchClass: Int, octaveOffset: Int = 0) {
            // Press-and-hold: ACTION_DOWN starts the (sustained) note, ACTION_UP/CANCEL releases it.
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        onKeyPressed(pitchClass, octaveOffset)
                        v.performClick()
                        true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        try { previewAudioPlayer.releaseSustainedNote() } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }
        }

        // Low octave: B from the octave below
        keyBLow.setKeyDownListener(11, octaveOffset = -1)

        // Current octave
        keyC.setKeyDownListener(0)
        keyCs.setKeyDownListener(1)
        keyD.setKeyDownListener(2)
        keyDs.setKeyDownListener(3)
        keyE.setKeyDownListener(4)
        keyF.setKeyDownListener(5)
        keyFs.setKeyDownListener(6)
        keyG.setKeyDownListener(7)
        keyGs.setKeyDownListener(8)
        keyA.setKeyDownListener(9)
        keyAs.setKeyDownListener(10)
        keyB.setKeyDownListener(11)

        keyCHigh.setKeyDownListener(0, octaveOffset = 1)
        keyCsHigh.setKeyDownListener(1, octaveOffset = 1)
        keyDHigh.setKeyDownListener(2, octaveOffset = 1)

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
        // Convert all measures' slots into SoloPatterns and return them all
        val allPatterns = allSlotsData.map { slotsToPattern(it) }
        val allPatternsJson = try {
            Json.encodeToString(ListSerializer(SoloPattern.serializer()), allPatterns)
        } catch (e: Exception) { null }
        val activePattern = allPatterns.getOrNull(activeMeasureIndex)
        val activeJson = activePattern?.let {
            try { Json.encodeToString(SoloPattern.serializer(), it) } catch (e: Exception) { null }
        }

        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_MEASURE_INDEX, intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1)
            if (activeJson != null) putExtra(EXTRA_SOLO_PATTERN_JSON, activeJson)
            if (allPatternsJson != null) putExtra(EXTRA_ALL_MEASURES_SOLO_PATTERNS_JSON, allPatternsJson)
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

    private fun performCopy() {
        // Copy all slot arrays to clipboard (deep copy to avoid shared references)
        clipboard = allSlotsData.map { originalSlots ->
            Array(originalSlots.size) { i -> 
                when (val slot = originalSlots[i]) {
                    is Slot.Rest -> Slot.Rest
                    is Slot.LetRing -> Slot.LetRing
                    is Slot.NoteSlot -> Slot.NoteSlot(slot.midi)
                }
            }
        }
        
        Toast.makeText(this, R.string.copied_solo_pattern, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Copied ${clipboard?.size} measures to clipboard")
    }

    private fun performPaste() {
        val source = clipboard
        if (source == null) {
            Toast.makeText(this, R.string.no_solo_pattern_to_paste, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Paste from clipboard, truncate if source has more measures than target
        val targetMeasureCount = allSlotsData.size
        val sourceMeasureCount = source.size
        
        for (i in 0 until minOf(targetMeasureCount, sourceMeasureCount)) {
            // Deep copy each slot array
            allSlotsData[i] = Array(source[i].size) { slotIndex ->
                when (val slot = source[i][slotIndex]) {
                    is Slot.Rest -> Slot.Rest
                    is Slot.LetRing -> Slot.LetRing
                    is Slot.NoteSlot -> Slot.NoteSlot(slot.midi)
                }
            }
        }
        
        // Re-render all measures (iterate through all rows)
        for (rowIdx in allSlotsData.indices) {
            renderRowSlots(rowIdx)
        }
        
        // Update preview if active
        updatePreviewIfActive()
        
        val pastedCount = minOf(targetMeasureCount, sourceMeasureCount)
        Toast.makeText(this, getString(R.string.pasted_solo_pattern), Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Pasted $pastedCount measures from clipboard (source had $sourceMeasureCount, target has $targetMeasureCount)")
    }

    private fun performPreview() {
        // Play preview of all measures
        try {
            if (!isPreviewActive) {
                // Start looping preview of all measures
                // Pass empty list as placeholder - function will use allSlotsData instead
                startPreviewWithCurrentPattern(emptyList())

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

    private fun startPreviewWithCurrentPattern(elements: List<de.metaviewsoft.chordprogressionhelper.model.SoloElement>) {
        val key = try { Key.valueOf(keyVal) } catch (_: Exception) { Key.C }
        val mode = try { Mode.valueOf(modeVal.uppercase()) } catch (_: Exception) { Mode.MAJOR }
        val tempProg = ChordProgression(name = "Preview", key = key, mode = mode, tempo = tempoVal)
        tempProg.measures.clear()

        // Add ALL measures to the progression for preview
        for (measureIdx in allSlotsData.indices) {
            val m = Measure(measureIdx + 1)

            // Parse the correct chord for this measure
            try {
                val chordName = measureChordNames.getOrNull(measureIdx) ?: ""
                val chord = if (chordName.isNotEmpty()) {
                    parseChordName(chordName) ?: tonicChord
                } else {
                    tonicChord
                }
                // Add chord at beat 0
                chord?.let { m.addChord(it, 0) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse chord for measure $measureIdx: ${e.message}")
                tonicChord?.let { m.addChord(it, 0) }
            }

            // Convert slots to pattern for this measure
            val pattern = slotsToPattern(allSlotsData[measureIdx])
            m.soloPattern = pattern

            // FEATURE: Play progression parallel to solo (with light accompaniment)
            try {
                // Simple strumming pattern on beats 1, 3, 5, 7 for accompaniment
                m.strummingPattern = StrummingPattern("Accompaniment", 
                    listOf(Strum.DOWN, Strum.REST, Strum.DOWN, Strum.REST, 
                           Strum.DOWN, Strum.REST, Strum.DOWN, Strum.REST))
                           
                // No drums during preview (keep focus on solo + chords)
                m.drumPattern = DrumPattern("Silent", List(8) { DrumStep() })
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set patterns: ${e.message}")
                m.drumPattern = DrumPattern("Silent", List(8) { DrumStep() })
                m.strummingPattern = StrummingPattern("Silent", List(8) { Strum.REST })
            }

            tempProg.measures.add(m)
        }

        try {
            PlaybackService.stopPreview(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop previous preview: ${e.message}")
        }

        // Update playback parameters to match settings (especially solo volume)
        try {
            val settingsRepo = SettingsRepository(this)
            val updateIntent = Intent(this, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_UPDATE_PARAMS
                putExtra(PlaybackService.EXTRA_SOLO_LEVEL, settingsRepo.soloLevel)
                putExtra(PlaybackService.EXTRA_DRUM_LEVEL, settingsRepo.drumLevel)
                putExtra(PlaybackService.EXTRA_STRUM_LEVEL, settingsRepo.strumLevel)
            }
            startService(updateIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update playback parameters: ${e.message}")
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
        // During playback: update without restarting in edit/live mode, restart in preview mode
        if (isPreviewActive) {
            if (currentMode == EditingMode.PREVIEW) {
                // Preview mode: restart preview
                val elements = mutableListOf<de.metaviewsoft.chordprogressionhelper.model.SoloElement>()
                var i = 0
                while (i < 8) {
                    when (val s = slots[i]) {
                        is Slot.NoteSlot -> {
                            var len = 1
                            var j = i + 1
                            while (j < 8 && slots[j] is Slot.LetRing) { len++; j++ }
                            elements.add(de.metaviewsoft.chordprogressionhelper.model.SoloElement.Note(s.midi, len))
                            i = j
                        }
                        is Slot.Rest -> {
                            var len = 1
                            var j = i + 1
                            while (j < 8 && slots[j] is Slot.Rest) { len++; j++ }
                            elements.add(de.metaviewsoft.chordprogressionhelper.model.SoloElement.Rest(len))
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
                return
            }
            
            // Edit or Live mode during playback: update the progression without restarting
            val key = try { Key.valueOf(keyVal) } catch (_: Exception) { Key.C }
            val mode = try { Mode.valueOf(modeVal.uppercase()) } catch (_: Exception) { Mode.MAJOR }
            val tempProg = ChordProgression(name = "Preview", key = key, mode = mode, tempo = tempoVal)
            tempProg.measures.clear()

            // Add ALL measures to the progression
            for (measureIdx in allSlotsData.indices) {
                val m = Measure(measureIdx + 1)

                // Parse the correct chord for this measure
                try {
                    val chordName = measureChordNames.getOrNull(measureIdx) ?: ""
                    val chord = if (chordName.isNotEmpty()) {
                        parseChordName(chordName) ?: tonicChord
                    } else {
                        tonicChord
                    }
                    chord?.let { m.addChord(it, 0) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse chord for measure $measureIdx: ${e.message}")
                    tonicChord?.let { m.addChord(it, 0) }
                }

                // Convert slots to pattern for this measure
                val pattern = slotsToPattern(allSlotsData[measureIdx])
                m.soloPattern = pattern

                // Simple strumming pattern on beats 1, 3, 5, 7 for accompaniment
                try {
                    m.strummingPattern = StrummingPattern("Accompaniment", 
                        listOf(Strum.DOWN, Strum.REST, Strum.DOWN, Strum.REST, 
                               Strum.DOWN, Strum.REST, Strum.DOWN, Strum.REST))
                    m.drumPattern = DrumPattern("Silent", List(8) { DrumStep() })
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set patterns: ${e.message}")
                    m.drumPattern = DrumPattern("Silent", List(8) { DrumStep() })
                    m.strummingPattern = StrummingPattern("Silent", List(8) { Strum.REST })
                }

                tempProg.measures.add(m)
            }

            // Update the progression in PlaybackService without restarting playback
            try {
                PlaybackService.updateProgression(this, tempProg)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update progression: ${e.message}")
            }
            return
        }
    }

    /**
     * Parse a chord name string (e.g. "C", "Dm", "G7", "Bb", "F#") into a Chord object.
     * Returns null if parsing fails.
     */
    private fun parseChordName(chordName: String): Chord? {
        if (chordName.isBlank()) return null
        
        val trimmed = chordName.trim()
        
        // Parse root note (first 1-2 characters)
        val rootChar = trimmed[0].uppercaseChar()
        val noteBase = when (rootChar) {
            'C' -> Note.C
            'D' -> Note.D
            'E' -> Note.E
            'F' -> Note.F
            'G' -> Note.G
            'A' -> Note.A
            'B' -> Note.B
            else -> return null
        }
        
        var idx = 1
        var root = noteBase
        
        // Check for accidental (#, b, ♯, ♭)
        if (idx < trimmed.length) {
            when (trimmed[idx]) {
                '#', '♯' -> {
                    // Sharp
                    root = when (noteBase) {
                        Note.C -> Note.C_SHARP
                        Note.D -> Note.E_FLAT  // D# = Eb
                        Note.E -> Note.F        // E# = F
                        Note.F -> Note.F_SHARP
                        Note.G -> Note.A_FLAT  // G# = Ab
                        Note.A -> Note.B_FLAT  // A# = Bb
                        Note.B -> Note.C       // B# = C
                        else -> noteBase
                    }
                    idx++
                }
                'b', '♭' -> {
                    // Flat
                    root = when (noteBase) {
                        Note.C -> Note.B       // Cb = B
                        Note.D -> Note.C_SHARP // Db = C#
                        Note.E -> Note.E_FLAT
                        Note.F -> Note.E       // Fb = E
                        Note.G -> Note.F_SHARP // Gb = F#
                        Note.A -> Note.A_FLAT
                        Note.B -> Note.B_FLAT
                        else -> noteBase
                    }
                    idx++
                }
            }
        }
        
        // Parse quality (rest of the string)
        val qualitySuffix = if (idx < trimmed.length) trimmed.substring(idx) else ""
        val quality = when {
            qualitySuffix.isEmpty() -> ChordType.MAJOR
            qualitySuffix == "m" || qualitySuffix == "min" || qualitySuffix == "minor" -> ChordType.MINOR
            qualitySuffix == "7" -> ChordType.DOMINANT_SEVENTH
            qualitySuffix == "°" || qualitySuffix == "dim" -> ChordType.DIMINISHED
            qualitySuffix == "5" -> ChordType.POWER
            else -> ChordType.MAJOR  // Default to major if unknown
        }
        
        // Scale degree name is just the chord display name for now
        val scaleDegreeName = root.displayName + quality.suffix
        
        return Chord(root, quality, scaleDegreeName)
    }

    // Expand a SoloPattern into an 8-slot representation
    private fun expandPatternToSlots(pattern: SoloPattern, target: Array<Slot>) {
        // initialize all to Rest
        for (k in 0 until 8) target[k] = Slot.Rest

        var pos = 0

        // Use elements from pattern
        val elementsList = pattern.elements

        for (element in elementsList) {
            if (pos >= 8) break
            val len = element.lengthEighths.coerceAtLeast(1)

            when (element) {
                is de.metaviewsoft.chordprogressionhelper.model.SoloElement.Note -> {
                    target[pos] = Slot.NoteSlot(element.midi)
                    // mark let ring for following positions
                    for (r in 1 until len) {
                        val idx = pos + r
                        if (idx >= 8) break
                        target[idx] = Slot.LetRing
                    }
                }
                is de.metaviewsoft.chordprogressionhelper.model.SoloElement.Rest -> {
                    // Fill with Rest slots
                    for (r in 0 until len) {
                        val idx = pos + r
                        if (idx >= 8) break
                        target[idx] = Slot.Rest
                    }
                }
                is de.metaviewsoft.chordprogressionhelper.model.SoloElement.LetRing -> {
                    // Standalone LetRing element (uncommon but valid)
                    for (r in 0 until len) {
                        val idx = pos + r
                        if (idx >= 8) break
                        target[idx] = Slot.LetRing
                    }
                }
            }
            pos += len
        }
    }

    private fun renderAllSlots() {
        renderRowSlots(activeMeasureIndex)
    }

    private fun renderRowSlots(rowIdx: Int) {
        val btns = rowSlotViews.getOrNull(rowIdx) ?: return
        val rowSlots = allSlotsData.getOrNull(rowIdx) ?: return
        for (i in 0 until 8) {
            val label = when (val s = rowSlots[i]) {
                is Slot.Rest -> "-"
                is Slot.LetRing -> " "
                is Slot.NoteSlot -> midiToName(s.midi)
            }
            btns.getOrNull(i)?.text = label
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
        highlightRowSlots(activeMeasureIndex, selectedSlot)
    }

    private fun highlightRowSlots(rowIdx: Int, selSlot: Int) {
        val btns = rowSlotViews.getOrNull(rowIdx) ?: return
        for ((i, btn) in btns.withIndex()) {
            if (i == selSlot) {
                // Ausgewählter Slot: helles lila
                btn.background = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.purple_button_bg_light, theme)
                btn.backgroundTintList = null
                btn.elevation = 10f
                btn.scaleX = 1.03f; btn.scaleY = 1.03f
            } else {
                // Normale Slots: grau (nicht lila!)
                btn.setBackgroundColor(slotDefaultColor)
                btn.backgroundTintList = null
                btn.elevation = 0f
                btn.scaleX = 1.0f; btn.scaleY = 1.0f
            }
        }
    }

    /** Switch to a different measure row and select a slot within it. */
    private fun activateAndSelectSlot(rowIdx: Int, slotIdx: Int) {
        if (rowIdx != activeMeasureIndex) {
            val oldRow = activeMeasureIndex
            activeMeasureIndex = rowIdx
            selectedSlot = slotIdx
            // deselect old row, select new row
            highlightRowSlots(oldRow, -1)
            highlightRowSlots(rowIdx, selectedSlot)
            updateRowHighlights()
        } else {
            selectSlot(slotIdx)
        }
    }

    /** Highlight active card with a coloured stroke, clear others. */
    private fun updateRowHighlights() {
        val dp = resources.displayMetrics.density
        val activeColor = ContextCompat.getColor(this, R.color.purple_700)
        for (i in rowCards.indices) {
            if (i == activeMeasureIndex) {
                rowCards[i].strokeWidth = (2 * dp).toInt()
                rowCards[i].strokeColor = activeColor
            } else {
                rowCards[i].strokeWidth = 0
            }
        }
    }

    /** Build one card row per measure in measureListContainer. */
    private fun buildMeasureRows() {
        val dp = resources.displayMetrics.density
        measureListContainer.removeAllViews()
        rowSlotViews.clear()
        rowCards.clear()

        for (rowIdx in allSlotsData.indices) {
            val chord = measureChordNames.getOrElse(rowIdx) { "" }
            val label = if (chord.isNotEmpty()) "${rowIdx + 1}  $chord" else "${rowIdx + 1}"

            // Card container
            val card = MaterialCardView(this).apply {
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
                layoutParams = lp
                radius = (8 * dp)
                cardElevation = 2 * dp
                setContentPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }
            rowCards.add(card)

            val inner = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Chord / measure label
            val labelTv = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Slots row
            val slotsRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (4 * dp).toInt() }
                weightSum = 8f
            }

            val slotBtns = (0 until 8).map { slotIdx ->
                android.widget.Button(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f)
                    text = "-"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 11f
                    // Initial color: gray (will be set properly by highlightRowSlots)
                    setBackgroundColor(slotDefaultColor)
                    backgroundTintList = null
                    val r = rowIdx; val s = slotIdx
                    setOnClickListener { activateAndSelectSlot(r, s) }
                }
            }
            slotBtns.forEach { slotsRow.addView(it) }
            rowSlotViews.add(slotBtns)

            inner.addView(labelTv)
            inner.addView(slotsRow)
            card.addView(inner)
            measureListContainer.addView(card)
        }

        // Render labels and apply initial highlights
        for (rowIdx in allSlotsData.indices) {
            renderRowSlots(rowIdx)
            // Set initial colors (all gray, no selection)
            highlightRowSlots(rowIdx, -1)
        }
        updateRowHighlights()
    }

    /** Convert an 8-slot array to a SoloPattern. */
    private fun slotsToPattern(slotArray: Array<Slot>): SoloPattern {
        val elements = mutableListOf<de.metaviewsoft.chordprogressionhelper.model.SoloElement>()
        var i = 0
        while (i < 8) {
            when (val s = slotArray[i]) {
                is Slot.NoteSlot -> {
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slotArray[j] is Slot.LetRing) { len++; j++ }
                    elements.add(de.metaviewsoft.chordprogressionhelper.model.SoloElement.Note(s.midi, len))
                    i = j
                }
                is Slot.Rest -> {
                    var len = 1
                    var j = i + 1
                    while (j < 8 && slotArray[j] is Slot.Rest) { len++; j++ }
                    elements.add(de.metaviewsoft.chordprogressionhelper.model.SoloElement.Rest(len))
                    i = j
                }
                is Slot.LetRing -> { i++ }
            }
        }
        return SoloPattern(name = "Custom", elements = elements)
    }

    /**
     * Fades keys that are not part of the current key's scale so the diatonic
     * (tonic-scale) keys visually stand out. Pitch classes are octave-independent,
     * so this stays correct across octave changes.
     */
    private fun applyScaleHighlighting() {
        val scalePitchClasses = try {
            val key = Key.valueOf(keyVal)
            val mode = Mode.valueOf(modeVal.uppercase())
            mode.getScale(key).map { ((it.noteOffset % 12) + 12) % 12 }.toSet()
        } catch (_: Exception) {
            return
        }
        val keyPitchClasses = listOf(
            keyBLow to 11,
            keyC to 0, keyCs to 1, keyD to 2, keyDs to 3, keyE to 4, keyF to 5,
            keyFs to 6, keyG to 7, keyGs to 8, keyA to 9, keyAs to 10, keyB to 11,
            keyCHigh to 0, keyCsHigh to 1, keyDHigh to 2
        )
        for ((view, pitchClass) in keyPitchClasses) {
            view.alpha = if (pitchClass in scalePitchClasses) 1.0f else OUT_OF_SCALE_KEY_ALPHA
        }
    }

    private fun onKeyPressed(pitchClass: Int, octaveOffset: Int = 0) {
        val midi = (currentOctave + octaveOffset + 1) * 12 + pitchClass
        
        // Always trigger note preview for audio feedback (sustained while the key is held down).
        try {
            Log.d(TAG, "onKeyPressed: midi=$midi, starting sustained note")
            previewAudioPlayer.startSustainedNote(midi)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sustained preview note: ${e.message}", e)
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

        // Handle note input based on current mode
        when (currentMode) {
            EditingMode.PREVIEW -> {
                // Preview mode: just play sound, don't write
                return
            }
            EditingMode.LIVE -> {
                // Live mode: only record during playback
                if (!isPreviewActive) {
                    // No playback running, don't record
                    return
                }
            }
            EditingMode.EDIT -> {
                // Edit mode: always allow input (handled below)
            }
        }

        // Determine target slot based on mode
        val targetMeasure: Int
        val targetSlot: Int
        
        if (currentMode == EditingMode.LIVE) {
            // Live mode: write at current playback position
            if (lastHighlightedMeasure >= 0 && lastHighlightedSlot >= 0) {
                targetMeasure = lastHighlightedMeasure
                targetSlot = lastHighlightedSlot
            } else {
                // Playback running but no position yet - don't write
                return
            }
        } else {
            // Edit mode: write at selected cursor position
            if (selectedSlot < 0) return  // No slot selected
            targetMeasure = activeMeasureIndex
            targetSlot = selectedSlot
        }

        // Write the note at target position
        if (targetMeasure in allSlotsData.indices && targetSlot in 0..7) {
            val targetSlots = allSlotsData[targetMeasure]
            targetSlots[targetSlot] = Slot.NoteSlot(midi)
            
            // Clear subsequent LetRing that belonged to a previous note
            var j = targetSlot + 1
            while (j < 8 && targetSlots[j] is Slot.LetRing) {
                targetSlots[j] = Slot.Rest
                j++
            }
            
            // Render the updated measure
            renderRowSlots(targetMeasure)
            
            // Auto-advance only in edit mode (not in live mode)
            if (currentMode == EditingMode.EDIT) {
                if (targetSlot == 7) {
                    // Jump to next measure if available
                    if (targetMeasure < allSlotsData.size - 1) {
                        activateAndSelectSlot(targetMeasure + 1, 0)
                    } else {
                        // Stay at end of last measure
                        selectedSlot = 7
                        highlightSelectedSlot()
                    }
                } else {
                    selectedSlot = targetSlot + 1
                    highlightSelectedSlot()
                }
            }
            
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

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume() called")
        PlaybackService.stop(this)
        
        // Re-apply settings in case they changed while activity was paused
        try {
            val settingsRepo = SettingsRepository(this)
            previewAudioPlayer.soloPreset = settingsRepo.soloPreset
            val soloLevel = settingsRepo.soloLevel.toDouble().coerceAtLeast(0.5)
            previewAudioPlayer.soloLevel = soloLevel
            previewAudioPlayer.strumLevel = settingsRepo.strumLevel.toDouble()
            previewAudioPlayer.drumLevel = settingsRepo.drumLevel.toDouble()
            previewAudioPlayer.masterVolume = 1.0
            Log.i(TAG, "Preview audio player settings updated: soloLevel=$soloLevel, masterVolume=${previewAudioPlayer.masterVolume}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update preview audio player settings: ${e.message}", e)
        }
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

    private fun observePlaybackPosition() {
        playbackService?.let { service ->
            // Use Main.immediate dispatcher for minimal latency
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                service.currentPlaybackPosition.collect { position ->
                    if (position != null) {
                        val (measureIndex, slotIndex) = position
                        highlightActiveNote(measureIndex, slotIndex)
                    } else {
                        // No playback - clear all highlights
                        clearAllHighlights()
                    }
                }
            }
        }
    }

    private var lastHighlightedMeasure: Int = -1
    private var lastHighlightedSlot: Int = -1

    private fun highlightActiveNote(measureIndex: Int, slotIndex: Int) {
        // Already on Main dispatcher from observePlaybackPosition - no need for runOnUiThread
        // Clear previous slot highlight (zurück zu grau)
        if (lastHighlightedMeasure >= 0 && lastHighlightedSlot >= 0 &&
            lastHighlightedMeasure < rowSlotViews.size &&
            lastHighlightedSlot < rowSlotViews[lastHighlightedMeasure].size) {
            // Reset to gray unless it's the selected slot
            if (lastHighlightedMeasure == activeMeasureIndex && lastHighlightedSlot == selectedSlot) {
                // Keep selected slot highlighted (light purple)
                rowSlotViews[lastHighlightedMeasure][lastHighlightedSlot].background = 
                    androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.purple_button_bg_light, theme)
            } else {
                rowSlotViews[lastHighlightedMeasure][lastHighlightedSlot].setBackgroundColor(slotDefaultColor)
            }
        }

        // Set new active slot highlight (grün)
        if (measureIndex >= 0 && slotIndex >= 0 &&
            measureIndex < rowSlotViews.size &&
            slotIndex < rowSlotViews[measureIndex].size) {
            rowSlotViews[measureIndex][slotIndex].setBackgroundColor(highlightColor)
        }

        // Remember current highlights
        lastHighlightedMeasure = measureIndex
        lastHighlightedSlot = slotIndex
        
        // In preview mode during playback: move cursor to follow playback position
        if (currentMode == EditingMode.PREVIEW && isPreviewActive) {
            if (measureIndex != activeMeasureIndex || slotIndex != selectedSlot) {
                activateAndSelectSlot(measureIndex, slotIndex)
            }
        }
    }

    private fun clearAllHighlights() {
        // Already on Main dispatcher from observePlaybackPosition - no need for runOnUiThread
        if (lastHighlightedMeasure >= 0 && lastHighlightedMeasure < rowCards.size) {
            rowCards[lastHighlightedMeasure].strokeWidth = 0
        }
        if (lastHighlightedMeasure >= 0 && lastHighlightedSlot >= 0 &&
            lastHighlightedMeasure < rowSlotViews.size &&
            lastHighlightedSlot < rowSlotViews[lastHighlightedMeasure].size) {
            rowSlotViews[lastHighlightedMeasure][lastHighlightedSlot].setBackgroundColor(slotDefaultColor)
        }
        lastHighlightedMeasure = -1
        lastHighlightedSlot = -1
    }
    
    private fun toggleEditMode() {
        // Cycle through modes: Preview -> Edit -> Live -> Preview
        currentMode = when (currentMode) {
            EditingMode.PREVIEW -> EditingMode.EDIT
            EditingMode.EDIT -> EditingMode.LIVE
            EditingMode.LIVE -> EditingMode.PREVIEW
        }
        updateEditModeButton()
    }
    
    private fun updateEditModeButton() {
        try {
            when (currentMode) {
                EditingMode.PREVIEW -> {
                    btnEditMode.text = getString(R.string.preview_mode)
                    btnEditMode.setIconResource(R.drawable.ic_visibility)
                    btnEditMode.alpha = 0.7f
                }
                EditingMode.EDIT -> {
                    btnEditMode.text = getString(R.string.edit_mode)
                    btnEditMode.setIconResource(R.drawable.ic_edit)
                    btnEditMode.alpha = 1.0f
                }
                EditingMode.LIVE -> {
                    btnEditMode.text = getString(R.string.live_mode)
                    btnEditMode.setIconResource(R.drawable.ic_radio_button_checked)
                    btnEditMode.alpha = 1.0f
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update edit mode button: ${e.message}")
        }
    }
}
