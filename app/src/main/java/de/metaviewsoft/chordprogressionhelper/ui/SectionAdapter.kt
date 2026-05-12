package de.metaviewsoft.chordprogressionhelper.ui

import android.os.Handler
import android.os.Looper
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
        val isAddButton: Boolean = false
    )

    private var selectedIndex: Int = -1
    private var playingIndex: Int = -1
    private var beatIndex: Int = -1
    private val beatHandler = Handler(Looper.getMainLooper())
    private val clearBeat = Runnable {
        val prev = beatIndex
        beatIndex = -1
        if (prev >= 0 && prev < currentList.size) notifyItemChanged(prev, BEAT_PAYLOAD)
    }

    companion object {
        const val VIEW_TYPE_SECTION = 0
        const val VIEW_TYPE_ADD = 1
        private const val BEAT_PAYLOAD = "beat"
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
        if (index < 0) {
            beatHandler.removeCallbacks(clearBeat)
            val prev = beatIndex
            beatIndex = -1
            if (prev >= 0 && prev < currentList.size) notifyItemChanged(prev, BEAT_PAYLOAD)
        }
        if (previous != index) {
            if (previous >= 0 && previous < currentList.size) notifyItemChanged(previous)
            if (index >= 0 && index < currentList.size) notifyItemChanged(index)
        }
    }

    /** Called on each beat — briefly flashes the playing section highlight. */
    fun onBeat() {
        if (playingIndex < 0 || playingIndex >= currentList.size) return
        beatHandler.removeCallbacks(clearBeat)
        beatIndex = playingIndex
        notifyItemChanged(beatIndex, BEAT_PAYLOAD)
        beatHandler.postDelayed(clearBeat, 60)
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
        if (payloads.contains(BEAT_PAYLOAD) && holder is SectionViewHolder) {
            holder.applyBackground(position == selectedIndex, position == beatIndex)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SectionViewHolder -> holder.bind(getItem(position).name, position, position == selectedIndex, position == playingIndex)
            is AddViewHolder -> holder.bind()
        }
    }

    inner class SectionViewHolder(private val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun applyBackground(isSelected: Boolean, isBeat: Boolean) {
            itemView.background = when {
                isBeat -> androidx.core.content.ContextCompat.getDrawable(itemView.context, R.drawable.section_item_background_beat)
                isSelected -> androidx.core.content.ContextCompat.getDrawable(itemView.context, R.drawable.section_item_background_selected)
                else -> androidx.core.content.ContextCompat.getDrawable(itemView.context, R.drawable.section_item_background)
            }
        }

        fun bind(sectionName: String, position: Int, isSelected: Boolean, isPlaying: Boolean) {
            binding.sectionNumberText.text = (position + 1).toString()
            binding.sectionNameText.text = sectionName.substringAfter(". ", sectionName)

            applyBackground(isSelected, position == beatIndex)

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
     * ItemTouchHelper.Callback for drag-drop reordering
     */
    class SectionTouchHelperCallback(
        private val adapter: SectionAdapter,
        private val onMove: (from: Int, to: Int) -> Unit
    ) : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            if (!adapter.isSectionPosition(viewHolder.bindingAdapterPosition)) {
                return 0
            }
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val fromPosition = source.bindingAdapterPosition
            val toPosition = target.bindingAdapterPosition

            if (!adapter.isSectionPosition(fromPosition) || !adapter.isSectionPosition(toPosition)) {
                return false
            }

            if (fromPosition < toPosition) {
                onMove(fromPosition, toPosition)
            } else if (fromPosition > toPosition) {
                onMove(fromPosition, toPosition)
            }
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // Swiping not supported; use menu for delete
        }

        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false
    }
}
