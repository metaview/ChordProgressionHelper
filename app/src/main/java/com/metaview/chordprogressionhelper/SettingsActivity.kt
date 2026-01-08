package com.metaview.chordprogressionhelper

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import com.metaview.chordprogressionhelper.data.SettingsRepository
import com.metaview.chordprogressionhelper.data.SoundPreset
import com.metaview.chordprogressionhelper.databinding.ActivitySettingsBinding
import com.metaview.chordprogressionhelper.service.PlaybackService

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsRepository: SettingsRepository

    private fun updatePlaybackServiceParams() {
        // Send an intent to the PlaybackService to apply new live parameters immediately.
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_UPDATE_PARAMS
            putExtra(PlaybackService.EXTRA_DRUM_LEVEL, settingsRepository.drumLevel)
            putExtra(PlaybackService.EXTRA_ENVELOPE_SCALE, settingsRepository.envelopeScale)
            putExtra(PlaybackService.EXTRA_HIHAT_HIGHPASS, settingsRepository.hiHatHighpass)
            // new strum timing params
            putExtra(PlaybackService.EXTRA_UP_STROKE_OFFSET_MS, settingsRepository.strokeOffsetMs)
            putExtra(PlaybackService.EXTRA_UP_STRING_STAGGER_MS, settingsRepository.stringStaggerMs)
            putExtra(PlaybackService.EXTRA_DOWN_STROKE_OFFSET_MS, settingsRepository.downStrokeOffsetMs)
            putExtra(PlaybackService.EXTRA_DOWN_STRING_STAGGER_MS, settingsRepository.downStringStaggerMs)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {
            // best-effort; service might not be running or start may be restricted — prefs listener still updates service when started
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = (application as MyApplication).settingsRepository

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.chordPreviewSwitch.isChecked = settingsRepository.isChordPreviewEnabled
        // New: pattern preview switch kept separate from chord preview
        binding.strumPatternPreviewSwitch.isChecked = settingsRepository.isStrumPatternPreviewEnabled
        // New: drum preview switch (per-step immediate drum sounds)
        binding.drumPreviewSwitch.isChecked = settingsRepository.isDrumPreviewEnabled
        // New: template preview switch (preview when selecting templates)
        binding.templatePreviewSwitch.isChecked = settingsRepository.isTemplatePreviewEnabled
        binding.loopByDefaultSwitch.isChecked = settingsRepository.isLoopingEnabled

        // Initialize Count-In spinner (options: No / 2 / 4 / 8)
        val adapter = android.widget.ArrayAdapter.createFromResource(this, R.array.count_in_options, android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.countInSpinner.adapter = adapter
        // Map stored beats to spinner index: 0->0, 2->1, 4->2, 8->3
        val spinnerIndex = when (settingsRepository.countInBeats) {
            0 -> 0
            2 -> 1
            4 -> 2
            8 -> 3
            else -> 2
        }
        binding.countInSpinner.setSelection(spinnerIndex)
        // Attach listener after setting selection to avoid immediate callback
        binding.countInSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val beats = when (position) {
                    0 -> 0
                    1 -> 2
                    2 -> 4
                    3 -> 8
                    else -> 4
                }
                settingsRepository.countInBeats = beats
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { /* no-op */ }
        }

        // Pluck strength UI removed; pluck defaults to Soft

        // Sound subgroup initial values (seekbars use percent * 100)
        val drumLevelPercent = (settingsRepository.drumLevel * 100f).toInt().coerceIn(0, 200)
        binding.drumLevelSeekBar.progress = drumLevelPercent
        binding.drumLevelLabel.text = getString(R.string.drum_level_format, drumLevelPercent)

        val soloLevelPercent = (settingsRepository.soloLevel * 100f).toInt().coerceIn(0, 200)
        binding.soloLevelSeekBar.progress = soloLevelPercent
        binding.soloLevelLabel.text = getString(R.string.solo_level_format, soloLevelPercent)

        val envelopePercent = (settingsRepository.envelopeScale * 100f).toInt().coerceIn(0, 200)
        binding.envelopeScaleSeekBar.progress = envelopePercent
        binding.envelopeScaleLabel.text = getString(R.string.envelope_scale_format, envelopePercent)

        val hiHatPercent = (settingsRepository.hiHatHighpass * 100f).toInt().coerceIn(0, 200)
        binding.hihatHighpassSeekBar.progress = hiHatPercent
        binding.hihatHighpassLabel.text = getString(R.string.hihat_highpass_format, hiHatPercent)

        // Strum preset selection
        when (settingsRepository.strumPreset) {
            SoundPreset.CLEAN -> binding.soundPresetRadioGroup.check(R.id.soundPresetClean)
            SoundPreset.OVERDRIVE -> binding.soundPresetRadioGroup.check(R.id.soundPresetOverdrive)
            SoundPreset.PIANO -> binding.soundPresetRadioGroup.check(R.id.soundPresetPiano)
        }

        // Piano preset selection
        when (settingsRepository.soloPreset) {
            SoundPreset.CLEAN -> binding.soloPresetRadioGroup.check(R.id.soloPresetClean)
            SoundPreset.OVERDRIVE -> binding.soloPresetRadioGroup.check(R.id.soloPresetOverdrive)
            SoundPreset.PIANO -> binding.soloPresetRadioGroup.check(R.id.soloPresetPiano)
        }

        // Ensure strum timing controls are enabled/disabled according to current preset
        setStrumSeekbarsEnabled(settingsRepository.strumPreset != SoundPreset.PIANO)

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

        // Sound SeekBars: write settings already during onProgressChanged when moved by the user
        binding.drumLevelSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.drumLevelLabel.text = getString(R.string.drum_level_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.drumLevel = value
                    // update running playback service immediately
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.soloLevelSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.soloLevelLabel.text = getString(R.string.solo_level_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.soloLevel = value
                    // update running playback service immediately
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.envelopeScaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.envelopeScaleLabel.text = getString(R.string.envelope_scale_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.envelopeScale = value
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.hihatHighpassSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.hihatHighpassLabel.text = getString(R.string.hihat_highpass_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.hiHatHighpass = value
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // New: up/down stroke offset and string stagger SeekBars
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

        // Strum preset radio group: write selection immediately
        binding.soundPresetRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.soundPresetClean -> settingsRepository.strumPreset = SoundPreset.CLEAN
                R.id.soundPresetOverdrive -> settingsRepository.strumPreset = SoundPreset.OVERDRIVE
                R.id.soundPresetPiano -> settingsRepository.strumPreset = SoundPreset.PIANO
            }
            // Enable/disable the strum timing seekbars based on the chosen preset
            setStrumSeekbarsEnabled((settingsRepository.strumPreset != SoundPreset.PIANO) || (settingsRepository.soloPreset != SoundPreset.PIANO))
        }

        // Solo preset radio group: write selection immediately
        binding.soloPresetRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.soloPresetClean -> settingsRepository.soloPreset = SoundPreset.CLEAN
                R.id.soloPresetOverdrive -> settingsRepository.soloPreset = SoundPreset.OVERDRIVE
                R.id.soloPresetPiano -> settingsRepository.soloPreset = SoundPreset.PIANO
            }
            // Enable/disable the strum timing seekbars based on the chosen preset
            setStrumSeekbarsEnabled((settingsRepository.strumPreset != SoundPreset.PIANO) || (settingsRepository.soloPreset != SoundPreset.PIANO))
        }

        // Reset button - restore defaults (100% => 1.0, Solo 150% => 1.5)
        binding.resetSoundButton.setOnClickListener {
            settingsRepository.drumLevel = 1.0f
            settingsRepository.soloLevel = 1.5f
            settingsRepository.envelopeScale = 1.0f
            settingsRepository.hiHatHighpass = 1.0f
            settingsRepository.strokeOffsetMs = 10
            settingsRepository.stringStaggerMs = 12
            settingsRepository.downStrokeOffsetMs = 0
            settingsRepository.downStringStaggerMs = 12

            // Update UI
            binding.drumLevelSeekBar.progress = kotlin.math.floor(settingsRepository.drumLevel * 100).toInt()
            binding.drumLevelLabel.text = getString(R.string.drum_level_format, binding.drumLevelSeekBar.progress)
            binding.soloLevelSeekBar.progress = kotlin.math.floor(settingsRepository.soloLevel * 100).toInt()
            binding.soloLevelLabel.text = getString(R.string.solo_level_format, binding.soloLevelSeekBar.progress)
            binding.envelopeScaleSeekBar.progress = kotlin.math.floor(settingsRepository.envelopeScale * 100).toInt()
            binding.envelopeScaleLabel.text = getString(R.string.envelope_scale_format, binding.envelopeScaleSeekBar.progress)
            binding.hihatHighpassSeekBar.progress = kotlin.math.floor(settingsRepository.hiHatHighpass * 100).toInt()
            binding.hihatHighpassLabel.text = getString(R.string.hihat_highpass_format, binding.hihatHighpassSeekBar.progress)
            binding.upStrokeOffsetSeekBar.progress = settingsRepository.strokeOffsetMs
            binding.upStrokeOffsetLabel.text = "Up Stroke Offset: ${settingsRepository.strokeOffsetMs}ms"
            binding.upStringStaggerSeekBar.progress = settingsRepository.stringStaggerMs
            binding.upStringStaggerLabel.text = "Up String Stagger: ${settingsRepository.stringStaggerMs}ms"
            binding.downStrokeOffsetSeekBar.progress = settingsRepository.downStrokeOffsetMs
            binding.downStrokeOffsetLabel.text = "Down Stroke Offset: ${settingsRepository.downStrokeOffsetMs}ms"
            binding.downStringStaggerSeekBar.progress = settingsRepository.downStringStaggerMs
            binding.downStringStaggerLabel.text = "Down String Stagger: ${settingsRepository.downStringStaggerMs}ms"
            // Apply changes to running playback service as well
            updatePlaybackServiceParams()
        }
    }

    // Enable or disable the four strum timing seekbars (and visually dim their labels)
    private fun setStrumSeekbarsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.45f
        binding.upStrokeOffsetSeekBar.isEnabled = enabled
        binding.upStrokeOffsetLabel.alpha = alpha

        binding.upStringStaggerSeekBar.isEnabled = enabled
        binding.upStringStaggerLabel.alpha = alpha

        binding.downStrokeOffsetSeekBar.isEnabled = enabled
        binding.downStrokeOffsetLabel.alpha = alpha

        binding.downStringStaggerSeekBar.isEnabled = enabled
        binding.downStringStaggerLabel.alpha = alpha
    }

    private fun setupListeners() {
        // Save simple toggles when changed
        binding.chordPreviewSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isChordPreviewEnabled = isChecked
        }
        binding.strumPatternPreviewSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isStrumPatternPreviewEnabled = isChecked
        }
        binding.drumPreviewSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isDrumPreviewEnabled = isChecked
        }
        binding.templatePreviewSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isTemplatePreviewEnabled = isChecked
        }
        binding.loopByDefaultSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isLoopingEnabled = isChecked
        }
    }
}
