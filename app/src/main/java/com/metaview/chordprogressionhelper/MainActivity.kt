package com.metaview.chordprogressionhelper

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metaview.chordprogressionhelper.databinding.ActivityMainBinding
import com.metaview.chordprogressionhelper.model.Key
import com.metaview.chordprogressionhelper.model.Mode
import com.metaview.chordprogressionhelper.ui.ChordAdapter
import com.metaview.chordprogressionhelper.ui.MeasureAdapter
import com.metaview.chordprogressionhelper.ui.ProgressionViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ProgressionViewModel
    private lateinit var chordAdapter: ChordAdapter
    private lateinit var measureAdapter: MeasureAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[ProgressionViewModel::class.java]

        setupSpinners()
        setupRecyclerViews()
        setupButtons()
        observeViewModel()
    }

    private fun setupSpinners() {
        // Key spinner
        val keyAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Key.values().map { it.displayName }
        )
        keyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.keySpinner.adapter = keyAdapter
        binding.keySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel.setKey(Key.values()[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Mode spinner
        val modeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Mode.values().map { it.displayName }
        )
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modeSpinner.adapter = modeAdapter
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel.setMode(Mode.values()[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerViews() {
        // Chord palette
        chordAdapter = ChordAdapter { chord ->
            viewModel.setSelectedChord(chord)
        }
        binding.chordRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = chordAdapter
        }

        // Measures
        measureAdapter = MeasureAdapter(
            onChordClick = { measureIndex, quarterNote ->
                viewModel.selectedChord.value?.let { chord ->
                    viewModel.addChordToMeasure(measureIndex, quarterNote, chord)
                }
            },
            onChordLongClick = { measureIndex, quarterNote ->
                viewModel.removeChordFromMeasure(measureIndex, quarterNote)
            },
            onStrummingPatternClick = { measureIndex ->
                showStrummingPatternDialog(measureIndex)
            },
            onChordDrop = { measureIndex, quarterNote, chord ->
                viewModel.addChordToMeasure(measureIndex, quarterNote, chord)
            }
        )
        binding.measureRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = measureAdapter
        }
    }

    private fun setupButtons() {
        binding.playButton.setOnClickListener {
            lifecycleScope.launch {
                binding.playButton.isEnabled = false
                binding.stopButton.isEnabled = true
                viewModel.play()
                binding.playButton.isEnabled = true
                binding.stopButton.isEnabled = false
            }
        }

        binding.stopButton.setOnClickListener {
            viewModel.stop()
            binding.playButton.isEnabled = true
            binding.stopButton.isEnabled = false
        }

        binding.addMeasureButton.setOnClickListener {
            viewModel.addMeasure()
        }

        binding.clearButton.setOnClickListener {
            viewModel.clearProgression()
        }
    }

    private fun observeViewModel() {
        viewModel.scaleDegreeChords.observe(this) { chords ->
            chordAdapter.submitList(chords)
        }

        viewModel.measures.observe(this) { measures ->
            measureAdapter.submitList(measures)
        }

        viewModel.selectedChord.observe(this) { chord ->
            chordAdapter.setSelectedChord(chord)
            
            // Show related chords when a chord is selected
            chord?.let {
                val relatedChords = viewModel.getRelatedChords(it)
                // You could display these in a separate section if needed
            }
        }
    }

    private fun showStrummingPatternDialog(measureIndex: Int) {
        val patterns = com.metaview.chordprogressionhelper.model.StrummingPattern.values()
        val patternNames = patterns.map { it.displayName }.toTypedArray()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Strumming Pattern")
            .setItems(patternNames) { _, which ->
                viewModel.setStrummingPattern(measureIndex, patterns[which])
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stop()
    }
}
