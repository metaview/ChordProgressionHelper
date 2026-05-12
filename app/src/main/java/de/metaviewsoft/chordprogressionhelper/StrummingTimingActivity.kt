package de.metaviewsoft.chordprogressionhelper

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import de.metaviewsoft.chordprogressionhelper.data.SettingsRepository
import de.metaviewsoft.chordprogressionhelper.databinding.ActivityStrummingTimingBinding
import de.metaviewsoft.chordprogressionhelper.service.PlaybackService

class StrummingTimingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStrummingTimingBinding
    private lateinit var settingsRepository: SettingsRepository

    private fun updatePlaybackServiceParams() {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_UPDATE_PARAMS
            putExtra(PlaybackService.EXTRA_UP_STROKE_OFFSET_MS, settingsRepository.strokeOffsetMs)
            putExtra(PlaybackService.EXTRA_UP_STRING_STAGGER_MS, settingsRepository.stringStaggerMs)
            putExtra(PlaybackService.EXTRA_DOWN_STROKE_OFFSET_MS, settingsRepository.downStrokeOffsetMs)
            putExtra(PlaybackService.EXTRA_DOWN_STRING_STAGGER_MS, settingsRepository.downStringStaggerMs)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {
            // best-effort
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStrummingTimingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = (application as MyApplication).settingsRepository

        // Toolbar setup
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Strumming Timing"

        loadSettings()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSettings() {
        // Initialize strumming timing seekbars and labels (ms values)
        val upOffset = settingsRepository.strokeOffsetMs.coerceIn(0, 100)
        binding.upStrokeOffsetSeekBar.progress = upOffset
        binding.upStrokeOffsetLabel.text = "Up Stroke Offset: ${upOffset}ms"

        val upStagger = settingsRepository.stringStaggerMs.coerceIn(0, 100)
        binding.upStringStaggerSeekBar.progress = upStagger
        binding.upStringStaggerLabel.text = "Up String Stagger: ${upStagger}ms"

        val downOffset = settingsRepository.downStrokeOffsetMs.coerceIn(0, 100)
        binding.downStrokeOffsetSeekBar.progress = downOffset
        binding.downStrokeOffsetLabel.text = "Down Stroke Offset: ${downOffset}ms"

        val downStagger = settingsRepository.downStringStaggerMs.coerceIn(0, 100)
        binding.downStringStaggerSeekBar.progress = downStagger
        binding.downStringStaggerLabel.text = "Down String Stagger: ${downStagger}ms"

        // SeekBar listeners
        binding.upStrokeOffsetSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.upStrokeOffsetLabel.text = "Up Stroke Offset: ${progress}ms"
                if (fromUser) {
                    settingsRepository.strokeOffsetMs = progress
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.upStringStaggerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.upStringStaggerLabel.text = "Up String Stagger: ${progress}ms"
                if (fromUser) {
                    settingsRepository.stringStaggerMs = progress
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.downStrokeOffsetSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.downStrokeOffsetLabel.text = "Down Stroke Offset: ${progress}ms"
                if (fromUser) {
                    settingsRepository.downStrokeOffsetMs = progress
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.downStringStaggerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.downStringStaggerLabel.text = "Down String Stagger: ${progress}ms"
                if (fromUser) {
                    settingsRepository.downStringStaggerMs = progress
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Reset button
        binding.resetTimingButton.setOnClickListener {
            settingsRepository.strokeOffsetMs = 10
            settingsRepository.stringStaggerMs = 12
            settingsRepository.downStrokeOffsetMs = 0
            settingsRepository.downStringStaggerMs = 12

            binding.upStrokeOffsetSeekBar.progress = settingsRepository.strokeOffsetMs
            binding.upStrokeOffsetLabel.text = "Up Stroke Offset: ${settingsRepository.strokeOffsetMs}ms"
            binding.upStringStaggerSeekBar.progress = settingsRepository.stringStaggerMs
            binding.upStringStaggerLabel.text = "Up String Stagger: ${settingsRepository.stringStaggerMs}ms"
            binding.downStrokeOffsetSeekBar.progress = settingsRepository.downStrokeOffsetMs
            binding.downStrokeOffsetLabel.text = "Down Stroke Offset: ${settingsRepository.downStrokeOffsetMs}ms"
            binding.downStringStaggerSeekBar.progress = settingsRepository.downStringStaggerMs
            binding.downStringStaggerLabel.text = "Down String Stagger: ${settingsRepository.downStringStaggerMs}ms"

            updatePlaybackServiceParams()
        }
    }
}

