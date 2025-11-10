package com.metaview.chordprogressionhelper.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.metaview.chordprogressionhelper.databinding.ItemMeasureBinding
import com.metaview.chordprogressionhelper.model.Measure

class MeasureAdapter(
    private val onChordClick: (measureIndex: Int, quarterNote: Int) -> Unit,
    private val onChordLongClick: (measureIndex: Int, quarterNote: Int) -> Unit,
    private val onStrummingPatternClick: (measureIndex: Int) -> Unit
) : ListAdapter<Measure, MeasureAdapter.MeasureViewHolder>(MeasureDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeasureViewHolder {
        val binding = ItemMeasureBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MeasureViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MeasureViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class MeasureViewHolder(
        private val binding: ItemMeasureBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(measure: Measure, measureIndex: Int) {
            binding.measureNumberText.text = "Measure ${measure.number}"
            binding.strummingPatternText.text = measure.strummingPattern.displayName
            
            // Set up quarter note slots
            setupQuarterNoteSlot(binding.quarterNote1, measure, measureIndex, 0)
            setupQuarterNoteSlot(binding.quarterNote2, measure, measureIndex, 1)
            setupQuarterNoteSlot(binding.quarterNote3, measure, measureIndex, 2)
            setupQuarterNoteSlot(binding.quarterNote4, measure, measureIndex, 3)
            
            binding.strummingPatternText.setOnClickListener {
                onStrummingPatternClick(measureIndex)
            }
        }

        private fun setupQuarterNoteSlot(
            textView: android.widget.TextView,
            measure: Measure,
            measureIndex: Int,
            quarterNote: Int
        ) {
            val chord = measure.getChordAt(quarterNote)
            textView.text = chord?.getDisplayName() ?: "-"
            
            textView.setOnClickListener {
                onChordClick(measureIndex, quarterNote)
            }
            
            textView.setOnLongClickListener {
                onChordLongClick(measureIndex, quarterNote)
                true
            }
        }
    }

    private class MeasureDiffCallback : DiffUtil.ItemCallback<Measure>() {
        override fun areItemsTheSame(oldItem: Measure, newItem: Measure): Boolean {
            return oldItem.number == newItem.number
        }

        override fun areContentsTheSame(oldItem: Measure, newItem: Measure): Boolean {
            return oldItem == newItem
        }
    }
}
