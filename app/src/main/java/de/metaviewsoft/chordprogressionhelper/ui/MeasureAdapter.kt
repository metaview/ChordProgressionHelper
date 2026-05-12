@file:OptIn(InternalSerializationApi::class)

package de.metaviewsoft.chordprogressionhelper.ui

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.graphics.Color
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.metaviewsoft.chordprogressionhelper.R
import de.metaviewsoft.chordprogressionhelper.databinding.ItemAddMeasureBinding
import de.metaviewsoft.chordprogressionhelper.databinding.ItemMeasureBinding
import de.metaviewsoft.chordprogressionhelper.model.Chord
import de.metaviewsoft.chordprogressionhelper.model.Measure
import de.metaviewsoft.chordprogressionhelper.model.Strum
import kotlinx.serialization.InternalSerializationApi

class MeasureAdapter(
    private val onChordClick: (measureIndex: Int, eighthNoteIndex: Int) -> Unit,
    private val onChordLongClick: (measureIndex: Int, eighthNoteIndex: Int) -> Unit,
    private val onStrummingPatternClick: (measureIndex: Int) -> Unit,
    private val onDrumPatternClick: (measureIndex: Int) -> Unit,
    private val onPianoPatternClick: (measureIndex: Int) -> Unit,
    private val onChordDrop: (measureIndex: Int, eighthNoteIndex: Int, chord: Chord) -> Unit,
    private val onRemoveMeasureClick: (measureIndex: Int) -> Unit,
    private val onDuplicateMeasureClick: (measureIndex: Int) -> Unit,
    private val onAddMeasureClick: () -> Unit,
    private val onStartDrag: (viewHolder: RecyclerView.ViewHolder) -> Unit
) : ListAdapter<MeasureAdapter.DisplayableItem, RecyclerView.ViewHolder>(MeasureDiffCallback()) {

    init {
        // Use stable IDs so RecyclerView can keep ViewHolders consistent while items are reordered
        setHasStableIds(true)
    }

    private var currentPlaybackPosition: Pair<Int, Int>? = null

    companion object {
        private const val VIEW_TYPE_MEASURE = 0
        private const val VIEW_TYPE_ADD_MEASURE = 1
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setPlaybackPosition(position: Pair<Int, Int>?) {
        val oldPosition = currentPlaybackPosition
        currentPlaybackPosition = position
        // Could be optimized to only notify the changed items
        oldPosition?.let { notifyItemChanged(it.first) }
        position?.let { notifyItemChanged(it.first) }
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
            else -> throw IllegalStateException("Null item at position $position")
        }
    }

    override fun getItemId(position: Int): Long {
        // Provide stable, unique id for each displayable item so RecyclerView doesn't confuse items during moves
        return try {
            getItem(position).id
        } catch (e: Exception) {
            RecyclerView.NO_ID
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
            binding.measureNumberText.text = binding.root.context.getString(R.string.measure_format, measure.number)

            // Populate small chips for each strum for improved readability and accessibility
            val container = binding.strumContainer
            container.removeAllViews()
            if (measure.strummingPattern.strums.isNotEmpty()) {
                measure.strummingPattern.strums.forEachIndexed { idx, strum ->
                    val chip = LayoutInflater.from(binding.root.context).inflate(R.layout.item_strum_chip, container, false)
                    val icon = chip.findViewById<android.widget.ImageView>(R.id.strumChipIcon)
                    val (drawableId, desc) = when (strum) {
                        Strum.DOWN -> Pair(R.drawable.ic_strum_down, "D")
                        Strum.UP -> Pair(R.drawable.ic_strum_up, "U")
                        Strum.MUTE -> Pair(R.drawable.ic_strum_mute, "M")
                        Strum.LETRING -> Pair(R.drawable.ic_strum_letring, "L")
                        Strum.REST -> Pair(R.drawable.ic_strum_rest, "-")
                    }
                    icon.setImageResource(drawableId)
                    chip.contentDescription = binding.root.context.getString(R.string.strum_content_description, desc, idx + 1)
                    chip.setOnClickListener { onStrummingPatternClick(measureIndex) }
                    container.addView(chip)
                }
            } else {
                // Fallback: show the rest icon and use name as description
                val chip = LayoutInflater.from(binding.root.context).inflate(R.layout.item_strum_chip, container, false)
                val icon = chip.findViewById<android.widget.ImageView>(R.id.strumChipIcon)
                icon.setImageResource(R.drawable.ic_strum_rest)
                chip.contentDescription = measure.strummingPattern.displayName
                chip.setOnClickListener { onStrummingPatternClick(measureIndex) }
                container.addView(chip)
            }

            val quarterNoteSlots = listOf(binding.quarterNote1, binding.quarterNote2, binding.quarterNote3, binding.quarterNote4)

            for ((quarterNoteIndex, slot) in quarterNoteSlots.withIndex()) {
                val eighthNoteIndex = quarterNoteIndex * 2
                val chord = measure.getChordAt(eighthNoteIndex) // Check chord at the downbeat
                slot.text = chord?.getDisplayName() ?: "-"

                // Highlight the currently playing slot
                val isPlayingThisSlot = currentPlaybackPosition?.first == measureIndex && currentPlaybackPosition?.second?.div(2) == quarterNoteIndex
                if (isPlayingThisSlot) {
                    slot.setBackgroundColor(Color.YELLOW)
                } else {
                    slot.setBackgroundResource(R.drawable.quarter_note_background)
                }

                slot.setOnClickListener {
                    onChordClick(measureIndex, eighthNoteIndex)
                }
                slot.setOnLongClickListener {
                    onChordLongClick(measureIndex, eighthNoteIndex)
                    true
                }
                slot.setOnDragListener(createDragListener(measureIndex, eighthNoteIndex))
            }

            // Also make the whole scroll area clickable to open the strumming pattern editor
            binding.strummingPatternScroll.setOnClickListener { onStrummingPatternClick(measureIndex) }

            // Wire the new drum icon (in header) to open the Drum Pattern editor
            try {
                val drumIcon = binding.root.findViewById<android.widget.ImageView>(R.id.drumIcon)
                drumIcon.visibility = View.VISIBLE
                drumIcon.contentDescription = binding.root.context.getString(R.string.drums_label)
                drumIcon.setOnClickListener { onDrumPatternClick(measureIndex) }
            } catch (e: Exception) {
                android.util.Log.w("MeasureAdapter", "failed to setup drumIcon: ${e.message}", e)
            }

            try {
                val pianoIcon = binding.root.findViewById<android.widget.ImageView>(R.id.pianoIcon)
                pianoIcon.visibility = View.VISIBLE
                pianoIcon.contentDescription = binding.root.context.getString(R.string.keyboard)
                pianoIcon.setOnClickListener {onPianoPatternClick(measureIndex) }
            } catch (e: Exception) {
                android.util.Log.w("MeasureAdapter", "failed to setup pianoIcon: ${e.message}", e)
            }

            binding.removeMeasureButton.setOnClickListener {
                onRemoveMeasureClick(measureIndex)
            }

            // Short click: Show context menu
            binding.dragHandle.setOnClickListener { view ->
                showMeasureContextMenu(view, measureIndex)
            }

            // Long click: Start drag & drop
            binding.dragHandle.setOnLongClickListener {
                onStartDrag(this)
                true
            }
        }

        private fun showMeasureContextMenu(view: View, measureIndex: Int) {
            val popup = android.widget.PopupMenu(view.context, view)
            popup.menu.add(0, 1, 0, R.string.delete_measure_title)
            popup.menu.add(0, 2, 1, R.string.duplicate_measure)
            popup.menu.add(0, 3, 2, R.string.clear_chords)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        onRemoveMeasureClick(measureIndex)
                        true
                    }
                    2 -> {
                        onDuplicateMeasureClick(measureIndex)
                        true
                    }
                    3 -> {
                        // TODO: Clear chords functionality
                        android.widget.Toast.makeText(view.context, "Clear chords not yet implemented", android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        private fun createDragListener(measureIndex: Int, eighthNoteIndex: Int): View.OnDragListener {
            return View.OnDragListener { view, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.clipDescription.hasMimeType(
                        ClipDescription.MIMETYPE_TEXT_PLAIN)
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        view.alpha = 0.5f; true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        view.alpha = 1.0f; true
                    }
                    DragEvent.ACTION_DROP -> {
                        view.alpha = 1.0f
                        val droppedChord = event.localState as? Chord
                        droppedChord?.let { onChordDrop(measureIndex, eighthNoteIndex, it) }
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
