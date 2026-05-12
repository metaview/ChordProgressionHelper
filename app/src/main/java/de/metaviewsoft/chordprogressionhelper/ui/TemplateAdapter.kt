package de.metaviewsoft.chordprogressionhelper.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.metaviewsoft.chordprogressionhelper.databinding.ItemProgressionTemplateBinding
import de.metaviewsoft.chordprogressionhelper.model.Key
import de.metaviewsoft.chordprogressionhelper.model.ProgressionTemplate

/**
 * Adapter für die Auswahl von Progressions-Templates.
 */
class TemplateAdapter(
    private val templates: List<ProgressionTemplate?>,
    private val currentKey: Key,
    private val onTemplateSelected: (ProgressionTemplate?) -> Unit
) : RecyclerView.Adapter<TemplateAdapter.TemplateViewHolder>() {

    private var selectedPosition: Int = -1

    inner class TemplateViewHolder(
        private val binding: ItemProgressionTemplateBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(template: ProgressionTemplate?, isSelected: Boolean) {
            if (template == null) {
                // Empty template
                binding.templateNameText.text = binding.root.context.getString(de.metaviewsoft.chordprogressionhelper.R.string.template_empty)
                binding.templateDescriptionText.text = binding.root.context.getString(de.metaviewsoft.chordprogressionhelper.R.string.template_empty_description)
            } else {
                binding.templateNameText.text = template.name
                // Generiere die Beschreibung basierend auf der aktuellen Tonart
                binding.templateDescriptionText.text = template.getDescription(currentKey)
            }

            // Visuell markieren, wenn ausgewählt
            binding.root.isActivated = isSelected
            binding.root.isSelected = isSelected

            binding.root.setOnClickListener {
                val oldPosition = selectedPosition
                selectedPosition = bindingAdapterPosition
                // Benachrichtige den Adapter über die Änderungen
                if (oldPosition != -1) {
                    notifyItemChanged(oldPosition)
                }
                notifyItemChanged(selectedPosition)
                onTemplateSelected(template)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TemplateViewHolder {
        val binding = ItemProgressionTemplateBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TemplateViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TemplateViewHolder, position: Int) {
        holder.bind(templates[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = templates.size
}
