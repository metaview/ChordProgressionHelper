package de.metaviewsoft.chordprogressionhelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import de.metaviewsoft.chordprogressionhelper.SongActivity
import de.metaviewsoft.chordprogressionhelper.R
import de.metaviewsoft.chordprogressionhelper.data.ProgressionStore
import de.metaviewsoft.chordprogressionhelper.data.SettingsRepository
import de.metaviewsoft.chordprogressionhelper.model.ChordProgression
import de.metaviewsoft.chordprogressionhelper.model.StrummingPattern
import de.metaviewsoft.chordprogressionhelper.model.DrumPattern
import de.metaviewsoft.chordprogressionhelper.model.DrumStep
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.Mode
import de.metaviewsoft.chordprogressionhelper.model.SoloPattern
import de.metaviewsoft.chordprogressionhelper.model.Strum
import de.metaviewsoft.chordprogressionhelper.util.AudioPlayer
import de.metaviewsoft.chordprogressionhelper.util.AudioThreadReadyCallback
import de.metaviewsoft.chordprogressionhelper.util.PreviewCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PlaybackService : Service() {
    private val TAG = "PlaybackService"

    private val binder = LocalBinder()
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var notificationManager: NotificationManager

    private var playbackJob: Job? = null
    private var currentProgression: ChordProgression? = null
    // When a preview is running, it must be independent from currentProgression
    private var previewProgression: ChordProgression? = null
    private var pausedPosition: Pair<Int, Int>? = null
    // If true the currently-loaded progression was launched as a temporary preview
    private var currentIsPreview: Boolean = false
    private var currentIsLoopingPreview: Boolean = false
    // If true the playback is a full-song playback (SongActivity); uses separate count-in setting
    private var currentIsSong: Boolean = false

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentPlaybackPosition = MutableStateFlow<Pair<Int, Int>?>(null)
    val currentPlaybackPosition = _currentPlaybackPosition.asStateFlow()

    private lateinit var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener

    // mediaSession removed for now to avoid dependency issues; keep notification MediaStyle
    private lateinit var mediaSession: MediaSessionCompat
    private var playbackPositionMs: Long = 0L

    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: initializing PlaybackService")
        audioPlayer = AudioPlayer()
        settingsRepository = SettingsRepository(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        // Ensure we register as a foreground service quickly to satisfy platform timing rules.
        try {
            val preparing = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Playback service")
                .setContentText("Ready")
                .setSmallIcon(R.drawable.ic_music_note)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
            try { startForeground(NOTIFICATION_ID, preparing) } catch (e: Exception) { Log.w(TAG, "early startForeground in onCreate failed: ${e.message}") }
        } catch (e: Exception) { Log.w(TAG, "failed to build early notification: ${e.message}") }

        // Callback wenn AudioThread bereit ist
        audioPlayer.audioThreadReadyCallback = object : AudioThreadReadyCallback {
            override fun onAudioThreadReady() {
                Log.d(TAG, "AudioThread ist bereit, starten Initialization Playback")
                // Starte das Warmup-Playback eines stillen Takts
                warmupAudioSystem()
            }
        }

        // Initialize audioPlayer live params from settings
        audioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
        audioPlayer.soloLevel = settingsRepository.soloLevel.toDouble()
        audioPlayer.strumLevel = settingsRepository.strumLevel.toDouble()
        audioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
        audioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
        audioPlayer.voicePreset = settingsRepository.strumPreset
        audioPlayer.soloPreset = settingsRepository.soloPreset
        // stroke offset (ms) for UP strokes
        audioPlayer.upStrokeOffsetMs = settingsRepository.strokeOffsetMs
        audioPlayer.upStringStaggerMs = settingsRepository.stringStaggerMs
        audioPlayer.downStrokeOffsetMs = settingsRepository.downStrokeOffsetMs
        audioPlayer.downStringStaggerMs = settingsRepository.downStringStaggerMs
        // Initialize shuffle factor from settings
        audioPlayer.shuffleFactor = settingsRepository.shuffleFactor.toFloat()
        // Initialize crunch levels from settings
        audioPlayer.strumCrunchLevel = settingsRepository.strumCrunchLevel
        audioPlayer.soloCrunchLevel = settingsRepository.soloCrunchLevel
        audioPlayer.masterVolume = settingsRepository.masterVolume.toDouble()

        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SettingsRepository.KEY_DRUM_LEVEL -> audioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
                SettingsRepository.KEY_SOLO_LEVEL -> audioPlayer.soloLevel = settingsRepository.soloLevel.toDouble()
                SettingsRepository.KEY_STRUM_LEVEL -> audioPlayer.strumLevel = settingsRepository.strumLevel.toDouble()
                SettingsRepository.KEY_ENVELOPE_SCALE -> audioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
                SettingsRepository.KEY_HIHAT_HIGHPASS -> audioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
                SettingsRepository.KEY_STRUM_PRESET -> audioPlayer.voicePreset = settingsRepository.strumPreset
                SettingsRepository.KEY_SOLO_PRESET -> audioPlayer.soloPreset = settingsRepository.soloPreset
                SettingsRepository.KEY_STROKE_OFFSET_MS -> audioPlayer.upStrokeOffsetMs = settingsRepository.strokeOffsetMs
                SettingsRepository.KEY_STRING_STAGGER_MS -> audioPlayer.upStringStaggerMs = settingsRepository.stringStaggerMs
                SettingsRepository.KEY_DOWN_STROKE_OFFSET_MS -> audioPlayer.downStrokeOffsetMs = settingsRepository.downStrokeOffsetMs
                SettingsRepository.KEY_DOWN_STRING_STAGGER_MS -> audioPlayer.downStringStaggerMs = settingsRepository.downStringStaggerMs
                SettingsRepository.KEY_SHUFFLE_FACTOR -> audioPlayer.shuffleFactor = settingsRepository.shuffleFactor.toFloat()
                SettingsRepository.KEY_STRUM_CRUNCH_LEVEL -> audioPlayer.strumCrunchLevel = settingsRepository.strumCrunchLevel
                SettingsRepository.KEY_SOLO_CRUNCH_LEVEL -> audioPlayer.soloCrunchLevel = settingsRepository.soloCrunchLevel
                SettingsRepository.KEY_MASTER_VOLUME -> audioPlayer.masterVolume = settingsRepository.masterVolume.toDouble()
            }
        }
        settingsRepository.registerChangeListener(prefsListener)

        // Initialize MediaSessionCompat for system integration (lockscreen, car, media buttons)
        mediaSession = MediaSessionCompat(this, "ChordProgressionHelperSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    currentProgression?.let { this@PlaybackService.startPlayback(it) }
                }

                override fun onPause() {
                    this@PlaybackService.pausePlayback()
                }

                override fun onStop() {
                    this@PlaybackService.stopPlayback()
                }

                override fun onSeekTo(pos: Long) {
                    // Not implemented: could set playback position for audioPlayer
                    playbackPositionMs = pos
                }
            })
            // No explicit flags set; default behavior is sufficient and avoids deprecated constants
            isActive = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsRepository.unregisterChangeListener(prefsListener)
        try {
            // Ensure audio and jobs are stopped when service is destroyed
            stopPlayback()
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: stopPlayback failed: ${e.message}")
        }
        try {
            mediaSession.release()
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        // Ensure we call startForeground as soon as possible (only once) to satisfy platform timing.
        if (!isForegroundStarted) {
            try {
                createNotificationChannel()
                val preparing = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Playback service")
                    .setContentText("Starting...")
                    .setSmallIcon(R.drawable.ic_music_note)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()
                startForeground(NOTIFICATION_ID, preparing)
                isForegroundStarted = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start foreground early: ${e.message}")
                // If startForeground fails, still continue; the system may still enforce the rule, but we try our best.
            }
        }

        // If the intent is an update params action, apply parameters and return quickly
        if (intent?.action == ACTION_UPDATE_PARAMS) {
             try {
                 if (intent.hasExtra(EXTRA_TEMPO)) {
                     setTempo(intent.getIntExtra(EXTRA_TEMPO, settingsRepository.defaultBpm))
                 }
                 val drum = intent.getFloatExtra(EXTRA_DRUM_LEVEL, settingsRepository.drumLevel)
                 val solo = intent.getFloatExtra(EXTRA_SOLO_LEVEL, settingsRepository.soloLevel)
                 val strum = intent.getFloatExtra(EXTRA_STRUM_LEVEL, settingsRepository.strumLevel)
                 val env = intent.getFloatExtra(EXTRA_ENVELOPE_SCALE, settingsRepository.envelopeScale)
                 val hh = intent.getFloatExtra(EXTRA_HIHAT_HIGHPASS, settingsRepository.hiHatHighpass)
                 audioPlayer.drumLevel = drum.toDouble()
                 audioPlayer.soloLevel = solo.toDouble()
                 audioPlayer.strumLevel = strum.toDouble()
                 audioPlayer.envelopeScale = env.toDouble()
                 audioPlayer.hiHatHighpass = hh.toDouble()
                // strum timing params (ms)
                try { audioPlayer.upStrokeOffsetMs = intent.getIntExtra(EXTRA_UP_STROKE_OFFSET_MS, settingsRepository.strokeOffsetMs) } catch (_: Exception) {}
                try { audioPlayer.upStringStaggerMs = intent.getIntExtra(EXTRA_UP_STRING_STAGGER_MS, settingsRepository.stringStaggerMs) } catch (_: Exception) {}
                try { audioPlayer.downStrokeOffsetMs = intent.getIntExtra(EXTRA_DOWN_STROKE_OFFSET_MS, settingsRepository.downStrokeOffsetMs) } catch (_: Exception) {}
                try { audioPlayer.downStringStaggerMs = intent.getIntExtra(EXTRA_DOWN_STRING_STAGGER_MS, settingsRepository.downStringStaggerMs) } catch (_: Exception) {}
                // crunch level params
                try { audioPlayer.strumCrunchLevel = intent.getFloatExtra(EXTRA_STRUM_CRUNCH_LEVEL, settingsRepository.strumCrunchLevel) } catch (_: Exception) {}
                try { audioPlayer.soloCrunchLevel = intent.getFloatExtra(EXTRA_SOLO_CRUNCH_LEVEL, settingsRepository.soloCrunchLevel) } catch (_: Exception) {}
             } catch (e: Exception) {
                 Log.w(TAG, "Failed to update params from intent: ${e.message}")
             }
             return START_NOT_STICKY
         }

        // If the intent is an updated progression, parse and replace the current progression so
        // live edits from editors (e.g. Drum/Strum editors) take effect immediately.
        if (intent?.action == ACTION_UPDATE_PROGRESSION) {
            try {
                val progJson = intent.getStringExtra(EXTRA_PROGRESSION)
                progJson?.let { prog ->
                    val firstIdx = prog.indexOfFirst { c -> c == '{' || c == '[' }
                    val trimmedProg = if (firstIdx >= 0 && firstIdx > 0) prog.substring(firstIdx) else prog
                    var parsed: ChordProgression? = null
                    try {
                        parsed = Json.decodeFromString<ChordProgression>(trimmedProg)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateProgression: parse failed: ${e.message}")
                        try {
                            val normalized = normalizeLooseJson(trimmedProg)
                            parsed = try { Json.decodeFromString<ChordProgression>(normalized) } catch (_: Exception) { null }
                        } catch (e2: Exception) { Log.w(TAG, "updateProgression normalization failed: ${e2.message}") }
                    }
                    if (parsed != null) {
                        // Delegate to helper that applies updates to either previewProgression
                        // or currentProgression depending on what is playing.
                        applyUpdatedProgression(parsed)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ACTION_UPDATE_PROGRESSION handling failed: ${e.message}")
            }
            return START_NOT_STICKY
        }

        // Dispatch play handling onto a coroutine so onStartCommand returns promptly after calling startForeground
        when (intent?.action) {
            ACTION_PLAY -> {
                Log.d(TAG, "onStartCommand: ACTION_PLAY received")
                // capture preview flag and then parse/load progression in background
                currentIsPreview = intent.getBooleanExtra(EXTRA_IS_PREVIEW, false)
                currentIsLoopingPreview = intent.getBooleanExtra(EXTRA_IS_LOOPING_PREVIEW, false)
                currentIsSong = intent.getBooleanExtra(EXTRA_IS_SONG, false)
                Log.d(TAG, "onStartCommand: ACTION_PLAY isPreview=$currentIsPreview, isLoopingPreview=$currentIsLoopingPreview")
                // Register service as preview owner so Coordinator can stop it when another owner starts
                if (currentIsPreview) {
                    PreviewCoordinator.requestStart("SERVICE", currentIsLoopingPreview) {
                        try { stopPreviewNow() } catch (_: Exception) {}
                    }
                }
                serviceScope.launch {
                    Log.d(TAG, "onStartCommand: ACTION_PLAY coroutine started, loading progression...")
                    // Read progression either from stored id, path or intent extra
                    var progressionString: String? = null
                    val progressionId = intent.getStringExtra(EXTRA_PROGRESSION_ID)
                    if (!progressionId.isNullOrEmpty()) {
                        progressionString = try { ProgressionStore.loadProgression(this@PlaybackService, progressionId) } catch (e: Exception) { Log.w(TAG, "ProgressionStore load failed: ${e.message}"); null }
                        if (!progressionString.isNullOrEmpty()) {
                            try { ProgressionStore.deleteProgression(this@PlaybackService, progressionId) } catch (_: Exception) {}
                        }
                    }
                    val progressionPath = intent.getStringExtra(EXTRA_PROGRESSION_PATH)
                    if (progressionString.isNullOrEmpty() && !progressionPath.isNullOrEmpty()) {
                        try {
                            val f = java.io.File(progressionPath)
                            if (f.exists()) progressionString = f.readText()
                        } catch (e: Exception) { Log.w(TAG, "Failed to read progression from path: $progressionPath -> ${e.message}") }
                    }
                    if (progressionString.isNullOrEmpty()) progressionString = intent.getStringExtra(EXTRA_PROGRESSION)
                    progressionString?.let { prog ->
                        val firstIdx = prog.indexOfFirst { c -> c == '{' || c == '[' }
                        val trimmedProg = if (firstIdx >= 0 && firstIdx > 0) prog.substring(firstIdx) else prog
                        var parsedProg: ChordProgression? = null
                        try {
                            parsedProg = Json.decodeFromString<ChordProgression>(trimmedProg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse progression JSON: ${e.message}")
                            try {
                                val normalized = normalizeLooseJson(trimmedProg)
                                parsedProg = try { Json.decodeFromString<ChordProgression>(normalized) } catch (_: Exception) { null }
                            } catch (e2: Exception) { Log.w(TAG, "Normalization failed: ${e2.message}") }
                        }
                        // If this is a preview start and the service already has a currentProgression (e.g. set via ACTION_UPDATE_PROGRESSION),
                        // merge parsedProg into existing progression instead of blindly overwriting. This preserves live edits that arrived
                        // before ACTION_PLAY was processed.
                        if (parsedProg != null) {
                            if (currentIsPreview) {
                                // PREVIEW: keep preview progression separate from currentProgression
                                // so that chords and other events provided for the preview are used exactly.
                                previewProgression = parsedProg
                            } else if (_isPlaying.value && currentProgression != null) {
                                // If playback is active and this is not a preview, merge incoming measures/patterns
                                try {
                                    val target = currentProgression!!
                                    if (parsedProg.measures.size > target.measures.size) {
                                        parsedProg.measures.subList(target.measures.size, parsedProg.measures.size).forEach { m -> target.measures.add(m) }
                                    }
                                    parsedProg.measures.forEachIndexed { idx, srcMeasure ->
                                        if (idx in target.measures.indices) {
                                            try { target.measures[idx].drumPattern = srcMeasure.drumPattern } catch (_: Exception) {}
                                            try { target.measures[idx].strummingPattern = srcMeasure.strummingPattern } catch (_: Exception) {}
                                        } else {
                                            target.measures.add(srcMeasure)
                                        }
                                    }
                                } catch (e: Exception) { Log.w(TAG, "ACTION_PLAY merge failed: ${e.message}") }
                            } else {
                                currentProgression = parsedProg
                            }
                        }
                    }

                    // Determine which progression we will play: previewProgression if preview, else currentProgression
                    val toPlayCandidate = if (currentIsPreview) previewProgression else currentProgression
                    Log.d(TAG, "onStartCommand: ACTION_PLAY progression loaded, toPlayCandidate=${toPlayCandidate?.measures?.size} measures, isPreview=$currentIsPreview")
                    if (toPlayCandidate == null) {
                        Log.e(TAG, "No valid progression provided for ACTION_PLAY (isPreview=$currentIsPreview)")
                        // Show a minimal fallback notification and stop service
                        createNotificationChannel()
                        val fallbackNotif = NotificationCompat.Builder(this@PlaybackService, CHANNEL_ID)
                            .setContentTitle("Playback error")
                            .setContentText("Invalid progression provided")
                            .setSmallIcon(R.drawable.ic_music_note)
                            .setOnlyAlertOnce(true)
                            .setSilent(true)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(false)
                            .build()
                        try { startForeground(NOTIFICATION_ID, fallbackNotif) } catch (e: Exception) { Log.w(TAG, "startForeground fallback failed: ${e.message}") }
                        stopSelf()
                        return@launch
                    }

                    // Start playback with parsed progression
                    try {
                        // If this play call is a preview, do not resume from any paused position
                        if (currentIsPreview) pausedPosition = null
                        Log.d(TAG, "onStartCommand: ACTION_PLAY calling startPlayback() with ${toPlayCandidate.measures.size} measures")
                        startPlayback(toPlayCandidate)
                        Log.d(TAG, "onStartCommand: ACTION_PLAY startPlayback() returned")
                    } catch (e: Exception) { Log.w(TAG, "startPlayback failed: ${e.message}") }
                 }
                 return START_NOT_STICKY
             }
            ACTION_PAUSE -> { pausePlayback(); return START_NOT_STICKY }
            ACTION_STOP -> {
                 Log.i(TAG, "onStartCommand: received ACTION_STOP")
                 stopPlayback()
                 Log.i(TAG, "onStartCommand: stopPlayback invoked via ACTION_STOP")
                 return START_NOT_STICKY
             }
            ACTION_STOP_PREVIEW -> {
                Log.i(TAG, "onStartCommand: received ACTION_STOP_PREVIEW")
                try { stopPreviewNow() } catch (e: Exception) { Log.w(TAG, "stopPreviewNow failed: ${e.message}") }
                return START_NOT_STICKY
            }
         }
         return START_NOT_STICKY
     }

    fun startPlayback(progression: ChordProgression) {
        // Ensure there's at least one measure to play; prevents silent no-op when progression.measures is empty
        if (progression.measures.isEmpty()) {
            progression.addMeasure()
            Log.i(TAG, "startPlayback: added default measure to empty progression")
        }
        Log.i(TAG, "startPlayback: progression=${progression.name}")
        if (_isPlaying.value) return
        _isPlaying.value = true

        // Update media session playback state & metadata
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, progression.name.ifBlank { "Untitled" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, progression.key.displayName)
            .build()
        mediaSession.setMetadata(metadata)
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PLAYING, playbackPositionMs, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SEEK_TO)
            .build())

        val startPos = pausedPosition
        pausedPosition = null // Consume the paused position
        val isResuming = startPos != null

        startForeground(NOTIFICATION_ID, createNotification(progression, true))

        playbackJob?.cancel()
        playbackJob = serviceScope.launch {
            audioPlayer.playProgression(
                progression = progression,
                // Looping behavior:
                // - if currentIsLoopingPreview => true (loop preview)
                // - else if currentIsPreview => false (single preview)
                // - else follow settings
                shouldLoop = { when {
                    currentIsLoopingPreview -> true
                    currentIsPreview -> false
                    currentIsSong -> settingsRepository.isLoopingSongEnabled
                    else -> settingsRepository.isLoopingProgressionEnabled
                } },
                pluckStrength = settingsRepository.pluckStrength,
                // For previews (Test/Default pattern) we must not perform a count-in
                countInBeats = when {
                    currentIsPreview -> 0
                    currentIsSong -> settingsRepository.countInBeatsSong
                    else -> settingsRepository.countInBeats
                },
                onPositionChanged = { measureIndex, strumIndex ->
                    _currentPlaybackPosition.value = Pair(measureIndex, strumIndex)
                },
                startMeasureIndex = startPos?.first ?: 0,
                startStrumIndex = startPos?.second ?: 0,
                isResuming = isResuming
            )
            // After playProgression returns, if this was a preview we should clear the preview flag
            if (currentIsPreview) {
                currentIsPreview = false
                currentIsLoopingPreview = false
                // Inform coordinator that service preview ended
                try { PreviewCoordinator.requestStop("SERVICE") } catch (_: Exception) {}
            }
            if (isLastService()) stopPlayback()
        }
    }

    fun pausePlayback() {
        Log.i(TAG, "pausePlayback")
        if (!_isPlaying.value) return
        playbackJob?.cancel()
        audioPlayer.stop()
        _isPlaying.value = false
        pausedPosition = _currentPlaybackPosition.value

        // update media session state
        playbackPositionMs = 0L
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, playbackPositionMs, 0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SEEK_TO)
            .build())
        currentProgression?.let { notificationManager.notify(NOTIFICATION_ID, createNotification(it, false)) }
        @Suppress("DEPRECATION")
        stopForeground(false)
    }

    /**
     * stopPreviewNow()
     * Instance API: stop only preview playback (single or loop) without stopping the service
     * or affecting main playback. Cancels playbackJob if it corresponds to a preview and
     * clears preview flags/progression.
     */
    fun stopPreviewNow() {
        Log.i(TAG, "stopPreviewNow - begin (currentIsPreview=$currentIsPreview, currentIsLoopingPreview=$currentIsLoopingPreview)")
        try {
            if (!currentIsPreview) return
            try { audioPlayer.stop() } catch (e: Exception) { Log.w(TAG, "stopPreviewNow audio stop failed: ${e.message}") }
            try { playbackJob?.cancel(); playbackJob = null } catch (e: Exception) { Log.w(TAG, "stopPreviewNow cancel failed: ${e.message}") }
            _isPlaying.value = false
            _currentPlaybackPosition.value = null
            pausedPosition = null
            currentIsPreview = false
            currentIsLoopingPreview = false
            previewProgression = null
            mediaSession.setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f).build())
            // Inform coordinator service preview is stopped
            try { PreviewCoordinator.requestStop("SERVICE") } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "stopPreviewNow failed: ${e.message}")
        }
        Log.i(TAG, "stopPreviewNow - end")
    }

    fun stopPlayback() {
        Log.i(TAG, "stopPlayback - begin (isPlaying=${_isPlaying.value}, currentIsPreview=$currentIsPreview, currentIsLoopingPreview=$currentIsLoopingPreview)")
        try {
            // Stop audio output first to unblock any blocking writes
            try { audioPlayer.stop() } catch (e: Exception) { Log.w(TAG, "stopPlayback: audioPlayer.stop failed: ${e.message}") }
            try { playbackJob?.cancel(); playbackJob = null } catch (e: Exception) { Log.w(TAG, "stopPlayback: cancel playbackJob failed: ${e.message}") }

            _isPlaying.value = false
            _currentPlaybackPosition.value = null
            pausedPosition = null

            // clear preview-specific state
            currentIsPreview = false
            currentIsLoopingPreview = false
            currentIsSong = false
            previewProgression = null

            // update media session and notification
            try {
                mediaSession.setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f).build())
            } catch (_: Exception) {}
            try { currentProgression?.let { notificationManager.notify(NOTIFICATION_ID, createNotification(it, false)) } } catch (_: Exception) {}

            @Suppress("DEPRECATION")
            try { stopForeground(true) } catch (_: Exception) {}
            try { stopSelf() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "stopPlayback failed: ${e.message}")
        }
        Log.i(TAG, "stopPlayback - end")
    }

    fun warmupAudioSystem() {
        Log.d(TAG, "warmupAudioSystem: starting initialization playback")
        serviceScope.launch {
            try {
                // Erstelle eine leere Progression mit einem Takt
                val tempProg = ChordProgression(name = "Preview", key = Key.C, mode = Mode.MAJOR, tempo = 240)
                tempProg.measures.clear()
                val m = Measure(1)
                m.soloPattern = SoloPattern("Silent", emptyList())
                // Ensure no drums are played during solo-only preview
                m.drumPattern = DrumPattern("Silent", List(8) { DrumStep() })
                // Ensure no strumming is played
                m.strummingPattern = StrummingPattern("Silent", List(8) { Strum.REST })
                tempProg.measures.add(m)
//                PlaybackService.play(this, tempProg, true, false)

                // Spiele den leeren Takt ab (ohne Count-In, kein Loop)
                audioPlayer.playProgression(
                    progression = tempProg,
                    shouldLoop = { false },
                    pluckStrength = 1,
                    countInBeats = 0,
                    onPositionChanged = { _, _ -> },
                    startMeasureIndex = 0,
                    startStrumIndex = 0,
                    isResuming = false
                )

                Log.d(TAG, "warmupAudioSystem: initialization playback completed")
            } catch (e: Exception) {
                Log.w(TAG, "warmupAudioSystem failed: ${e.message}")
            }
        }
    }

    private fun createPendingIntentForAction(action: String, requestCode: Int): PendingIntent {
        // Try building a MediaButton PendingIntent first (better integration),
        // but fall back to a service PendingIntent if that returns null on some devices.
        val mediaAction = when (action) {
            ACTION_PLAY -> PlaybackStateCompat.ACTION_PLAY
            ACTION_PAUSE -> PlaybackStateCompat.ACTION_PAUSE
            ACTION_STOP -> PlaybackStateCompat.ACTION_STOP
            else -> PlaybackStateCompat.ACTION_PLAY
        }
        try {
            val mediaPending = MediaButtonReceiver.buildMediaButtonPendingIntent(this, mediaAction)
            if (mediaPending != null) return mediaPending
        } catch (e: Exception) {
            Log.w(TAG, "MediaButtonReceiver failed: ${e.message}")
        }

        // Fallback: explicit intent to this service with the action
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
            currentProgression?.let { putExtra(EXTRA_PROGRESSION, Json.encodeToString(it)) }
        }
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createNotification(progression: ChordProgression, isPlaying: Boolean): Notification {
        Log.i(TAG, "createNotification: isPlaying=$isPlaying, progression=${progression.name}")
        val notificationIntent = Intent(this, SongActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        // Create PendingIntents for the remoteview buttons
        val playPausePending = if (isPlaying) createPendingIntentForAction(ACTION_PAUSE, RC_PAUSE) else createPendingIntentForAction(ACTION_PLAY, RC_PLAY)
        val stopPending = createPendingIntentForAction(ACTION_STOP, RC_STOP)

        // Note: RemoteViews must only contain allowed Views; to keep things simple and avoid
        // RemoteViews restrictions we omit custom RemoteViews here and rely on MediaStyle actions
        // with icons. The expanded notification will show the actions with icons.

        // Build media style notification using MediaSession token so Auto/Car can control playback
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Playing: ${progression.name.ifBlank { "Untitled" }}")
            .setContentText(progression.key.displayName)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1))
            .setOngoing(isPlaying)

        // Add actions (play/pause, stop)
        val playPauseAction = if (isPlaying) NotificationCompat.Action(R.drawable.ic_pause, "Pause", playPausePending) else NotificationCompat.Action(R.drawable.ic_play_arrow, "Play", playPausePending)
        val stopAction = NotificationCompat.Action(R.drawable.ic_stop, "Stop", stopPending)
        builder.addAction(playPauseAction)
        builder.addAction(stopAction)

        return builder.build()
    }

    // Public API helpers
    fun setTempo(newTempo: Int) {
        Log.i(TAG, "setTempo: $newTempo")
        // Update the progression that is currently being played. If a preview is running, update that;
        // otherwise update the main progression. This ensures live tempo changes affect active playback.
        val target = if (currentIsPreview && previewProgression != null) previewProgression else currentProgression
        target?.let {
            it.tempo = newTempo
            try { notificationManager.notify(NOTIFICATION_ID, createNotification(it, _isPlaying.value)) } catch (_: Exception) {}
        }
    }

    /**
     * Update a single measure's strumming pattern in the service's in-memory progression.
     * If playback is active, the change will take effect on the next strum because
     * playProgression reads the progression object during playback. We also update
     * the notification so the UI reflects the change.
     */
    fun updateStrummingPattern(measureIndex: Int, pattern: StrummingPattern) {
         try {
            val target = if (currentIsPreview && previewProgression != null) previewProgression else currentProgression
            target?.let { prog ->
                if (measureIndex in prog.measures.indices) {
                    prog.measures[measureIndex].strummingPattern = pattern
                    try { notificationManager.notify(NOTIFICATION_ID, createNotification(prog, _isPlaying.value)) } catch (nfe: Exception) { Log.w(TAG, "Failed to refresh notification after pattern update: ${nfe.message}") }
                }
            }
         } catch (e: Exception) {
            Log.w(TAG, "updateStrummingPattern failed: ${e.message}")
         }
     }

    /**
     * Update a single measure's drum pattern in the service's in-memory progression.
     * If playback is active, the change will take effect immediately because
     * playProgression reads the progression object during playback. We also update
     * the notification so the UI reflects the change.
     */
    fun updateDrumPattern(measureIndex: Int, pattern: DrumPattern) {
         try {
            val target = if (currentIsPreview && previewProgression != null) previewProgression else currentProgression
            target?.let { prog ->
                if (measureIndex in prog.measures.indices) {
                    prog.measures[measureIndex].drumPattern = pattern
                    try { notificationManager.notify(NOTIFICATION_ID, createNotification(prog, _isPlaying.value)) } catch (nfe: Exception) { Log.w(TAG, "Failed to refresh notification after drum pattern update: ${nfe.message}") }
                }
            }
         } catch (e: Exception) {
            Log.w(TAG, "updateDrumPattern failed: ${e.message}")
         }
    }

    // Helper to apply live progression updates (used by ACTION_UPDATE_PROGRESSION)
    private fun applyUpdatedProgression(parsed: ChordProgression) {
         try {
            // Merge parsed progression into either the previewProgression (if preview running) or
            // into currentProgression. playProgression reads the progression object during playback,
            // so we can safely update the in-memory progression in-place and the running audio will
            // reflect the changes without cancelling/restarting the playback coroutine.
            if (currentIsPreview) {
                val target = previewProgression ?: run {
                    previewProgression = parsed
                    try { notificationManager.notify(NOTIFICATION_ID, createNotification(parsed, _isPlaying.value)) } catch (_: Exception) {}
                    return
                }
                if (parsed.measures.size > target.measures.size) {
                    parsed.measures.subList(target.measures.size, parsed.measures.size).forEach { m -> target.measures.add(m) }
                }
                parsed.measures.forEachIndexed { idx, srcMeasure ->
                    if (idx in target.measures.indices) {
                        try { target.measures[idx].drumPattern = srcMeasure.drumPattern } catch (_: Exception) {}
                        try { target.measures[idx].strummingPattern = srcMeasure.strummingPattern } catch (_: Exception) {}
                    } else {
                        target.measures.add(srcMeasure)
                    }
                }
                try { notificationManager.notify(NOTIFICATION_ID, createNotification(target, _isPlaying.value)) } catch (_: Exception) {}
            } else {
                val target = currentProgression ?: run {
                    currentProgression = parsed
                    try { notificationManager.notify(NOTIFICATION_ID, createNotification(parsed, _isPlaying.value)) } catch (_: Exception) {}
                    return
                }
                if (parsed.measures.size > target.measures.size) {
                    parsed.measures.subList(target.measures.size, parsed.measures.size).forEach { m -> target.measures.add(m) }
                }
                parsed.measures.forEachIndexed { idx, srcMeasure ->
                    if (idx in target.measures.indices) {
                        try { target.measures[idx].drumPattern = srcMeasure.drumPattern } catch (_: Exception) {}
                        try { target.measures[idx].strummingPattern = srcMeasure.strummingPattern } catch (_: Exception) {}
                    } else {
                        target.measures.add(srcMeasure)
                    }
                }
                try { notificationManager.notify(NOTIFICATION_ID, createNotification(target, _isPlaying.value)) } catch (_: Exception) {}
            }
         } catch (e: Exception) {
             Log.w(TAG, "applyUpdatedProgression failed: ${e.message}")
         }
     }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                enableVibration(false)
                vibrationPattern = longArrayOf(0L)
                setSound(null, null)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }
    
    private fun isLastService() : Boolean {
        return true
    }

    companion object {
        const val EXTRA_IS_LOOPING_PREVIEW = "de.metaviewsoft.chordprogressionhelper.extra.IS_LOOPING_PREVIEW"
        const val CHANNEL_ID = "PlaybackServiceChannelV2"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "de.metaviewsoft.chordprogressionhelper.action.PLAY"
        const val ACTION_PAUSE = "de.metaviewsoft.chordprogressionhelper.action.PAUSE"
        const val ACTION_STOP = "de.metaviewsoft.chordprogressionhelper.action.STOP"
        const val ACTION_STOP_PREVIEW = "de.metaviewsoft.chordprogressionhelper.action.STOP_PREVIEW"
        // Action to update live playback parameters while service may be running
        const val ACTION_UPDATE_PARAMS = "de.metaviewsoft.chordprogressionhelper.action.UPDATE_PARAMS"
        const val ACTION_UPDATE_PROGRESSION = "de.metaviewsoft.chordprogressionhelper.action.UPDATE_PROGRESSION"

        const val EXTRA_DRUM_LEVEL = "de.metaviewsoft.chordprogressionhelper.extra.DRUM_LEVEL"
        const val EXTRA_SOLO_LEVEL = "de.metaviewsoft.chordprogressionhelper.extra.SOLO_LEVEL"
        const val EXTRA_STRUM_LEVEL = "de.metaviewsoft.chordprogressionhelper.extra.STRUM_LEVEL"
        const val EXTRA_ENVELOPE_SCALE = "de.metaviewsoft.chordprogressionhelper.extra.ENVELOPE_SCALE"
        const val EXTRA_HIHAT_HIGHPASS = "de.metaviewsoft.chordprogressionhelper.extra.HIHAT_HIGHPASS"
        const val EXTRA_UP_STROKE_OFFSET_MS = "de.metaviewsoft.chordprogressionhelper.extra.UP_STROKE_OFFSET_MS"
        const val EXTRA_UP_STRING_STAGGER_MS = "de.metaviewsoft.chordprogressionhelper.extra.UP_STRING_STAGGER_MS"
        const val EXTRA_DOWN_STROKE_OFFSET_MS = "de.metaviewsoft.chordprogressionhelper.extra.DOWN_STROKE_OFFSET_MS"
        const val EXTRA_DOWN_STRING_STAGGER_MS = "de.metaviewsoft.chordprogressionhelper.extra.DOWN_STRING_STAGGER_MS"
        const val EXTRA_STRUM_CRUNCH_LEVEL = "de.metaviewsoft.chordprogressionhelper.extra.STRUM_CRUNCH_LEVEL"
        const val EXTRA_SOLO_CRUNCH_LEVEL = "de.metaviewsoft.chordprogressionhelper.extra.SOLO_CRUNCH_LEVEL"
        const val EXTRA_TEMPO = "de.metaviewsoft.chordprogressionhelper.extra.TEMPO"

        const val EXTRA_PROGRESSION = "de.metaviewsoft.chordprogressionhelper.extra.PROGRESSION"
        const val EXTRA_PROGRESSION_PATH = "de.metaviewsoft.chordprogressionhelper.extra.PROGRESSION_PATH"
        const val EXTRA_PROGRESSION_ID = "de.metaviewsoft.chordprogressionhelper.extra.PROGRESSION_ID"
        const val EXTRA_IS_PREVIEW = "de.metaviewsoft.chordprogressionhelper.extra.IS_PREVIEW"
        const val EXTRA_IS_SONG = "de.metaviewsoft.chordprogressionhelper.extra.IS_SONG"

        // Request codes used for notification PendingIntents
        private const val RC_PLAY = 100
        private const val RC_PAUSE = 101
        private const val RC_STOP = 102

        // Public helper: start playback by saving progression to ProgressionStore and starting the service
        fun play(context: android.content.Context, progression: ChordProgression, isPreview: Boolean = false, isLoopingPreview: Boolean = false) {
            Log.d("PlaybackService", "play() called: isPreview=$isPreview, isLoopingPreview=$isLoopingPreview, progression=${progression.measures.size} measures")
            val progressionString = Json.encodeToString(progression)
            val id = try { ProgressionStore.saveProgression(context, null, progressionString) } catch (e: Exception) { Log.w("PlaybackService", "Failed to save progression to store: ${e.message}"); null }
            val intent = Intent(context, PlaybackService::class.java).apply { action = ACTION_PLAY }
            if (!id.isNullOrEmpty()) intent.putExtra(EXTRA_PROGRESSION_ID, id) else intent.putExtra(EXTRA_PROGRESSION, progressionString)
            intent.putExtra(EXTRA_IS_PREVIEW, isPreview)
            intent.putExtra(EXTRA_IS_LOOPING_PREVIEW, isLoopingPreview)
            Log.d("PlaybackService", "play() starting service with intent action=${intent.action}")
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) } catch (e: Exception) { Log.w("PlaybackService", "play start service failed: ${e.message}") }
        }

        // Song playback: same as play() but marks playback as song mode (uses song count-in setting)
        fun playSong(context: android.content.Context, progression: ChordProgression) {
            Log.d("PlaybackService", "playSong() called: progression=${progression.measures.size} measures")
            val progressionString = Json.encodeToString(progression)
            val id = try { ProgressionStore.saveProgression(context, null, progressionString) } catch (e: Exception) { Log.w("PlaybackService", "Failed to save progression to store: ${e.message}"); null }
            val intent = Intent(context, PlaybackService::class.java).apply { action = ACTION_PLAY }
            if (!id.isNullOrEmpty()) intent.putExtra(EXTRA_PROGRESSION_ID, id) else intent.putExtra(EXTRA_PROGRESSION, progressionString)
            intent.putExtra(EXTRA_IS_PREVIEW, false)
            intent.putExtra(EXTRA_IS_LOOPING_PREVIEW, false)
            intent.putExtra(EXTRA_IS_SONG, true)
            Log.d("PlaybackService", "playSong() starting service with intent action=${intent.action}")
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) } catch (e: Exception) { Log.w("PlaybackService", "playSong start service failed: ${e.message}") }
        }

        fun pause(context: android.content.Context) {
            val intent = Intent(context, PlaybackService::class.java).apply { action = ACTION_PAUSE }
            try { context.startService(intent) } catch (e: Exception) { Log.w("PlaybackService", "pause start service failed: ${e.message}") }
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, PlaybackService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w("PlaybackService", "stop(context) failed to call stopService: ${e.message}")
                try {
                    val stopIntent = Intent(context, PlaybackService::class.java).apply { action = ACTION_STOP }
                    context.startService(stopIntent)
                } catch (e2: Exception) {
                    Log.w("PlaybackService", "stop(context) fallback failed: ${e2.message}")
                }
            }
        }

         // Helper to send an updated progression to the running service so it can apply changes live
         fun updateProgression(context: android.content.Context, progression: ChordProgression) {
             val intent = Intent(context, PlaybackService::class.java).apply {
                 action = ACTION_UPDATE_PROGRESSION
                 putExtra(EXTRA_PROGRESSION, Json.encodeToString(progression))
             }
             try {
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
             } catch (e: Exception) {
                 Log.w("PlaybackService", "updateProgression failed to start service: ${e.message}")
             }
         }

        /**
         * Stop only previews (both single and looping) without stopping the entire service
         * or affecting main playback. Safe to call from Activities when only a preview should be ended.
         */
        fun stopPreview(context: android.content.Context) {
            val intent = Intent(context, PlaybackService::class.java).apply { action = ACTION_STOP_PREVIEW }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                Log.w("PlaybackService", "stopPreview failed to start service: ${e.message}")
            }
        }
      }

    // Tolerant helper: quote simple unquoted keys/values to allow parsing of malformed JSON-like input.
    private fun normalizeLooseJson(input: String): String {
        var out = input.trim()
        // Strip common BOM / garbage characters before the first JSON token
        out = out.replaceFirst(regex = Regex("""^[^{\\[]+"""), replacement = "")
        try {
            // 1) Quote simple unquoted keys appearing after '{', '[' or ','
            // character class: literal '[' is escaped, '{' and ',' are included as literals
            out = out.replace(Regex("""([\[{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:"""), "$1\"$2\":")
            // 2) Quote simple unquoted identifier-like values (not numbers/booleans) followed by , or ] or }
            // Use a character class in the lookahead to avoid single-character alternation warnings
            out = out.replace(Regex("""(:\s*)([A-Za-z_][A-ZaZ0-9_]*)(?=\s*[,}\]])"""), "$1\"$2\"")
        } catch (e: Exception) {
            Log.w(TAG, "normalizeLooseJson failed: ${e.message}")
        }
        return out
    }

    /**
     * Spielt ein Template-Preview ab - alle Strums des Patterns pro Akkord, einmal durchgespielt
     */
    fun playTemplatePreview(progression: ChordProgression) {
        Log.i(TAG, "playTemplatePreview - start")

        // Stoppe alles (Preview oder reguläres Playback)
        try { audioPlayer.stop() } catch (e: Exception) { Log.w(TAG, "playTemplatePreview: audioPlayer.stop failed: ${e.message}") }
        try { playbackJob?.cancel(); playbackJob = null } catch (e: Exception) { Log.w(TAG, "playTemplatePreview: cancel playbackJob failed: ${e.message}") }

        // Setze Flags zurück
        _isPlaying.value = false
        _currentPlaybackPosition.value = null
        pausedPosition = null

        // Wenn vorher ein Preview lief, stoppe es beim Coordinator
        if (currentIsPreview) {
            currentIsPreview = false
            currentIsLoopingPreview = false
            previewProgression = null
            try { PreviewCoordinator.requestStop("SERVICE") } catch (_: Exception) {}
        }

        // Registriere neues Preview beim Coordinator
        PreviewCoordinator.requestStart("SERVICE", false) {
            // onStop callback - wird aufgerufen wenn Preview gestoppt werden soll
            try { audioPlayer.stop() } catch (_: Exception) {}
            try { playbackJob?.cancel() } catch (_: Exception) {}
            _isPlaying.value = false
            currentIsPreview = false
        }

        currentIsPreview = true
        currentIsLoopingPreview = false
        previewProgression = progression
        _isPlaying.value = true

        playbackJob = serviceScope.launch {
            try {
                // Verwende die normale playProgression Methode, aber ohne Loop
                audioPlayer.playProgression(
                    progression = progression,
                    shouldLoop = { false }, // Kein Loop - nur einmal durchspielen
                    pluckStrength = 3, // Soft pluck
                    countInBeats = 0, // Kein Count-in für Preview
                    onPositionChanged = { _, _ -> },
                    startMeasureIndex = 0,
                    startStrumIndex = 0,
                    isResuming = false
                )
            } catch (e: Exception) {
                Log.w(TAG, "playTemplatePreview error: ${e.message}")
            } finally {
                // Preview beenden
                currentIsPreview = false
                currentIsLoopingPreview = false
                previewProgression = null
                _isPlaying.value = false
                _currentPlaybackPosition.value = null
                PreviewCoordinator.requestStop("SERVICE")
                Log.i(TAG, "playTemplatePreview - end")
            }
        }
    }
}
