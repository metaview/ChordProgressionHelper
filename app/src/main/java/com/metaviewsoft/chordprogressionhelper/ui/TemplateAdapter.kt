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

    inner class TemplateViewHolder(
        private val binding: ItemProgressionTemplateBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(template: ProgressionTemplate?) {
            if (template == null) {
                // Empty template
                binding.templateNameText.text = binding.root.context.getString(de.metaviewsoft.chordprogressionhelper.R.string.template_empty)
                binding.templateDescriptionText.text = binding.root.context.getString(de.metaviewsoft.chordprogressionhelper.R.string.template_empty_description)
            } else {
                binding.templateNameText.text = template.name
                // Generiere die Beschreibung basierend auf der aktuellen Tonart
                binding.templateDescriptionText.text = template.getDescription(currentKey)
            }

            binding.root.setOnClickListener {
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
        holder.bind(templates[position])
    }

    override fun getItemCount(): Int = templates.size
}
