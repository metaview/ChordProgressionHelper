package de.metaviewsoft.chordprogressionhelper

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import de.metaviewsoft.chordprogressionhelper.data.SettingsRepository
import de.metaviewsoft.chordprogressionhelper.data.SoundPreset
import de.metaviewsoft.chordprogressionhelper.databinding.ActivitySettingsBinding
import de.metaviewsoft.chordprogressionhelper.service.PlaybackService

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsRepository: SettingsRepository

    private fun updatePlaybackServiceParams() {
        // Send an intent to the PlaybackService to apply new live parameters immediately.
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_UPDATE_PARAMS
            putExtra(PlaybackService.EXTRA_DRUM_LEVEL, settingsRepository.drumLevel)
            putExtra(PlaybackService.EXTRA_SOLO_LEVEL, settingsRepository.soloLevel)
            putExtra(PlaybackService.EXTRA_STRUM_LEVEL, settingsRepository.strumLevel)
            putExtra(PlaybackService.EXTRA_ENVELOPE_SCALE, settingsRepository.envelopeScale)
            putExtra(PlaybackService.EXTRA_HIHAT_HIGHPASS, settingsRepository.hiHatHighpass)
            // new strum timing params
            putExtra(PlaybackService.EXTRA_UP_STROKE_OFFSET_MS, settingsRepository.strokeOffsetMs)
            putExtra(PlaybackService.EXTRA_UP_STRING_STAGGER_MS, settingsRepository.stringStaggerMs)
            putExtra(PlaybackService.EXTRA_DOWN_STROKE_OFFSET_MS, settingsRepository.downStrokeOffsetMs)
            putExtra(PlaybackService.EXTRA_DOWN_STRING_STAGGER_MS, settingsRepository.downStringStaggerMs)
            putExtra(PlaybackService.EXTRA_STRUM_CRUNCH_LEVEL, settingsRepository.strumCrunchLevel)
            putExtra(PlaybackService.EXTRA_SOLO_CRUNCH_LEVEL, settingsRepository.soloCrunchLevel)
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

        val strumLevelPercent = (settingsRepository.strumLevel * 100f).toInt().coerceIn(0, 200)
        binding.strumLevelSeekBar.progress = strumLevelPercent
        binding.strumLevelLabel.text = getString(R.string.strum_level_format, strumLevelPercent)

        // Set envelope scale and hihat highpass to fixed 100% (1.0f)
        settingsRepository.envelopeScale = 1.0f
        settingsRepository.hiHatHighpass = 1.0f

        // Shuffle factor SeekBar initialization
        val shuffleFactorPercent = (settingsRepository.shuffleFactor * 100f).toInt().coerceIn(0, 200)
        binding.shuffleFactorSeekBar.progress = shuffleFactorPercent
        binding.shuffleFactorLabel.text = getString(R.string.shuffle_factor_format, shuffleFactorPercent)

        // Crunch level SeekBar initialization
        val strumCrunchPercent = (settingsRepository.strumCrunchLevel * 100f).toInt().coerceIn(0, 200)
        binding.strumCrunchLevelSeekBar.progress = strumCrunchPercent
        binding.strumCrunchLevelLabel.text = getString(R.string.strum_crunch_level_format, strumCrunchPercent)

        val soloCrunchPercent = (settingsRepository.soloCrunchLevel * 100f).toInt().coerceIn(0, 200)
        binding.soloCrunchLevelSeekBar.progress = soloCrunchPercent
        binding.soloCrunchLevelLabel.text = getString(R.string.solo_crunch_level_format, soloCrunchPercent)

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
        // (removed - timing controls are now in separate activity)

        // Initialize strumming timing button
        binding.strummingTimingButton.setOnClickListener {
            val intent = Intent(this, StrummingTimingActivity::class.java)
            startActivity(intent)
        }

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

        binding.strumLevelSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.strumLevelLabel.text = getString(R.string.strum_level_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.strumLevel = value
                    // update running playback service immediately
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Crunch level SeekBar listeners
        binding.strumCrunchLevelSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.strumCrunchLevelLabel.text = getString(R.string.strum_crunch_level_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.strumCrunchLevel = value
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.soloCrunchLevelSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.soloCrunchLevelLabel.text = getString(R.string.solo_crunch_level_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.soloCrunchLevel = value
                    updatePlaybackServiceParams()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        // Shuffle factor SeekBar listener
        binding.shuffleFactorSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.shuffleFactorLabel.text = getString(R.string.shuffle_factor_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.shuffleFactor = value
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
        }

        // Solo preset radio group: write selection immediately
        binding.soloPresetRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.soloPresetClean -> settingsRepository.soloPreset = SoundPreset.CLEAN
                R.id.soloPresetOverdrive -> settingsRepository.soloPreset = SoundPreset.OVERDRIVE
                R.id.soloPresetPiano -> settingsRepository.soloPreset = SoundPreset.PIANO
            }
        }

        // Reset button - restore defaults (100% => 1.0, Solo 150% => 1.5)
        binding.resetSoundButton.setOnClickListener {
            settingsRepository.drumLevel = 1.0f
            settingsRepository.soloLevel = 1.5f
            settingsRepository.strumLevel = 1.0f
            settingsRepository.envelopeScale = 1.0f
            settingsRepository.hiHatHighpass = 1.0f
            settingsRepository.strokeOffsetMs = 10
            settingsRepository.stringStaggerMs = 12
            settingsRepository.downStrokeOffsetMs = 0
            settingsRepository.downStringStaggerMs = 12
            settingsRepository.shuffleFactor = 0.0f // Reset shuffle factor to default (no shuffle)

            settingsRepository.strumCrunchLevel = 1.0f // Reset crunch level to default (medium)
            settingsRepository.soloCrunchLevel = 1.0f // Reset crunch level to default (medium)

            // Update UI
            binding.drumLevelSeekBar.progress = kotlin.math.floor(settingsRepository.drumLevel * 100).toInt()
            binding.drumLevelLabel.text = getString(R.string.drum_level_format, binding.drumLevelSeekBar.progress)
            binding.soloLevelSeekBar.progress = kotlin.math.floor(settingsRepository.soloLevel * 100).toInt()
            binding.soloLevelLabel.text = getString(R.string.solo_level_format, binding.soloLevelSeekBar.progress)
            binding.strumLevelSeekBar.progress = kotlin.math.floor(settingsRepository.strumLevel * 100).toInt()
            binding.strumLevelLabel.text = getString(R.string.strum_level_format, binding.strumLevelSeekBar.progress)
            binding.shuffleFactorSeekBar.progress = kotlin.math.floor(settingsRepository.shuffleFactor * 100).toInt()
            binding.shuffleFactorLabel.text = getString(R.string.shuffle_factor_format, binding.shuffleFactorSeekBar.progress)
            binding.strumCrunchLevelSeekBar.progress = kotlin.math.floor(settingsRepository.strumCrunchLevel * 100).toInt()
            binding.strumCrunchLevelLabel.text = getString(R.string.strum_crunch_level_format, binding.strumCrunchLevelSeekBar.progress)
            binding.soloCrunchLevelSeekBar.progress = kotlin.math.floor(settingsRepository.soloCrunchLevel * 100).toInt()
            binding.soloCrunchLevelLabel.text = getString(R.string.solo_crunch_level_format, binding.soloCrunchLevelSeekBar.progress)
            // Apply changes to running playback service as well
            updatePlaybackServiceParams()
        }
    }

    // Enable or disable the four strum timing seekbars (and visually dim their labels)
    // (removed - timing controls are now in separate activity)
    private fun setStrumSeekbarsEnabled(enabled: Boolean) {
        // This method is no longer needed but kept for compatibility
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
