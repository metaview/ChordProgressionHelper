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
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.metaviewsoft.chordprogressionhelper.BuildConfig
import de.metaviewsoft.chordprogressionhelper.databinding.ActivitySongBinding
import de.metaviewsoft.chordprogressionhelper.SettingsActivity
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.service.PlaybackService
import de.metaviewsoft.chordprogressionhelper.ui.ProgressionViewModel
import de.metaviewsoft.chordprogressionhelper.ui.SongViewModel
import de.metaviewsoft.chordprogressionhelper.ui.SectionAdapter
import de.metaviewsoft.chordprogressionhelper.util.ThemeColorResolver
import kotlinx.coroutines.launch

class SongActivity : AppCompatActivity() {

    companion object {
        /** The currently active progression across all activities. */
        var selectedProgression: ChordProgression? = null
    }

    private val TAG = "SongActivity"

    private lateinit var binding: ActivitySongBinding
    private lateinit var settingsRepository: de.metaviewsoft.chordprogressionhelper.data.SettingsRepository
    private lateinit var songViewModel: SongViewModel
    private lateinit var progressionViewModel: ProgressionViewModel
    private lateinit var sectionAdapter: SectionAdapter
    private lateinit var sectionTouchHelper: ItemTouchHelper
    private var lastSelectedIndex = -1

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

