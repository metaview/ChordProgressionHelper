package de.metaviewsoft.chordprogressionhelper.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.metaviewsoft.chordprogressionhelper.R
import de.metaviewsoft.chordprogressionhelper.databinding.ItemSectionAddBinding
import de.metaviewsoft.chordprogressionhelper.databinding.ItemSectionBinding

class SectionAdapter(
    private val onSectionClick: (Int) -> Unit,
    private val onMenuClick: (position: Int, anchor: View) -> Unit,
    private val onAddSectionClick: () -> Unit,
    private val onStartDrag: (viewHolder: RecyclerView.ViewHolder) -> Unit
) : ListAdapter<SectionAdapter.SectionItem, RecyclerView.ViewHolder>(SectionDiffCallback()) {

    data class SectionItem(
        val name: String,
        val chords: List<ChordMark> = emptyList(),
        val isAddButton: Boolean = false
    )

    private var selectedIndex: Int = -1
    private var playingIndex: Int = -1
    private var playProgress: Float = 0f

    companion object {
        const val VIEW_TYPE_SECTION = 0
        const val VIEW_TYPE_ADD = 1
        private const val PROGRESS_PAYLOAD = "progress"
        class SectionDiffCallback : DiffUtil.ItemCallback<SectionItem>() {
            override fun areItemsTheSame(oldItem: SectionItem, newItem: SectionItem): Boolean {
                return oldItem.name == newItem.name && oldItem.isAddButton == newItem.isAddButton
            }

            override fun areContentsTheSame(oldItem: SectionItem, newItem: SectionItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setSelectedIndex(index: Int) {
        val previous = selectedIndex
        selectedIndex = index
        if (previous >= 0 && previous < currentList.size) notifyItemChanged(previous)
        if (index >= 0 && index < currentList.size) notifyItemChanged(index)
    }

    fun setPlayingIndex(index: Int) {
        val previous = playingIndex
        playingIndex = index
        playProgress = 0f
        if (previous != index) {
            if (previous >= 0 && previous < currentList.size) notifyItemChanged(previous)
            if (index >= 0 && index < currentList.size) notifyItemChanged(index)
        }
    }

    /** Called as playback advances through the playing section; fraction is 0..1, filled left-to-right. */
    fun setProgress(index: Int, fraction: Float) {
        if (index != playingIndex || index < 0 || index >= currentList.size) return
        playProgress = fraction.coerceIn(0f, 1f)
        notifyItemChanged(index, PROGRESS_PAYLOAD)
    }

    fun getSectionCount(): Int = currentList.count { !it.isAddButton }

    fun isSectionPosition(position: Int): Boolean =
        position in 0 until itemCount && !currentList[position].isAddButton

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isAddButton) VIEW_TYPE_ADD else VIEW_TYPE_SECTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_ADD -> {
                val binding = ItemSectionAddBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                AddViewHolder(binding)
            }

            else -> {
                val binding = ItemSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                SectionViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.contains(PROGRESS_PAYLOAD) && holder is SectionViewHolder) {
            holder.applyProgress(playProgress)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SectionViewHolder -> holder.bind(getItem(position), position, position == playingIndex)
            is AddViewHolder -> holder.bind()
        }
    }

    inner class SectionViewHolder(private val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root) {

        private val cornerRadiusPx = 8f * itemView.resources.displayMetrics.density
        private val playingDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.section_item_bg))
        }

        /** Highlights the whole row while playing; the moving fill lives in the chord track. */
        fun applyPlayingState(isPlaying: Boolean) {
            itemView.background = if (isPlaying) {
                playingDrawable
            } else {
                androidx.core.content.ContextCompat.getDrawable(itemView.context, R.drawable.section_item_background)
            }
            binding.chordTrackView.setPlaying(isPlaying)
        }

        fun applyProgress(progress: Float) {
            binding.chordTrackView.setProgress(progress)
        }

        fun bind(item: SectionItem, position: Int, isPlaying: Boolean) {
            binding.sectionNumberText.text = (position + 1).toString()
            binding.sectionNameText.text = item.name.substringAfter(". ", item.name)
            binding.chordTrackView.setChords(item.chords)

            applyPlayingState(isPlaying)
            applyProgress(if (isPlaying) playProgress else 0f)

            binding.root.setOnClickListener {
                onSectionClick(position)
            }

            binding.sectionDragHandle.setOnClickListener { anchor ->
                onMenuClick(position, anchor)
            }

            binding.sectionDragHandle.setOnLongClickListener {
                onStartDrag(this)
                true
            }
        }
    }

    inner class AddViewHolder(private val binding: ItemSectionAddBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.root.setOnClickListener {
                onAddSectionClick()
            }
        }
    }

    /**
     * ItemTouchHelper.Callback for drag-drop reordering.
     * Visual feedback during drag via notifyItemMoved; ViewModel is only updated once on drop.
     */
    class SectionTouchHelperCallback(
        private val adapter: SectionAdapter,
        private val onDrop: (from: Int, to: Int) -> Unit
    ) : ItemTouchHelper.Callback() {

        private var dragFrom = -1
        private var dragCurrent = -1
        private var originalBackground: android.graphics.drawable.Drawable? = null

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            if (!adapter.isSectionPosition(viewHolder.bindingAdapterPosition)) {
                return 0
            }
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = source.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (!adapter.isSectionPosition(from) || !adapter.isSectionPosition(to)) return false
            if (dragFrom < 0) dragFrom = from
            dragCurrent = to
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.let { v ->
                    originalBackground = v.background
                    v.alpha = 0.95f
                    v.elevation = 24f
                    v.setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
                }
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1.0f
            viewHolder.itemView.elevation = 0f
            viewHolder.itemView.background = originalBackground
            originalBackground = null
            val from = dragFrom
            val to = dragCurrent
            dragFrom = -1
            dragCurrent = -1
            if (from >= 0 && to >= 0 && from != to) {
                onDrop(from, to)
            }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false
    }
}
