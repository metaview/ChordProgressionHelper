package com.metaview.chordprogressionhelper

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.metaview.chordprogressionhelper.databinding.DialogPianoPatternBinding

class PianoPatternActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEASURE_INDEX = "extra_measure_index"
        const val EXTRA_PIANO_PATTERN_JSON = "extra_piano_pattern_json"
        const val EXTRA_ALL_PATTERNS_JSON = "extra_all_patterns_json"
        private const val TAG = "PianoPatternActivity"
    }

    private lateinit var binding: DialogPianoPatternBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogPianoPatternBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val measureIndex = intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1

        binding.titleText.text = getString(R.string.piano_editor_title, if (measureIndex >= 0) (measureIndex + 1).toString() else "-")

        // Simple demo: clicking the piano image shows a toast. Full editor behavior can be added later.
        binding.pianoImage.setOnClickListener {
            Toast.makeText(this, R.string.piano_tap_hint, Toast.LENGTH_SHORT).show()
        }

        binding.btnOk.setOnClickListener {
            performOk()
        }

        // Handle back press via OnBackPressedDispatcher
        try {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    try {
                        performCancel()
                    } catch (_: Exception) {
                        // fallback: close activity safely from the callback
                        try { this@PianoPatternActivity.finish() } catch (_: Exception) { /* best-effort */ }
                    }
                }
            })
        } catch (_: Exception) {}
    }

    private fun performOk() {
        // Return the measure index as result (no changes saved yet in this simple editor)
        setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_MEASURE_INDEX, intent?.getIntExtra(EXTRA_MEASURE_INDEX, -1) ?: -1) })
        finish()
    }

    private fun performCancel() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
