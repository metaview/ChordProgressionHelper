@file:OptIn(InternalSerializationApi::class)

package com.metaview.chordprogressionhelper

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.content.res.ColorStateList
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metaview.chordprogressionhelper.databinding.ActivityMainBinding
import com.metaview.chordprogressionhelper.databinding.DialogSaveProgressionBinding
import com.metaview.chordprogressionhelper.model.Key
import com.metaview.chordprogressionhelper.model.Chord
import com.metaview.chordprogressionhelper.model.ChordType
import com.metaview.chordprogressionhelper.model.StrummingPattern
import com.metaview.chordprogressionhelper.model.DrumPattern
import com.metaview.chordprogressionhelper.service.PlaybackService
import com.metaview.chordprogressionhelper.ui.ChordAdapter
import com.metaview.chordprogressionhelper.ui.MeasureAdapter
import com.metaview.chordprogressionhelper.ui.ProgressionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@OptIn(InternalSerializationApi::class)
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ProgressionViewModel
    private val TAG = "MainActivity"
    private lateinit var chordAdapter: ChordAdapter
    private lateinit var relatedChordAdapter: ChordAdapter
    private lateinit var borrowedChordAdapter: ChordAdapter
    private lateinit var measureAdapter: MeasureAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var strumPatternLauncher: ActivityResultLauncher<Intent>
    private lateinit var drumPatternLauncher: ActivityResultLauncher<Intent>
    // Fallback receiver for DrumPattern updates (sent by DrumPatternActivity before finish)
    private val drumPatternReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            try {
                if (intent == null) return
                val action = intent.action ?: return
                if (action == "com.metaview.chordprogressionhelper.ACTION_DRUM_PATTERN_UPDATED") {
                    val mIndex = intent.getIntExtra(DrumPatternActivity.EXTRA_MEASURE_INDEX, -1)
                    val json = intent.getStringExtra(DrumPatternActivity.EXTRA_DRUM_PATTERN_JSON)
                    if (mIndex >= 0 && !json.isNullOrEmpty()) {
                        try {
                            val pattern = Json.decodeFromString(com.metaview.chordprogressionhelper.model.DrumPattern.serializer(), json)
                            viewModel.setDrumPattern(mIndex, pattern)
                        } catch (e: Exception) { Log.w(TAG, "drumPatternReceiver: failed to decode pattern: ${e.message}") }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "drumPatternReceiver onReceive failed: ${e.message}") }
        }
    }

    private var areExtraChordsExpanded = false
    private var lastScrolledMeasure = -1

    private var playbackService: PlaybackService? = null
    private var isBound = false
    // If the user presses Stop while not bound, request a bind+stop via this flag
    private var pendingStopRequest: Boolean = false

    // Indicates whether the last started playback was a temporary preview spawned by the dialog
    private var isDialogPreviewActive = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            observeServiceState()
            // If a pending Stop was requested while we were unbound, execute it now
            if (pendingStopRequest) {
                try {
                    Log.i(TAG, "Processing pending stop request via bound service")
                    playbackService?.stopPlayback()
                } catch (e: Exception) { Log.w(TAG, "pendingStop stopPlayback failed: ${e.message}") }
                pendingStopRequest = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for background playback.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[ProgressionViewModel::class.java]

        // Register ActivityResult launcher to receive StrummingPatternActivity results
        strumPatternLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val mIndex = data?.getIntExtra(StrummingPatternActivity.EXTRA_MEASURE_INDEX, -1) ?: -1
                val json = data?.getStringExtra(StrummingPatternActivity.EXTRA_STRUMMING_PATTERN_JSON)
                if (mIndex >= 0 && !json.isNullOrEmpty()) {
                    try {
                        val pattern = Json.decodeFromString(StrummingPattern.serializer(), json)
                        viewModel.setStrummingPattern(mIndex, pattern)
                    } catch (e: Exception) { Log.w(TAG, "Failed to decode StrummingPattern from activity result: ${e.message}") }
                }
            }
        }

        // Register launcher to receive DrumPatternActivity results
        drumPatternLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val mIndex = data?.getIntExtra(DrumPatternActivity.EXTRA_MEASURE_INDEX, -1) ?: -1
                val json = data?.getStringExtra(DrumPatternActivity.EXTRA_DRUM_PATTERN_JSON)
                if (mIndex >= 0 && !json.isNullOrEmpty()) {
                    try {
                        val pattern = Json.decodeFromString(DrumPattern.serializer(), json)
                        viewModel.setDrumPattern(mIndex, pattern)
                    } catch (e: Exception) { Log.w(TAG, "Failed to decode DrumPattern from activity result: ${e.message}") }
                }
            }
        }

        setupControls()
        setupRecyclerViews()
        setupDragAndDrop()
        observeViewModel()
        askForNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
        try {
            val filter = IntentFilter("com.metaview.chordprogressionhelper.ACTION_DRUM_PATTERN_UPDATED")
            registerReceiver(drumPatternReceiver, filter)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        try { unregisterReceiver(drumPatternReceiver) } catch (_: Exception) {}
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        val keyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Key.entries.map { it.displayName })
        keyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.keySpinner.adapter = keyAdapter
        binding.keySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedKey = Key.entries[position]
                if (viewModel.key.value != selectedKey) { viewModel.setKey(selectedKey) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.bpmEditText.setText(viewModel.tempo.value?.toString())
        binding.bpmEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { viewModel.setTempo(it) } }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.bpmUpButton.setOnTouchListener(createTempoButtonTouchListener(true))
        binding.bpmUpButton.setOnClickListener(createTempoButtonClickListener(true))
        binding.bpmDownButton.setOnTouchListener(createTempoButtonTouchListener(false))
        binding.bpmDownButton.setOnClickListener(createTempoButtonClickListener(false))

        binding.menuButton.setOnClickListener { showMenu(it) }
        binding.playPauseButton.setOnClickListener {
             // Optimistically animate immediately for snappy UX
             val willPlay = playbackService?.isPlaying?.value != true
             animatePlayPause(willPlay)
             if (playbackService?.isPlaying?.value == true) {
                // If a dialog preview is running, the user's 'play' intent should stop the preview and start the main progression
                if (isDialogPreviewActive) {
                    try { PlaybackService.stop(this) } catch (_: Exception) {}
                    isDialogPreviewActive = false
                    PlaybackService.play(this, viewModel.progression)
                } else {
                    PlaybackService.pause(this)
                }
            } else {
                // No playback active -> start main progression
                PlaybackService.play(this, viewModel.progression)
            }
        }
        binding.stopButton.setOnClickListener {
            Log.i(TAG, "Stop button pressed; isBound=$isBound")
            try {
                if (isBound && playbackService != null) {
                    Log.i(TAG, "Stopping via bound service.stopPlayback()")
                    try { playbackService?.stopPlayback() } catch (e: Exception) { Log.w(TAG, "Bound stopPlayback failed: ${e.message}") }
                } else {
                    Log.i(TAG, "Not bound: requesting bind+stop and invoking companion stop() as fallback")
                    // Remember we want to stop after binding completes
                    pendingStopRequest = true
                    try { val bindIntent = Intent(this, PlaybackService::class.java); bindService(bindIntent, connection, BIND_AUTO_CREATE) } catch (e: Exception) { Log.w(TAG, "bindService for pending stop failed: ${e.message}") }
                    // Also attempt companion stop immediately as a best-effort fallback
                    try { PlaybackService.stop(this) } catch (e: Exception) { Log.w(TAG, "PlaybackService.stop(context) failed: ${e.message}") }
                }
            } catch (e: Exception) { Log.w(TAG, "Stop button handler failed: ${e.message}") }

            // Also send explicit ACTION_STOP as a last-resort fallback
            try {
                val stopIntent = Intent(this, PlaybackService::class.java).apply { action = PlaybackService.ACTION_STOP }
                startService(stopIntent)
            } catch (e: Exception) { Log.w(TAG, "Failed to start ACTION_STOP intent: ${e.message}") }

            isDialogPreviewActive = false
        }
        binding.repeatButton.setOnClickListener { viewModel.onRepeatToggle(!(viewModel.isLooping.value ?: false)) }
        binding.expandRelatedChordsButton.setOnClickListener { toggleExtraChordsVisibility() }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item: MenuItem ->
             when (item.itemId) {
                R.id.action_new -> { viewModel.requestNewProgression(); true }
                R.id.action_load -> { showLoadDialog(); true }
                R.id.action_save -> { showSaveDialog(); true }
                R.id.action_delete -> { showDeleteDialog(); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.action_about -> { showAboutDialog(); true }
                else -> false
             }
         }
         popup.show()
    }

    private fun showAboutDialog() {
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle("About Chord Progression Helper")
            .setMessage("Version 1.0\n\nDeveloped with assistance from an AI assistant.")
            .setPositiveButton("OK", null)
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    private fun showLoadDialog() {
        val savedNames = viewModel.getSavedProgressionNames()
        if (savedNames.isEmpty()) {
            Toast.makeText(this, "No saved progressions found.", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle("Load Progression")
            .setItems(savedNames.toTypedArray()) { d, which ->
                viewModel.loadProgression(savedNames[which])
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    private fun showDeleteDialog() {
        val savedNames = viewModel.getSavedProgressionNames()
        if (savedNames.isEmpty()) {
            Toast.makeText(this, "No saved progressions to delete.", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle("Delete Progression")
            .setItems(savedNames.toTypedArray()) { _, which ->
                val confirm = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
                    .setTitle("Confirm Delete")
                    .setMessage("Are you sure you want to delete '${savedNames[which]}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewModel.deleteProgression(savedNames[which])
                        Toast.makeText(this, "'${savedNames[which]}' deleted.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                confirm.setOnShowListener { styleDialogButtons(confirm) }
                confirm.show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    private fun showSaveDialog() {
        val dialogBinding = DialogSaveProgressionBinding.inflate(LayoutInflater.from(this))
        val savedNames = viewModel.getSavedProgressionNames()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, savedNames)
        dialogBinding.savedProgressionsListView.adapter = adapter
        dialogBinding.savedProgressionsListView.setOnItemClickListener { _, _, position, _ ->
            dialogBinding.saveNameEditText.setText(savedNames[position])
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            // Ensure theme-styled buttons are applied
            styleDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                 val name = dialogBinding.saveNameEditText.text.toString()
                 if (name.isBlank()) {
                     Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
                     return@setOnClickListener
                 }
                 if (savedNames.contains(name)) {
                    val ov = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
                        .setTitle("Overwrite?")
                        .setMessage("A progression named '$name' already exists. Overwrite it?")
                        .setPositiveButton("Overwrite") { _, _ -> viewModel.saveNamedProgression(name); dialog.dismiss() }
                        .setNegativeButton("Cancel", null)
                        .create()
                    ov.setOnShowListener { styleDialogButtons(ov) }
                    ov.show()
                 } else {
                     viewModel.saveNamedProgression(name)
                     dialog.dismiss()
                 }
             }
         }
         dialog.show()
    }

    private fun toggleExtraChordsVisibility() {
        areExtraChordsExpanded = !areExtraChordsExpanded
        // Use an explicit AutoTransition (ChangeBounds) so ConstraintLayout re-evaluates constraints and children move smoothly
        try {
            val t = androidx.transition.AutoTransition()
            t.duration = 180
            androidx.transition.TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup, t)
        } catch (_: Exception) { /* best-effort, ignore if transitions not available */ }

        val newVisibility = if (areExtraChordsExpanded) View.VISIBLE else View.GONE
        binding.relatedChordsLabel.visibility = newVisibility
        binding.relatedChordContainer.visibility = newVisibility
        binding.relatedChordRecyclerView.visibility = newVisibility
        binding.borrowedChordsLabel.visibility = newVisibility
        binding.borrowedChordContainer.visibility = newVisibility
        binding.borrowedChordRecyclerView.visibility = newVisibility
        binding.expandRelatedChordsButton.rotation = if (areExtraChordsExpanded) 180f else 0f

        // Force inner recyclers to layout and update fade overlays (the lists are never empty)
        binding.relatedChordRecyclerView.post {
            binding.relatedChordRecyclerView.requestLayout()
            updateRecyclerFade(binding.relatedChordRecyclerView, binding.relatedFadeLeft, binding.relatedFadeRight)
        }
        binding.borrowedChordRecyclerView.post {
            binding.borrowedChordRecyclerView.requestLayout()
            updateRecyclerFade(binding.borrowedChordRecyclerView, binding.borrowedFadeLeft, binding.borrowedFadeRight)
        }

        // Force a relayout of the measure list (and parent) after visibility change so it snaps into place.
        binding.measureRecyclerView.post {
            // multiple invalidation/requestLayout calls help on OEM-modified Android versions
            binding.measureRecyclerView.requestLayout()
            binding.measureRecyclerView.invalidate()
            (binding.measureRecyclerView.parent as? View)?.requestLayout()
            (binding.measureRecyclerView.parent as? View)?.invalidate()
            binding.root.requestLayout()
            binding.root.invalidate()

            // If we just collapsed extra chords make sure the measure list scrolls up so content isn't obscured.
            if (!areExtraChordsExpanded) {
                try {
                    val lm = binding.measureRecyclerView.layoutManager
                    if (lm is LinearLayoutManager) {
                        // Ensure top of list aligns with top of RecyclerView (no partial offset)
                        lm.scrollToPositionWithOffset(0, 0)
                    } else {
                        binding.measureRecyclerView.scrollToPosition(0)
                    }
                } catch (_: Exception) {
                    try { binding.measureRecyclerView.scrollToPosition(0) } catch (_: Exception) {}
                }
            }
        }
     }

    private fun createTempoButtonClickListener(increment: Boolean): View.OnClickListener {
        return View.OnClickListener {
            if (increment) {
                viewModel.incrementTempo()
            } else {
                viewModel.decrementTempo()
            }
        }
    }

    private fun createTempoButtonTouchListener(increment: Boolean): View.OnTouchListener {
        var job: Job? = null
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    job?.cancel()
                    view.performClick()
                    job = lifecycleScope.launch {
                        delay(500)
                        while (job?.isActive == true) {
                            if (increment) {
                                viewModel.incrementTempo()
                            } else {
                                viewModel.decrementTempo()
                            }
                            delay(100)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { job?.cancel(); true }
                else -> false
            }
        }
    }

    private fun setupRecyclerViews() {
        chordAdapter = ChordAdapter({ viewModel.setSelectedChord(it) }) { chord ->
            // Convert chord to power chord and select it
            try {
                val powerChord = Chord(chord.root, ChordType.POWER, chord.scaleDegreeName)
                viewModel.setSelectedChord(powerChord)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to make power chord: ${e.message}")
            }
        }
        relatedChordAdapter = ChordAdapter({ viewModel.setSelectedChord(it) }) { chord ->
            val powerChord = Chord(chord.root, ChordType.POWER, chord.scaleDegreeName)
            viewModel.setSelectedChord(powerChord)
        }
        borrowedChordAdapter = ChordAdapter({ viewModel.setSelectedChord(it) }) { chord ->
            val powerChord = Chord(chord.root, ChordType.POWER, chord.scaleDegreeName)
            viewModel.setSelectedChord(powerChord)
        }
        binding.chordRecyclerView.adapter = chordAdapter
        binding.relatedChordRecyclerView.adapter = relatedChordAdapter
        binding.borrowedChordRecyclerView.adapter = borrowedChordAdapter
        binding.chordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.relatedChordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.borrowedChordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        measureAdapter = MeasureAdapter(
            onChordClick = { measureIndex, eighthNoteIndex -> viewModel.addChordToMeasure(measureIndex, eighthNoteIndex) },
            onChordLongClick = { measureIndex, eighthNoteIndex -> viewModel.removeChordFromMeasure(measureIndex, eighthNoteIndex) },
            onStrummingPatternClick = { index ->
                val intent = Intent(this, StrummingPatternActivity::class.java)
                intent.putExtra(StrummingPatternActivity.EXTRA_MEASURE_INDEX, index)
                // Provide tonic chord and context for preview
                val tonic = viewModel.progression.getScaleDegreeChords().firstOrNull()
                try {
                    if (tonic != null) {
                        val tonicJson = Json.encodeToString(Chord.serializer(), tonic)
                        intent.putExtra(StrummingPatternActivity.EXTRA_TONIC_CHORD_JSON, tonicJson)
                    }
                } catch (_: Exception) {}
                // Pass the current measure's strumming pattern so the editor shows it
                try {
                    val measurePattern = viewModel.progression.measures.getOrNull(index)?.strummingPattern
                    if (measurePattern != null) {
                        val patternJson = Json.encodeToString(StrummingPattern.serializer(), measurePattern)
                        intent.putExtra(StrummingPatternActivity.EXTRA_STRUMMING_PATTERN_JSON, patternJson)
                    }
                } catch (_: Exception) {}
                // Also collect all unique strumming patterns used in the progression and pass them to the editor
                try {
                    val seen = mutableSetOf<String>()
                    val patterns = mutableListOf<StrummingPattern>()
                    viewModel.progression.measures.forEach { m ->
                        try {
                            val p = m.strummingPattern
                            val key = p.strums.joinToString(",") { it.name }
                            if (!seen.contains(key)) {
                                seen.add(key)
                                patterns.add(p)
                            }
                        } catch (_: Exception) {}
                    }
                    if (patterns.isNotEmpty()) {
                        val arrJson = Json.encodeToString(ListSerializer(StrummingPattern.serializer()), patterns)
                        intent.putExtra(StrummingPatternActivity.EXTRA_ALL_PATTERNS_JSON, arrJson)
                    }
                } catch (_: Exception) {}
                intent.putExtra("extra_key", viewModel.key.value?.name ?: viewModel.progression.key.name)
                intent.putExtra("extra_mode", viewModel.progression.mode.name)
                intent.putExtra("extra_tempo", viewModel.tempo.value ?: viewModel.progression.tempo)
                strumPatternLauncher.launch(intent)
            },
            onDrumPatternClick = { index ->
                 val intent = Intent(this, DrumPatternActivity::class.java)
                 intent.putExtra(DrumPatternActivity.EXTRA_MEASURE_INDEX, index)
                 // Pass current drum pattern to editor
                 try {
                     val dp = viewModel.progression.measures.getOrNull(index)?.drumPattern
                     if (dp != null) {
                         intent.putExtra(DrumPatternActivity.EXTRA_DRUM_PATTERN_JSON, Json.encodeToString(DrumPattern.serializer(), dp))
                     }
                 } catch (_: Exception) {}
                // Also collect all unique drum patterns used in the progression and pass them to the editor
                try {
                    val seen = mutableSetOf<String>()
                    val patterns = mutableListOf<DrumPattern>()
                    viewModel.progression.measures.forEach { m ->
                        try {
                            val p = m.drumPattern
                            val key = p.steps.joinToString(";") { s -> "${s.kick}:${s.snare}:${s.hiHat}" }
                            if (!seen.contains(key)) { seen.add(key); patterns.add(p) }
                        } catch (_: Exception) {}
                    }
                    if (patterns.isNotEmpty()) {
                        val arrJson = Json.encodeToString(ListSerializer(DrumPattern.serializer()), patterns)
                        intent.putExtra(DrumPatternActivity.EXTRA_ALL_PATTERNS_JSON, arrJson)
                    }
                } catch (_: Exception) {}
                 drumPatternLauncher.launch(intent)
            },
            onChordDrop = { measureIndex, eighthNoteIndex, chord -> viewModel.addChordToMeasure(measureIndex, eighthNoteIndex, chord) },
            onRemoveMeasureClick = { viewModel.removeMeasure(it) },
            onAddMeasureClick = { viewModel.addMeasure() },
            onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) }
        )
        binding.measureRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = measureAdapter
        }

        // Setup fade overlays for horizontal chord scrollers
        setupRecyclerFadeOverlays(binding.chordRecyclerView, binding.chordFadeLeft, binding.chordFadeRight)
        setupRecyclerFadeOverlays(binding.relatedChordRecyclerView, binding.relatedFadeLeft, binding.relatedFadeRight)
        setupRecyclerFadeOverlays(binding.borrowedChordRecyclerView, binding.borrowedFadeLeft, binding.borrowedFadeRight)
    }

    // Update fade visibility based on RecyclerView scroll state
    private fun updateRecyclerFade(rv: RecyclerView, left: View, right: View) {
        rv.post {
            val range = rv.computeHorizontalScrollRange()
            val extent = rv.computeHorizontalScrollExtent()
            val offset = rv.computeHorizontalScrollOffset()
            val canScroll = range > extent
            left.visibility = if (canScroll && offset > 0) View.VISIBLE else View.GONE
            right.visibility = if (canScroll && (offset + extent) < range) View.VISIBLE else View.GONE
        }
    }

    private fun setupRecyclerFadeOverlays(rv: RecyclerView, left: View, right: View) {
        // Apply themed fade drawables so the fade is visible under dark/light themes
        val baseColor = resolveSurfaceColor()
        applyFadeToView(left, baseColor, isLeft = true)
        applyFadeToView(right, baseColor, isLeft = false)
        // Make overlays visible initially; updateRecyclerFade will toggle them if needed
        left.visibility = View.VISIBLE
        right.visibility = View.VISIBLE

        // Match overlay height to the recycler's height so overlays don't grow to full screen
        rv.post {
            val h = rv.height
            if (h > 0) {
                left.layoutParams = left.layoutParams.apply { height = h }
                right.layoutParams = right.layoutParams.apply { height = h }
                left.requestLayout()
                right.requestLayout()
            } else {
                // Fallback: set once after layout
                rv.viewTreeObserver.addOnGlobalLayoutListener {
                    val hh = rv.height
                    if (hh > 0) {
                        left.layoutParams = left.layoutParams.apply { height = hh }
                        right.layoutParams = right.layoutParams.apply { height = hh }
                        left.requestLayout()
                        right.requestLayout()
                    }
                }
            }
        }

         // Initial update
         updateRecyclerFade(rv, left, right)

         // Scroll listener
         rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
             override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                 updateRecyclerFade(rv, left, right)
             }
         })

         // Adapter changes
         rv.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
             override fun onChanged() { updateRecyclerFade(rv, left, right) }
             override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateRecyclerFade(rv, left, right) }
             override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateRecyclerFade(rv, left, right) }
         })

         // Also update when layout happens
         rv.viewTreeObserver.addOnGlobalLayoutListener { updateRecyclerFade(rv, left, right) }
    }

    private fun resolveSurfaceColor(): Int {
        val typed = TypedValue()
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typed, true)) {
            typed.data
        } else {
            // fallback to white/black based on UI
            Color.WHITE
        }
    }

    private fun applyFadeToView(view: View, baseColor: Int, isLeft: Boolean) {
        // Use the surface/background color and vary only its alpha so the fade keeps the same color
        // Left: surface (opaque) -> surface (transparent)
        // Right: surface (transparent) -> surface (opaque)
        val surfaceOpaque = applyAlpha(baseColor, 1.0f)
        val surfaceTransparent = applyAlpha(baseColor, 0f)
        val colors = if (isLeft) intArrayOf(surfaceOpaque, surfaceTransparent) else intArrayOf(surfaceTransparent, surfaceOpaque)
        val orientation = GradientDrawable.Orientation.LEFT_RIGHT
        val gd = GradientDrawable(orientation, colors)
        gd.cornerRadius = 0f
        view.background = gd
        view.isClickable = false
        view.isFocusable = false
        view.bringToFront()
        view.elevation = 8f
    }

    // Ensure dialog buttons are filled with primary color and white text for readability
    private fun styleDialogButtons(alert: AlertDialog) {
        try {
            val positive = alert.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = alert.getButton(AlertDialog.BUTTON_NEGATIVE)
            val neutral = alert.getButton(AlertDialog.BUTTON_NEUTRAL)
            val tv = TypedValue()
            val primaryColor = if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)) tv.data else resolveSurfaceColor()
            val onPrimary = if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, tv, true)) tv.data else Color.WHITE
            // Create a rounded GradientDrawable to force the button background color across OEMs
            val radiusPx = (8f * resources.displayMetrics.density)
            fun applyButtonStyle(button: android.widget.Button?) {
                button?.let {
                    val gd = GradientDrawable().apply { cornerRadius = radiusPx; setColor(primaryColor) }
                    it.background = gd
                    it.setTextColor(onPrimary)
                    it.isAllCaps = false
                    it.setPadding(24, 12, 24, 12)
                }
            }
            applyButtonStyle(positive)
            applyButtonStyle(negative)
            applyButtonStyle(neutral)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to style dialog buttons", e)
        }
    }

    private fun showDeleteConfirmationDialog(measureIndex: Int) {
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle("Delete Measure?")
            .setMessage("This measure contains chords. Are you sure you want to delete it?")
            .setPositiveButton("Delete") { _, _ -> viewModel.confirmRemoveMeasure(measureIndex) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { viewModel.onDeleteConfirmationHandled() }
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    // Helper to apply an alpha multiplier to a color (0.0 - 1.0)
    private fun applyAlpha(color: Int, alpha: Float): Int {
        val originalA = Color.alpha(color)
        val newA = (originalA * alpha).toInt().coerceIn(0, 255)
        return (newA shl 24) or (color and 0x00FFFFFF)
    }

    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                if (target is MeasureAdapter.AddMeasureViewHolder) return false
                Log.d(TAG, "onMove: from=$fromPosition to=$toPosition")
                // Update underlying model and let ListAdapter/AsyncListDiffer handle the UI diff
                viewModel.moveMeasure(fromPosition, toPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                // Provide immediate visual feedback for the dragged view
                viewHolder?.itemView?.let { v ->
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        v.alpha = 0.95f
                        v.elevation = 24f
                        // Temporarily disable item animator change animations while dragging
                        binding.measureRecyclerView.itemAnimator?.let { if (it is androidx.recyclerview.widget.SimpleItemAnimator) it.supportsChangeAnimations = false }
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Reset visual state
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.elevation = 0f
                Log.d(TAG, "clearView: finalizing move and saving session")
                viewModel.finalizeMeasureMove()
                // Re-enable change animations after the move
                binding.measureRecyclerView.itemAnimator?.let { if (it is androidx.recyclerview.widget.SimpleItemAnimator) it.supportsChangeAnimations = true }
            }

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                if (viewHolder is MeasureAdapter.AddMeasureViewHolder) return 0
                return super.getDragDirs(recyclerView, viewHolder)
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.measureRecyclerView)
    }

    @OptIn(InternalSerializationApi::class)
    private fun observeViewModel() {
        viewModel.key.observe(this) { key ->
            if (binding.keySpinner.selectedItem.toString() != key.displayName) {
                binding.keySpinner.setSelection(Key.entries.indexOf(key))
            }
        }
        viewModel.scaleDegreeChords.observe(this) { chordAdapter.submitList(it) }
        viewModel.primaryChords.observe(this) { chordAdapter.setPrimaryChords(it) }
        viewModel.relatedChords.observe(this) { chords ->
            relatedChordAdapter.submitList(chords)
            val visibility = if (!areExtraChordsExpanded) View.GONE else View.VISIBLE
            binding.relatedChordsLabel.visibility = visibility
            binding.relatedChordContainer.visibility = visibility
            binding.relatedChordRecyclerView.visibility = visibility
            updateRecyclerFade(binding.relatedChordRecyclerView, binding.relatedFadeLeft, binding.relatedFadeRight)
        }
        viewModel.borrowedChords.observe(this) { chords ->
            borrowedChordAdapter.submitList(chords)
            val visibility = if (!areExtraChordsExpanded) View.GONE else View.VISIBLE
            binding.borrowedChordsLabel.visibility = visibility
            binding.borrowedChordContainer.visibility = visibility
            binding.borrowedChordRecyclerView.visibility = visibility
            updateRecyclerFade(binding.borrowedChordRecyclerView, binding.borrowedFadeLeft, binding.borrowedFadeRight)
        }
        viewModel.measures.observe(this) { measures ->
            val items = measures.map { MeasureAdapter.DisplayableItem.MeasureItem(it) } + MeasureAdapter.DisplayableItem.AddMeasureItem
            measureAdapter.submitList(items)
        }
        viewModel.selectedChord.observe(this) { chord ->
            chordAdapter.setSelectedChord(chord)
            relatedChordAdapter.setSelectedChord(chord)
            borrowedChordAdapter.setSelectedChord(chord)
        }
        viewModel.targetChord.observe(this) { chord ->
            chordAdapter.setTargetChord(chord)
            relatedChordAdapter.setTargetChord(chord)
            borrowedChordAdapter.setTargetChord(chord)
        }
        viewModel.suggestedChord.observe(this) { chord ->
            chordAdapter.setSuggestedChord(chord)
            relatedChordAdapter.setSuggestedChord(chord)
            borrowedChordAdapter.setSuggestedChord(chord)
        }
        viewModel.isLooping.observe(this) { isToggled ->
            val typedValue = TypedValue()
            if (isToggled) {
                theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
                binding.repeatButton.backgroundTintList = ColorStateList.valueOf(typedValue.data)
                theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, typedValue, true)
                binding.repeatButton.setColorFilter(typedValue.data)
                binding.repeatButton.alpha = 1.0f
            } else {
                // Use surface color for untoggled state
                theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
                binding.repeatButton.backgroundTintList = ColorStateList.valueOf(typedValue.data)
                binding.repeatButton.clearColorFilter()
                binding.repeatButton.alpha = 0.9f
            }
        }
        viewModel.tempo.observe(this) { newTempo ->
            if (binding.bpmEditText.text.toString() != newTempo.toString()) {
                binding.bpmEditText.setText(newTempo.toString())
            }
            // Apply tempo to playback service immediately so users hear changes while adjusting BPM
            playbackService?.setTempo(newTempo)
        }
        viewModel.showDeleteConfirmation.observe(this) { it?.let { showDeleteConfirmationDialog(it) } }
        viewModel.showNewProgressionConfirmation.observe(this) { if (it == true) showNewProgressionConfirmationDialog() }
    }

    // Small animation: scale button and crossfade icon when play/pause toggles
    private fun animatePlayPause(isPlaying: Boolean) {
        val button = binding.playPauseButton
        val newIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
         // Scale out, set icon, scale in
         button.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
             button.setImageResource(newIcon)
             button.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
         }.start()
     }

    @OptIn(InternalSerializationApi::class)
    private fun observeServiceState() {
        lifecycleScope.launch {
            playbackService?.isPlaying?.collectLatest { isPlaying ->
                // Animate icon change
                animatePlayPause(isPlaying)
                 // Stop button is always visible; disable (greyed) when not playing
                 binding.stopButton.isEnabled = isPlaying
                 binding.stopButton.alpha = if (isPlaying) 1.0f else 0.4f
                 // If playback stopped, clear any dialog preview flag so UI state stays consistent
                 if (!isPlaying) {
                     isDialogPreviewActive = false
                 }
              }
          }
        lifecycleScope.launch {
            playbackService?.currentPlaybackPosition?.collectLatest { position ->
                measureAdapter.setPlaybackPosition(position)
                position?.let { (measureIndex, _) ->
                    if (measureIndex >= 0 && measureIndex != lastScrolledMeasure && measureIndex < measureAdapter.itemCount) {
                        binding.measureRecyclerView.smoothScrollToPosition(measureIndex)
                        lastScrolledMeasure = measureIndex
                    }
                } ?: run { lastScrolledMeasure = -1 }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun showNewProgressionConfirmationDialog() {
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle("Start New Progression?")
            .setMessage("This will clear the current progression. Are you sure?")
            .setPositiveButton("New") { _, _ -> viewModel.confirmNewProgression() }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { viewModel.onNewProgressionConfirmationHandled() }
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }
}
