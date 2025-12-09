package com.metaview.chordprogressionhelper

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.metaview.chordprogressionhelper.databinding.DialogStrummingPatternBinding
import com.metaview.chordprogressionhelper.model.*
import com.metaview.chordprogressionhelper.service.PlaybackService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class StrummingPatternActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEASURE_INDEX = "extra_measure_index"
        const val EXTRA_STRUMMING_PATTERN_JSON = "extra_strumming_pattern_json"
        const val EXTRA_TONIC_CHORD_JSON = "extra_tonic_chord_json"
        const val EXTRA_ALL_PATTERNS_JSON = "extra_all_patterns_json"
        private const val TAG = "StrummingPatternActivity"
    }

    private lateinit var binding: DialogStrummingPatternBinding
    private var isPreviewActive = false
    private var measureIndex = -1
    private lateinit var currentStrums: MutableList<Strum>
    // Preview context passed from MainActivity
    private var tonicChord: Chord? = null
    private var keyVal: Key = Key.C
    private var modeVal: Mode = Mode.MAJOR
    private var tempoVal: Int = 120
    // Patterns provided externally (e.g. all patterns used in song)
    private var extraPatterns: List<StrummingPattern> = emptyList()

    // Bind to PlaybackService to check if main playback is running
    private var playbackService: PlaybackService? = null
    private var isServiceBound = false
    // If a preview is requested before the service is bound, store it here and start when bound
    private var pendingPreviewProgression: ChordProgression? = null
    private var pendingPreviewLooping: Boolean = false
    private var playbackStateJob: Job? = null
    // When false previews are suppressed and UI disabled
    private var previewsAllowed: Boolean = true
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
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
            try {
                val pending = pendingPreviewProgression
                // If the activity is finishing or destroyed, don't start a pending preview; just clear it
                if (isFinishing || isDestroyed) {
                    pendingPreviewProgression = null
                    pendingPreviewLooping = false
                } else if (pending != null) {
                    Log.i(TAG, "Service bound: starting pending preview")
                    PlaybackService.play(this@StrummingPatternActivity, pending, true, pendingPreviewLooping)
                    pendingPreviewProgression = null
                    pendingPreviewLooping = false
                    isPreviewActive = true
                    setPreviewsAllowed(previewsAllowed)
                    try {
                        val btnTest = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTest)
                        btnTest.setIconResource(R.drawable.ic_stop)
                        btnTest.contentDescription = getString(R.string.stop)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) { Log.w(TAG, "Failed to start pending preview: ${e.message}") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
            playbackStateJob?.cancel()
            playbackStateJob = null
            setPreviewsAllowed(true)
        }
    }

    private fun setPreviewsAllowed(allowed: Boolean) {
        previewsAllowed = allowed
        // Disable/enable Test button
        try {
            val btnTest = binding.root.findViewById<MaterialButton>(R.id.btnTest)
            // Keep Test enabled while a local preview is active so the user can stop it
            val testEnabled = allowed || isPreviewActive
            btnTest.isEnabled = testEnabled
            btnTest.alpha = if (testEnabled) 1.0f else 0.45f
        } catch (_: Exception) {}
        // Disable/enable chips
        try {
            val editor = binding.strummingPatternEditor
            for (i in 0 until editor.childCount) {
                val child = editor.getChildAt(i)
                // Keep editor chips disabled when previews are not allowed
                child.isEnabled = allowed
                child.alpha = if (allowed) 1.0f else 0.45f
            }
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Use the fullscreen dialog theme via manifest/theme. setTheme not required here.
        super.onCreate(savedInstanceState)
        binding = DialogStrummingPatternBinding.inflate(layoutInflater)
        setContentView(binding.root)

        measureIndex = intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1
        val startPattern = (intent?.getStringExtra(EXTRA_STRUMMING_PATTERN_JSON))?.let {
            try { Json.decodeFromString(StrummingPattern.serializer(), it) } catch (_: Exception) { null }
        } ?: StrummingPattern.DEFAULT

        // Try to deserialize a tonic chord (first scale degree) and other context provided by MainActivity for preview playback
        val tonicJson = intent?.getStringExtra(EXTRA_TONIC_CHORD_JSON)
        tonicChord = try { if (!tonicJson.isNullOrEmpty()) Json.decodeFromString(Chord.serializer(), tonicJson) else null } catch (_: Exception) { null }
        keyVal = try { Key.valueOf(intent?.getStringExtra("extra_key") ?: Key.C.name) } catch (_: Exception) { Key.C }
        modeVal = try { Mode.valueOf(intent?.getStringExtra("extra_mode") ?: Mode.MAJOR.name) } catch (_: Exception) { Mode.MAJOR }
        tempoVal = intent?.getIntExtra("extra_tempo", 120) ?: 120

        currentStrums = startPattern.strums.toMutableList()

        // Parse any externally-provided list of all patterns used in the progression
        try {
            val allJson = intent?.getStringExtra(EXTRA_ALL_PATTERNS_JSON)
            if (!allJson.isNullOrEmpty()) {
                extraPatterns = try { Json.decodeFromString(ListSerializer(StrummingPattern.serializer()), allJson) } catch (_: Exception) { emptyList() }
            }
        } catch (_: Exception) { extraPatterns = emptyList() }

        setupFadesAndScroll()
        setupStrumChips()
        setupDefaultPatterns()
        setupButtons()
        updateStrumViews()
    }

    override fun onStart() {
        super.onStart()
        // Bind to playback service to query playing state
        try {
            val intent = Intent(this, PlaybackService::class.java)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        // Ensure any active preview is stopped immediately when dialog is left
        if (isPreviewActive) {
            try {
                if (isServiceBound && playbackService != null) playbackService?.stopPlayback() else PlaybackService.stop(this)
            } catch (_: Exception) {}
            isPreviewActive = false
            // update UI after stopping preview
            setPreviewsAllowed(previewsAllowed)
            try {
                val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTest)
                btn.setIconResource(R.drawable.ic_play_arrow)
                btn.contentDescription = getString(R.string.test)
            } catch (_: Exception) {}
        }
        // If binding is in progress but a pending preview was set, clear it so it won't start after onStop
        pendingPreviewProgression = null
        pendingPreviewLooping = false

        if (isServiceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isServiceBound = false
            playbackService = null
            playbackStateJob?.cancel()
            playbackStateJob = null
            setPreviewsAllowed(true)
        }
    }

    private fun setupFadesAndScroll() {
        try {
            val scroll = binding.strumEditorScroll
            val fadeLeft = binding.root.findViewById<View>(R.id.fadeLeft)
            val fadeRight = binding.root.findViewById<View>(R.id.fadeRight)
            fadeLeft.visibility = View.GONE
            fadeRight.visibility = View.GONE

            fun updateDialogFades() {
                val canLeft = scroll.canScrollHorizontally(-1)
                val canRight = scroll.canScrollHorizontally(1)
                fadeLeft.visibility = if (canLeft) View.VISIBLE else View.GONE
                fadeRight.visibility = if (canRight) View.VISIBLE else View.GONE
            }

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
            try { scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateDialogFades() } } catch (_: Exception) { scroll.viewTreeObserver.addOnScrollChangedListener { updateDialogFades() } }
            scroll.post { updateDialogFades() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setup dialog fades", e)
        }
    }

    private fun setupStrumChips() {
        val inflater = layoutInflater
        binding.strummingPatternEditor.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.pattern_icon_size)
        for (i in 0 until 8) {
            val chip = inflater.inflate(R.layout.item_strum_chip, binding.strummingPatternEditor, false)
            chip.layoutParams = LinearLayout.LayoutParams(size, size, 0f).apply { setMargins(4, 4, 4, 4) }
            chip.setOnClickListener {
                // Chips only toggle the strum value and update the UI. No preview on chip clicks.
                currentStrums[i] = when (currentStrums[i]) {
                    Strum.DOWN -> Strum.UP
                    Strum.UP -> Strum.MUTE
                    Strum.MUTE -> Strum.REST
                    Strum.REST -> Strum.LETRING
                    Strum.LETRING -> Strum.DOWN
                }
                updateStrumViews()
                // If service bound and a valid measure index is provided, inform the service to update the pattern
                try {
                    if (isServiceBound && measureIndex >= 0) {
                        val newPattern = StrummingPattern("Custom", currentStrums.toList())
                        playbackService?.updateStrummingPattern(measureIndex, newPattern)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send pattern update to service: ${e.message}")
                }
            }
            binding.strummingPatternEditor.addView(chip)
        }
    }

    private fun setupDefaultPatterns() {
        binding.defaultPatternsLayout.removeAllViews()
        try { binding.defaultPatternsLayout.orientation = LinearLayout.VERTICAL } catch (_: Exception) {}
        val itemPadding = (2 * resources.displayMetrics.density).toInt()
        val iconSize = (20 * resources.displayMetrics.density).toInt()
        val rowMargin = (1 * resources.displayMetrics.density).toInt()
        val iconMargin = 0
        val nameMargin = resources.getDimensionPixelSize(R.dimen.pattern_name_margin)

        // Build sets to avoid duplicates
        val seen = mutableSetOf<String>()
        fun keyOf(p: StrummingPattern) = p.strums.joinToString(",") { it.name }

        // If there are extraPatterns (from the progression) show them first with a header
        try {
            if (extraPatterns.isNotEmpty()) {
                val header = TextView(this).apply {
                    text = getString(R.string.used_patterns_header)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(itemPadding, itemPadding / 2, itemPadding, itemPadding / 4)
                    try { val tv = android.util.TypedValue(); if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) setTextColor(tv.data) } catch (_: Exception) {}
                    try { typeface = ResourcesCompat.getFont(context, R.font.roboto_mono) } catch (_: Exception) {}
                }
                binding.defaultPatternsLayout.addView(header)

                extraPatterns.forEach { p ->
                    try {
                        val k = keyOf(p)
                        if (seen.contains(k)) return@forEach
                        seen.add(k)
                        // create row for this pattern (reuse existing row-building code below)
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(rowMargin, rowMargin, rowMargin, rowMargin) }
                            setPadding(itemPadding, itemPadding / 2, itemPadding, itemPadding / 2)
                            isClickable = true
                            isFocusable = true
                            val tv = android.util.TypedValue()
                            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) foreground = ContextCompat.getDrawable(context, tv.resourceId)
                        }
                        p.strums.forEach { s ->
                            val iv = ImageView(this).apply {
                                setImageResource(strumToDrawable(s))
                                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { setMargins(iconMargin, 0, iconMargin, 0) }
                                try { val tv = android.util.TypedValue(); if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) setColorFilter(tv.data) } catch (_: Exception) {}
                            }
                            row.addView(iv)
                        }
                        val safeName = p.name
                            .replace('\u2013', '-')
                            .replace('\u2014', '-')
                            .replace('\u2010', '-')
                            .replace('\u2011', '-')
                        val nameTv = TextView(this).apply {
                            text = safeName
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(nameMargin, 0, 0, 0) }
                            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                            isSingleLine = true
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setHorizontallyScrolling(true)
                            includeFontPadding = false
                            letterSpacing = 0f
                            try { val tv = android.util.TypedValue(); if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) setTextColor(tv.data) } catch (_: Exception) {}
                        }
                        try { val embedded = ResourcesCompat.getFont(this, R.font.roboto_mono); if (embedded != null) nameTv.typeface = embedded } catch (_: Exception) {}
                        row.addView(nameTv)

                        row.setOnClickListener {
                            if (!previewsAllowed) return@setOnClickListener
                            p.strums.forEachIndexed { idx, s -> if (idx < currentStrums.size) currentStrums[idx] = s }
                            updateStrumViews()
                            try {
                                val livePattern = StrummingPattern(p.name, p.strums.toList())
                                val chord = tonicChord
                                if (chord != null) {
                                    val tempProg = ChordProgression(name = "Preview", key = keyVal, mode = modeVal, tempo = tempoVal)
                                    try { tempProg.measures.clear() } catch (_: Exception) {}
                                    val m = Measure(1)
                                    try { m.addChord(chord, 0) } catch (_: Exception) {}
                                    m.strummingPattern = livePattern
                                    tempProg.measures.add(m)
                                    try { PlaybackService.stop(this) } catch (_: Exception) {}
                                    PlaybackService.play(this, tempProg, true)
                                    isPreviewActive = true
                                    // re-evaluate preview-enabled UI so Test button remains enabled for local preview
                                    setPreviewsAllowed(previewsAllowed)
                                 }
                             } catch (e: Exception) { Log.w(TAG, "Failed to handle pattern click: ${e.message}") }
                         }

                        binding.defaultPatternsLayout.addView(row)
                    } catch (_: Exception) {}
                }

                // add a thin divider before defaults
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt())
                    try {
                        val tv = android.util.TypedValue()
                        val color = if (theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true)) tv.data else android.graphics.Color.WHITE
                        setBackgroundColor(color)
                    } catch (_: Exception) {}
                }
                binding.defaultPatternsLayout.addView(divider)
            }
        } catch (_: Exception) {}

        // Now render default patterns with a header (skip those already seen)
        try {
            val headerDefaults = TextView(this).apply {
                text = getString(R.string.other_patterns_header)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(itemPadding, itemPadding / 2, itemPadding, itemPadding / 4)
                try { val tv = android.util.TypedValue(); if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) setTextColor(tv.data) } catch (_: Exception) {}
                try { typeface = ResourcesCompat.getFont(context, R.font.roboto_mono) } catch (_: Exception) {}
            }
            binding.defaultPatternsLayout.addView(headerDefaults)
        } catch (_: Exception) {}

        StrummingPattern.defaultPatterns.forEach { pattern ->
            try {
                val k = keyOf(pattern)
                if (seen.contains(k)) return@forEach
                seen.add(k)
            } catch (_: Exception) {}
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(rowMargin, rowMargin, rowMargin, rowMargin) }
                setPadding(itemPadding, itemPadding / 2, itemPadding, itemPadding / 2)
                isClickable = true
                isFocusable = true
                val tv = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) foreground = ContextCompat.getDrawable(context, tv.resourceId)
            }
            pattern.strums.forEach { s ->
                val iv = ImageView(this).apply {
                    setImageResource(strumToDrawable(s))
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { setMargins(iconMargin, 0, iconMargin, 0) }
                    try { val tv = android.util.TypedValue(); if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) setColorFilter(tv.data) } catch (_: Exception) {}
                }
                row.addView(iv)
            }
            // Add pattern name on the right side of the row (takes remaining space, right-aligned)
            try {
                // sanitize name to avoid font-fallback for special dashes/characters
                val safeName = pattern.name
                    .replace('\u2013', '-') // en dash
                    .replace('\u2014', '-') // em dash
                    .replace('\u2010', '-') // hyphen
                    .replace('\u2011', '-') // non-breaking hyphen
                val nameTv = TextView(this).apply {
                    // set sanitized text
                    text = safeName
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(nameMargin, 0, 0, 0) }
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setHorizontallyScrolling(true)
                    includeFontPadding = false
                    letterSpacing = 0f
                    try { val tv = android.util.TypedValue(); if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)) setTextColor(tv.data) } catch (_: Exception) {}
                }
                // Request the monospace font via the Google Fonts provider and apply it when ready.
                // Prefer an embedded TTF if present (place RobotoMono-Regular.ttf as res/font/roboto_mono.ttf)
                try {
                    val embedded = ResourcesCompat.getFont(this, R.font.roboto_mono)
                    if (embedded != null) {
                        nameTv.typeface = embedded
                    } else {
                        requestAndApplyMonospace(nameTv)
                    }
                } catch (_: Exception) {
                    requestAndApplyMonospace(nameTv)
                }
                row.addView(nameTv)
            } catch (_: Exception) {}

            row.setOnClickListener {
                // If previews are not allowed, ignore the click
                if (!previewsAllowed) return@setOnClickListener

                pattern.strums.forEachIndexed { idx, s -> if (idx < currentStrums.size) currentStrums[idx] = s }
                updateStrumViews()
                // play preview with tonic if available
                try {
                    val livePattern = StrummingPattern(pattern.name, pattern.strums.toList())
                    val chord = tonicChord
                    if (chord != null) {
                        val tempProg = ChordProgression(name = "Preview", key = keyVal, mode = modeVal, tempo = tempoVal)
                        try { tempProg.measures.clear() } catch (_: Exception) {}
                        val m = Measure(1)
                        try { m.addChord(chord, 0) } catch (_: Exception) {}
                        m.strummingPattern = livePattern
                        tempProg.measures.add(m)
                        try { PlaybackService.stop(this) } catch (_: Exception) {}
                        PlaybackService.play(this, tempProg, true)
                        isPreviewActive = true
                        // ensure UI reflects that a local preview is active (keeps Test enabled)
                        setPreviewsAllowed(previewsAllowed)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to handle default pattern click: ${e.message}")
                }
            }
            binding.defaultPatternsLayout.addView(row)
        }
    }

    private fun strumToDrawable(s: Strum): Int = when (s) {
        Strum.DOWN -> R.drawable.ic_strum_down
        Strum.UP -> R.drawable.ic_strum_up
        Strum.MUTE -> R.drawable.ic_strum_mute
        Strum.LETRING -> R.drawable.ic_strum_letring
        Strum.REST -> R.drawable.ic_strum_rest
    }

    private fun updateStrumViews() {
        val views = binding.strummingPatternEditor
        for (i in 0 until views.childCount) {
            val view = views.getChildAt(i)
            val iconView = view.findViewById<ImageView?>(R.id.strumChipIcon)
            val strum = currentStrums.getOrNull(i) ?: Strum.DOWN
            val drawableId = strumToDrawable(strum)
            iconView?.setImageResource(drawableId)
            view.contentDescription = getString(R.string.strum_content_description, strum.name, i + 1)
        }
    }

    private fun setupButtons() {
        val btnOk = binding.root.findViewById<MaterialButton>(R.id.btnOk)
        val btnCancel = binding.root.findViewById<MaterialButton>(R.id.btnCancel)
        val btnTest = binding.root.findViewById<MaterialButton>(R.id.btnTest)

        btnOk.setOnClickListener {
            try {
                val pattern = StrummingPattern("Custom", currentStrums.toList())
                val json = Json.encodeToString(pattern)
                val intent = intent
                intent.putExtra(EXTRA_MEASURE_INDEX, measureIndex)
                intent.putExtra(EXTRA_STRUMMING_PATTERN_JSON, json)
                setResult(RESULT_OK, intent)
                if (isPreviewActive) try { PlaybackService.stop(this) } catch (_: Exception) {}
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save strumming pattern", e)
                Log.w(TAG, "Failed to save strumming pattern: ${e.message}")
            }
        }

        btnCancel.setOnClickListener {
            if (isPreviewActive) try { PlaybackService.stop(this) } catch (_: Exception) {}
            setResult(RESULT_CANCELED)
            finish()
        }

        btnTest.setOnClickListener {
            try {
                // Allow interacting with the Test toggle when a local preview is active
                if (!previewsAllowed && !isPreviewActive) return@setOnClickListener

                if (!isPreviewActive) {
                    // start looping preview
                    val livePattern = StrummingPattern("Test", currentStrums.toList())
                    val tempProg = ChordProgression(name = "Preview", key = keyVal, mode = modeVal, tempo = tempoVal)
                    tempProg.measures.clear()
                    val m = Measure(1)
                    try { tonicChord?.let { m.addChord(it, 0) } } catch (_: Exception) {}
                    m.strummingPattern = livePattern
                    // Ensure no drums are played during a strumming-only preview
                    try { m.drumPattern = com.metaview.chordprogressionhelper.model.DrumPattern("Silent", List(8) { com.metaview.chordprogressionhelper.model.DrumStep() }) } catch (_: Exception) {}
                    tempProg.measures.add(m)
                    try { PlaybackService.stop(this) } catch (_: Exception) {}
                    try {
                        if (isServiceBound) {
                            // start immediately via companion
                            PlaybackService.play(this, tempProg, true, true)
                            isPreviewActive = true
                        } else {
                            // Save pending preview and bind; it will start in onServiceConnected
                            pendingPreviewProgression = tempProg
                            pendingPreviewLooping = true
                            try { val bindIntent = Intent(this, PlaybackService::class.java); bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE) } catch (_: Exception) {}
                            isPreviewActive = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "PlaybackService.play failed: ${e.message}")
                    }
                    // ensure UI reflects that a local preview is active (keeps Test enabled)
                    setPreviewsAllowed(previewsAllowed)
                    try {
                        (btnTest as? com.google.android.material.button.MaterialButton)?.apply {
                            setIconResource(R.drawable.ic_stop)
                            contentDescription = getString(R.string.stop)
                        }
                    } catch (_: Exception) {}
                } else {
                    // stop looping preview - prefer bound stop to avoid timing foreground issues
                    try {
                        if (isServiceBound && playbackService != null) {
                            playbackService?.stopPlayback()
                        } else {
                            PlaybackService.stop(this)
                        }
                    } catch (e: Exception) { Log.w(TAG, "Failed to stop preview: ${e.message}") }
                    isPreviewActive = false
                    // update UI after stopping preview
                    setPreviewsAllowed(previewsAllowed)
                    try {
                        (btnTest as? com.google.android.material.button.MaterialButton)?.apply {
                            setIconResource(R.drawable.ic_play_arrow)
                            contentDescription = getString(R.string.test)
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Test toggle failed: ${e.message}")
            }
        }
    }

    private fun requestAndApplyMonospace(textView: TextView) {
        // Fallback: Verwende die System-Monospace-Schrift. Die eingebettete Roboto-Mono wird
        // bereits an den Aufrufstellen bevorzugt geladen (ResourcesCompat.getFont).
        // Entferne die ursprüngliche FontRequest.Builder-Verwendung, die in diesem Projekt
        // zu einem "Unresolved reference 'Builder'" Compilerfehler geführt hat.
        try {
            textView.typeface = android.graphics.Typeface.MONOSPACE
        } catch (_: Exception) {
            // if anything goes wrong, ensure we at least don't crash
            textView.typeface = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure nothing is left playing and clear pending previews
        try {
            if (isPreviewActive) {
                if (isServiceBound && playbackService != null) playbackService?.stopPlayback() else PlaybackService.stop(this)
            }
        } catch (_: Exception) {}
        isPreviewActive = false
        pendingPreviewProgression = null
        pendingPreviewLooping = false
    }
}
