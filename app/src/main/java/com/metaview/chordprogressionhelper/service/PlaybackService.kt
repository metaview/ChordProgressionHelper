package com.metaview.chordprogressionhelper.service

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
import com.metaview.chordprogressionhelper.MainActivity
import com.metaview.chordprogressionhelper.R
import com.metaview.chordprogressionhelper.data.ProgressionStore
import com.metaview.chordprogressionhelper.data.SettingsRepository
import com.metaview.chordprogressionhelper.model.ChordProgression
import com.metaview.chordprogressionhelper.model.StrummingPattern
import com.metaview.chordprogressionhelper.util.AudioPlayer
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
    private var pausedPosition: Pair<Int, Int>? = null
    // If true the currently-loaded progression was launched as a temporary preview
    private var currentIsPreview: Boolean = false
    private var currentIsLoopingPreview: Boolean = false

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
                .setOngoing(true)
                .build()
            try { startForeground(NOTIFICATION_ID, preparing) } catch (e: Exception) { Log.w(TAG, "early startForeground in onCreate failed: ${e.message}") }
        } catch (e: Exception) { Log.w(TAG, "failed to build early notification: ${e.message}") }

        // Initialize audioPlayer live params from settings
        audioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
        audioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
        audioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
        audioPlayer.voicePreset = settingsRepository.soundPreset

        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SettingsRepository.KEY_DRUM_LEVEL -> audioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
                SettingsRepository.KEY_ENVELOPE_SCALE -> audioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
                SettingsRepository.KEY_HIHAT_HIGHPASS -> audioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
                SettingsRepository.KEY_SOUND_PRESET -> audioPlayer.voicePreset = settingsRepository.soundPreset
            }
        }
        settingsRepository.registerChangeListener(prefsListener)

        // Initialize MediaSessionCompat for system integration (lockscreen, car, media buttons)
        mediaSession = MediaSessionCompat(this, "ChordProgressionHelperSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    currentProgression?.let { startPlayback(it) }
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onStop() {
                    stopPlayback()
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
                val drum = intent.getFloatExtra(EXTRA_DRUM_LEVEL, settingsRepository.drumLevel)
                val env = intent.getFloatExtra(EXTRA_ENVELOPE_SCALE, settingsRepository.envelopeScale)
                val hh = intent.getFloatExtra(EXTRA_HIHAT_HIGHPASS, settingsRepository.hiHatHighpass)
                audioPlayer.drumLevel = drum.toDouble()
                audioPlayer.envelopeScale = env.toDouble()
                audioPlayer.hiHatHighpass = hh.toDouble()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update params from intent: ${e.message}")
            }
            return START_NOT_STICKY
        }

        // Dispatch play handling onto a coroutine so onStartCommand returns promptly after calling startForeground
        when (intent?.action) {
            ACTION_PLAY -> {
                // capture preview flag and then parse/load progression in background
                currentIsPreview = intent.getBooleanExtra(EXTRA_IS_PREVIEW, false)
                currentIsLoopingPreview = intent.getBooleanExtra(EXTRA_IS_LOOPING_PREVIEW, false)
                serviceScope.launch {
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
                    if (!progressionString.isNullOrEmpty()) {
                        val firstIdx = progressionString.indexOfFirst { c -> c == '{' || c == '[' }
                        if (firstIdx >= 0 && firstIdx > 0) progressionString = progressionString.substring(firstIdx)
                        try {
                            currentProgression = Json.decodeFromString<ChordProgression>(progressionString)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse progression JSON: ${e.message}")
                            try {
                                val normalized = normalizeLooseJson(progressionString)
                                currentProgression = try { Json.decodeFromString<ChordProgression>(normalized) } catch (_: Exception) { null }
                            } catch (e2: Exception) { Log.w(TAG, "Normalization failed: ${e2.message}") }
                        }
                    }

                    if (currentProgression == null) {
                        Log.e(TAG, "No valid progression provided for ACTION_PLAY")
                        // Show a minimal fallback notification and stop service
                        createNotificationChannel()
                        val fallbackNotif = NotificationCompat.Builder(this@PlaybackService, CHANNEL_ID)
                            .setContentTitle("Playback error")
                            .setContentText("Invalid progression provided")
                            .setSmallIcon(R.drawable.ic_music_note)
                            .setOngoing(false)
                            .build()
                        try { startForeground(NOTIFICATION_ID, fallbackNotif) } catch (e: Exception) { Log.w(TAG, "startForeground fallback failed: ${e.message}") }
                        stopSelf()
                        return@launch
                    }

                    // Start playback with parsed progression
                    try { startPlayback(currentProgression!!) } catch (e: Exception) { Log.w(TAG, "startPlayback failed: ${e.message}") }
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
                    else -> settingsRepository.isLoopingEnabled
                } },
                pluckStrength = settingsRepository.pluckStrength,
                // For previews (Test/Default pattern) we must not perform a count-in
                countInBeats = if (currentIsPreview) 0 else settingsRepository.countInBeats,
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

    fun stopPlayback() {
        Log.i(TAG, "stopPlayback - begin (isPlaying=${_isPlaying.value}, currentIsPreview=$currentIsPreview, currentIsLoopingPreview=$currentIsLoopingPreview)")
        try {
            // First stop audio output to unblock any blocking writes
            audioPlayer.stop()
        } catch (e: Exception) { Log.w(TAG, "stopPlayback: audioPlayer.stop failed: ${e.message}") }
        try {
            playbackJob?.cancel()
            playbackJob = null
        } catch (e: Exception) { Log.w(TAG, "stopPlayback: cancel playbackJob failed: ${e.message}") }
        _isPlaying.value = false
        _currentPlaybackPosition.value = null
        pausedPosition = null
        currentIsPreview = false
        currentIsLoopingPreview = false

        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
            .build())
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) { Log.w(TAG, "stopPlayback: stopForeground failed: ${e.message}") }
        try {
            stopSelf()
        } catch (e: Exception) { Log.w(TAG, "stopPlayback: stopSelf failed: ${e.message}") }
        Log.i(TAG, "stopPlayback - end (cleared flags)")
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
        val notificationIntent = Intent(this, MainActivity::class.java)
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
        currentProgression?.let { it.tempo = newTempo }
        currentProgression?.let { notificationManager.notify(NOTIFICATION_ID, createNotification(it, _isPlaying.value)) }
    }

    /**
     * Update a single measure's strumming pattern in the service's in-memory progression.
     * If playback is active, the change will take effect on the next strum because
     * playProgression reads the progression object during playback. We also update
     * the notification so the UI reflects the change.
     */
    fun updateStrummingPattern(measureIndex: Int, pattern: StrummingPattern) {
        try {
            currentProgression?.let { prog ->
                if (measureIndex in prog.measures.indices) {
                    prog.measures[measureIndex].strummingPattern = pattern
                    // Refresh notification so title/summary reflect current progression
                    try {
                        notificationManager.notify(NOTIFICATION_ID, createNotification(prog, _isPlaying.value))
                    } catch (nfe: Exception) {
                        Log.w(TAG, "Failed to refresh notification after pattern update: ${nfe.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateStrummingPattern failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_DEFAULT)
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
        const val EXTRA_IS_LOOPING_PREVIEW = "com.metaview.chordprogressionhelper.extra.IS_LOOPING_PREVIEW"
        const val CHANNEL_ID = "PlaybackServiceChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.metaview.chordprogressionhelper.action.PLAY"
        const val ACTION_PAUSE = "com.metaview.chordprogressionhelper.action.PAUSE"
        const val ACTION_STOP = "com.metaview.chordprogressionhelper.action.STOP"
        // Action to update live playback parameters while service may be running
        const val ACTION_UPDATE_PARAMS = "com.metaview.chordprogressionhelper.action.UPDATE_PARAMS"

        const val EXTRA_DRUM_LEVEL = "com.metaview.chordprogressionhelper.extra.DRUM_LEVEL"
        const val EXTRA_ENVELOPE_SCALE = "com.metaview.chordprogressionhelper.extra.ENVELOPE_SCALE"
        const val EXTRA_HIHAT_HIGHPASS = "com.metaview.chordprogressionhelper.extra.HIHAT_HIGHPASS"

        const val EXTRA_PROGRESSION = "com.metaview.chordprogressionhelper.extra.PROGRESSION"
        const val EXTRA_PROGRESSION_PATH = "com.metaview.chordprogressionhelper.extra.PROGRESSION_PATH"
        const val EXTRA_PROGRESSION_ID = "com.metaview.chordprogressionhelper.extra.PROGRESSION_ID"
        const val EXTRA_IS_PREVIEW = "com.metaview.chordprogressionhelper.extra.IS_PREVIEW"

        private const val RC_PLAY = 100
        private const val RC_PAUSE = 101
        private const val RC_STOP = 102

        // New: save progression to ProgressionStore and send only the ID to the service (preferred)
        fun play(context: android.content.Context, progression: ChordProgression, isPreview: Boolean = false, isLoopingPreview: Boolean = false) {
            val progressionString = Json.encodeToString(progression)
            val id = try { ProgressionStore.saveProgression(context, null, progressionString) } catch (e: Exception) {
                Log.w("PlaybackService", "Failed to save progression to store: ${e.message}")
                null
            }
            val intent = Intent(context, PlaybackService::class.java)
            intent.action = ACTION_PLAY
            if (!id.isNullOrEmpty()) {
                intent.putExtra(EXTRA_PROGRESSION_ID, id)
            } else {
                intent.putExtra(EXTRA_PROGRESSION, progressionString)
            }
            intent.putExtra(EXTRA_IS_PREVIEW, isPreview)
            intent.putExtra(EXTRA_IS_LOOPING_PREVIEW, isLoopingPreview)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun pause(context: android.content.Context) {
            val intent = Intent(context, PlaybackService::class.java).apply { action = ACTION_PAUSE }
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, PlaybackService::class.java)
            try {
                // Request the system to stop the service outright; onDestroy will call stopPlayback()
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w("PlaybackService", "stop(context) failed to call stopService: ${e.message}")
                // Fallback: try to send ACTION_STOP intent in case stopService did not work
                try {
                    val stopIntent = Intent(context, PlaybackService::class.java).apply { action = ACTION_STOP }
                    context.startService(stopIntent)
                } catch (e2: Exception) {
                    Log.w("PlaybackService", "stop(context) fallback failed: ${e2.message}")
                }
            }
         }
     }

    // Tolerant helper: quote simple unquoted keys/values to allow parsing of malformed JSON-like input.
    private fun normalizeLooseJson(input: String): String {
        var out = input.trim()
        // Strip common BOM / garbage characters before the first JSON token
        out = out.replaceFirst(Regex("""^[^{\[]+"""), "")
        try {
            // 1) Quote simple unquoted keys appearing after '{', '[' or ','
            out = out.replace(Regex("""([\{\[,]\s*)([A-Za-z_][A-ZaZ0-9_]*)\s*:"""), "$1\"$2\":")
            // 2) Quote simple unquoted identifier-like values (not numbers/booleans) followed by , or ] or }
            out = out.replace(Regex("""(:\s*)([A-Za-z_][A-ZaZ0-9_]*)(?=\s*(,|\]|\}))"""), "$1\"$2\"")
        } catch (e: Exception) {
            Log.w(TAG, "normalizeLooseJson failed: ${e.message}")
        }
        return out
    }
}
