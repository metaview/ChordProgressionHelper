package com.metaview.chordprogressionhelper.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.metaview.chordprogressionhelper.R
import com.metaview.chordprogressionhelper.databinding.ItemChordBinding
import com.metaview.chordprogressionhelper.model.Chord

class ChordAdapter(
    private val onChordClick: (Chord) -> Unit
) : ListAdapter<Chord, ChordAdapter.ChordViewHolder>(ChordDiffCallback()) {

    private var selectedChord: Chord? = null

    fun setSelectedChord(chord: Chord?) {
        val previousSelected = selectedChord
        selectedChord = chord
        
        // Notify items to update their selection state
        currentList.forEachIndexed { index, item ->
            if (item == previousSelected || item == chord) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChordViewHolder {
        val binding = ItemChordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChordViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChordViewHolder(
        private val binding: ItemChordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chord: Chord) {
            binding.chordNameText.text = chord.getDisplayName()
            binding.romanNumeralText.text = chord.getRomanNumeral() ?: ""
            
            // Highlight selected chord
            val isSelected = chord == selectedChord
            val backgroundColor = if (isSelected) {
                ContextCompat.getColor(binding.root.context, R.color.chord_selected)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.chord_normal)
            }
            binding.root.setBackgroundColor(backgroundColor)
            
            binding.root.setOnClickListener {
                onChordClick(chord)
            }
        }
    }

    private class ChordDiffCallback : DiffUtil.ItemCallback<Chord>() {
        override fun areItemsTheSame(oldItem: Chord, newItem: Chord): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: Chord, newItem: Chord): Boolean {
            return oldItem == newItem
        }
    }
}
