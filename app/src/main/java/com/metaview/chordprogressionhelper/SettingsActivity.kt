package com.metaview.chordprogressionhelper

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.metaview.chordprogressionhelper.data.SettingsRepository
import com.metaview.chordprogressionhelper.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsRepository: SettingsRepository

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
        binding.loopByDefaultSwitch.isChecked = settingsRepository.isLoopingEnabled

        when (settingsRepository.countInBeats) {
            0 -> binding.countInNone.isChecked = true
            2 -> binding.countIn2Beats.isChecked = true
            4 -> binding.countIn4Beats.isChecked = true
            8 -> binding.countIn8Beats.isChecked = true
        }

        // Pluck strength UI removed; pluck defaults to Soft

        // Sound subgroup initial values (seekbars use percent * 100)
        val drumLevelPercent = (settingsRepository.drumLevel * 100f).toInt().coerceIn(0, 200)
        binding.drumLevelSeekBar.progress = drumLevelPercent
        binding.drumLevelLabel.text = getString(R.string.drum_level_format, drumLevelPercent)

        val envelopePercent = (settingsRepository.envelopeScale * 100f).toInt().coerceIn(0, 200)
        binding.envelopeScaleSeekBar.progress = envelopePercent
        binding.envelopeScaleLabel.text = getString(R.string.envelope_scale_format, envelopePercent)

        val hiHatPercent = (settingsRepository.hiHatHighpass * 100f).toInt().coerceIn(0, 200)
        binding.hihatHighpassSeekBar.progress = hiHatPercent
        binding.hihatHighpassLabel.text = getString(R.string.hihat_highpass_format, hiHatPercent)
    }

    private fun setupListeners() {
        binding.chordPreviewSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isChordPreviewEnabled = isChecked
        }

        binding.loopByDefaultSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isLoopingEnabled = isChecked
        }

        binding.countInRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val beats = when (checkedId) {
                R.id.countInNone -> 0
                R.id.countIn2Beats -> 2
                R.id.countIn4Beats -> 4
                R.id.countIn8Beats -> 8
                else -> 4
            }
            settingsRepository.countInBeats = beats
        }

        // Sound SeekBars: write settings already during onProgressChanged when moved by the user
        binding.drumLevelSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.drumLevelLabel.text = getString(R.string.drum_level_format, progress)
                if (fromUser) {
                    val value = (progress).toFloat() / 100f
                    settingsRepository.drumLevel = value
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
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Reset button - restore defaults (100% => 1.0)
        binding.resetSoundButton.setOnClickListener {
            settingsRepository.drumLevel = 1.0f
            settingsRepository.envelopeScale = 1.0f
            settingsRepository.hiHatHighpass = 1.0f

            // Update UI
            binding.drumLevelSeekBar.progress = 100
            binding.drumLevelLabel.text = getString(R.string.drum_level_format, 100)
            binding.envelopeScaleSeekBar.progress = 100
            binding.envelopeScaleLabel.text = getString(R.string.envelope_scale_format, 100)
            binding.hihatHighpassSeekBar.progress = 100
            binding.hihatHighpassLabel.text = getString(R.string.hihat_highpass_format, 100)
            Toast.makeText(this, getString(R.string.reset_sound_toast), Toast.LENGTH_SHORT).show()
        }
    }
}
