package com.metaview.chordprogressionhelper.ui

import android.annotation.SuppressLint
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.metaview.chordprogressionhelper.databinding.ItemAddMeasureBinding
import com.metaview.chordprogressionhelper.databinding.ItemMeasureBinding
import com.metaview.chordprogressionhelper.model.Chord
import com.metaview.chordprogressionhelper.model.Measure

class MeasureAdapter(
    private val onChordClick: (measureIndex: Int, quarterNote: Int) -> Unit,
    private val onChordLongClick: (measureIndex: Int, quarterNote: Int) -> Unit,
    private val onStrummingPatternClick: (measureIndex: Int) -> Unit,
    private val onChordDrop: (measureIndex: Int, quarterNote: Int, chord: Chord) -> Unit,
    private val onRemoveMeasureClick: (measureIndex: Int) -> Unit,
    private val onAddMeasureClick: () -> Unit,
    private val onStartDrag: (viewHolder: RecyclerView.ViewHolder) -> Unit
) : ListAdapter<MeasureAdapter.DisplayableItem, RecyclerView.ViewHolder>(MeasureDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_MEASURE = 0
        private const val VIEW_TYPE_ADD_MEASURE = 1
    }

    sealed class DisplayableItem {
        abstract val id: Long
        data class MeasureItem(val measure: Measure) : DisplayableItem() {
            override val id = measure.id
        }
        object AddMeasureItem : DisplayableItem() {
            override val id = Long.MIN_VALUE
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DisplayableItem.MeasureItem -> VIEW_TYPE_MEASURE
            is DisplayableItem.AddMeasureItem -> VIEW_TYPE_ADD_MEASURE
            null -> throw IllegalStateException("Null item at position $position")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_MEASURE -> {
                val binding = ItemMeasureBinding.inflate(inflater, parent, false)
                MeasureViewHolder(binding)
            }
            VIEW_TYPE_ADD_MEASURE -> {
                val binding = ItemAddMeasureBinding.inflate(inflater, parent, false)
                AddMeasureViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is MeasureViewHolder) {
            val measureItem = getItem(position) as DisplayableItem.MeasureItem
            holder.bind(measureItem.measure, position)
        }
    }

    inner class MeasureViewHolder(
        private val binding: ItemMeasureBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(measure: Measure, measureIndex: Int) {
            binding.measureNumberText.text = "Measure ${measure.number}"
            binding.strummingPatternText.text = measure.strummingPattern.displayName

            setupQuarterNoteSlot(binding.quarterNote1, measure, measureIndex, 0)
            setupQuarterNoteSlot(binding.quarterNote2, measure, measureIndex, 1)
            setupQuarterNoteSlot(binding.quarterNote3, measure, measureIndex, 2)
            setupQuarterNoteSlot(binding.quarterNote4, measure, measureIndex, 3)

            binding.strummingPatternText.setOnClickListener {
                onStrummingPatternClick(measureIndex)
            }

            binding.removeMeasureButton.setOnClickListener {
                onRemoveMeasureClick(measureIndex)
            }

            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
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

            textView.setOnDragListener { view, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.clipDescription.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN)
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        view.alpha = 0.5f; true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        view.alpha = 1.0f; true
                    }
                    DragEvent.ACTION_DROP -> {
                        view.alpha = 1.0f
                        val droppedChord = event.localState as? Chord
                        droppedChord?.let { onChordDrop(measureIndex, quarterNote, it) }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        view.alpha = 1.0f; true
                    }
                    else -> false
                }
            }
        }
    }

    inner class AddMeasureViewHolder(
        binding: ItemAddMeasureBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.addMeasureButton.setOnClickListener {
                onAddMeasureClick()
            }
        }
    }

    private class MeasureDiffCallback : DiffUtil.ItemCallback<DisplayableItem>() {
        override fun areItemsTheSame(oldItem: DisplayableItem, newItem: DisplayableItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DisplayableItem, newItem: DisplayableItem): Boolean {
            return oldItem == newItem
        }
    }
}
