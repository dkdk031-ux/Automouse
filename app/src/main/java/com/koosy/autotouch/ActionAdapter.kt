package com.koosy.autotouch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ActionAdapter(
    private val items: MutableList<ActionItem>,
    private val onChanged: () -> Unit,
    private val onEdit: (Int) -> Unit
) : RecyclerView.Adapter<ActionAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.txtIndex)
        val title: TextView = v.findViewById(R.id.txtTitle)
        val sub: TextView = v.findViewById(R.id.txtSub)
        val enabled: CheckBox = v.findViewById(R.id.chkEnabled)
        val up: TextView = v.findViewById(R.id.btnUp)
        val down: TextView = v.findViewById(R.id.btnDown)
        val edit: TextView = v.findViewById(R.id.btnEdit)
        val delete: TextView = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_action, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[holder.bindingAdapterPosition.takeIf { it >= 0 } ?: position]
        holder.index.text = "${position + 1}"
        holder.title.text = a.title()
        holder.sub.text = a.subtitle()
        holder.enabled.setOnCheckedChangeListener(null)
        holder.enabled.isChecked = a.enabled
        holder.itemView.alpha = if (a.enabled) 1f else 0.45f

        holder.enabled.setOnCheckedChangeListener { _, checked ->
            val p = holder.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
            items[p].enabled = checked
            holder.itemView.alpha = if (checked) 1f else 0.45f
            onChanged()
        }

        holder.up.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p <= 0) return@setOnClickListener
            val it0 = items.removeAt(p)
            items.add(p - 1, it0)
            notifyItemMoved(p, p - 1)
            notifyItemRangeChanged(0, items.size)
            onChanged()
        }

        holder.down.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION || p >= items.size - 1) return@setOnClickListener
            val it0 = items.removeAt(p)
            items.add(p + 1, it0)
            notifyItemMoved(p, p + 1)
            notifyItemRangeChanged(0, items.size)
            onChanged()
        }

        holder.edit.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onEdit(p)
        }

        holder.delete.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnClickListener
            items.removeAt(p)
            notifyItemRemoved(p)
            notifyItemRangeChanged(0, items.size)
            onChanged()
        }
    }
}
