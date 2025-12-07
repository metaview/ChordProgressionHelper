package com.metaview.chordprogressionhelper.ui

import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
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
    private var targetChord: Chord? = null
    private var suggestedChord: Chord? = null
    private var primaryChords: List<Chord> = emptyList()

    fun setSelectedChord(chord: Chord?) {
        val previousSelected = selectedChord
        selectedChord = chord
        previousSelected?.let { notifyItemChanged(currentList.indexOf(it)) }
        chord?.let { notifyItemChanged(currentList.indexOf(it)) }
    }

    fun setTargetChord(chord: Chord?) {
        val previousTarget = targetChord
        targetChord = chord
        previousTarget?.let { notifyItemChanged(currentList.indexOf(it)) }
        chord?.let { notifyItemChanged(currentList.indexOf(it)) }
    }

    fun setSuggestedChord(chord: Chord?) {
        val previousSuggested = suggestedChord
        suggestedChord = chord
        previousSuggested?.let { notifyItemChanged(currentList.indexOf(it)) }
        chord?.let { notifyItemChanged(currentList.indexOf(it)) }
    }

    fun setPrimaryChords(chords: List<Chord>) {
        val oldChords = primaryChords
        primaryChords = chords
        oldChords.forEach { notifyItemChanged(currentList.indexOf(it)) }
        chords.forEach { notifyItemChanged(currentList.indexOf(it)) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChordViewHolder {
        val binding = ItemChordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChordViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChordViewHolder(private val binding: ItemChordBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chord: Chord) {
            binding.chordNameText.text = chord.getDisplayName()
            binding.romanNumeralText.text = chord.getRomanNumeral() ?: ""

            // Visual state handling with priority
            when {
                chord == targetChord -> {
                    val greenDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 24f
                        setColor(ContextCompat.getColor(itemView.context, R.color.color_target))
                    }
                    itemView.background = greenDrawable
                    binding.chordNameText.setTextColor(Color.WHITE)
                    binding.romanNumeralText.setTextColor(Color.WHITE)
                    itemView.isActivated = false
                }
                chord == suggestedChord -> {
                    val yellowDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 24f
                        setColor(ContextCompat.getColor(itemView.context, R.color.color_suggestion))
                    }
                    itemView.background = yellowDrawable
                    binding.chordNameText.setTextColor(Color.BLACK)
                    binding.romanNumeralText.setTextColor(Color.BLACK)
                    itemView.isActivated = false
                }
                chord in primaryChords -> {
                    itemView.setBackgroundResource(R.drawable.primary_chord_background)
                    binding.chordNameText.setTextColor(itemView.context.getColorStateList(R.color.chord_text_color))
                    binding.romanNumeralText.setTextColor(itemView.context.getColorStateList(R.color.chord_text_color))
                    itemView.isActivated = (chord == selectedChord)
                }
                else -> {
                    // Standard background for selected/default
                    itemView.setBackgroundResource(R.drawable.chord_item_background)
                    binding.chordNameText.setTextColor(itemView.context.getColorStateList(R.color.chord_text_color))
                    binding.romanNumeralText.setTextColor(itemView.context.getColorStateList(R.color.chord_text_color))
                    itemView.isActivated = (chord == selectedChord)
                }
            }

            binding.root.setOnClickListener { onChordClick(chord) }

            binding.root.setOnLongClickListener { view ->
                val item = ClipData.Item(chord.getDisplayName())
                val dragData = ClipData(view.tag as? CharSequence, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), item)
                view.startDragAndDrop(dragData, View.DragShadowBuilder(view), chord, 0)
                true
            }
        }
    }

    private class ChordDiffCallback : DiffUtil.ItemCallback<Chord>() {
        override fun areItemsTheSame(oldItem: Chord, newItem: Chord): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: Chord, newItem: Chord): Boolean = oldItem == newItem
    }
}
