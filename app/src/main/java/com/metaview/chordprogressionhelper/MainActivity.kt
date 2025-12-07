@file:OptIn(InternalSerializationApi::class)

package com.metaview.chordprogressionhelper

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metaview.chordprogressionhelper.databinding.ActivityMainBinding
import com.metaview.chordprogressionhelper.databinding.DialogSaveProgressionBinding
import com.metaview.chordprogressionhelper.databinding.DialogStrummingPatternBinding
import com.metaview.chordprogressionhelper.model.Key
import com.metaview.chordprogressionhelper.model.Strum
import com.metaview.chordprogressionhelper.model.StrummingPattern
import com.metaview.chordprogressionhelper.service.PlaybackService
import com.metaview.chordprogressionhelper.ui.ChordAdapter
import com.metaview.chordprogressionhelper.ui.MeasureAdapter
import com.metaview.chordprogressionhelper.ui.ProgressionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ProgressionViewModel
    private lateinit var chordAdapter: ChordAdapter
    private lateinit var relatedChordAdapter: ChordAdapter
    private lateinit var borrowedChordAdapter: ChordAdapter
    private lateinit var measureAdapter: MeasureAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private var areExtraChordsExpanded = false
    private var lastScrolledMeasure = -1

    private var playbackService: PlaybackService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            observeServiceState()
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
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
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
                PlaybackService.pause(this)
            } else {
                PlaybackService.play(this, viewModel.progression)
            }
        }
        binding.stopButton.setOnClickListener { PlaybackService.stop(this) }
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
        MaterialAlertDialogBuilder(this)
            .setTitle("About Chord Progression Helper")
            .setMessage("Version 1.0\n\nDeveloped with assistance from an AI assistant.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLoadDialog() {
        val savedNames = viewModel.getSavedProgressionNames()
        if (savedNames.isEmpty()) {
            Toast.makeText(this, "No saved progressions found.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Load Progression")
            .setItems(savedNames.toTypedArray()) { dialog, which ->
                viewModel.loadProgression(savedNames[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog() {
        val savedNames = viewModel.getSavedProgressionNames()
        if (savedNames.isEmpty()) {
            Toast.makeText(this, "No saved progressions to delete.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Progression")
            .setItems(savedNames.toTypedArray()) { _, which ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Confirm Delete")
                    .setMessage("Are you sure you want to delete '${savedNames[which]}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewModel.deleteProgression(savedNames[which])
                        Toast.makeText(this, "'${savedNames[which]}' deleted.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSaveDialog() {
        val dialogBinding = DialogSaveProgressionBinding.inflate(LayoutInflater.from(this))
        val savedNames = viewModel.getSavedProgressionNames()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, savedNames)
        dialogBinding.savedProgressionsListView.adapter = adapter
        dialogBinding.savedProgressionsListView.setOnItemClickListener { _, _, position, _ ->
            dialogBinding.saveNameEditText.setText(savedNames[position])
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.saveNameEditText.text.toString()
                if (name.isBlank()) {
                    Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (savedNames.contains(name)) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Overwrite?")
                        .setMessage("A progression named '$name' already exists. Overwrite it?")
                        .setPositiveButton("Overwrite") { _, _ -> viewModel.saveNamedProgression(name); dialog.dismiss() }
                        .setNegativeButton("Cancel", null)
                        .show()
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
        val newVisibility = if (areExtraChordsExpanded) View.VISIBLE else View.GONE
        binding.relatedChordsLabel.visibility = newVisibility
        binding.relatedChordRecyclerView.visibility = newVisibility
        binding.borrowedChordsLabel.visibility = newVisibility
        binding.borrowedChordRecyclerView.visibility = newVisibility
        binding.expandRelatedChordsButton.rotation = if (areExtraChordsExpanded) 180f else 0f
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
        chordAdapter = ChordAdapter { viewModel.setSelectedChord(it) }
        relatedChordAdapter = ChordAdapter { viewModel.setSelectedChord(it) }
        borrowedChordAdapter = ChordAdapter { viewModel.setSelectedChord(it) }
        binding.chordRecyclerView.adapter = chordAdapter
        binding.relatedChordRecyclerView.adapter = relatedChordAdapter
        binding.borrowedChordRecyclerView.adapter = borrowedChordAdapter
        binding.chordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.relatedChordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.borrowedChordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        measureAdapter = MeasureAdapter(
            onChordClick = { measureIndex, eighthNoteIndex -> viewModel.addChordToMeasure(measureIndex, eighthNoteIndex) },
            onChordLongClick = { measureIndex, eighthNoteIndex -> viewModel.removeChordFromMeasure(measureIndex, eighthNoteIndex) },
            onStrummingPatternClick = { showStrummingPatternDialog(it) },
            onChordDrop = { measureIndex, eighthNoteIndex, chord -> viewModel.addChordToMeasure(measureIndex, eighthNoteIndex, chord) },
            onRemoveMeasureClick = { viewModel.removeMeasure(it) },
            onAddMeasureClick = { viewModel.addMeasure() },
            onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) }
        )
        binding.measureRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = measureAdapter
        }
    }

    private fun setupDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                if (target is MeasureAdapter.AddMeasureViewHolder) return false
                viewModel.moveMeasure(fromPosition, toPosition)
                measureAdapter.notifyItemMoved(fromPosition, toPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewModel.finalizeMeasureMove()
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
            val visibility = if (chords.isNullOrEmpty() || !areExtraChordsExpanded) View.GONE else View.VISIBLE
            binding.relatedChordsLabel.visibility = visibility
            binding.relatedChordRecyclerView.visibility = visibility
        }
        viewModel.borrowedChords.observe(this) { chords ->
            borrowedChordAdapter.submitList(chords)
            val visibility = if (chords.isNullOrEmpty() || !areExtraChordsExpanded) View.GONE else View.VISIBLE
            binding.borrowedChordsLabel.visibility = visibility
            binding.borrowedChordRecyclerView.visibility = visibility
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Start New Progression?")
            .setMessage("This will clear the current progression. Are you sure?")
            .setPositiveButton("New") { _, _ -> viewModel.confirmNewProgression() }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { viewModel.onNewProgressionConfirmationHandled() }
            .show()
    }

    @OptIn(InternalSerializationApi::class)
    private fun showStrummingPatternDialog(measureIndex: Int) {
        val currentPattern = viewModel.measures.value?.get(measureIndex)?.strummingPattern ?: StrummingPattern.DEFAULT
        val dialogBinding = DialogStrummingPatternBinding.inflate(layoutInflater)
        val strumViews = mutableListOf<TextView>()
        val currentStrums = currentPattern.strums.toMutableList()

        fun updateStrumViews() { strumViews.forEachIndexed { index, textView -> textView.text = currentStrums[index].displayName } }

        for (i in 0 until 8) {
            val textView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = currentStrums[i].displayName
                textSize = 18f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(8)
                setBackgroundResource(R.drawable.quarter_note_background)
                setOnClickListener {
                    currentStrums[i] = when (currentStrums[i]) {
                        Strum.DOWN -> Strum.UP
                        Strum.UP -> Strum.MUTE
                        Strum.MUTE -> Strum.REST
                        Strum.REST -> Strum.LETRING
                        Strum.LETRING -> Strum.DOWN
                    }
                    updateStrumViews()
                }
            }
            dialogBinding.strummingPatternEditor.addView(textView)
            strumViews.add(textView)
        }

        StrummingPattern.defaultPatterns.forEach { pattern ->
            val button = Button(this).apply {
                text = pattern.name
                setOnClickListener { currentStrums.indices.forEach { i -> currentStrums[i] = pattern.strums[i] }; updateStrumViews() }
            }
            dialogBinding.defaultPatternsLayout.addView(button)
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("OK") { _, _ -> viewModel.setStrummingPattern(measureIndex, StrummingPattern("Custom", currentStrums)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmationDialog(measureIndex: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Measure?")
            .setMessage("This measure contains chords. Are you sure you want to delete it?")
            .setPositiveButton("Delete") { _, _ -> viewModel.confirmRemoveMeasure(measureIndex) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { viewModel.onDeleteConfirmationHandled() }
            .show()
    }
}
