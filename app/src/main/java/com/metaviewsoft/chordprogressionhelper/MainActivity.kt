@file:OptIn(InternalSerializationApi::class)

package de.metaviewsoft.chordprogressionhelper

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
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.TextView
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
import de.metaviewsoft.chordprogressionhelper.databinding.ActivityMainBinding
import de.metaviewsoft.chordprogressionhelper.databinding.DialogSaveProgressionBinding
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.Note
import de.metaviewsoft.chordprogressionhelper.model.Chord
import de.metaviewsoft.chordprogressionhelper.model.ChordType
import de.metaviewsoft.chordprogressionhelper.model.StrummingPattern
import de.metaviewsoft.chordprogressionhelper.model.DrumPattern
import de.metaviewsoft.chordprogressionhelper.model.SoloPattern
import de.metaviewsoft.chordprogressionhelper.model.ProgressionTemplate
import de.metaviewsoft.chordprogressionhelper.model.ProgressionTemplates
import de.metaviewsoft.chordprogressionhelper.service.PlaybackService
import de.metaviewsoft.chordprogressionhelper.util.PreviewCoordinator
import de.metaviewsoft.chordprogressionhelper.ui.ChordAdapter
import de.metaviewsoft.chordprogressionhelper.ui.MeasureAdapter
import de.metaviewsoft.chordprogressionhelper.ui.ProgressionViewModel
import de.metaviewsoft.chordprogressionhelper.ui.TemplateAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@OptIn(InternalSerializationApi::class)
@SuppressLint("UnspecifiedRegisterReceiverFlag")
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ProgressionViewModel
    private val TAG = "MainActivity"
    private lateinit var chordAdapter: ChordAdapter
    private lateinit var relatedChordAdapter: ChordAdapter
    private lateinit var borrowedMinorChordAdapter: ChordAdapter
    private lateinit var borrowedMajorChordAdapter: ChordAdapter
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
                            val pattern = Json.decodeFromString(DrumPattern.serializer(), json)
                            viewModel.setDrumPattern(mIndex, pattern)
                        } catch (e: Exception) { Log.w(TAG, "drumPatternReceiver: failed to decode pattern: ${e.message}") }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "drumPatternReceiver onReceive failed: ${e.message}") }
        }
    }

    private var areExtraChordsExpanded = false
    private var lastScrolledMeasure = -1
    private var isSyncingBorrowedScroll = false

    private var playbackService: PlaybackService? = null
    private var isBound = false
    // If the user presses Stop while not bound, request a bind+stop via this flag
    private var pendingStopRequest: Boolean = false

    // Indicates whether the last started playback was a temporary preview spawned by the dialog
    private var isDialogPreviewActive = false
    // Keep a reference to the PreviewCoordinator listener so we can remove it on destroy
    private var previewOwnerListener: ((owner: String?, isLooping: Boolean) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true

            // Trigger AudioSystem warm-up im PlaybackService
            playbackService?.warmupAudioSystem()

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
            Toast.makeText(this, getString(R.string.notification_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var exportCreateLauncher: ActivityResultLauncher<String>
    private lateinit var importOpenLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var exportDirLauncher: ActivityResultLauncher<android.net.Uri?>

    private var pendingExportName: String? = null

    private fun shareExportedProgression(name: String) {
        try {
            val repo = (application as MyApplication).progressionRepository
            val tempF = java.io.File.createTempFile("share_${name}", ".json", cacheDir)
            if (!repo.exportProgressionToFile(name, tempF)) {
                Toast.makeText(this, getString(R.string.export_for_sharing_failed), Toast.LENGTH_SHORT).show()
                tempF.delete()
                return
            }
            val authority = "${applicationContext.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(this, authority, tempF)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_progression)))
            // schedule deletion of temp file after a short delay
            tempF.deleteOnExit()
        } catch (e: Exception) {
            Log.w(TAG, "shareExportedProgression failed: ${e.message}")
            Toast.makeText(this, getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptChooseAndExport() {
        val savedNames = viewModel.getSavedProgressionNames()
        if (savedNames.isEmpty()) { Toast.makeText(this, getString(R.string.no_saved_progressions_export), Toast.LENGTH_SHORT).show(); return }

        // Custom Layout mit Fade-Effekten
        val dialogView = layoutInflater.inflate(R.layout.dialog_list_with_fade, null)
        val listView = dialogView.findViewById<android.widget.ListView>(android.R.id.list)
        val topFade = dialogView.findViewById<View>(R.id.topFade)
        val bottomFade = dialogView.findViewById<View>(R.id.bottomFade)

        val repo = (application as MyApplication).progressionRepository
        val items = savedNames.map { name -> Pair(name, try { repo.getPreviewFor(name) } catch (_: Exception) { null }) }
        val adapter = object : ArrayAdapter<Pair<String, String?>>(this, android.R.layout.simple_list_item_2, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val v = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
                val tv1 = v.findViewById<TextView>(android.R.id.text1)
                val tv2 = v.findViewById<TextView>(android.R.id.text2)
                val item = getItem(position)!!
                tv1.text = item.first
                tv2.text = item.second ?: ""
                tv2.alpha = 0.75f
                return v
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, which, _ ->
            val name = items[which].first
            pendingExportName = name
            val suggested = "${name}.json"
            exportCreateLauncher.launch(suggested)
        }

        // Setup Fade-Effekte
        setupListViewFadeEffects(listView, topFade, bottomFade)

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.export_progression_title))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            listView.post {
                setupListViewFadeEffects(listView, topFade, bottomFade)
            }
        }
        dialog.show()
    }

    private fun promptChooseAndShare() {
        val savedNames = viewModel.getSavedProgressionNames()
        if (savedNames.isEmpty()) { Toast.makeText(this, getString(R.string.no_saved_progressions_share), Toast.LENGTH_SHORT).show(); return }

        // Custom Layout mit Fade-Effekten
        val dialogView = layoutInflater.inflate(R.layout.dialog_list_with_fade, null)
        val listView = dialogView.findViewById<android.widget.ListView>(android.R.id.list)
        val topFade = dialogView.findViewById<View>(R.id.topFade)
        val bottomFade = dialogView.findViewById<View>(R.id.bottomFade)

        val repo = (application as MyApplication).progressionRepository
        val items = savedNames.map { name -> Pair(name, try { repo.getPreviewFor(name) } catch (_: Exception) { null }) }
        val adapter = object : ArrayAdapter<Pair<String, String?>>(this, android.R.layout.simple_list_item_2, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val v = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
                val tv1 = v.findViewById<TextView>(android.R.id.text1)
                val tv2 = v.findViewById<TextView>(android.R.id.text2)
                val item = getItem(position)!!
                tv1.text = item.first
                tv2.text = item.second ?: ""
                tv2.alpha = 0.75f
                return v
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, which, _ ->
            val name = items[which].first
            shareExportedProgression(name)
        }

        // Setup Fade-Effekte
        setupListViewFadeEffects(listView, topFade, bottomFade)

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.share_progression_title))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            listView.post {
                setupListViewFadeEffects(listView, topFade, bottomFade)
            }
        }
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[ProgressionViewModel::class.java]

        // Listen to preview ownership changes so we can reflect MAIN-owned previews in the UI
        previewOwnerListener = { owner: String?, _: Boolean -> isDialogPreviewActive = (owner == "MAIN") }
        previewOwnerListener?.let { PreviewCoordinator.addListener(it) }

         // Register SAF launchers for export/import
         exportCreateLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            // Write selected progression JSON to the created uri via contentResolver
            val name = pendingExportName
            if (name == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    val repo = (application as MyApplication).progressionRepository
                    val tempF = java.io.File.createTempFile("export", ".json", cacheDir)
                    try {
                        if (repo.exportProgressionToFile(name, tempF)) {
                            tempF.inputStream().use { it.copyTo(os) }
                            Toast.makeText(this, getString(R.string.exported_x, name), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        try { tempF.delete() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "exportCreateLauncher: write failed: ${e.message}")
            } finally {
                pendingExportName = null
            }
        }

        importOpenLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                try {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        val text = ins.readBytes().toString(Charsets.UTF_8)
                        val repo = (application as MyApplication).progressionRepository
                        // save to temp and import
                        val tmp = java.io.File.createTempFile("import", ".json", cacheDir)
                        tmp.writeText(text)
                        val name = repo.importProgressionFromFile(tmp, overwrite = false)
                        tmp.delete()
                        if (name != null) Toast.makeText(this, getString(R.string.imported_x, name), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) { Log.w(TAG, "importOpenLauncher failed: ${e.message}") }
            }
        }

        // For simplicity, exportDirLauncher will be an OPEN_DOCUMENT_TREE that returns a Uri. We copy files using DocumentFile if needed.
        exportDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            treeUri?.let { uri ->
                try {
                    val takeFlags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                    val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                    if (docFile != null && docFile.isDirectory) {
                        val repo = (application as MyApplication).progressionRepository
                        val exported = repo.exportAllProgressionsToDir(java.io.File(cacheDir, "exports"))
                        // Copy exported files to treeUri using DocumentFile
                        for (f in exported) {
                            val existing = docFile.findFile(f.name)
                            existing?.delete()
                            val out = docFile.createFile("application/json", f.name)
                            out?.uri?.let { destUri ->
                                contentResolver.openOutputStream(destUri)?.use { os -> f.inputStream().use { it.copyTo(os) } }
                            }
                        }
                        Toast.makeText(this, getString(R.string.exported_n_files, exported.size), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) { Log.w(TAG, "exportDirLauncher failed: ${e.message}") }
            }
        }

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

                // Check for DrumPattern result
                val drumJson = data?.getStringExtra(DrumPatternActivity.EXTRA_DRUM_PATTERN_JSON)
                if (mIndex >= 0 && !drumJson.isNullOrEmpty()) {
                    try {
                        val pattern = Json.decodeFromString(DrumPattern.serializer(), drumJson)
                        viewModel.setDrumPattern(mIndex, pattern)
                    } catch (e: Exception) { Log.w(TAG, "Failed to decode DrumPattern from activity result: ${e.message}") }
                }

                // Check for SoloPattern result
                val soloJson = data?.getStringExtra(SoloPatternActivity.EXTRA_SOLO_PATTERN_JSON)
                if (mIndex >= 0 && !soloJson.isNullOrEmpty()) {
                    try {
                        val pattern = Json.decodeFromString(SoloPattern.serializer(), soloJson)
                        viewModel.setSoloPattern(mIndex, pattern)
                    } catch (e: Exception) { Log.w(TAG, "Failed to decode PianoPattern from activity result: ${e.message}") }
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
             Log.d(TAG, "playPauseButton clicked: isBound=$isBound, playbackService=$playbackService, isPlaying=${playbackService?.isPlaying?.value}")
             // Optimistically animate immediately for snappy UX
             val willPlay = playbackService?.isPlaying?.value != true
             Log.d(TAG, "playPauseButton: willPlay=$willPlay, animating...")
             animatePlayPause(willPlay)
             if (playbackService?.isPlaying?.value == true) {
                Log.d(TAG, "playPauseButton: service is playing, handling pause/stop")
                // If a dialog preview is running, the user's 'play' intent should stop the preview and start the main progression
                if (isDialogPreviewActive) {
                    Log.d(TAG, "playPauseButton: stopping dialog preview, starting main")
                    try { PlaybackService.stop(this) } catch (_: Exception) {}
                    isDialogPreviewActive = false
                    PlaybackService.play(this, viewModel.progression)
                } else {
                    Log.d(TAG, "playPauseButton: pausing playback")
                    PlaybackService.pause(this)
                }
            } else {
                Log.d(TAG, "playPauseButton: starting main progression playback")
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
                R.id.action_export -> { promptChooseAndExport(); true }
                R.id.action_import -> { importOpenLauncher.launch(arrayOf("application/json")); true }
                R.id.action_share -> { promptChooseAndShare(); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.action_about -> { showAboutDialog(); true }
                else -> false
             }
         }
         popup.show()
    }

    private fun showAboutDialog() {
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(getString(R.string.ok), null)
             .create()
        dialog.setOnShowListener { styleDialogButtons(dialog) }
        dialog.show()
    }

    private fun showLoadDialog() {
        val savedNames = viewModel.getSavedProgressionNames()
        if (savedNames.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_saved_progressions_found), Toast.LENGTH_SHORT).show()
            return
        }

        // Custom Layout mit Fade-Effekten
        val dialogView = layoutInflater.inflate(R.layout.dialog_list_with_fade, null)
        val listView = dialogView.findViewById<android.widget.ListView>(android.R.id.list)
        val topFade = dialogView.findViewById<View>(R.id.topFade)
        val bottomFade = dialogView.findViewById<View>(R.id.bottomFade)

        val repo = (application as MyApplication).progressionRepository
        val items = savedNames.map { name -> Pair(name, try { repo.getPreviewFor(name) } catch (_: Exception) { null }) }
        val adapter = object : ArrayAdapter<Pair<String, String?>>(this, android.R.layout.simple_list_item_2, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val v = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
                val tv1 = v.findViewById<TextView>(android.R.id.text1)
                val tv2 = v.findViewById<TextView>(android.R.id.text2)
                val item = getItem(position)!!
                tv1.text = item.first
                tv2.text = item.second ?: ""
                tv2.alpha = 0.75f
                return v
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, which, _ ->
            viewModel.loadProgression(items[which].first)
        }

        // Setup Fade-Effekte
        setupListViewFadeEffects(listView, topFade, bottomFade)

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.load_title))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            // Trigger initial fade check after dialog is shown
            listView.post {
                setupListViewFadeEffects(listView, topFade, bottomFade)
            }
        }
        dialog.show()
    }

    private fun showDeleteDialog() {
        val savedNames = viewModel.getSavedProgressionNames()
        if (savedNames.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_saved_progressions_delete), Toast.LENGTH_SHORT).show()
            return
        }

        // Custom Layout mit Fade-Effekten
        val dialogView = layoutInflater.inflate(R.layout.dialog_list_with_fade, null)
        val listView = dialogView.findViewById<android.widget.ListView>(android.R.id.list)
        val topFade = dialogView.findViewById<View>(R.id.topFade)
        val bottomFade = dialogView.findViewById<View>(R.id.bottomFade)

        val repo = (application as MyApplication).progressionRepository
        val items = savedNames.map { name -> Pair(name, try { repo.getPreviewFor(name) } catch (_: Exception) { null }) }
        val adapter = object : ArrayAdapter<Pair<String, String?>>(this, android.R.layout.simple_list_item_2, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val v = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
                val tv1 = v.findViewById<TextView>(android.R.id.text1)
                val tv2 = v.findViewById<TextView>(android.R.id.text2)
                val item = getItem(position)!!
                tv1.text = item.first
                tv2.text = item.second ?: ""
                tv2.alpha = 0.75f
                return v
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, which, _ ->
            val originalName = items[which].first
            val confirm = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
                .setTitle(getString(R.string.confirm_delete_title))
                .setMessage(getString(R.string.confirm_delete_message, originalName))
                .setPositiveButton(getString(R.string.delete_measure_title)) { _, _ ->
                    viewModel.deleteProgression(originalName)
                    Toast.makeText(this, getString(R.string.deleted_message, originalName), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            confirm.setOnShowListener { styleDialogButtons(confirm) }
            confirm.show()
        }

        // Setup Fade-Effekte
        setupListViewFadeEffects(listView, topFade, bottomFade)

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.delete_title))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            // Trigger initial fade check after dialog is shown
            listView.post {
                setupListViewFadeEffects(listView, topFade, bottomFade)
            }
        }
        dialog.show()
    }

    private fun showSaveDialog() {
        val dialogBinding = DialogSaveProgressionBinding.inflate(LayoutInflater.from(this))
        val savedNames = viewModel.getSavedProgressionNames()
        val repo = (application as MyApplication).progressionRepository
        val items = savedNames.map { name -> Pair(name, try { repo.getPreviewFor(name) } catch (_: Exception) { null }) }
        val listAdapter = object : ArrayAdapter<Pair<String, String?>>(this, android.R.layout.simple_list_item_2, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val v = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
                val tv1 = v.findViewById<TextView>(android.R.id.text1)
                val tv2 = v.findViewById<TextView>(android.R.id.text2)
                val item = getItem(position)!!
                tv1.text = item.first
                tv2.text = item.second ?: ""
                tv2.alpha = 0.75f
                return v
            }
        }
        dialogBinding.savedProgressionsListView.adapter = listAdapter
        dialogBinding.savedProgressionsListView.setOnItemClickListener { _, _, position, _ ->
            dialogBinding.saveNameEditText.setText(items[position].first)
        }

        // Setup Fade-Effekte für die ListView
        setupListViewFadeEffects(
            dialogBinding.savedProgressionsListView,
            dialogBinding.topFade,
            dialogBinding.bottomFade
        )

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.save_button), null)
            .setNegativeButton(getString(R.string.cancel), null)
             .create()
        dialog.setOnShowListener {
            // Ensure theme-styled buttons are applied
            styleDialogButtons(dialog)

            // Trigger initial fade check after dialog is shown
            dialogBinding.savedProgressionsListView.post {
                setupListViewFadeEffects(
                    dialogBinding.savedProgressionsListView,
                    dialogBinding.topFade,
                    dialogBinding.bottomFade
                )
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                 val name = dialogBinding.saveNameEditText.text.toString()
                 if (name.isBlank()) {
                     Toast.makeText(this, getString(R.string.please_enter_name), Toast.LENGTH_SHORT).show()
                     return@setOnClickListener
                 }
                 if (savedNames.contains(name)) {
                     val ov = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
                        .setTitle(getString(R.string.overwrite_title))
                        .setMessage(getString(R.string.overwrite_message, name))
                        .setPositiveButton(getString(R.string.overwrite)) { _, _ -> viewModel.saveNamedProgression(name); dialog.dismiss() }
                        .setNegativeButton(getString(R.string.cancel), null)
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
        binding.borrowedMinorContainer.visibility = newVisibility
        binding.borrowedMajorContainer.visibility = newVisibility
        binding.expandRelatedChordsButton.rotation = if (areExtraChordsExpanded) 180f else 0f

        // Force inner recyclers to layout and update fade overlays (the lists are never empty)
        binding.relatedChordRecyclerView.post {
            binding.relatedChordRecyclerView.requestLayout()
            updateRecyclerFade(binding.relatedChordRecyclerView, binding.relatedFadeLeft, binding.relatedFadeRight)
        }
        binding.borrowedMinorChordRecyclerView.post {
            binding.borrowedMinorChordRecyclerView.requestLayout()
            updateRecyclerFade(binding.borrowedMinorChordRecyclerView, binding.borrowedMinorFadeLeft, binding.borrowedMinorFadeRight)
        }
        binding.borrowedMajorChordRecyclerView.post {
            binding.borrowedMajorChordRecyclerView.requestLayout()
            updateRecyclerFade(binding.borrowedMajorChordRecyclerView, binding.borrowedMajorFadeLeft, binding.borrowedMajorFadeRight)
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
        chordAdapter = ChordAdapter({ chord ->
            // OPTIMIZATION: Start preview IMMEDIATELY on click for better responsiveness
            // Update visual state first for instant feedback
            viewModel.setSelectedChord(chord, ownerId = "MAIN", startPreviewImmediately = true)
        }, { chord ->
             // Convert chord to power chord and select it
             try {
                 val powerChord = Chord(chord.root, ChordType.POWER, chord.scaleDegreeName)
                Log.d(TAG, "MainActivity: requesting viewModel.stopPreviewNow() (power chord)")
                try {
                    viewModel.stopPreviewNow()
                    Log.d(TAG, "MainActivity: viewModel.stopPreviewNow() returned (power chord)")
                } catch (e: Exception) {
                    Log.w(TAG, "viewModel.stopPreviewNow() failed", e)
                }
                Log.d(TAG, "MainActivity: attempting to stop service preview for power chord (bound=$isBound)")
                try {
                    if (isBound && playbackService != null) {
                        try { playbackService?.stopPreviewNow(); Log.d(TAG, "MainActivity: playbackService.stopPreviewNow() returned (power chord)") } catch (e: Exception) { Log.w(TAG, "playbackService.stopPreviewNow() failed", e) }
                    } else {
                        try { PlaybackService.stopPreview(this); Log.d(TAG, "MainActivity: PlaybackService.stopPreview(companion) returned (power chord)") } catch (e: Exception) { Log.w(TAG, "PlaybackService.stopPreview(companion) failed", e) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stop service preview block failed", e)
                }
                Log.d(TAG, "MainActivity: calling PreviewCoordinator.forceStopAll() (power chord)")
                try {
                    PreviewCoordinator.forceStopAll(); Log.d(TAG, "MainActivity: PreviewCoordinator.forceStopAll() returned (power chord)")
                } catch (e: Exception) {
                    Log.w(TAG, "PreviewCoordinator.forceStopAll() failed", e)
                }
                 viewModel.setSelectedChord(powerChord, ownerId = "MAIN")
             } catch (e: Exception) {
                 Log.w(TAG, "Failed to make power chord: ${e.message}")
             }
         })
        relatedChordAdapter = ChordAdapter({ chord ->
            viewModel.setSelectedChord(chord, ownerId = "MAIN", startPreviewImmediately = true)
        }) { chord ->
            val powerChord = Chord(chord.root, ChordType.POWER, chord.scaleDegreeName)
            viewModel.setSelectedChord(powerChord, ownerId = "MAIN", startPreviewImmediately = true)
        }
        borrowedMinorChordAdapter = ChordAdapter({ chord ->
            viewModel.setSelectedChord(chord, ownerId = "MAIN", startPreviewImmediately = true)
        }) { chord ->
            val powerChord = Chord(chord.root, ChordType.POWER, chord.scaleDegreeName)
            viewModel.setSelectedChord(powerChord, ownerId = "MAIN", startPreviewImmediately = true)
        }
        borrowedMajorChordAdapter = ChordAdapter({ chord ->
            viewModel.setSelectedChord(chord, ownerId = "MAIN", startPreviewImmediately = true)
        }) { chord ->
            val powerChord = Chord(chord.root, ChordType.POWER, chord.scaleDegreeName)
            viewModel.setSelectedChord(powerChord, ownerId = "MAIN", startPreviewImmediately = true)
        }
        binding.chordRecyclerView.adapter = chordAdapter
        binding.relatedChordRecyclerView.adapter = relatedChordAdapter
        binding.borrowedMinorChordRecyclerView.adapter = borrowedMinorChordAdapter
        binding.borrowedMajorChordRecyclerView.adapter = borrowedMajorChordAdapter
        binding.chordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.relatedChordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.borrowedMinorChordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.borrowedMajorChordRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Disable item animations for instant visual feedback (no lag)
        binding.chordRecyclerView.itemAnimator = null
        binding.relatedChordRecyclerView.itemAnimator = null
        binding.borrowedMinorChordRecyclerView.itemAnimator = null
        binding.borrowedMajorChordRecyclerView.itemAnimator = null

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
            onPianoPatternClick = { index ->
                val intent = Intent(this, SoloPatternActivity::class.java)
                intent.putExtra(SoloPatternActivity.EXTRA_MEASURE_INDEX, index)
                // Pass current piano pattern to editor
                try {
                    val pp = viewModel.progression.measures.getOrNull(index)?.soloPattern
                    if (pp != null) {
                        intent.putExtra(SoloPatternActivity.EXTRA_SOLO_PATTERN_JSON, Json.encodeToString(SoloPattern.serializer(), pp))
                    }
                } catch (_: Exception) {}
                // Pass context for preview (tonic chord, key, mode, tempo)
                try {
                    val tonicChord = viewModel.progression.measures.getOrNull(index)?.chordEvents?.firstOrNull()?.chord
                    if (tonicChord != null) {
                        intent.putExtra(SoloPatternActivity.EXTRA_TONIC_CHORD_JSON, Json.encodeToString(Chord.serializer(), tonicChord))
                    }
                    intent.putExtra(SoloPatternActivity.EXTRA_KEY, viewModel.progression.key)
                    intent.putExtra(SoloPatternActivity.EXTRA_MODE, viewModel.progression.mode)
                    intent.putExtra(SoloPatternActivity.EXTRA_TEMPO, viewModel.progression.tempo)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to pass preview context to PianoPatternActivity: ${e.message}")
                }
                // Also collect all unique piano patterns used in the progression and pass them to the editor
                try {
                    val seen = mutableSetOf<String>()
                    val patterns = mutableListOf<SoloPattern>()
                    viewModel.progression.measures.forEach { m ->
                        try {
                            val p = m.soloPattern
                            val key = p.elements.joinToString(",") { it.toString() }
                            if (!seen.contains(key)) { seen.add(key); patterns.add(p) }
                        } catch (_: Exception) {}
                    }
                    if (patterns.isNotEmpty()) {
                        val arrJson = Json.encodeToString(ListSerializer(SoloPattern.serializer()), patterns)
                        intent.putExtra(SoloPatternActivity.EXTRA_ALL_PATTERNS_JSON, arrJson)
                    }
                } catch (_: Exception) {}
                drumPatternLauncher.launch(intent)
            },
            onChordDrop = { measureIndex, eighthNoteIndex, chord -> viewModel.addChordToMeasure(measureIndex, eighthNoteIndex, chord) },
            onRemoveMeasureClick = { viewModel.removeMeasure(it) },
            onDuplicateMeasureClick = { viewModel.duplicateMeasure(it) },
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
        setupRecyclerFadeOverlays(binding.borrowedMinorChordRecyclerView, binding.borrowedMinorFadeLeft, binding.borrowedMinorFadeRight)
        setupRecyclerFadeOverlays(binding.borrowedMajorChordRecyclerView, binding.borrowedMajorFadeLeft, binding.borrowedMajorFadeRight)

        val borrowedMinorLayoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val borrowedMajorLayoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // Synchronize scrolling between borrowedMinorChordRecyclerView and borrowedMajorChordRecyclerView
        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isSyncingBorrowedScroll) return  // Prevent infinite loop

                isSyncingBorrowedScroll = true
                try {
                    if (recyclerView == binding.borrowedMinorChordRecyclerView) {
                        binding.borrowedMajorChordRecyclerView.scrollBy(dx, dy)
                    } else if (recyclerView == binding.borrowedMajorChordRecyclerView) {
                        binding.borrowedMinorChordRecyclerView.scrollBy(dx, dy)
                    }
                } finally {
                    isSyncingBorrowedScroll = false
                }
            }
        }

        binding.borrowedMinorChordRecyclerView.addOnScrollListener(scrollListener)
        binding.borrowedMajorChordRecyclerView.addOnScrollListener(scrollListener)

        binding.borrowedMinorChordRecyclerView.layoutManager = borrowedMinorLayoutManager
        binding.borrowedMajorChordRecyclerView.layoutManager = borrowedMajorLayoutManager
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
            .setTitle(getString(R.string.delete_measure_title))
            .setMessage(getString(R.string.delete_measure_message))
            .setPositiveButton(getString(R.string.delete_measure_title)) { _, _ -> viewModel.confirmRemoveMeasure(measureIndex) }
            .setNegativeButton(getString(R.string.cancel), null)
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

            private var originalBackground: android.graphics.drawable.Drawable? = null

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                // Provide immediate visual feedback for the dragged view
                viewHolder?.itemView?.let { v ->
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        // Store original background before changing it
                        originalBackground = v.background
                        v.alpha = 0.95f
                        v.elevation = 24f
                        // Lighten background during drag
                        v.setBackgroundColor(Color.parseColor("#F0F0F0"))
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
                // Restore original background
                viewHolder.itemView.background = originalBackground
                originalBackground = null
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
        viewModel.borrowedMinorChords.observe(this) { chords ->
            borrowedMinorChordAdapter.submitList(chords)
            val visibility = if (!areExtraChordsExpanded) View.GONE else View.VISIBLE
            binding.borrowedChordsLabel.visibility = visibility
            binding.borrowedMinorContainer.visibility = visibility
            updateRecyclerFade(binding.borrowedMinorChordRecyclerView, binding.borrowedMinorFadeLeft, binding.borrowedMinorFadeRight)

            // Set label text to current key root note (e.g., "C" instead of "C / Am")
            binding.borrowedMinorLabel.text = viewModel.progression.key.rootNote.displayName
        }
        viewModel.borrowedMajorChords.observe(this) { chords ->
            borrowedMajorChordAdapter.submitList(chords)
            val visibility = if (!areExtraChordsExpanded) View.GONE else View.VISIBLE
            binding.borrowedMajorContainer.visibility = visibility
            updateRecyclerFade(binding.borrowedMajorChordRecyclerView, binding.borrowedMajorFadeLeft, binding.borrowedMajorFadeRight)

            // Set label text to relative minor key (e.g., "Am" for key C - 3 semitones down)
            val relativeMinorMidiOffset = (viewModel.progression.key.rootNote.midiOffset - 3 + 12) % 12
            val relativeMinorRootNote = Note.entries.first { it.midiOffset == relativeMinorMidiOffset }
            binding.borrowedMajorLabel.text = relativeMinorRootNote.displayName + "m"
        }
        viewModel.measures.observe(this) { measures ->
            val items = measures.map { MeasureAdapter.DisplayableItem.MeasureItem(it) } + MeasureAdapter.DisplayableItem.AddMeasureItem
            measureAdapter.submitList(items)
        }
        viewModel.selectedChord.observe(this) { chord ->
            chordAdapter.setSelectedChord(chord)
            relatedChordAdapter.setSelectedChord(chord)
            borrowedMinorChordAdapter.setSelectedChord(chord)
            borrowedMajorChordAdapter.setSelectedChord(chord)
        }
        viewModel.targetChord.observe(this) { chord ->
            chordAdapter.setTargetChord(chord)
            relatedChordAdapter.setTargetChord(chord)
            borrowedMinorChordAdapter.setTargetChord(chord)
            borrowedMajorChordAdapter.setTargetChord(chord)
        }
        viewModel.suggestedChord.observe(this) { chord ->
            chordAdapter.setSuggestedChord(chord)
            relatedChordAdapter.setSuggestedChord(chord)
            borrowedMinorChordAdapter.setSuggestedChord(chord)
            borrowedMajorChordAdapter.setSuggestedChord(chord)
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
        // Zeige zuerst eine Warnung, dass alles gelöscht wird
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setTitle(getString(R.string.start_new_title))
            .setMessage(getString(R.string.start_new_message))
            .setPositiveButton(getString(R.string.continue_button)) { _, _ ->
                // Wenn bestätigt, zeige Template-Auswahl
                showTemplateSelectionDialog()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                viewModel.onNewProgressionConfirmationHandled()
            }
            .setOnDismissListener { viewModel.onNewProgressionConfirmationHandled() }
            .create()
            .apply {
                setOnShowListener { styleDialogButtons(this) }
                show()
            }
    }

    private fun showTemplateSelectionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_choose_template, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.templateRecyclerView)
        val topFade = dialogView.findViewById<View>(R.id.topFade)
        val bottomFade = dialogView.findViewById<View>(R.id.bottomFade)
        val cancelButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
        val continueButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.continueButton)

        // Liste mit allen Templates + "Empty" am Anfang
        val templates = mutableListOf<ProgressionTemplate?>(null)
        templates.addAll(ProgressionTemplates.getAllTemplates())

        // Dialog ohne Standard-Buttons (wir verwenden custom Buttons)
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ChordProgressionHelper_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        // Hole die aktuelle Tonart aus dem ViewModel
        val currentKey = viewModel.key.value ?: Key.C
        val settingsRepository = (application as MyApplication).settingsRepository
        val isPreviewEnabled = settingsRepository.isTemplatePreviewEnabled

        // Variable für das aktuell ausgewählte Template
        var selectedTemplate: ProgressionTemplate? = null

        val adapter = TemplateAdapter(templates, currentKey) { template ->
            selectedTemplate = template
            continueButton.isEnabled = true

            // Preview abspielen, wenn aktiviert
            if (isPreviewEnabled) {
                previewTemplate(template, currentKey)
            }
        }

        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Scroll-Listener für Fade-Effekte
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                // Prüfe, ob wir am Anfang sind
                val canScrollUp = recyclerView.canScrollVertically(-1)
                topFade.visibility = if (canScrollUp) View.VISIBLE else View.GONE

                // Prüfe, ob wir am Ende sind
                val canScrollDown = recyclerView.canScrollVertically(1)
                bottomFade.visibility = if (canScrollDown) View.VISIBLE else View.GONE
            }
        })

        // Initiale Sichtbarkeit setzen
        recyclerView.post {
            topFade.visibility = View.GONE
            bottomFade.visibility = if (recyclerView.canScrollVertically(1)) View.VISIBLE else View.GONE
        }

        // Button-Listener
        cancelButton.setOnClickListener {
            // Preview stoppen
            playbackService?.stopPreviewNow()
            dialog.dismiss()
        }

        continueButton.setOnClickListener {
            // Preview stoppen
            playbackService?.stopPreviewNow()
            selectedTemplate?.let { template ->
                viewModel.confirmNewProgression(template)
            }
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            // Preview stoppen wenn Dialog geschlossen wird
            playbackService?.stopPreviewNow()
        }

        dialog.show()
    }

    /**
     * Spielt ein Template-Preview ab (ein Strum pro Akkord)
     */
    private fun previewTemplate(template: ProgressionTemplate?, key: Key) {
        if (template == null) {
            // Leeres Template - kein Preview
            playbackService?.stopPreviewNow()
            return
        }

        playbackService?.let { service ->
            // Erstelle eine temporäre Mini-Progression für das Preview
            val previewProgression = ProgressionTemplates.createProgressionFromTemplate(template, key)

            // Übernehme das aktuelle Tempo aus dem ViewModel
            previewProgression.tempo = viewModel.tempo.value ?: 120

            // Stoppe aktuelles Preview
            service.stopPreviewNow()

            // Starte neues Preview mit einem Strum pro Akkord
            lifecycleScope.launch {
                kotlinx.coroutines.delay(50) // Kurze Pause zwischen Previews
                service.playTemplatePreview(previewProgression)
            }
        }
    }

    /**
     * Aktiviert Fade-Effekte für eine ListView basierend auf der Scroll-Position.
     */
    private fun setupListViewFadeEffects(listView: android.widget.ListView, topFade: View, bottomFade: View) {
        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {}

            override fun onScroll(
                view: android.widget.AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                // Prüfe, ob wir am Anfang sind
                val canScrollUp = firstVisibleItem > 0 || (listView.childCount > 0 && listView.getChildAt(0).top < 0)
                topFade.visibility = if (canScrollUp) View.VISIBLE else View.GONE

                // Prüfe, ob wir am Ende sind
                val lastVisibleItem = firstVisibleItem + visibleItemCount
                val canScrollDown = lastVisibleItem < totalItemCount ||
                    (listView.childCount > 0 && listView.getChildAt(listView.childCount - 1).bottom > listView.height)
                bottomFade.visibility = if (canScrollDown) View.VISIBLE else View.GONE
            }
        })

        // Initiale Sichtbarkeit setzen
        listView.post {
            topFade.visibility = View.GONE
            val canScrollDown = listView.count > 0 &&
                (listView.childCount == 0 || listView.getChildAt(listView.childCount - 1).bottom > listView.height)
            bottomFade.visibility = if (canScrollDown) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove the preview owner listener
        previewOwnerListener?.let { PreviewCoordinator.removeListener(it) }
    }
}
