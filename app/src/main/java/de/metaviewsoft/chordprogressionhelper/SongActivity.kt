package de.metaviewsoft.chordprogressionhelper

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.metaviewsoft.chordprogressionhelper.databinding.ActivitySongBinding
import de.metaviewsoft.chordprogressionhelper.SettingsActivity
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.service.PlaybackService
import de.metaviewsoft.chordprogressionhelper.ui.ProgressionViewModel
import de.metaviewsoft.chordprogressionhelper.ui.SectionAdapter
import de.metaviewsoft.chordprogressionhelper.util.ThemeColorResolver
import kotlinx.coroutines.launch

class SongActivity : AppCompatActivity() {

    companion object {
        /** The currently active progression across all activities. */
        var selectedProgression: ChordProgression? = null
    }

    private lateinit var binding: ActivitySongBinding
    private lateinit var viewModel: ProgressionViewModel
    private lateinit var sectionAdapter: SectionAdapter
    private lateinit var sectionTouchHelper: ItemTouchHelper
    private var lastSelectedIndex = -1
    private var isUpdatingSongName = false

    private var playbackService: PlaybackService? = null
    private var isServiceBound = false

    private val beatHandler = Handler(Looper.getMainLooper())
    private var beatRunnable: Runnable? = null
    private var currentPlayingSectionIdx: Int = -1

    private fun startBeatTimer(eighthNoteMs: Long) {
        stopBeatTimer()
        val r = object : Runnable {
            override fun run() {
                if (currentPlayingSectionIdx >= 0) {
                    sectionAdapter.onBeat()
                    beatHandler.postDelayed(this, eighthNoteMs)
                }
            }
        }
        beatRunnable = r
        beatHandler.post(r)
    }