        songViewModel = ViewModelProvider(
            application as MyApplication,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[SongViewModel::class.java]

        progressionViewModel = ViewModelProvider(
            application as MyApplication,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[ProgressionViewModel::class.java]

        // Select the first section whose progression matches the globally selected one
        val prog = selectedProgression ?: songViewModel.getCurrentProgression()
        val idx = songViewModel.findSectionIndexForProgression(prog)
        songViewModel.selectSongSection(idx)?.let { progression ->
            progressionViewModel.progression = progression
        }

        PlaybackService.stop(this)
        setupRecyclerView()
        setupVolumeControl()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Stop any running playback when entering this activity
        PlaybackService.stop(this)
        // Sync the highlighted section with whatever is currently active in the ViewModel.
        // This covers the case where the user changed sections via the spinner in ProgressionActivity
        // and then navigated back here.
        val idx = songViewModel.selectedSongSectionIndex.value ?: 0
        sectionAdapter.setSelectedIndex(idx)
        // Sync volume SeekBar with current in-app master volume
        if (::settingsRepository.isInitialized) {
            binding.volumeSeekBar.progress =
                (settingsRepository.masterVolume * 100).toInt()
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stopBeatTimer()
        // Only stop playback when the activity is finishing (switching to another activity),
        // not when the app is just going to background
        if (isFinishing || isChangingConfigurations) {
            Log.i(TAG, "onStop: Activity finishing/changing config - stopping playback")
            if (isServiceBound && playbackService != null) {
                try { playbackService?.stopPlayback() } catch (e: Exception) { Log.w(TAG, "onStop: stopPlayback failed: ${e.message}") }
            } else {
                try { PlaybackService.stop(this) } catch (e: Exception) { Log.w(TAG, "onStop: PlaybackService.stop failed: ${e.message}") }
            }
        } else {
            Log.i(TAG, "onStop: Activity going to background - keeping playback running")
        }
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun setupRecyclerView() {
        sectionAdapter = SectionAdapter(
            onSectionClick = { position ->
                songViewModel.selectSongSection(position)?.let { progression ->
                    progressionViewModel.progression = progression
                    selectedProgression = progression
                }
                startActivity(Intent(this, ProgressionActivity::class.java))
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
            SectionAdapter.SectionTouchHelperCallback(sectionAdapter) { from, to ->
                songViewModel.moveSongSection(from, to)?.let { progression ->
                    progressionViewModel.progression = progression
                }
                refreshSections()
            }
        )
        sectionTouchHelper.attachToRecyclerView(binding.songSectionsRecyclerView)
    }

    private fun setupVolumeControl() {
        settingsRepository = de.metaviewsoft.chordprogressionhelper.data.SettingsRepository(this)
        binding.volumeSeekBar.max = 100
        binding.volumeSeekBar.progress = (settingsRepository.masterVolume * 100).toInt()
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsRepository.masterVolume = progress / 100f
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupListeners() {
        binding.songMenuButton.setOnClickListener { showMenu(it) }

        binding.songPlayButton.setOnClickListener {
            if (playbackService?.isPlaying?.value == true) {
                PlaybackService.pause(this)
            } else {
                PlaybackService.playSong(this, songViewModel.createSongPlaybackProgression())
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
            songViewModel.onSongRepeatToggle(!(songViewModel.isSongLooping.value ?: false))
        }
    }

    private fun observeViewModel() {
        songViewModel.songName.observe(this) { name ->
            binding.songNameEditText.text = name ?: getString(R.string.song_name_default)
        }

        songViewModel.songSectionNames.observe(this) {
            refreshSections()
        }

        songViewModel.selectedSongSectionIndex.observe(this) { index ->
            lastSelectedIndex = index
            sectionAdapter.setSelectedIndex(index)
        }

        songViewModel.isSongLooping.observe(this) { isLooping ->
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
                    val sectionIdx = songViewModel.getSectionIndexForMeasure(measureIndex)
                    val sectionTempo = songViewModel.getTempoForMeasure(measureIndex)
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
        val sectionNames = songViewModel.songSectionNames.value.orEmpty()
        val selectedIndex = songViewModel.selectedSongSectionIndex.value ?: 0
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
        promptForSectionName(getString(R.string.song_section_add), "") { name ->
            val currentProgression = songViewModel.getCurrentProgression()
            songViewModel.addSongSection(name, currentProgression.key, currentProgression.mode, currentProgression.tempo)
            selectedProgression = songViewModel.getCurrentProgression()
            progressionViewModel.progression = selectedProgression!!
            refreshSections()
        }
    }

    private fun showDuplicateSectionDialog(position: Int) {
        val currentSection = songViewModel.getSectionAt(position) ?: return
        val currentName = currentSection.name.ifBlank { "Section ${position + 1}" }
        val suggestedName = "$currentName Copy"
        promptForSectionName(getString(R.string.song_section_duplicate), suggestedName) { name ->
            val source = songViewModel.getSectionAt(position) ?: return@promptForSectionName
            val copied = try {
                kotlinx.serialization.json.Json.decodeFromString(
                    de.metaviewsoft.chordprogressionhelper.model.ChordProgression.serializer(),
                    kotlinx.serialization.json.Json.encodeToString(
                        de.metaviewsoft.chordprogressionhelper.model.ChordProgression.serializer(),
                        source.progression
                    )
                )
            } catch (_: Exception) {
                source.progression.copy(measures = source.progression.measures.toMutableList())
            }
            copied.name = name
            songViewModel.addSongSectionWithProgression(name, copied)
            selectedProgression = songViewModel.getCurrentProgression()
            progressionViewModel.progression = selectedProgression!!
            refreshSections()
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, getString(R.string.new_song_title))
        popup.menu.add(0, 1, 1, getString(R.string.load_song_title))
        popup.menu.add(0, 2, 2, getString(R.string.save_song_title))
        popup.menu.add(0, 3, 3, getString(R.string.delete_song_title))
        popup.menu.add(0, 4, 4, getString(R.string.settings_title))
        popup.menu.add(0, 5, 5, getString(R.string.about_title))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { showNewSongDialog(); true }
                1 -> { showLoadSongDialog(); true }
                2 -> { showSaveSongDialog(); true }
                3 -> { showDeleteSongDialog(); true }
                4 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                5 -> { showAboutDialog(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAboutDialog() {
        val versionInfo = "Version ${BuildConfig.VERSION_NAME}\n\n"
        val message = versionInfo + getString(R.string.about_message)
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.about_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    private fun showSectionMenu(position: Int, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, getString(R.string.song_section_rename))
        popup.menu.add(0, 1, 1, getString(R.string.song_section_duplicate))
        popup.menu.add(0, 2, 2, getString(R.string.delete))
        popup.menu.add(0, 3, 3, getString(R.string.song_section_move_up))
        popup.menu.add(0, 4, 4, getString(R.string.song_section_move_down))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> {
                    val currentTitle = songViewModel.songSectionNames.value?.getOrNull(position).orEmpty()
                    val defaultName = currentTitle.substringAfter(". ", currentTitle)
                    promptForSectionName(getString(R.string.song_section_rename), defaultName, excludePosition = position) { name ->
                        songViewModel.renameSongSection(position, name)
                        refreshSections()
                    }
                    true
                }
                1 -> {
                    showDuplicateSectionDialog(position)
                    true
                }
                2 -> {
                    val currentProgression = songViewModel.getCurrentProgression()
                    songViewModel.deleteSongSection(position, currentProgression.key, currentProgression.mode, currentProgression.tempo)?.let { progression ->
                        progressionViewModel.progression = progression
                        selectedProgression = progression
                    }
                    refreshSections()
                    true
                }
                3 -> {
                    songViewModel.moveSongSection(position, (position - 1).coerceAtLeast(0))?.let { progression ->
                        progressionViewModel.progression = progression
                    }
                    refreshSections()
                    true
                }
                4 -> {
                    val names = songViewModel.songSectionNames.value.orEmpty()
                    songViewModel.moveSongSection(position, (position + 1).coerceAtMost(names.lastIndex))?.let { progression ->
                        progressionViewModel.progression = progression
                    }
                    refreshSections()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun promptForSectionName(title: String, initialValue: String, excludePosition: Int = -1, onConfirm: (String) -> Unit) {
        val editText = android.widget.EditText(this).apply {
            setText(initialValue)
            setSelection(text.length)
            hint = getString(R.string.song_section_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                // Check if name already exists (excluding current position for rename)
                if (name.isNotEmpty() && songViewModel.isSectionNameTaken(name, excludePosition)) {
                    Toast.makeText(this, getString(R.string.song_section_name_exists), Toast.LENGTH_SHORT).show()
                    // Reopen dialog with same input
                    promptForSectionName(title, name, excludePosition, onConfirm)
                } else {
                    onConfirm(name)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    private fun showNewSongDialog() {
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.new_song_title))
            .setMessage(getString(R.string.new_song_message))
            .setPositiveButton(getString(R.string.continue_button)) { _, _ ->
                PlaybackService.stop(this)
                val defaultKey = settingsRepository.defaultKeyName.let { name ->
                    de.metaviewsoft.chordprogressionhelper.model.Key.entries.firstOrNull { it.name == name } ?: de.metaviewsoft.chordprogressionhelper.model.Key.C
                }
                val defaultTempo = settingsRepository.defaultBpm.coerceIn(60, 240)
                songViewModel.newSong(defaultKey, defaultTempo)
                selectedProgression = null
                progressionViewModel.progression = songViewModel.getCurrentProgression()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    private fun showLoadSongDialog() {
        val savedNames = songViewModel.getSavedSongNames()
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
                    songViewModel.loadSong(savedNames[selectedPosition])?.let { progression ->
                        progressionViewModel.progression = progression
                        selectedProgression = progression
                    }
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
            val currentName = songViewModel.songName.value.orEmpty()
            setText(currentName)
            setSelection(text.length)
            hint = getString(R.string.song_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
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
                val existing = songViewModel.getSavedSongNames()
                if (existing.contains(name)) {
                    val overwriteDialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
                        .setTitle(getString(R.string.overwrite_song_title))
                        .setMessage(getString(R.string.overwrite_song_message, name))
                        .setPositiveButton(getString(R.string.overwrite)) { _, _ ->
                            songViewModel.saveNamedSong(name)
                            dialog.dismiss()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .create()
                    overwriteDialog.setOnShowListener { styleDialogButtons(overwriteDialog) }
                    overwriteDialog.show()
                } else {
                    songViewModel.saveNamedSong(name)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteSongDialog() {
        val savedNames = songViewModel.getSavedSongNames()
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
                    songViewModel.deleteSong(songName)
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
