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
import com.metaview.chordprogressionhelper.MainActivity
import com.metaview.chordprogressionhelper.R
import com.metaview.chordprogressionhelper.data.ProgressionStore
import com.metaview.chordprogressionhelper.data.SettingsRepository
import com.metaview.chordprogressionhelper.model.ChordProgression
import com.metaview.chordprogressionhelper.util.AudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import android.util.Log

class PlaybackService : Service() {
    private val TAG = "PlaybackService"

    private val binder = LocalBinder()
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var notificationManager: NotificationManager

    private var playbackJob: Job? = null
    private var currentProgression: ChordProgression? = null
    private var pausedPosition: Pair<Int, Int>? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentPlaybackPosition = MutableStateFlow<Pair<Int, Int>?>(null)
    val currentPlaybackPosition = _currentPlaybackPosition.asStateFlow()

    private lateinit var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener

    // mediaSession removed for now to avoid dependency issues; keep notification MediaStyle
    private lateinit var mediaSession: MediaSessionCompat
    private var playbackPositionMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: initializing PlaybackService")
        audioPlayer = AudioPlayer()
        settingsRepository = SettingsRepository(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        // Initialize audioPlayer live params from settings
        audioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
        audioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
        audioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()

        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SettingsRepository.KEY_DRUM_LEVEL -> audioPlayer.drumLevel = settingsRepository.drumLevel.toDouble()
                SettingsRepository.KEY_ENVELOPE_SCALE -> audioPlayer.envelopeScale = settingsRepository.envelopeScale.toDouble()
                SettingsRepository.KEY_HIHAT_HIGHPASS -> audioPlayer.hiHatHighpass = settingsRepository.hiHatHighpass.toDouble()
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
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsRepository.unregisterChangeListener(prefsListener)
        try {
            mediaSession.release()
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        // Read progression either from a file path (preferred, avoids large Intent extras)
        var progressionString: String? = null
        // New: try id-based loading first
        val progressionId = intent?.getStringExtra(EXTRA_PROGRESSION_ID)
        if (!progressionId.isNullOrEmpty()) {
            progressionString = ProgressionStore.loadProgression(this, progressionId)
            if (progressionString == null) {
                Log.w(TAG, "Progression ID $progressionId not found in store")
            } else {
                // Clean up the stored temp file after reading
                try { ProgressionStore.deleteProgression(this, progressionId) } catch (_: Exception) {}
            }
        }
        val progressionPath = intent?.getStringExtra(EXTRA_PROGRESSION_PATH)
        if (!progressionPath.isNullOrEmpty()) {
            try {
                val f = java.io.File(progressionPath)
                if (f.exists()) {
                    progressionString = f.readText()
                    // Clean up temp file after reading
                    try { f.delete() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read progression from path: $progressionPath -> ${e.message}")
            }
        }
        if (progressionString == null) {
            progressionString = intent?.getStringExtra(EXTRA_PROGRESSION)
        }
        if (progressionString != null) {
            // Remove leading BOM or any garbage characters before the first JSON object/array
            val cleaned = progressionString.replaceFirst(Regex("^[^\\{\\[]+"), "")
            if (cleaned != progressionString) {
                Log.i(TAG, "Trimmed leading garbage from progression input")
                progressionString = cleaned
            }
            try {
                currentProgression = Json.decodeFromString<ChordProgression>(progressionString)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse progression JSON: ${e.message}")
                Log.w(TAG, "JSON input: $progressionString")
                // Try a tolerant normalization for loose JSON (e.g. {name:Test,...} without quotes)
                try {
                    val normalized = normalizeLooseJson(progressionString)
                    Log.i(TAG, "Attempting to parse normalized JSON: $normalized")
                    currentProgression = try { Json.decodeFromString<ChordProgression>(normalized) } catch (e2: Exception) { null }
                    if (currentProgression == null) {
                        Log.w(TAG, "Normalized JSON still failed to parse")
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Normalization failed: ${e2.message}")
                }
            }
        }

        // If parsing failed entirely, publish a minimal foreground notification and stop the service
        if (currentProgression == null && intent?.action == ACTION_PLAY) {
            Log.e(TAG, "No valid progression provided, stopping service to avoid crash")
            // Ensure notification channel exists
            createNotificationChannel()
            val fallbackNotif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Playback error")
                .setContentText("Invalid progression provided")
                .setSmallIcon(R.drawable.ic_music_note)
                .setOngoing(false)
                .build()
            try {
                startForeground(NOTIFICATION_ID, fallbackNotif)
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed for fallback notification: ${e.message}")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PLAY -> currentProgression?.let { startPlayback(it) }
            ACTION_PAUSE -> pausePlayback()
            ACTION_STOP -> stopPlayback()
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
                shouldLoop = { settingsRepository.isLoopingEnabled },
                pluckStrength = settingsRepository.pluckStrength,
                countInBeats = settingsRepository.countInBeats,
                onPositionChanged = { measureIndex, strumIndex ->
                    _currentPlaybackPosition.value = Pair(measureIndex, strumIndex)
                },
                startMeasureIndex = startPos?.first ?: 0,
                startStrumIndex = startPos?.second ?: 0,
                isResuming = isResuming
            )
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
        Log.i(TAG, "stopPlayback")
        playbackJob?.cancel()
        audioPlayer.stop()
        _isPlaying.value = false
        _currentPlaybackPosition.value = null
        pausedPosition = null

        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
            .build())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
    fun updateStrummingPattern(measureIndex: Int, pattern: com.metaview.chordprogressionhelper.model.StrummingPattern) {
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
        const val CHANNEL_ID = "PlaybackServiceChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.metaview.chordprogressionhelper.action.PLAY"
        const val ACTION_PAUSE = "com.metaview.chordprogressionhelper.action.PAUSE"
        const val ACTION_STOP = "com.metaview.chordprogressionhelper.action.STOP"

        const val EXTRA_PROGRESSION = "com.metaview.chordprogressionhelper.extra.PROGRESSION"
        const val EXTRA_PROGRESSION_PATH = "com.metaview.chordprogressionhelper.extra.PROGRESSION_PATH"
        const val EXTRA_PROGRESSION_ID = "com.metaview.chordprogressionhelper.extra.PROGRESSION_ID"

        private const val RC_PLAY = 100
        private const val RC_PAUSE = 101
        private const val RC_STOP = 102

        // New: save progression to ProgressionStore and send only the ID to the service (preferred)
        fun play(context: android.content.Context, progression: ChordProgression) {
            val progressionString = Json.encodeToString(progression)
            val id = try { ProgressionStore.saveProgression(context, null, progressionString) } catch (e: Exception) {
                Log.w("PlaybackService", "Failed to save progression to store: ${e.message}"); null
            }
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_PLAY
                if (!id.isNullOrEmpty()) putExtra(EXTRA_PROGRESSION_ID, id) else putExtra(EXTRA_PROGRESSION, progressionString)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun pause(context: android.content.Context) {
            val intent = Intent(context, PlaybackService::class.java).apply { action = ACTION_PAUSE }
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, PlaybackService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    // Try to convert very loosely-formatted JSON like {name:Test,key:C,...} into valid JSON with quoted keys and string values.
    private fun normalizeLooseJson(input: String): String {
        var out = input.trim()
        // Strip common BOM / garbage characters before the first JSON token
        out = out.replaceFirst(Regex("^[^\\{\\[]+"), "")
        // 1) Quote unquoted keys: {name: or ,name: -> "name":
        out = out.replace(Regex("(?<=\\{|,)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:"), """$1":""")
        // 2) Quote unquoted string values: "name":Test, "key":C -> "name":"Test", "key":"C"
        out = out.replace(Regex("""(?<=":\s*)([A-Za-z_][A-Za-z0-9_]*)(?=,|\})"""), """"$1"""")
        return out
    }
}