    private fun stopBeatTimer() {
        beatRunnable?.let { beatHandler.removeCallbacks(it) }
        beatRunnable = null
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playbackService = (binder as? PlaybackService.LocalBinder)?.getService()
            isServiceBound = true
            observePlaybackState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySongBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            application as MyApplication,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[ProgressionViewModel::class.java]

        // Select the first section whose progression matches the globally selected one
        val prog = selectedProgression ?: viewModel.progression
        val idx = viewModel.findSectionIndexForProgression(prog)
        viewModel.selectSongSection(idx)

        PlaybackService.stop(this)
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stopBeatTimer()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun setupRecyclerView() {
        sectionAdapter = SectionAdapter(
            onSectionClick = { position ->
                val currentIndex = viewModel.selectedSongSectionIndex.value ?: 0
                if (position == currentIndex && lastSelectedIndex == currentIndex) {
                    finish()
                } else {
                    lastSelectedIndex = position
                    viewModel.selectSongSection(position)
                    selectedProgression = viewModel.progression
                    refreshSections()
                }
            },
            onMenuClick = { position, anchor ->
                showSectionMenu(position, anchor)
            },
            onAddSectionClick = {
                showAddSectionDialog()
            },
            onStartDrag = { viewHolder ->
                sectionTouchHelper.startDrag(viewHolder)
            }
        )

        binding.songSectionsRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.songSectionsRecyclerView.adapter = sectionAdapter

        sectionTouchHelper = ItemTouchHelper(
            SectionAdapter.SectionTouchHelperCallback(sectionAdapter) { fromPosition, toPosition ->
                viewModel.moveSongSection(fromPosition, toPosition)
                refreshSections()
            }
        )
        sectionTouchHelper.attachToRecyclerView(binding.songSectionsRecyclerView)
    }

    private fun setupListeners() {
        binding.songNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingSongName) {
                    viewModel.setSongName(s?.toString().orEmpty())
                }
            }
        })

        binding.songMenuButton.setOnClickListener { showMenu(it) }

        binding.songPlayButton.setOnClickListener {
            if (playbackService?.isPlaying?.value == true) {
                PlaybackService.pause(this)
            } else {
                PlaybackService.playSong(this, viewModel.createSongPlaybackProgression())
            }
        }

        binding.songStopButton.setOnClickListener {
            if (isServiceBound && playbackService != null) {
                playbackService?.stopPlayback()
            } else {
                PlaybackService.stop(this)
            }
        }

        binding.songRepeatButton.setOnClickListener {
            viewModel.onRepeatToggle(!(viewModel.isLooping.value ?: false))
        }
    }

    private fun observeViewModel() {
        viewModel.songName.observe(this) { name ->
            isUpdatingSongName = true
            if (binding.songNameEditText.text?.toString() != name) {
                binding.songNameEditText.setText(name ?: getString(R.string.song_name_default))
                binding.songNameEditText.setSelection(binding.songNameEditText.text?.length ?: 0)
            }
            isUpdatingSongName = false
        }

        viewModel.songSectionNames.observe(this) {
            refreshSections()
        }

        viewModel.selectedSongSectionIndex.observe(this) { index ->
            lastSelectedIndex = index
            sectionAdapter.setSelectedIndex(index)
        }

        viewModel.isLooping.observe(this) { isLooping ->
            updateRepeatButton(isLooping)
        }
    }

    private fun observePlaybackState() {
        val service = playbackService ?: return
        lifecycleScope.launch {
            service.isPlaying.collect { isPlaying ->
                binding.songStopButton.isEnabled = isPlaying
                binding.songStopButton.alpha = if (isPlaying) 1.0f else 0.4f
                binding.songPlayButton.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                )
                if (!isPlaying) {
                    stopBeatTimer()
                    currentPlayingSectionIdx = -1
                    sectionAdapter.setPlayingIndex(-1)
                }
            }
        }
        lifecycleScope.launch {
            service.currentPlaybackPosition.collect { position ->
                if (position == null) {
                    stopBeatTimer()
                    currentPlayingSectionIdx = -1
                    sectionAdapter.setPlayingIndex(-1)
                } else {
                    val (measureIndex, _) = position
                    val sectionIdx = viewModel.getSectionIndexForMeasure(measureIndex)
                    val sectionTempo = viewModel.getTempoForMeasure(measureIndex)
                    playbackService?.setTempo(sectionTempo)
                    if (sectionIdx != currentPlayingSectionIdx) {
                        currentPlayingSectionIdx = sectionIdx
                        sectionAdapter.setPlayingIndex(sectionIdx)
                        // Derive 8th-note duration from tempo of current section
                        val eighthNoteMs = (60_000L / sectionTempo) / 2
                        startBeatTimer(eighthNoteMs)
                    }
                }
            }
        }
    }

    private fun refreshSections() {
        val sectionNames = viewModel.songSectionNames.value.orEmpty()
        val selectedIndex = viewModel.selectedSongSectionIndex.value ?: 0
        val items = sectionNames.map { name -> SectionAdapter.SectionItem(name = name) } +
            SectionAdapter.SectionItem(name = "", isAddButton = true)
        sectionAdapter.submitList(items) {
            sectionAdapter.setSelectedIndex(selectedIndex)
        }
        lastSelectedIndex = selectedIndex
    }

    private fun updateRepeatButton(isLooping: Boolean) {
        if (isLooping) {
            val primaryColor = ThemeColorResolver.primary(this)
            val onPrimary = if (Color.luminance(primaryColor) > 0.5f) Color.BLACK else Color.WHITE
            binding.songRepeatButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(primaryColor)
            binding.songRepeatButton.setColorFilter(onPrimary)
            binding.songRepeatButton.alpha = 1.0f
        } else {
            binding.songRepeatButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(ThemeColorResolver.surface(this))
            val offIconColor = ThemeColorResolver.onBackground(this)
            binding.songRepeatButton.setColorFilter(offIconColor)
            binding.songRepeatButton.alpha = 0.9f
        }
    }

    private fun showAddSectionDialog() {
        val uniqueProgressions = viewModel.getUniqueSongProgressions()
        val entries = uniqueProgressions.map { it.name.ifBlank { getString(R.string.song_section_unnamed) } }.toMutableList()
        entries.add(getString(R.string.song_section_new_progression))
        val newProgressionIndex = entries.lastIndex

        var selectedIndex = newProgressionIndex
        val dialogView = layoutInflater.inflate(android.R.layout.select_dialog_singlechoice, null)
        val listView = android.widget.ListView(this)
        listView.choiceMode = android.widget.AbsListView.CHOICE_MODE_SINGLE
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.select_dialog_singlechoice, entries)
        listView.adapter = adapter
        listView.setItemChecked(newProgressionIndex, true)
        listView.setOnItemClickListener { _, _, position, _ -> selectedIndex = position }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog
        )
            .setTitle(getString(R.string.song_section_add))
            .setView(listView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                promptForSectionName(getString(R.string.song_section_add), "") { name ->
                    if (selectedIndex == newProgressionIndex) {
                        viewModel.addSongSection(name)
                    } else {
                        viewModel.addSongSectionWithProgression(name, uniqueProgressions[selectedIndex])
                    }
                    selectedProgression = viewModel.progression
                    refreshSections()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.show()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, getString(R.string.new_song_title))
        popup.menu.add(0, 1, 1, getString(R.string.load_song_title))
        popup.menu.add(0, 2, 2, getString(R.string.save_song_title))
        popup.menu.add(0, 3, 3, getString(R.string.delete_song_title))
        popup.menu.add(0, 4, 4, getString(R.string.settings_title))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { viewModel.requestNewProgression(); finish(); true }
                1 -> { showLoadSongDialog(); true }
                2 -> { showSaveSongDialog(); true }
                3 -> { showDeleteSongDialog(); true }
                4 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showSectionMenu(position: Int, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.song_section_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_rename_section -> {
                    val currentTitle = viewModel.songSectionNames.value?.getOrNull(position).orEmpty()
                    val defaultName = currentTitle.substringAfter(". ", currentTitle)
                    promptForSectionName(getString(R.string.song_section_rename), defaultName) { name ->
                        viewModel.renameSongSection(position, name)
                        refreshSections()
                    }
                    true
                }
                R.id.menu_delete_section -> {
                    viewModel.deleteSongSection(position)
                    refreshSections()
                    true
                }
                R.id.menu_move_section_up -> {
                    viewModel.moveSongSection(position, (position - 1).coerceAtLeast(0))
                    refreshSections()
                    true
                }
                R.id.menu_move_section_down -> {
                    val names = viewModel.songSectionNames.value.orEmpty()
                    viewModel.moveSongSection(position, (position + 1).coerceAtMost(names.lastIndex))
                    refreshSections()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun promptForSectionName(title: String, initialValue: String, onConfirm: (String) -> Unit) {
        val editText = android.widget.EditText(this).apply {
            setText(initialValue)
            setSelection(text.length)
            hint = getString(R.string.song_section_name_hint)
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                onConfirm(editText.text?.toString().orEmpty())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    private fun showLoadSongDialog() {
        val savedNames = viewModel.getSavedSongNames()
        if (savedNames.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_saved_songs_found), Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_list_with_fade, null)
        val listView = dialogView.findViewById<android.widget.ListView>(android.R.id.list)
        val topFade = dialogView.findViewById<View>(R.id.topFade)
        val bottomFade = dialogView.findViewById<View>(R.id.bottomFade)

        val adapter = ArrayAdapter(this, R.layout.list_item_two_line_selectable, android.R.id.text1, savedNames)
        listView.adapter = adapter

        var selectedPosition = -1
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.load_song_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                if (selectedPosition >= 0) {
                    viewModel.loadSong(savedNames[selectedPosition])
                    refreshSections()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.isEnabled = false
            listView.setOnItemClickListener { _, _, which, _ ->
                selectedPosition = which
                listView.setItemChecked(which, true)
                okButton.isEnabled = true
            }
            setupListViewFadeEffects(listView, topFade, bottomFade)
        }
        setupListViewFadeEffects(listView, topFade, bottomFade)
        dialog.show()
    }

    private fun showSaveSongDialog() {
        val editText = android.widget.EditText(this).apply {
            val currentName = viewModel.songName.value.orEmpty()
            setText(currentName)
            setSelection(text.length)
            hint = getString(R.string.song_name_hint)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.save_song_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.save_button), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editText.text?.toString().orEmpty().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, getString(R.string.please_enter_name), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val existing = viewModel.getSavedSongNames()
                if (existing.contains(name)) {
                    val overwriteDialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
                        .setTitle(getString(R.string.overwrite_song_title))
                        .setMessage(getString(R.string.overwrite_song_message, name))
                        .setPositiveButton(getString(R.string.overwrite)) { _, _ ->
                            viewModel.saveNamedSong(name)
                            dialog.dismiss()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .create()
                    overwriteDialog.setOnShowListener { styleDialogButtons(overwriteDialog) }
                    overwriteDialog.show()
                } else {
                    viewModel.saveNamedSong(name)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteSongDialog() {
        val savedNames = viewModel.getSavedSongNames()
        if (savedNames.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_saved_songs_delete), Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_list_with_fade, null)
        val listView = dialogView.findViewById<android.widget.ListView>(android.R.id.list)
        val topFade = dialogView.findViewById<View>(R.id.topFade)
        val bottomFade = dialogView.findViewById<View>(R.id.bottomFade)

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, savedNames)

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.delete_song_title))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        listView.setOnItemClickListener { _, _, which, _ ->
            val songName = savedNames[which]
            val confirm = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
                .setTitle(getString(R.string.confirm_delete_title))
                .setMessage(getString(R.string.confirm_delete_message, songName))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    viewModel.deleteSong(songName)
                    Toast.makeText(this, getString(R.string.song_deleted_message, songName), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            confirm.setOnShowListener { styleDialogButtons(confirm) }
            confirm.show()
        }

        setupListViewFadeEffects(listView, topFade, bottomFade)
        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            listView.post { setupListViewFadeEffects(listView, topFade, bottomFade) }
        }
        dialog.show()
    }

    private fun styleDialogButtons(alert: AlertDialog) {
        try {
            val positive = alert.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = alert.getButton(AlertDialog.BUTTON_NEGATIVE)
            val neutral = alert.getButton(AlertDialog.BUTTON_NEUTRAL)
            val primaryColor = ThemeColorResolver.primary(this)
            val onPrimary = if (Color.luminance(primaryColor) > 0.5f) Color.BLACK else Color.WHITE
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
            Log.w("SongActivity", "Failed to style dialog buttons", e)
        }
    }

    private fun setupListViewFadeEffects(
        listView: android.widget.ListView,
        topFade: View,
        bottomFade: View
    ) {
        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {}
            override fun onScroll(view: android.widget.AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                val canScrollUp = firstVisibleItem > 0 || (listView.childCount > 0 && listView.getChildAt(0).top < 0)
                topFade.visibility = if (canScrollUp) View.VISIBLE else View.GONE
                val lastVisibleItem = firstVisibleItem + visibleItemCount
                val canScrollDown = lastVisibleItem < totalItemCount ||
                    (listView.childCount > 0 && listView.getChildAt(listView.childCount - 1).bottom > listView.height)
                bottomFade.visibility = if (canScrollDown) View.VISIBLE else View.GONE
            }
        })
        listView.post {
            topFade.visibility = View.GONE
            val canScrollDown = listView.count > 0 &&
                (listView.childCount == 0 || listView.getChildAt(listView.childCount - 1).bottom > listView.height)
            bottomFade.visibility = if (canScrollDown) View.VISIBLE else View.GONE
        }
    }
}
