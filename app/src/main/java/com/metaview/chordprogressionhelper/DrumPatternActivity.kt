package com.metaview.chordprogressionhelper

import android.app.Activity
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
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)
class DrumPatternActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEASURE_INDEX = "extra_measure_index"
        const val EXTRA_DRUM_PATTERN_JSON = "extra_drum_pattern_json"
        private const val TAG = "DrumPatternActivity"
    }

    private var measureIndex = -1
    private var currentPattern: DrumPattern = DrumPattern.DEFAULT
    private var tonicChord: Chord? = null
    private var keyVal: Key = Key.C
    private var modeVal: Mode = Mode.MAJOR
    private var tempoVal: Int = 120
    private var isPreviewActive = false
    // Service binding to allow immediate stop control
    private var playbackService: PlaybackService? = null
    private var isServiceBound = false
    // If user requests a preview before binding completes, hold it here and start after bound
    private var pendingPreviewProgression: ChordProgression? = null
    private var pendingPreviewLooping: Boolean = false
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as? PlaybackService.LocalBinder
            playbackService = binder?.getService()
            isServiceBound = true
            // If a preview was requested before binding completed, start it now via the static helper
            try {
                val pending = pendingPreviewProgression
                if (pending != null) {
                    Log.i(TAG, "Service bound: starting pending preview")
                    // Use the companion helper to launch the service; service is already bound so direct binder methods are also available
                    PlaybackService.play(this@DrumPatternActivity, pending, true, pendingPreviewLooping)
                    pendingPreviewProgression = null
                    pendingPreviewLooping = false
                }
            } catch (e: Exception) { Log.w(TAG, "Failed to start pending preview: ${e.message}") }
            // enable Test button now that service is bound
            try {
                val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTest)
                btn.isEnabled = true
                btn.isClickable = true
                btn.isFocusable = true
                btn.alpha = 1.0f
                // set icon tint to theme primary color so it doesn't appear greyed out
                try {
                    val tv = TypedValue()
                    val resolved = theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                    val color = if (resolved) tv.data else 0xFF000000.toInt()
                    btn.iconTint = ColorStateList.valueOf(color)
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            playbackService = null
            isServiceBound = false
            // Keep the Test button enabled so the user can still create pending previews even if service is momentarily disconnected
            try {
                val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTest)
                btn.isEnabled = true
                btn.isClickable = true
                btn.isFocusable = true
                btn.alpha = 1.0f
                try {
                    val tv = TypedValue()
                    val resolved = theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                    val color = if (resolved) tv.data else 0xFF000000.toInt()
                    btn.iconTint = ColorStateList.valueOf(color)
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_drum_pattern)

        measureIndex = intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1
        val json = intent?.getStringExtra(EXTRA_DRUM_PATTERN_JSON)
        if (!json.isNullOrEmpty()) {
            try { currentPattern = Json.decodeFromString(DrumPattern.serializer(), json) } catch (_: Exception) {}
        }
        // optional preview context
        val tonicJson = intent?.getStringExtra("extra_tonic_chord_json")
        tonicChord = try { if (!tonicJson.isNullOrEmpty()) Json.decodeFromString(Chord.serializer(), tonicJson) else null } catch (_: Exception) { null }
        keyVal = try { Key.valueOf(intent?.getStringExtra("extra_key") ?: Key.C.name) } catch (_: Exception) { Key.C }
        modeVal = try { Mode.valueOf(intent?.getStringExtra("extra_mode") ?: Mode.MAJOR.name) } catch (_: Exception) { Mode.MAJOR }
        tempoVal = intent?.getIntExtra("extra_tempo", 120) ?: 120

        setupUi()
    }

    private fun setupUi() {
        val container = findViewById<LinearLayout>(R.id.drumStepsContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        currentPattern.steps.forEachIndexed { idx, step ->
            val stepLayout = inflater.inflate(R.layout.item_drum_step, container, false) as LinearLayout
            val kickIv = stepLayout.findViewById<ImageView>(R.id.kickIcon)
            val snareIv = stepLayout.findViewById<ImageView>(R.id.snareIcon)
            val hihatIv = stepLayout.findViewById<ImageView>(R.id.hihatIcon)
            fun applyState() {
                kickIv.alpha = if (step.kick) 1.0f else 0.25f
                snareIv.alpha = if (step.snare) 1.0f else 0.25f
                hihatIv.alpha = if (step.hiHat) 1.0f else 0.25f
            }
            applyState()
            kickIv.setOnClickListener {
                currentPattern = currentPattern.copy(steps = currentPattern.steps.mapIndexed { i, s -> if (i==idx) s.copy(kick = !s.kick) else s })
                applyState()
            }
            snareIv.setOnClickListener {
                currentPattern = currentPattern.copy(steps = currentPattern.steps.mapIndexed { i, s -> if (i==idx) s.copy(snare = !s.snare) else s })
                applyState()
            }
            hihatIv.setOnClickListener {
                currentPattern = currentPattern.copy(steps = currentPattern.steps.mapIndexed { i, s -> if (i==idx) s.copy(hiHat = !s.hiHat) else s })
                applyState()
            }
            val label = stepLayout.findViewById<TextView>(R.id.stepLabel)
            label.text = (idx + 1).toString()
            container.addView(stepLayout)
        }

        findViewById<View>(R.id.btnCancel).setOnClickListener {
            if (isPreviewActive) try { PlaybackService.stop(this) } catch (_: Exception) {}
            setResult(RESULT_CANCELED)
            finish()
        }
        // Test preview toggle button: start/stop looping preview
        val btnTest = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTest)
        // Ensure the Test button is available immediately (pending preview will bind/start if needed)
        try {
            btnTest.isEnabled = true
            btnTest.isClickable = true
            btnTest.isFocusable = true
            btnTest.alpha = 1.0f
            // set icon tint to theme primary color so it doesn't appear greyed out
            try {
                val tv = TypedValue()
                val resolved = theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                val color = if (resolved) tv.data else 0xFF000000.toInt()
                btnTest.iconTint = ColorStateList.valueOf(color)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        btnTest.setOnClickListener {
            Log.i(TAG, "btnTest clicked")
            try {
                if (isPreviewActive) {
                    // Stop the running preview. Prefer bound stopPlayback for immediacy, otherwise send stop request.
                    Log.i(TAG, "User requested stop preview (btn)")
                    try {
                        if (isServiceBound && playbackService != null) {
                            playbackService?.stopPlayback()
                        } else {
                            PlaybackService.stop(this)
                        }
                    } catch (e: Exception) { Log.w(TAG, "Failed to stop preview: ${e.message}") }

                    isPreviewActive = false
                    try {
                        btnTest.setIconResource(R.drawable.ic_play_arrow)
                        btnTest.contentDescription = getString(R.string.test)
                    } catch (_: Exception) {}

                    // Re-enable the Test button so the user can start another preview (pending logic works when not bound)
                    try {
                        btnTest.isEnabled = true
                        btnTest.isClickable = true
                        btnTest.isFocusable = true
                        btnTest.alpha = 1.0f
                        try {
                            val tv = TypedValue()
                            val resolved = theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                            val color = if (resolved) tv.data else 0xFF000000.toInt()
                            btnTest.iconTint = ColorStateList.valueOf(color)
                        } catch (_: Exception) {}
                    } catch (_: Exception) {}

                } else {
                    // Start a preview. Build the temporary progression first.
                    val tempProg = ChordProgression(name = "Preview", key = keyVal, mode = modeVal, tempo = tempoVal)
                    val m = Measure(1)
                    try { tonicChord?.let { m.addChord(it, 0) } } catch (_: Exception) {}
                    m.drumPattern = currentPattern
                    tempProg.measures.add(m)

                    val serialized = try { Json.encodeToString(ChordProgression.serializer(), tempProg) } catch (e: Exception) {
                        Log.w(TAG, "Failed to serialize temporary progression for preview: ${e.message}")
                        Toast.makeText(this, getString(R.string.test_preview_failed), Toast.LENGTH_SHORT).show()
                        null
                    }

                    if (serialized != null) {
                        try { PlaybackService.stop(this) } catch (_: Exception) {}
                        try {
                            if (isServiceBound) {
                                // start immediately
                                PlaybackService.play(this@DrumPatternActivity, tempProg, true, true)
                                isPreviewActive = true
                            } else {
                                // Save pending preview and ensure we bind; onServiceConnected will start it
                                pendingPreviewProgression = tempProg
                                pendingPreviewLooping = true
                                try { val bindIntent = Intent(this@DrumPatternActivity, PlaybackService::class.java); bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE) } catch (_: Exception) {}
                                isPreviewActive = true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "PlaybackService.play failed: ${e.message}")
                            Toast.makeText(this, getString(R.string.test_preview_failed), Toast.LENGTH_SHORT).show()
                        }
                        try {
                            btnTest.setIconResource(R.drawable.ic_stop)
                            btnTest.contentDescription = getString(R.string.stop)
                        } catch (_: Exception) {}

                        // Ensure Test button stays enabled so the user can always restart previews
                        try {
                            btnTest.isEnabled = true
                            btnTest.isClickable = true
                            btnTest.isFocusable = true
                            btnTest.alpha = 1.0f
                            try {
                                val tv = TypedValue()
                                val resolved = theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                                val color = if (resolved) tv.data else 0xFF000000.toInt()
                                btnTest.iconTint = ColorStateList.valueOf(color)
                            } catch (_: Exception) {}
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "btnTest click handling error: ${e.message}")
            }
        }

        // Wire OK button: serialize current pattern, return result and finish.
        val btnOk = findViewById<com.google.android.material.button.MaterialButton?>(R.id.btnOk)
        if (btnOk == null) {
            Log.w(TAG, "OK button not found (btnOk)")
        } else {
            btnOk.isEnabled = true
            btnOk.isClickable = true
            btnOk.bringToFront()
            try { btnOk.setOnLongClickListener { Log.i(TAG, "btnOk long click detected"); false } } catch (_: Exception) {}
            try {
                // Raise elevation so it's not covered by other views
                btnOk.elevation = 8f * resources.displayMetrics.density
            } catch (_: Exception) {}
            // Add a touch listener to log and ensure clicks are delivered
            try {
                btnOk.setOnTouchListener { v, event ->
                    try { Log.i(TAG, "btnOk onTouch: action=${event.action}") } catch (_: Exception) {}
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        try { v.performClick() } catch (_: Exception) {}
                    }
                    // allow normal handling
                    false
                }
            } catch (_: Exception) {}
            btnOk.setOnClickListener {
                 Log.i(TAG, "OK pressed: returning DrumPattern (measureIndex=$measureIndex)")
                 try {
                     val jsonOut = Json.encodeToString(DrumPattern.serializer(), currentPattern)
                     val out = Intent().apply {
                         putExtra(EXTRA_MEASURE_INDEX, measureIndex)
                         putExtra(EXTRA_DRUM_PATTERN_JSON, jsonOut)
                     }
                     setResult(RESULT_OK, out)
                     // Broadcast as a fallback so the main activity can update immediately even if the result path fails
                     try {
                        val action = "com.metaview.chordprogressionhelper.ACTION_DRUM_PATTERN_UPDATED"
                        val bcast = Intent(action).apply {
                            putExtra(EXTRA_MEASURE_INDEX, measureIndex)
                            putExtra(EXTRA_DRUM_PATTERN_JSON, jsonOut)
                        }
                        sendBroadcast(bcast)
                        Log.i(TAG, "Broadcast sent for drum pattern update (measure=$measureIndex)")
                     } catch (_: Exception) {}
                     Toast.makeText(this, getString(R.string.pattern_saved), Toast.LENGTH_SHORT).show()
                 } catch (e: Exception) {
                     Log.w(TAG, "Failed to serialize DrumPattern on OK: ${e.message}")
                     Toast.makeText(this, getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                 }
                 // Finish; onStop will stop/unbind the preview/service
                 finish()
             }
         }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { PlaybackService.stop(this) } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        // If a preview was running, stop it when the dialog/activity is left
        if (isPreviewActive) {
            try {
                if (isServiceBound && playbackService != null) {
                    Log.i(TAG, "onStop: stopping preview via bound service")
                    try { playbackService?.stopPlayback() } catch (e: Exception) { Log.w(TAG, "onStop: bound stopPlayback failed: ${e.message}") }
                } else {
                    Log.i(TAG, "onStop: stopping preview via companion stop()")
                    try { PlaybackService.stop(this) } catch (e: Exception) { Log.w(TAG, "onStop: companion stop failed: ${e.message}") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onStop: failed to stop preview: ${e.message}")
            }
            isPreviewActive = false
            // Reset the Test button icon to Play and ensure it's enabled so the dialog can be reused
            try {
                val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTest)
                btn.setIconResource(R.drawable.ic_play_arrow)
                btn.contentDescription = getString(R.string.test)
                btn.isEnabled = true
                btn.isClickable = true
                btn.isFocusable = true
                btn.alpha = 1.0f
            } catch (_: Exception) {}
        }

        // If we are bound to the playback service, unbind now to avoid leaks
        if (isServiceBound) {
            try { unbindService(serviceConnection) } catch (e: Exception) { Log.w(TAG, "onStop: unbindService failed: ${e.message}") }
            isServiceBound = false
            playbackService = null
        }

        // Clear any pending preview that wasn't started yet
        pendingPreviewProgression = null
        pendingPreviewLooping = false
    }

    override fun onResume() {
        super.onResume()
        try {
            val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTest)
            btn.isEnabled = true
            btn.isClickable = true
            btn.isFocusable = true
            btn.alpha = 1.0f
            btn.visibility = View.VISIBLE
            try {
                val tv = TypedValue()
                val resolved = theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                val color = if (resolved) tv.data else 0xFF000000.toInt()
                btn.iconTint = ColorStateList.valueOf(color)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }
}
