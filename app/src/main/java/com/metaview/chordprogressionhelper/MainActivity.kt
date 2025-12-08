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
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.LinearLayout
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
    private val TAG = "MainActivity"
    private lateinit var chordAdapter: ChordAdapter
    private lateinit var relatedChordAdapter: ChordAdapter
    private lateinit var borrowedChordAdapter: ChordAdapter
    private lateinit var measureAdapter: MeasureAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private var areExtraChordsExpanded = false
    private var lastScrolledMeasure = -1

    private var playbackService: PlaybackService? = null
    private var isBound = false
    // Indicates whether the last started playback was a temporary preview spawned by the dialog
    private var isDialogPreviewActive = false

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
        binding.stopButton.setOnClickListener { PlaybackService.stop(this); isDialogPreviewActive = false }
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
        chordAdapter = ChordAdapter({ viewModel.setSelectedChord(it) }) { chord ->
            // Convert chord to power chord and select it
            try {
                val powerChord = com.metaview.chordprogressionhelper.model.Chord(chord.root, com.metaview.chordprogressionhelper.model.ChordType.POWER, chord.scaleDegreeName)
                viewModel.setSelectedChord(powerChord)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to make power chord: ${e.message}")
            }
        }
        relatedChordAdapter = ChordAdapter({ viewModel.setSelectedChord(it) }) { chord ->
            val powerChord = com.metaview.chordprogressionhelper.model.Chord(chord.root, com.metaview.chordprogressionhelper.model.ChordType.POWER, chord.scaleDegreeName)
            viewModel.setSelectedChord(powerChord)
        }
        borrowedChordAdapter = ChordAdapter({ viewModel.setSelectedChord(it) }) { chord ->
            val powerChord = com.metaview.chordprogressionhelper.model.Chord(chord.root, com.metaview.chordprogressionhelper.model.ChordType.POWER, chord.scaleDegreeName)
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
            val tv = TypedValue()
            val primaryColor = if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)) tv.data else resolveSurfaceColor()
            val onPrimary = if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, tv, true)) tv.data else Color.WHITE
            // Create a rounded GradientDrawable to force the button background color across OEMs
            val radiusPx = (8f * resources.displayMetrics.density)
            val gd = GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(primaryColor)
            }
            positive?.let {
                // Clone drawable for each button to avoid shared state
                it.background = gd.mutate().constantState?.newDrawable()?.mutate() ?: gd
                it.setTextColor(onPrimary)
                it.isAllCaps = false
                // Ensure padding is reasonable
                it.setPadding(24, 12, 24, 12)
            }
            negative?.let {
                val gd2 = GradientDrawable().apply { cornerRadius = radiusPx; setColor(primaryColor) }
                it.background = gd2
                it.setTextColor(onPrimary)
                it.isAllCaps = false
                it.setPadding(24, 12, 24, 12)
            }
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

    @OptIn(InternalSerializationApi::class)
    private fun showStrummingPatternDialog(measureIndex: Int) {
        val currentPattern = viewModel.measures.value?.get(measureIndex)?.strummingPattern ?: StrummingPattern.DEFAULT
        val dialogBinding = DialogStrummingPatternBinding.inflate(layoutInflater)
        // Dialog-specific fades: blend into the dialog background (dark gray) and hide at scroller edges
        Log.d(TAG, "showStrummingPatternDialog($measureIndex) called")
        try {
            val fadeLeft = dialogBinding.root.findViewById<View>(R.id.fadeLeft)
            val fadeRight = dialogBinding.root.findViewById<View>(R.id.fadeRight)
            val scroll = dialogBinding.strumEditorScroll

            // We'll determine the actual dialog background color later (after the dialog is shown)
            // For now keep references to the scroll/view and let the onShowListener set the gradients.
            fadeLeft.visibility = View.GONE
            fadeRight.visibility = View.GONE

            // Ensure overlays only cover the height of the HorizontalScrollView and update their visibility based on scroll
            fun updateDialogFades() {
                // Use canScrollHorizontally which is more reliable across platform versions
                val canScrollLeft = scroll.canScrollHorizontally(-1)
                val canScrollRight = scroll.canScrollHorizontally(1)
                fadeLeft.visibility = if (canScrollLeft) View.VISIBLE else View.GONE
                fadeRight.visibility = if (canScrollRight) View.VISIBLE else View.GONE
            }

            // Set heights to match the scroll view
            scroll.viewTreeObserver.addOnGlobalLayoutListener {
                val h = scroll.height
                if (h > 0) {
                    fadeLeft.layoutParams = fadeLeft.layoutParams.apply { height = h }
                    fadeRight.layoutParams = fadeRight.layoutParams.apply { height = h }
                    fadeLeft.requestLayout()
                    fadeRight.requestLayout()
                    fadeLeft.bringToFront()
                    fadeRight.bringToFront()
                    updateDialogFades()
                }
            }

            // Scroll listener - modern devices
            try {
                scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateDialogFades() }
            } catch (_: Exception) {
                // fallback for older devices
                scroll.viewTreeObserver.addOnScrollChangedListener { updateDialogFades() }
            }

            // Ensure initial visibility is correct after content added
            scroll.post { updateDialogFades() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup dialog fades", e)
        }

        val strumViews = mutableListOf<View>()
        val currentStrums = currentPattern.strums.toMutableList()

        fun updateStrumViews() {
            strumViews.forEachIndexed { index, view ->
                val strum = currentStrums[index]
                val iconView = view.findViewById<ImageView?>(R.id.strumChipIcon)
                val (drawableId, desc) = when (strum) {
                    Strum.DOWN -> Pair(R.drawable.ic_strum_down, "D")
                    Strum.UP -> Pair(R.drawable.ic_strum_up, "U")
                    Strum.MUTE -> Pair(R.drawable.ic_strum_mute, "M")
                    Strum.LETRING -> Pair(R.drawable.ic_strum_letring, "L")
                    Strum.REST -> Pair(R.drawable.ic_strum_rest, "-")
                }
                iconView?.setImageResource(drawableId)
                view.contentDescription = getString(R.string.strum_content_description, desc, index + 1)
            }
        }

        for (i in 0 until 8) {
             val chip = layoutInflater.inflate(R.layout.item_strum_chip, dialogBinding.strummingPatternEditor, false)
             // Scale the chip to pattern icon size
             val size = resources.getDimensionPixelSize(R.dimen.pattern_icon_size)
             chip.layoutParams = LinearLayout.LayoutParams(size, size, 0f).apply { setMargins(4, 4, 4, 4) }
             // ImageView will be updated in updateStrumViews(); no local reference required
             chip.setOnClickListener {
                 currentStrums[i] = when (currentStrums[i]) {
                     Strum.DOWN -> Strum.UP
                     Strum.UP -> Strum.MUTE
                     Strum.MUTE -> Strum.REST
                     Strum.REST -> Strum.LETRING
                     Strum.LETRING -> Strum.DOWN
                 }
                 updateStrumViews()
                 // Apply immediately to ViewModel (so UI list shows 'Custom')
                 // but do not persist until OK is pressed; still send live preview to PlaybackService
                 try {
                     // Create a transient pattern object for live preview
                     val livePattern = com.metaview.chordprogressionhelper.model.StrummingPattern("Custom", currentStrums.toList())
                     playbackService?.updateStrummingPattern(measureIndex, livePattern)
                 } catch (e: Exception) {
                     Log.w(TAG, "Failed to send live pattern update to service: ${e.message}")
                 }
             }
             dialogBinding.strummingPatternEditor.addView(chip)
             strumViews.add(chip)
         }

        // Show default patterns as a vertical list of icon-based items (one row per pattern)
        dialogBinding.defaultPatternsLayout.removeAllViews()
        // Ensure vertical orientation for the defaults container
        try {
            dialogBinding.defaultPatternsLayout.orientation = LinearLayout.VERTICAL
        } catch (_: Exception) { /* fallback if layout type differs */ }

        // Helper to map a Strum to drawable id
        fun strumToDrawable(s: Strum): Int = when (s) {
            Strum.DOWN -> R.drawable.ic_strum_down
            Strum.UP -> R.drawable.ic_strum_up
            Strum.MUTE -> R.drawable.ic_strum_mute
            Strum.LETRING -> R.drawable.ic_strum_letring
            Strum.REST -> R.drawable.ic_strum_rest
        }

        val itemPadding = (2 * resources.displayMetrics.density).toInt() // 2dp inner padding (tighter)
        val iconSize = (20 * resources.displayMetrics.density).toInt() // 20dp icons
        val rowMargin = (1 * resources.displayMetrics.density).toInt() // 1dp row margin (even tighter)
        val iconMargin = (0 * resources.displayMetrics.density).toInt() // 0dp between icons (no gap)

        StrummingPattern.defaultPatterns.forEach { pattern ->
            // container row: horizontal, fills width
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(rowMargin, rowMargin, rowMargin, rowMargin)
                }
                setPadding(itemPadding, itemPadding / 2, itemPadding, itemPadding / 2)
                isClickable = true
                isFocusable = true
                // ripple background from theme if available
                val tv = TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                    foreground = ContextCompat.getDrawable(context, tv.resourceId)
                }
            }

            // Add icon sequence for this pattern
            pattern.strums.forEach { s ->
                val iv = ImageView(this).apply {
                    setImageResource(strumToDrawable(s))
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { setMargins(iconMargin, 0, iconMargin, 0) }
                    // make icons slightly tinted to onSurface color
                    try {
                        val tv = TypedValue()
                        if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) {
                            setColorFilter(tv.data)
                        }
                    } catch (_: Exception) {}
                }
                row.addView(iv)
            }

            // Optional: pattern name at the end in smaller text
            val nameView = android.widget.TextView(this).apply {
                text = pattern.name
                setTextColor(resolveSurfaceColor().let { if (it == Color.WHITE) Color.BLACK else Color.WHITE })
                setPadding(12, 0, 0, 0)
                // Use 0 width and weight so it expands to fill remaining space
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameView)

            row.setOnClickListener {
                // apply pattern icons -> set currentStrums to pattern
                pattern.strums.forEachIndexed { idx, s -> if (idx < currentStrums.size) currentStrums[idx] = s }
                updateStrumViews()
                try {
                    val livePattern = com.metaview.chordprogressionhelper.model.StrummingPattern(pattern.name, pattern.strums.toList())
                    // If service is playing, just send live update
                    val isPlayingNow = playbackService?.isPlaying?.value == true
                    if (isPlayingNow) {
                        playbackService?.updateStrummingPattern(measureIndex, livePattern)
                    } else {
                        // Not playing -> start a one-measure preview with the tonic chord
                        val tonic = viewModel.progression.getScaleDegreeChords().firstOrNull()
                        if (tonic != null) {
                            val tempProg = com.metaview.chordprogressionhelper.model.ChordProgression(
                                name = "Preview",
                                key = viewModel.key.value ?: com.metaview.chordprogressionhelper.model.Key.C,
                                mode = viewModel.progression.mode,
                                tempo = viewModel.tempo.value ?: viewModel.progression.tempo
                            )
                            // Replace default measure with one configured measure
                            try { tempProg.measures.clear() } catch (_: Exception) {}
                            val m = com.metaview.chordprogressionhelper.model.Measure(1)
                            try { m.addChord(tonic, 0) } catch (_: Exception) {}
                            m.strummingPattern = livePattern
                            tempProg.measures.add(m)

                            // Stop any previous playback/preview first, then start the new temporary progression
                            try { PlaybackService.stop(this) } catch (_: Exception) {}
                            PlaybackService.play(this, tempProg, true)
                            isDialogPreviewActive = true
                         }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to handle default pattern click: ${e.message}")
                }
            }

            dialogBinding.defaultPatternsLayout.addView(row)
        }

        // Initialize icons with current pattern
        updateStrumViews()

        // After the chips are added and icons initialized, ensure the fades are recalculated once the scroll content has measured.
        try {
            val scroll = dialogBinding.strumEditorScroll
            // Post a runnable to run after layout; this re-evaluates visibility with final content width
            scroll.post {
                try {
                    val fadeLeft = dialogBinding.root.findViewById<View>(R.id.fadeLeft)
                    val fadeRight = dialogBinding.root.findViewById<View>(R.id.fadeRight)
                    val child = scroll.getChildAt(0)
                    if (child != null) {
                        val range = child.width
                        val extent = scroll.width
                        val offset = scroll.scrollX
                        val canScroll = range > extent
                        fadeLeft.visibility = if (canScroll && offset > 0) View.VISIBLE else View.GONE
                        fadeRight.visibility = if (canScroll && (offset + extent) < range) View.VISIBLE else View.GONE
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error while updating dialog fades in post", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post fade update", e)
        }

        // Finally, create the dialog and set up the fades after the dialog window is available
        val alert = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setView(dialogBinding.root)
            .setPositiveButton("OK") { _, _ ->
                // Save currentStrums as a custom pattern for this measure
                try {
                    viewModel.setStrummingPattern(measureIndex, StrummingPattern("Custom", currentStrums))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save strumming pattern", e)
                    Toast.makeText(this, "Failed to save strumming pattern: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test", null)
            .create()

        alert.setOnShowListener {
            try {
                // Prefer the real window/content background color (what user actually sees) and fall back to Material surface
                val windowBg = alert.window?.decorView?.background
                val contentBg = alert.findViewById<View>(android.R.id.content)?.background
                val chosenBg = windowBg ?: contentBg ?: dialogBinding.root.background
                val baseBgColor = when (chosenBg) {
                    is android.graphics.drawable.ColorDrawable -> chosenBg.color
                    else -> resolveSurfaceColor()
                }

                // Use the determined dialog background color and vary only alpha for the fade
                val surfaceOpaque = applyAlpha(baseBgColor, 1.0f)
                val surfaceTransparent = applyAlpha(baseBgColor, 0.12f)
                val leftGd = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(surfaceOpaque, surfaceTransparent))
                val rightGd = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(surfaceTransparent, surfaceOpaque))
                leftGd.cornerRadius = 0f
                rightGd.cornerRadius = 0f
                val fadeLeft = dialogBinding.root.findViewById<View>(R.id.fadeLeft)
                val fadeRight = dialogBinding.root.findViewById<View>(R.id.fadeRight)
                fadeLeft.background = leftGd
                fadeRight.background = rightGd

                // Style buttons (force drawable background etc.)
                try { styleDialogButtons(alert) } catch (_: Exception) {}

                // Neutral 'Test' button: play the current pattern with the tonic chord without dismissing the dialog
                try {
                    val testButton = alert.getButton(AlertDialog.BUTTON_NEUTRAL)
                    testButton?.setOnClickListener {
                        try {
                            val livePattern = com.metaview.chordprogressionhelper.model.StrummingPattern("Test", currentStrums.toList())
                            // Determine tonic chord from current progression
                            val tonic = viewModel.progression.getScaleDegreeChords().firstOrNull()
                            if (tonic != null) {
                                // Create temporary progression with one measure using the selected pattern and tonic chord
                                val tempProg = com.metaview.chordprogressionhelper.model.ChordProgression(
                                    name = "Preview",
                                    key = viewModel.key.value ?: com.metaview.chordprogressionhelper.model.Key.C,
                                    mode = viewModel.progression.mode,
                                    tempo = viewModel.tempo.value ?: viewModel.progression.tempo
                                )
                                // Replace default measure with one configured measure
                                try {
                                    tempProg.measures.clear()
                                } catch (_: Exception) {}
                                val m = com.metaview.chordprogressionhelper.model.Measure(1)
                                try { m.addChord(tonic, 0) } catch (_: Exception) {}
                                m.strummingPattern = livePattern
                                tempProg.measures.add(m)

                                // Stop any previous playback/preview first, then start the new temporary progression
                                try { PlaybackService.stop(this) } catch (_: Exception) {}
                                PlaybackService.play(this, tempProg, true)
                                isDialogPreviewActive = true
                             }
                        } catch (e: Exception) {
                            Log.w(TAG, "Test button failed to play pattern: ${e.message}")
                            Toast.makeText(this, "Failed to play pattern: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setup Test button listener: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply dialog fade backgrounds on show", e)
            }
        }

        // Ensure any dialog-launched preview is stopped when the dialog is dismissed
        alert.setOnDismissListener {
            if (isDialogPreviewActive) {
                try { PlaybackService.stop(this) } catch (_: Exception) {}
                isDialogPreviewActive = false
            }
        }

        alert.show()
     }
 }
