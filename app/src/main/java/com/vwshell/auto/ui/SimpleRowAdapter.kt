package com.vwshell.auto.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vwshell.auto.R

data class RowItem(val title: String, val subtitle: String = "", val checked: Boolean = false, val payload: Any? = null)

/** Универсальный адаптер строк списков (навигаторы, контакты, вызовы, станции). */
class SimpleRowAdapter(
    private val items: MutableList<RowItem> = mutableListOf(),
    private val onClick: ((RowItem) -> Unit)? = null
) : RecyclerView.Adapter<SimpleRowAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val sub: TextView = v.findViewById(R.id.rowSub)
        val check: ImageView = v.findViewById(R.id.rowCheck)
        val marker: View = v.findViewById(R.id.rowMarker)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.row_simple, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.sub.text = item.subtitle
        holder.sub.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
        holder.check.visibility = if (item.checked) View.VISIBLE else View.GONE
        // Текущий элемент списка: красная полоска border-left 3dp #FF1A1A (по утверждённой демке)
        holder.marker.visibility = if (item.checked) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick?.invoke(item) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<RowItem>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }
}
