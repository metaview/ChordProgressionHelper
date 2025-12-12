package com.metaview.chordprogressionhelper

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.metaview.chordprogressionhelper.model.DrumPattern
import com.metaview.chordprogressionhelper.model.Chord
import com.metaview.chordprogressionhelper.model.ChordProgression
import com.metaview.chordprogressionhelper.model.Measure
import com.metaview.chordprogressionhelper.service.PlaybackService
import com.metaview.chordprogressionhelper.model.Key
import com.metaview.chordprogressionhelper.model.Mode
import kotlinx.serialization.json.Json
import android.widget.Toast
import android.content.res.ColorStateList
import android.util.TypedValue
import androidx.activity.OnBackPressedCallback
import kotlinx.serialization.InternalSerializationApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.metaview.chordprogressionhelper.data.SettingsRepository
import com.metaview.chordprogressionhelper.databinding.DialogDrumPatternBinding
import com.metaview.chordprogressionhelper.util.AudioPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import com.metaview.chordprogressionhelper.model.StrummingPattern
import com.metaview.chordprogressionhelper.model.Strum
import androidx.appcompat.app.AlertDialog

@OptIn(InternalSerializationApi::class)
class DrumPatternActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEASURE_INDEX = "extra_measure_index"
        const val EXTRA_DRUM_PATTERN_JSON = "extra_drum_pattern_json"
        const val EXTRA_ALL_PATTERNS_JSON = "extra_all_patterns_json"
        private const val TAG = "DrumPatternActivity"
    }

    private var measureIndex = -1
    private var currentPattern: DrumPattern = DrumPattern.DEFAULT
    private lateinit var settingsRepository: SettingsRepository
    private val previewAudioPlayer = AudioPlayer()

    // Temporary progression used for previews so we can push live updates
    private var tempPreviewProgression: ChordProgression? = null
    private var tonicChord: Chord? = null
    private var keyVal: Key = Key.C
    private var modeVal: Mode = Mode.MAJOR
    private var tempoVal: Int = 120

    private lateinit var binding: DialogDrumPatternBinding
    private var isPreviewActive = false

    // Bind to PlaybackService to check if main playback is running
    private var playbackService: PlaybackService? = null
    private var isServiceBound = false
    private var pendingPreviewProgression: ChordProgression? = null
    private var pendingPreviewLooping: Boolean = false
    // When false previews are suppressed and UI disabled
    private var previewsAllowed: Boolean = true
    private var playbackStateJob: Job? = null

    /**
     * serviceConnection: verwaltet die Bindung an den PlaybackService.
     * onServiceConnected: erhält das Binder-Objekt, startet einen Listener auf den Play-Status
     * und startet ggf. eine zuvor gespeicherte pending-Preview.
     * onServiceDisconnected: räumt Referenzen auf und reaktiviert UI-Controls.
     */
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as? PlaybackService.LocalBinder
            playbackService = binder?.getService()
            isServiceBound = true

            // Start observing playback state to enable/disable preview UI
            playbackStateJob?.cancel()
            playbackStateJob = lifecycleScope.launch {
                playbackService?.isPlaying?.collectLatest { playing ->
                    // Keep Test enabled while a local preview (isPreviewActive) is running.
                    // Only disable previews when the service is playing something that is not a local preview.
                    setPreviewsAllowed(!playing || isPreviewActive)
                }
            }

            // If a preview was requested before binding completed, start it now
            pendingPreviewProgression?.let { pending ->
                try {
                    PlaybackService.play(this@DrumPatternActivity, pending, true, pendingPreviewLooping)
                    isPreviewActive = true
                    tempPreviewProgression = pending
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start pending preview: ${e.message}")
                } finally {
                    pendingPreviewProgression = null
                    pendingPreviewLooping = false
                }
                try {
                    binding.btnTest.setIconResource(R.drawable.ic_stop)
                    binding.btnTest.contentDescription = getString(R.string.stop)
                } catch (_: Exception) {}
            }

            // enable Test button now that service is bound
            try {
                binding.btnTest.isEnabled = true
                binding.btnTest.isClickable = true
                binding.btnTest.isFocusable = true
                binding.btnTest.alpha = 1.0f
                try {
                    val tv = TypedValue()
                    val resolved = theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                    val color = if (resolved) tv.data else 0xFF000000.toInt()
                    binding.btnTest.iconTint = ColorStateList.valueOf(color)
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            playbackService = null
            isServiceBound = false
            playbackStateJob?.cancel()
            playbackStateJob = null
            try {
                binding.btnTest.isEnabled = true
                binding.btnTest.isClickable = true
                binding.btnTest.isFocusable = true
                binding.btnTest.alpha = 1.0f
            } catch (_: Exception) {}
        }
    }

    /**
     * setPreviewsAllowed(allowed)
     * Zweck: aktiviert/deaktiviert UI-Elemente für Previews (Test-Button) basierend auf
     * erlaubtem Zustand und laufender lokaler Preview.
     * Eingabe: allowed — generell erlauben; Seiteneffekt: ändert Button-Enabled/Alpha.
     */
    private fun setPreviewsAllowed(allowed: Boolean) {
        previewsAllowed = allowed
        try {
            val testEnabled = allowed || isPreviewActive
            binding.btnTest.isEnabled = testEnabled
            binding.btnTest.alpha = if (testEnabled) 1.0f else 0.45f
        } catch (_: Exception) {}
    }

    /**
     * onCreate(savedInstanceState)
     * Initialisiert die Activity: ViewBinding, SettingsRepository, Eingabe-Extras (Pattern,
     * Tempo/Key/Mode) parsen und UI-Aufbau (Chips, Pattern-Listen, Buttons) ausführen.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(R.layout.dialog_drum_pattern)
        binding = DialogDrumPatternBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = (application as MyApplication).settingsRepository

        measureIndex = intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1
        intent?.getStringExtra(EXTRA_DRUM_PATTERN_JSON)?.let { json ->
            try { currentPattern = Json.decodeFromString(DrumPattern.serializer(), json) } catch (_: Exception) {}
        }

        // Parse optional extra patterns list
        val extraPatterns = try {
            intent?.getStringExtra(EXTRA_ALL_PATTERNS_JSON)?.let { Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DrumPattern.serializer()), it) } ?: emptyList()
        } catch (_: Exception) { emptyList<DrumPattern>() }

        // optional preview context
        intent?.getStringExtra("extra_tonic_chord_json")?.let {
            tonicChord = try { Json.decodeFromString(Chord.serializer(), it) } catch (_: Exception) { null }
        }
        keyVal = try { Key.valueOf(intent?.getStringExtra("extra_key") ?: Key.C.name) } catch (_: Exception) { Key.C }
        modeVal = try { Mode.valueOf(intent?.getStringExtra("extra_mode") ?: Mode.MAJOR.name) } catch (_: Exception) { Mode.MAJOR }
        tempoVal = intent?.getIntExtra("extra_tempo", 120) ?: 120

        // Build UI pieces similarly to StrummingPatternActivity: chips, patterns list, buttons
        setupDrumChips()
        setupDefaultPatterns(extraPatterns)
        setupButtons()

        // Handle back press via OnBackPressedDispatcher
        try {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    try {
                        performCancel()
                    } catch (_: Exception) {
                        // fallback: close activity safely from the callback
                        try { this@DrumPatternActivity.finish() } catch (_: Exception) { /* best-effort */ }
                    }
                }
            })
        } catch (_: Exception) {}
    }

    /**
     * setupDrumChips()
     * Baut die UI-Reihen für die Drum-Schritte auf.
     * Für jeden Schritt werden Kick/Snare/HiHat-Icons erzeugt, Klicks toggeln die Zustände,
     * aktualisieren das temporäre Preview-Progression-Objekt und spielen optional einen
     * Einzelklang als Preview (abhängig von Settings.isDrumPreviewEnabled).
     */
    private fun setupDrumChips() {
        val container = binding.drumStepsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        currentPattern.steps.forEachIndexed { idx, _ ->
            val stepLayout = inflater.inflate(R.layout.item_drum_step, container, false) as LinearLayout
            val kickIv = stepLayout.findViewById<ImageView>(R.id.kickIcon)
            val snareIv = stepLayout.findViewById<ImageView>(R.id.snareIcon)
            val hihatIv = stepLayout.findViewById<ImageView>(R.id.hihatIcon)

            fun applyState() {
                val s = currentPattern.steps.getOrNull(idx)
                kickIv.alpha = if (s?.kick == true) 1.0f else 0.25f
                snareIv.alpha = if (s?.snare == true) 1.0f else 0.25f
                hihatIv.alpha = if (s?.hiHat == true) 1.0f else 0.25f
            }
            applyState()

            kickIv.setOnClickListener {
                currentPattern = currentPattern.copy(steps = currentPattern.steps.mapIndexed { i, s -> if (i==idx) s.copy(kick = !s.kick) else s })
                applyState()
                tempPreviewProgression?.let { tp ->
                    if (tp.measures.isNotEmpty()) {
                        tp.measures[0].drumPattern = currentPattern
                        if (isPreviewActive) {
                            if (isServiceBound && playbackService != null) {
                                try { playbackService?.updateDrumPattern(0, currentPattern) } catch (_: Exception) {}
                            } else {
                                try { pendingPreviewProgression = tp; pendingPreviewLooping = true; tempPreviewProgression = tp } catch (_: Exception) {}
                            }
                        }
                    }
                }
                if (settingsRepository.isDrumPreviewEnabled) {
                    lifecycleScope.launch {
                        previewAudioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
                        previewAudioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
                        previewAudioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
                        previewAudioPlayer.voicePreset = settingsRepository.soundPreset
                        try { previewAudioPlayer.previewKick() } catch (e: Exception) { Log.w(TAG, "previewKick failed: ${e.message}") }
                    }
                }
            }

            snareIv.setOnClickListener {
                currentPattern = currentPattern.copy(steps = currentPattern.steps.mapIndexed { i, s -> if (i==idx) s.copy(snare = !s.snare) else s })
                applyState()
                tempPreviewProgression?.let { tp ->
                    if (tp.measures.isNotEmpty()) {
                        tp.measures[0].drumPattern = currentPattern
                        if (isPreviewActive) {
                            if (isServiceBound && playbackService != null) {
                                try { playbackService?.updateDrumPattern(0, currentPattern) } catch (_: Exception) {}
                            } else {
                                try { pendingPreviewProgression = tp; pendingPreviewLooping = true; tempPreviewProgression = tp } catch (_: Exception) {}
                            }
                        }
                    }
                }
                if (settingsRepository.isDrumPreviewEnabled) {
                    lifecycleScope.launch {
                        previewAudioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
                        previewAudioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
                        previewAudioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
                        previewAudioPlayer.voicePreset = settingsRepository.soundPreset
                        try { previewAudioPlayer.previewSnare() } catch (e: Exception) { Log.w(TAG, "previewSnare failed: ${e.message}") }
                    }
                }
            }

            hihatIv.setOnClickListener {
                currentPattern = currentPattern.copy(steps = currentPattern.steps.mapIndexed { i, s -> if (i==idx) s.copy(hiHat = !s.hiHat) else s })
                applyState()
                tempPreviewProgression?.let { tp ->
                    if (tp.measures.isNotEmpty()) {
                        tp.measures[0].drumPattern = currentPattern
                        if (isPreviewActive) {
                            if (isServiceBound && playbackService != null) {
                                try { playbackService?.updateDrumPattern(0, currentPattern) } catch (_: Exception) {}
                            } else {
                                try { pendingPreviewProgression = tp; pendingPreviewLooping = true; tempPreviewProgression = tp } catch (_: Exception) {}
                            }
                        }
                    }
                }
                if (settingsRepository.isDrumPreviewEnabled) {
                    lifecycleScope.launch {
                        previewAudioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
                        previewAudioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
                        previewAudioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
                        previewAudioPlayer.voicePreset = settingsRepository.soundPreset
                        try { previewAudioPlayer.previewHiHat() } catch (e: Exception) { Log.w(TAG, "previewHiHat failed: ${e.message}") }
                    }
                }
            }

            val label = stepLayout.findViewById<TextView>(R.id.stepLabel)
            label.text = (idx + 1).toString()
            container.addView(stepLayout)
        }
    }

    /**
     * setupDefaultPatterns(extraPatterns)
     * Wrapper: initialisiert die Anzeige der verwendeten und vorgeschlagenen Drum-Pattern.
     * Eingabe: Liste zusätzlicher Pattern aus der Progression; ruft renderPatterns zur Darstellung auf.
     */
    private fun setupDefaultPatterns(extraPatterns: List<DrumPattern>) {
        // Reuse existing renderPatterns to populate Used/Other lists; wrapper for parity with Strumming
        renderPatterns(extraPatterns)
    }

    /**
     * setupButtons()
     * Initialisiert OK/Test Buttons und deren Handler.
     * - Ok: speichert das Pattern als Activity-Result
     * Test: startet/stoppt eine loopende Drum-Preview über den PlaybackService; berücksichtigt
     * die Service-Bindung und speichert ggf. eine pending-Preview.
     */
    private fun setupButtons() {
        val btnOk = binding.btnOk
        val btnTest = binding.btnTest

        btnOk.setOnClickListener { performOk() }

        btnTest.isEnabled = true
        btnTest.setOnClickListener {
            try {
                if (!previewsAllowed && !isPreviewActive) return@setOnClickListener
                if (!isPreviewActive) {
                    val tempMeasure = Measure(1)
                    tempMeasure.drumPattern = currentPattern
                    // Ensure drum preview has no chords and no audible strumming: assign a silent StrummingPattern
                    try { tempMeasure.strummingPattern = StrummingPattern("Silent", List(8) { Strum.REST }) } catch (_: Exception) {}
                    val tempProg = ChordProgression(name = "Preview", key = keyVal, mode = modeVal, tempo = tempoVal)
                    tempProg.measures.clear()
                    tempProg.measures.add(tempMeasure)
                    try { PlaybackService.stop(this) } catch (_: Exception) {}
                    try {
                        if (isServiceBound) {
                            PlaybackService.play(this, tempProg, true, true)
                            tempPreviewProgression = tempProg
                        } else {
                            // Start service immediately to avoid silent preview when not bound, then bind to receive updates
                            try {
                                PlaybackService.play(this, tempProg, true, true)
                                tempPreviewProgression = tempProg
                                val bindIntent = Intent(this, PlaybackService::class.java)
                                bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE)
                            } catch (_: Exception) {
                                // Fallback to pending path if immediate start fails for some reason
                                pendingPreviewProgression = tempProg
                                pendingPreviewLooping = true
                                tempPreviewProgression = tempProg
                                try { val bindIntent = Intent(this, PlaybackService::class.java); bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE) } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "PlaybackService.play failed: ${e.message}") }
                    setPreviewsAllowed(previewsAllowed)
                    try { btnTest.setIconResource(R.drawable.ic_stop); btnTest.contentDescription = getString(R.string.stop) } catch (_: Exception) {}
                    isPreviewActive = true
                } else {
                    try {
                        if (isServiceBound && playbackService != null) playbackService?.stopPlayback() else PlaybackService.stop(this)
                    } catch (e: Exception) { Log.w(TAG, "Failed to stop preview: ${e.message}") }
                    isPreviewActive = false
                    setPreviewsAllowed(previewsAllowed)
                    try { btnTest.setIconResource(R.drawable.ic_play_arrow); btnTest.contentDescription = getString(R.string.test) } catch (_: Exception) {}
                }
            } catch (e: Exception) { Log.w(TAG, "Test toggle failed: ${e.message}") }
        }
    }

    /**
     * performOk()
     * Serialisiert das aktuelle DrumPattern und liefert es als Activity-Result zurück.
     * Bei aktiver Preview wird diese gestoppt. Auf Fehler wird ein Toast angezeigt.
     */
    private fun performOk() {
        try {
            val jsonOut = Json.encodeToString(DrumPattern.serializer(), currentPattern)
            val out = Intent().apply {
                putExtra(EXTRA_MEASURE_INDEX, measureIndex)
                putExtra(EXTRA_DRUM_PATTERN_JSON, jsonOut)
            }
            setResult(RESULT_OK, out)
            if (isPreviewActive) try { PlaybackService.stop(this@DrumPatternActivity) } catch (_: Exception) {}
            finish()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize DrumPattern on OK: ${e.message}")
            Toast.makeText(this@DrumPatternActivity, "Failed to serialize DrumPattern on OK: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * performCancel()
     * Verwirft Änderungen und beendet die Activity mit RESULT_CANCELED; stoppt Preview falls aktiv.
     */
    private fun performCancel() {
        if (isPreviewActive) try { PlaybackService.stop(this@DrumPatternActivity) } catch (_: Exception) {}
        setResult(RESULT_CANCELED)
        finish()
    }

    /**
     * renderPatterns(extraPatterns)
     * Rendert die 'Used' und 'Other' Drum-Pattern-Listen in den entsprechenden Containern.
     * Klick auf ein Pattern setzt das aktuelle Pattern und aktualisiert die UI/Preview.
     */
    private fun renderPatterns(extraPatterns: List<DrumPattern>) {
        val usedContainer = binding.defaultPatternsLayout
        val otherContainer = binding.otherPatternsLayout
        // Ensure used patterns are displayed vertically (user requested vertical layout)
        try { usedContainer.orientation = LinearLayout.VERTICAL } catch (_: Exception) {}
        usedContainer.removeAllViews()
        otherContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val seen = mutableSetOf<String>()
        fun keyOf(p: DrumPattern) = p.steps.joinToString(";") { s -> "${s.kick}:${s.snare}:${s.hiHat}" }

        val used = mutableListOf<DrumPattern>()
        if (extraPatterns.isNotEmpty()) used.addAll(extraPatterns)
        if (used.isEmpty()) used.add(DrumPattern.DEFAULT)

        used.forEach { p ->
            try {
                val k = keyOf(p)
                if (seen.contains(k)) return@forEach
                seen.add(k)
                val chip = inflater.inflate(R.layout.item_drum_pattern_chip, usedContainer, false)
                val label = chip.findViewById<TextView>(R.id.patternName)
                // For used patterns we hide the name to keep the UI compact (user requested no name shown)
                try { label.visibility = View.GONE } catch (_: Exception) { label.text = "" }
                // Populate preview strip for used patterns
                try {
                    val preview = chip.findViewById<LinearLayout>(R.id.previewContainer)
                    preview.removeAllViews()
                    p.steps.forEach { step ->
                        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val lp = LinearLayout.LayoutParams(24, LinearLayout.LayoutParams.WRAP_CONTENT); lp.setMargins(2,0,2,0); layoutParams = lp; gravity = android.view.Gravity.CENTER }
                        val kick = View(this).apply { layoutParams = LinearLayout.LayoutParams(12,12); val bg = android.graphics.drawable.GradientDrawable(); bg.cornerRadius = 6f; setBackground(bg); alpha = if (step.kick) 1.0f else 0.25f }
                        val snare = View(this).apply { layoutParams = LinearLayout.LayoutParams(12,12); val bg = android.graphics.drawable.GradientDrawable(); bg.cornerRadius = 6f; setBackground(bg); alpha = if (step.snare) 1.0f else 0.25f }
                        val hihat = View(this).apply { layoutParams = LinearLayout.LayoutParams(12,12); val bg = android.graphics.drawable.GradientDrawable(); bg.cornerRadius = 6f; setBackground(bg); alpha = if (step.hiHat) 1.0f else 0.25f }
                        // apply theme color
                        try { val tv = TypedValue(); if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) { val color = tv.data; (kick.background as? android.graphics.drawable.GradientDrawable)?.setColor(color); (snare.background as? android.graphics.drawable.GradientDrawable)?.setColor(color); (hihat.background as? android.graphics.drawable.GradientDrawable)?.setColor(color) } } catch (_: Exception) {}
                        col.addView(kick); col.addView(snare); col.addView(hihat)
                        preview.addView(col)
                    }
                } catch (_: Exception) {}
                chip.setOnClickListener {
                    currentPattern = p
                    setupDrumChips()
                    // If a preview is active, update the running preview immediately
                    try {
                        tempPreviewProgression?.let { tp ->
                            if (tp.measures.isNotEmpty()) {
                                tp.measures[0].drumPattern = currentPattern
                                if (isPreviewActive) {
                                    if (isServiceBound && playbackService != null) {
                                        try { playbackService?.updateDrumPattern(0, currentPattern) } catch (_: Exception) {}
                                    } else {
                                        // Do not call updateProgression here; instead store as pending to be applied when bound
                                        try { pendingPreviewProgression = tp; pendingPreviewLooping = true; tempPreviewProgression = tp } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                usedContainer.addView(chip)
            } catch (_: Exception) {}
        }

        DrumPattern.defaultPatterns.forEach { p ->
            try {
                val k = keyOf(p)
                //if (seen.contains(k)) return@forEach
                seen.add(k)
                val chip = inflater.inflate(R.layout.item_drum_pattern_chip, otherContainer, false)
                val label = chip.findViewById<TextView>(R.id.patternName)
                label.text = p.name
                // Populate preview for other patterns too
                try {
                    val preview = chip.findViewById<LinearLayout>(R.id.previewContainer)
                    preview.removeAllViews()
                    p.steps.forEach { step ->
                        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val lp = LinearLayout.LayoutParams(24, LinearLayout.LayoutParams.WRAP_CONTENT); lp.setMargins(2,0,2,0); layoutParams = lp; gravity = android.view.Gravity.CENTER }
                        val kick = View(this).apply { layoutParams = LinearLayout.LayoutParams(12,12); val bg = android.graphics.drawable.GradientDrawable(); bg.cornerRadius = 6f; setBackground(bg); alpha = if (step.kick) 1.0f else 0.25f }
                        val snare = View(this).apply { layoutParams = LinearLayout.LayoutParams(12,12); val bg = android.graphics.drawable.GradientDrawable(); bg.cornerRadius = 6f; setBackground(bg); alpha = if (step.snare) 1.0f else 0.25f }
                        val hihat = View(this).apply { layoutParams = LinearLayout.LayoutParams(12,12); val bg = android.graphics.drawable.GradientDrawable(); bg.cornerRadius = 6f; setBackground(bg); alpha = if (step.hiHat) 1.0f else 0.25f }
                        try { val tv = TypedValue(); if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) { val color = tv.data; (kick.background as? android.graphics.drawable.GradientDrawable)?.setColor(color); (snare.background as? android.graphics.drawable.GradientDrawable)?.setColor(color); (hihat.background as? android.graphics.drawable.GradientDrawable)?.setColor(color) } } catch (_: Exception) {}
                        col.addView(kick); col.addView(snare); col.addView(hihat)
                        preview.addView(col)
                    }
                } catch (_: Exception) {}
                chip.setOnClickListener {
                    currentPattern = p
                    setupDrumChips()
                    // If a preview is active, update the running preview immediately
                    try {
                        tempPreviewProgression?.let { tp ->
                            if (tp.measures.isNotEmpty()) {
                                tp.measures[0].drumPattern = currentPattern
                                if (isPreviewActive) {
                                    if (isServiceBound && playbackService != null) {
                                        try { playbackService?.updateDrumPattern(0, currentPattern) } catch (_: Exception) {}
                                    } else {
                                        try { pendingPreviewProgression = tp; pendingPreviewLooping = true; tempPreviewProgression = tp } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                otherContainer.addView(chip)
            } catch (_: Exception) {}
        }
    }

    /**
     * onStart()
     * Lifecycle: bindet an den PlaybackService bei Activity-Start, damit Preview-Status abgefragt werden kann.
     */
    override fun onStart() {
        super.onStart()
        try {
            val intent = Intent(this, PlaybackService::class.java)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        } catch (_: Exception) {}
    }

    /**
     * onStop()
     * Lifecycle: stoppt aktive Previews, hebt Service-Bindung auf und räumt temporäre Pending-Previews auf.
     */
    override fun onStop() {
        super.onStop()
        if (isPreviewActive) {
            try {
                if (isServiceBound && playbackService != null) playbackService?.stopPlayback() else PlaybackService.stop(this)
            } catch (e: Exception) { Log.w(TAG, "onStop: failed to stop preview: ${e.message}") }
            isPreviewActive = false
        }
        if (isServiceBound) {
            try { unbindService(serviceConnection) } catch (e: Exception) { Log.w(TAG, "onStop: unbindService failed: ${e.message}") }
            isServiceBound = false
            playbackService = null
            playbackStateJob?.cancel()
            playbackStateJob = null
        }
        pendingPreviewProgression = null
        tempPreviewProgression = null
        pendingPreviewLooping = false
    }

    /**
     * onDestroy()
     * Lifecycle: letzte Sicherheit, stoppt PlaybackService falls noch aktiv.
     */
    override fun onDestroy() {
        super.onDestroy()
        try { PlaybackService.stop(this) } catch (_: Exception) {}
    }

    /**
     * onResume()
     * Lifecycle: stellt sicher, dass der Test-Button sichtbar/aktiv ist wenn die Activity wieder sichtbar wird.
     */
    override fun onResume() {
        super.onResume()
        try {
            binding.btnTest.isEnabled = true
            binding.btnTest.isClickable = true
            binding.btnTest.isFocusable = true
            binding.btnTest.alpha = 1.0f
            binding.btnTest.visibility = View.VISIBLE
        } catch (_: Exception) {}
    }
}
